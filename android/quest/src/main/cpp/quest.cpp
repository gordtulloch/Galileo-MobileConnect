// Galileo for Meta Quest: the native layer (docs/quest/SDD.md Q-XR, Q-SKY).
//
// This file does three things and decides nothing. It runs OpenXR (the session, the frame loop, passthrough, the Touch
// controllers), it draws the star field from data Kotlin hands it, and it shows Android surfaces on floating quads (the dock
// and the panes). Everything that is a decision (what is picked, where a pane goes, when a held move stops) is in Kotlin,
// where it is tested (:quest-logic). The OpenXR bring-up, the EGL setup, the action bindings and the zoom maths come from
// spikes/quest-atlas, which was run on a Quest 2.
//
// Frame data flow: Kotlin pushes (stars, lines, markers, overlay, labels, quads, sky state) through JNI at its own pace; the
// render thread copies what changed under one mutex and draws. The render thread publishes a snapshot of head, controllers
// and buttons that Kotlin reads. Nothing here calls back into Kotlin from the render thread.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <android_native_app_glue.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <algorithm>
#include <functional>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "music.h"

#define TAG "GalileoQuest"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define XR_OK(call)                                                            \
    do {                                                                       \
        XrResult r_ = (call);                                                  \
        if (XR_FAILED(r_)) {                                                   \
            LOGE("%s failed: %d (%s:%d)", #call, (int)r_, __FILE__, __LINE__); \
            return false;                                                      \
        }                                                                      \
    } while (0)

namespace {

using Clock = std::chrono::steady_clock;
constexpr float kPi = 3.14159265358979f;
constexpr float kDome = 900.f;        // stars and lines sit on this sphere (no parallax)
constexpr float kOverlay = 880.f;     // overlay shapes just inside it
constexpr float kLabel = 870.f;
constexpr int kMaxQuads = 16;
constexpr float kEyeHeight = 1.4f;    // the ground plane is this far below the head at start (LOCAL space origin)

// ------------------------------------------------------------------ maths

struct Vec3 { float x, y, z; };
Vec3 operator+(Vec3 a, Vec3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
Vec3 operator-(Vec3 a, Vec3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
Vec3 operator*(Vec3 a, float s) { return {a.x * s, a.y * s, a.z * s}; }
float dot(Vec3 a, Vec3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
Vec3 cross(Vec3 a, Vec3 b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
float length(Vec3 a) { return std::sqrt(dot(a, a)); }
Vec3 normalize(Vec3 a) {
    float l = length(a);
    return l > 1e-9f ? a * (1.f / l) : Vec3{0, 0, -1};
}

struct Mat4 { float m[16]; };  // column-major, m[col*4+row]
Mat4 identity() { Mat4 r{}; r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1.f; return r; }
Mat4 mul(const Mat4& a, const Mat4& b) {
    Mat4 r{};
    for (int c = 0; c < 4; ++c)
        for (int row = 0; row < 4; ++row) {
            float s = 0;
            for (int k = 0; k < 4; ++k) s += a.m[k * 4 + row] * b.m[c * 4 + k];
            r.m[c * 4 + row] = s;
        }
    return r;
}
Mat4 projection(const XrFovf& f, float n, float fa) {
    float l = tanf(f.angleLeft), r = tanf(f.angleRight), u = tanf(f.angleUp), d = tanf(f.angleDown);
    float w = r - l, h = u - d;
    Mat4 p{};
    p.m[0] = 2.f / w;
    p.m[5] = 2.f / h;
    p.m[8] = (r + l) / w;
    p.m[9] = (u + d) / h;
    p.m[10] = -(fa + n) / (fa - n);
    p.m[11] = -1.f;
    p.m[14] = -2.f * fa * n / (fa - n);
    return p;
}
// Inverse of the head orientation only: the sky has no parallax.
Mat4 viewRotation(const XrQuaternionf& q) {
    float x = q.x, y = q.y, z = q.z, w = q.w;
    float r[3][3] = {{1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)},
                     {2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)},
                     {2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)}};
    Mat4 m = identity();
    for (int c = 0; c < 3; ++c)
        for (int row = 0; row < 3; ++row) m.m[c * 4 + row] = r[c][row];  // transpose
    return m;
}
// Inverse of the full head pose: the ground, the laser.
Mat4 viewFull(const XrPosef& p) {
    Mat4 m = viewRotation(p.orientation);
    const float px = p.position.x, py = p.position.y, pz = p.position.z;
    for (int r = 0; r < 3; ++r) m.m[12 + r] = -(m.m[0 * 4 + r] * px + m.m[1 * 4 + r] * py + m.m[2 * 4 + r] * pz);
    return m;
}
Vec3 aimDir(const XrQuaternionf& q) {  // -Z rotated by q
    float x = q.x, y = q.y, z = q.z, w = q.w;
    return normalize({-(2 * (x * z + y * w)), -(2 * (y * z - x * w)), -(1 - 2 * (x * x + y * y))});
}
Vec3 upDir(const XrQuaternionf& q) {  // +Y rotated by q
    float x = q.x, y = q.y, z = q.z, w = q.w;
    return normalize({2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w)});
}
// A rotation with these columns (right, up, normal) as a quaternion.
XrQuaternionf quatFromBasis(Vec3 r, Vec3 u, Vec3 n) {
    float m00 = r.x, m01 = u.x, m02 = n.x, m10 = r.y, m11 = u.y, m12 = n.y, m20 = r.z, m21 = u.z, m22 = n.z;
    XrQuaternionf q;
    float t = m00 + m11 + m22;
    if (t > 0) {
        float s = std::sqrt(t + 1.f) * 2.f;
        q.w = 0.25f * s; q.x = (m21 - m12) / s; q.y = (m02 - m20) / s; q.z = (m10 - m01) / s;
    } else if (m00 > m11 && m00 > m22) {
        float s = std::sqrt(1.f + m00 - m11 - m22) * 2.f;
        q.w = (m21 - m12) / s; q.x = 0.25f * s; q.y = (m01 + m10) / s; q.z = (m02 + m20) / s;
    } else if (m11 > m22) {
        float s = std::sqrt(1.f + m11 - m00 - m22) * 2.f;
        q.w = (m02 - m20) / s; q.x = (m01 + m10) / s; q.y = 0.25f * s; q.z = (m12 + m21) / s;
    } else {
        float s = std::sqrt(1.f + m22 - m00 - m11) * 2.f;
        q.w = (m10 - m01) / s; q.x = (m02 + m20) / s; q.y = (m12 + m21) / s; q.z = 0.25f * s;
    }
    return q;
}

// ------------------------------------------------------------------ GL helpers

GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        LOGE("shader: %s", log);
    }
    return s;
}
GLuint program(const char* vs, const char* fs) {
    GLuint p = glCreateProgram();
    GLuint v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
    glAttachShader(p, v);
    glAttachShader(p, f);
    glLinkProgram(p);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetProgramInfoLog(p, sizeof log, nullptr, log);
        LOGE("link: %s", log);
    }
    glDeleteShader(v);
    glDeleteShader(f);
    return p;
}

// Zoom: the field is magnified about a centre by multiplying angular distance from it (spike: azimuthal-equidistant).
// Kotlin picks with the same map inverted (quest-logic Zoom), so a pick lands on what is drawn.
#define ZOOM_GLSL R"(
uniform vec4 uZoom;                   // xyz: zoom centre (true sky, world frame), w: magnification
vec3 zoomFwd(vec3 w) {                // true direction -> displayed direction; vec3(0) if pushed past the antipode
    if (uZoom.w > 1.001) {
        float c = clamp(dot(w, uZoom.xyz), -1.0, 1.0);
        vec3 p = w - uZoom.xyz * c;
        float pl = length(p);
        if (pl > 1e-6) {
            float t = acos(c) * uZoom.w;
            if (t > 3.1) return vec3(0.0);
            w = uZoom.xyz * cos(t) + p * (sin(t) / pl);
        }
    }
    return w;
}
)"

// Stars: J2000 unit vector + magnitude, rotated to the world by uRot (Kotlin: horizon frame of the shown time).
const char* kStarVS = R"(#version 300 es)" ZOOM_GLSL R"(
layout(location=0) in vec3 aDir;
layout(location=1) in float aMag;
uniform mat4 uVP;
uniform mat3 uRot;
uniform float uPx;
uniform float uLimit;
uniform float uBright;
uniform float uNight;
out vec3 vCol;
void main() {
    vec3 w = zoomFwd(uRot * aDir);
    bool hide = dot(w, w) < 0.25 || w.y < 0.0 || aMag > uLimit;     // past the antipode, below the ground, too faint
    gl_Position = hide ? vec4(2.0, 2.0, 2.0, 1.0) : uVP * vec4(w * 900.0, 1.0);
    gl_PointSize = hide ? 0.0 : min(max(1.5, 1.4 + 0.9 * (6.5 - aMag)), 14.0) * uPx;
    float lum = clamp(1.0 - 0.09 * (aMag + 0.5), 0.12, 1.0) * uBright;
    lum *= smoothstep(0.0, 0.05, w.y);                               // extinction into the horizon haze
    vCol = (uNight > 0.5 ? vec3(1.0, 0.0, 0.0) : vec3(1.0, 0.97, 0.93)) * lum;
}
)";
const char* kStarFS = R"(#version 300 es
precision mediump float;
in vec3 vCol;
out vec4 o;
void main() {
    vec2 d = gl_PointCoord * 2.0 - 1.0;
    float r2 = dot(d, d);
    o = vec4(vCol * exp(-r2 * 4.0) * step(r2, 1.0), 1.0);
}
)";

// Lines in J2000 (constellation figures): rotated like the stars.
const char* kSkyLineVS = R"(#version 300 es)" ZOOM_GLSL R"(
layout(location=0) in vec3 aDir;
uniform mat4 uVP;
uniform mat3 uRot;
out float vUp;
void main() {
    vec3 t = uRot * aDir;
    vec3 w = zoomFwd(t);
    bool hide = dot(w, w) < 0.25;
    if (hide) w = t;
    vUp = hide ? -1.0 : w.y;
    gl_Position = uVP * vec4(w * 890.0, 1.0);
}
)";
const char* kSkyLineFS = R"(#version 300 es
precision mediump float;
in float vUp;
uniform vec3 uColor;
out vec4 o;
void main() {
    if (vUp < 0.0) discard;
    o = vec4(uColor * mix(0.4, 1.0, smoothstep(0.0, 0.1, vUp)), 1.0);
}
)";

// Deep-sky symbols and bodies: a point sprite per object, the shape drawn in the fragment shader by kind.
const char* kMarkVS = R"(#version 300 es)" ZOOM_GLSL R"(
layout(location=0) in vec3 aDir;
layout(location=1) in vec2 aSizeKind;     // x: size in degrees, y: kind
uniform mat4 uVP;
uniform mat3 uRot;
uniform float uPx;
out float vKind;
void main() {
    vec3 w = zoomFwd(uRot * aDir);
    bool hide = dot(w, w) < 0.25 || w.y < 0.0;
    gl_Position = hide ? vec4(2.0, 2.0, 2.0, 1.0) : uVP * vec4(w * 885.0, 1.0);
    float px = aSizeKind.y > 5.5 ? 16.0 : clamp(aSizeKind.x * uZoom.w * 20.0, 20.0, 110.0);
    gl_PointSize = hide ? 0.0 : px * uPx;
    vKind = aSizeKind.y;
}
)";
const char* kMarkFS = R"(#version 300 es
precision mediump float;
in float vKind;
uniform vec3 uColor;
out vec4 o;
void main() {
    vec2 p = gl_PointCoord * 2.0 - 1.0;
    float r = length(p);
    float a = 0.0;
    if (vKind > 5.5) {                                                 // body: a filled disc
        a = 1.0 - smoothstep(0.7, 0.95, r);
    } else if (vKind > 4.5) {                                          // nebula / remnant: a square
        float m = max(abs(p.x), abs(p.y));
        a = smoothstep(0.62, 0.7, m) * (1.0 - smoothstep(0.78, 0.86, m));
    } else {
        float ring = (vKind > 0.5 && vKind < 1.5) ? length(vec2(p.x * 0.7, p.y * 1.25)) : r;   // galaxy: an ellipse
        a = smoothstep(0.66, 0.74, ring) * (1.0 - smoothstep(0.82, 0.9, ring));
        if (vKind > 1.5 && vKind < 2.5) a *= step(0.5, fract(atan(p.y, p.x) * 1.9099));        // open cluster: dashed
        if (vKind > 2.5 && vKind < 3.5) a = max(a, step(abs(p.x), 0.06) * step(r, 0.74) + step(abs(p.y), 0.06) * step(r, 0.74)); // globular: a cross
        if (vKind > 3.5 && vKind < 4.5) a = max(a, step(0.74, r) * step(r, 0.95) * step(abs(p.x), 0.07)); // planetary: ticks
    }
    if (r > 1.0) discard;
    o = vec4(uColor * clamp(a, 0.0, 1.0), 1.0);
}
)";

// Overlay shapes already in the world frame, true sky directions (the horizon, rings, the pointing cross, the field frame,
// the selection ring): coloured lines and triangles, zoomed with the sky.
const char* kOverVS = R"(#version 300 es)" ZOOM_GLSL R"(
layout(location=0) in vec3 aPos;
layout(location=1) in vec4 aCol;
uniform mat4 uVP;
out vec4 vCol;
void main() {
    vec3 w = zoomFwd(normalize(aPos));
    bool hide = dot(w, w) < 0.25;
    gl_Position = hide ? vec4(2.0, 2.0, 2.0, 1.0) : uVP * vec4(w * 880.0, 1.0);
    vCol = aCol;
}
)";
const char* kOverFS = R"(#version 300 es
precision mediump float;
in vec4 vCol;
out vec4 o;
void main() { o = vCol; }
)";

// Solid shapes with a full view (the ground, the lasers) and textured label quads.
const char* kSolidVS = R"(#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uVP;
void main() { gl_Position = uVP * vec4(aPos, 1.0); }
)";
const char* kSolidFS = R"(#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 o;
void main() { o = uColor; }
)";
const char* kLabelVS = R"(#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec2 aUv;
uniform mat4 uVP;
out vec2 vUv;
void main() { vUv = aUv; gl_Position = uVP * vec4(aPos, 1.0); }
)";
const char* kLabelFS = R"(#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec3 uColor;
out vec4 o;
void main() {
    float a = texture(uTex, vUv).a;
    o = vec4(uColor * a, a);
}
)";

// A pane's content arrives as an Android surface (a SurfaceTexture, sampled as an external texture) and is copied into the
// pane's OpenXR swapchain each frame. This replaces XR_KHR_android_surface_swapchain, which this runtime refuses.
const char* kPaneVS = R"(#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)";
const char* kPaneFS = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vUv;
uniform samplerExternalOES uTex;
uniform mat4 uTexMat;
uniform float uToLinear;
out vec4 o;
void main() {
    vec4 c = texture(uTex, (uTexMat * vec4(vUv, 0.0, 1.0)).xy);
    if (uToLinear > 0.5) c.rgb = pow(c.rgb, vec3(2.2));
    o = c;
}
)";

// Controllers, drawn the way the atlas spike drew them: the runtime's own controller model when it has one (XR_FB_render_model),
// else a procedural virtual hand whose fingers follow the trigger, grip and thumb touch.
const char* kHandVS = R"(#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNrm;
uniform mat4 uMVP;
uniform mat4 uModel;
out vec3 vN;
void main() { vN = mat3(uModel) * aNrm; gl_Position = uMVP * vec4(aPos, 1.0); }
)";
const char* kHandFS = R"(#version 300 es
precision mediump float;
in vec3 vN;
uniform vec3 uColor;
out vec4 o;
void main() {
    float l = 0.25 + 0.75 * max(dot(normalize(vN), normalize(vec3(0.3, 0.8, 0.5))), 0.0);
    o = vec4(uColor * l, 1.0);
}
)";
// Controller model: vertex colours come from the glTF materials (the runtime's textures are KTX2 and are not decoded).
const char* kCtlVS = R"(#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNrm;
layout(location=2) in vec3 aCol;
uniform mat4 uMVP;
uniform mat4 uModel;
out vec3 vN;
out vec3 vC;
void main() { vN = mat3(uModel) * aNrm; vC = aCol; gl_Position = uMVP * vec4(aPos, 1.0); }
)";
const char* kCtlFS = R"(#version 300 es
precision mediump float;
in vec3 vN;
in vec3 vC;
uniform float uNight;
out vec4 o;
void main() {
    vec3 n = normalize(vN);
    float l = 0.30 + 0.55 * max(dot(n, normalize(vec3(0.3, 0.8, 0.5))), 0.0) + 0.25 * max(dot(n, normalize(vec3(-0.6, 0.2, -0.4))), 0.0);
    vec3 c = vC * l;
    if (uNight > 0.5) c = vec3(dot(c, vec3(0.30, 0.59, 0.11)), 0.0, 0.0) * vec3(0.9, 0.0, 0.0);
    o = vec4(c, 1.0);
}
)";

Mat4 rotFromQuat(const XrQuaternionf& q) {
    float x = q.x, y = q.y, z = q.z, w = q.w;
    Mat4 m = identity();
    m.m[0] = 1 - 2 * (y * y + z * z); m.m[4] = 2 * (x * y - z * w);     m.m[8] = 2 * (x * z + y * w);
    m.m[1] = 2 * (x * y + z * w);     m.m[5] = 1 - 2 * (x * x + z * z); m.m[9] = 2 * (y * z - x * w);
    m.m[2] = 2 * (x * z - y * w);     m.m[6] = 2 * (y * z + x * w);     m.m[10] = 1 - 2 * (x * x + y * y);
    return m;
}


// -------- controller models (XR_FB_render_model): a GLB per controller, flattened to coloured triangles

struct JV {  // just enough JSON for a glTF
    enum T { Null, Bool, Num, Str, Arr, Obj } t = Null;
    double n = 0;
    std::string s;
    std::vector<JV> a;
    std::vector<std::pair<std::string, JV>> o;
    const JV* get(const char* k) const {
        for (const auto& kv : o) if (kv.first == k) return &kv.second;
        return nullptr;
    }
    const JV* at(size_t i) const { return i < a.size() ? &a[i] : nullptr; }
    double num(double d = 0) const { return t == Num ? n : d; }
};

struct JsonParser {
    const char* p;
    const char* e;
    void ws() { while (p < e && (*p == ' ' || *p == '\n' || *p == '\r' || *p == '\t')) ++p; }
    bool str(std::string& out) {
        if (p >= e || *p != '"') return false;
        ++p;
        while (p < e && *p != '"') {
            if (*p == '\\' && p + 1 < e) {
                ++p;
                char c = *p;
                if (c == 'n') out += '\n';
                else if (c == 't') out += '\t';
                else if (c == 'u') { out += '?'; p += 4; }
                else out += c;
                ++p;
            } else {
                out += *p++;
            }
        }
        if (p >= e) return false;
        ++p;
        return true;
    }
    bool val(JV& v, int depth = 0) {
        if (depth > 32) return false;
        ws();
        if (p >= e) return false;
        if (*p == '{') {
            v.t = JV::Obj;
            ++p;
            ws();
            if (p < e && *p == '}') { ++p; return true; }
            for (;;) {
                ws();
                std::string k;
                if (!str(k)) return false;
                ws();
                if (p >= e || *p != ':') return false;
                ++p;
                v.o.emplace_back(k, JV{});
                if (!val(v.o.back().second, depth + 1)) return false;
                ws();
                if (p < e && *p == ',') { ++p; continue; }
                if (p < e && *p == '}') { ++p; return true; }
                return false;
            }
        }
        if (*p == '[') {
            v.t = JV::Arr;
            ++p;
            ws();
            if (p < e && *p == ']') { ++p; return true; }
            for (;;) {
                v.a.emplace_back();
                if (!val(v.a.back(), depth + 1)) return false;
                ws();
                if (p < e && *p == ',') { ++p; continue; }
                if (p < e && *p == ']') { ++p; return true; }
                return false;
            }
        }
        if (*p == '"') { v.t = JV::Str; return str(v.s); }
        if (e - p >= 4 && !strncmp(p, "true", 4)) { v.t = JV::Bool; v.n = 1; p += 4; return true; }
        if (e - p >= 5 && !strncmp(p, "false", 5)) { v.t = JV::Bool; p += 5; return true; }
        if (e - p >= 4 && !strncmp(p, "null", 4)) { p += 4; return true; }
        char* end = nullptr;
        v.n = strtod(p, &end);
        if (end == p) return false;
        v.t = JV::Num;
        p = end;
        return true;
    }
};

// Flatten every triangle primitive of a GLB's default scene into pos(3) normal(3) colour(3) vertices in model space.
// Colour is the material's base colour factor, dimmed when the material relies on a texture we cannot decode.
bool buildGlbMesh(const std::vector<uint8_t>& glb, std::vector<float>& out) {
    if (glb.size() < 28 || memcmp(glb.data(), "glTF", 4) != 0) return false;
    uint32_t jl;
    memcpy(&jl, glb.data() + 12, 4);
    if (20 + size_t(jl) + 8 > glb.size()) return false;
    const char* js = reinterpret_cast<const char*>(glb.data()) + 20;
    JV root;
    JsonParser jp{js, js + jl};
    if (!jp.val(root)) return false;
    uint32_t bl;
    memcpy(&bl, glb.data() + 20 + jl, 4);
    const uint8_t* bin = glb.data() + 20 + jl + 8;
    const size_t binLen = std::min<size_t>(bl, glb.size() - (20 + jl + 8));

    const JV* accessors = root.get("accessors");
    const JV* views = root.get("bufferViews");
    const JV* meshes = root.get("meshes");
    const JV* nodes = root.get("nodes");
    const JV* materials = root.get("materials");
    if (!accessors || !views || !meshes || !nodes) return false;

    struct Acc { const uint8_t* base; size_t count, stride; int ctype; };
    auto access = [&](double idx, int comps, Acc& a) {
        const JV* ac = accessors->at(size_t(idx));
        if (!ac) return false;
        const JV* bv = views->at(size_t(ac->get("bufferView") ? ac->get("bufferView")->num(-1) : -1));
        if (!bv) return false;
        a.count = size_t(ac->get("count") ? ac->get("count")->num() : 0);
        a.ctype = int(ac->get("componentType") ? ac->get("componentType")->num() : 0);
        size_t csz = (a.ctype == 5126 || a.ctype == 5125) ? 4 : a.ctype == 5123 ? 2 : 1;
        size_t off = size_t(bv->get("byteOffset") ? bv->get("byteOffset")->num() : 0) +
                     size_t(ac->get("byteOffset") ? ac->get("byteOffset")->num() : 0);
        a.stride = size_t(bv->get("byteStride") ? bv->get("byteStride")->num() : 0);
        if (!a.stride) a.stride = csz * size_t(comps);
        if (off + (a.count ? (a.count - 1) * a.stride + csz * size_t(comps) : 0) > binLen) return false;
        a.base = bin + off;
        return true;
    };
    auto readIndex = [](const Acc& a, size_t i) -> uint32_t {
        const uint8_t* q = a.base + i * a.stride;
        if (a.ctype == 5125) { uint32_t v; memcpy(&v, q, 4); return v; }
        if (a.ctype == 5123) { uint16_t v; memcpy(&v, q, 2); return v; }
        return *q;
    };
    auto readVec3 = [](const Acc& a, size_t i) {
        float f[3];
        memcpy(f, a.base + i * a.stride, 12);
        return Vec3{f[0], f[1], f[2]};
    };

    bool ok = false;
    std::function<void(size_t, const Mat4&, int)> walk = [&](size_t ni, const Mat4& parent, int depth) {
        const JV* nd = nodes->at(ni);
        if (!nd || depth > 32) return;
        Mat4 local = identity();
        if (const JV* m = nd->get("matrix")) {
            for (int k = 0; k < 16 && size_t(k) < m->a.size(); ++k) local.m[k] = float(m->a[size_t(k)].num());
        } else {
            if (const JV* r = nd->get("rotation")) {
                XrQuaternionf q{float(r->at(0) ? r->at(0)->num() : 0), float(r->at(1) ? r->at(1)->num() : 0),
                                float(r->at(2) ? r->at(2)->num() : 0), float(r->at(3) ? r->at(3)->num(1) : 1)};
                local = rotFromQuat(q);
            }
            if (const JV* sc = nd->get("scale")) {
                for (int c = 0; c < 3; ++c) {
                    float f = float(sc->at(size_t(c)) ? sc->at(size_t(c))->num(1) : 1);
                    for (int r = 0; r < 3; ++r) local.m[c * 4 + r] *= f;
                }
            }
            if (const JV* tr = nd->get("translation"))
                for (int c = 0; c < 3; ++c) local.m[12 + c] = float(tr->at(size_t(c)) ? tr->at(size_t(c))->num() : 0);
        }
        Mat4 world = mul(parent, local);
        if (const JV* mi = nd->get("mesh")) {
            const JV* mesh = meshes->at(size_t(mi->num()));
            const JV* prims = mesh ? mesh->get("primitives") : nullptr;
            for (size_t pi = 0; prims && pi < prims->a.size(); ++pi) {
                const JV& pr = prims->a[pi];
                if (pr.get("mode") && pr.get("mode")->num(4) != 4) continue;
                const JV* at = pr.get("attributes");
                if (!at || !at->get("POSITION")) continue;
                Acc ap{}, an{}, ai{};
                bool hasN = at->get("NORMAL") != nullptr;
                if (!access(at->get("POSITION")->num(), 3, ap) || ap.ctype != 5126) continue;
                if (hasN && (!access(at->get("NORMAL")->num(), 3, an) || an.ctype != 5126 || an.count < ap.count)) hasN = false;
                bool indexed = pr.get("indices") != nullptr;
                if (indexed && !access(pr.get("indices")->num(), 1, ai)) continue;
                float col[3] = {0.6f, 0.6f, 0.6f};
                if (materials && pr.get("material")) {
                    const JV* mat = materials->at(size_t(pr.get("material")->num()));
                    const JV* pbr = mat ? mat->get("pbrMetallicRoughness") : nullptr;
                    if (const JV* bc = pbr ? pbr->get("baseColorFactor") : nullptr)
                        for (int k = 0; k < 3; ++k) col[k] = float(bc->at(size_t(k)) ? bc->at(size_t(k))->num(1) : 1);
                    else col[0] = col[1] = col[2] = 1.f;
                    if (pbr && pbr->get("baseColorTexture"))
                        for (float& c : col) c *= 0.55f;
                }
                size_t n = indexed ? ai.count : ap.count;
                for (size_t k = 0; k + 2 < n; k += 3) {
                    Vec3 P3[3], N3[3];
                    bool bad = false;
                    for (int c = 0; c < 3; ++c) {
                        size_t vi = indexed ? readIndex(ai, k + size_t(c)) : k + size_t(c);
                        if (vi >= ap.count) { bad = true; break; }
                        Vec3 p = readVec3(ap, vi);
                        P3[c] = {world.m[0] * p.x + world.m[4] * p.y + world.m[8] * p.z + world.m[12],
                                 world.m[1] * p.x + world.m[5] * p.y + world.m[9] * p.z + world.m[13],
                                 world.m[2] * p.x + world.m[6] * p.y + world.m[10] * p.z + world.m[14]};
                        if (hasN) {
                            Vec3 nn = readVec3(an, vi);
                            Vec3 t{world.m[0] * nn.x + world.m[4] * nn.y + world.m[8] * nn.z,
                                   world.m[1] * nn.x + world.m[5] * nn.y + world.m[9] * nn.z,
                                   world.m[2] * nn.x + world.m[6] * nn.y + world.m[10] * nn.z};
                            N3[c] = dot(t, t) > 1e-12f ? normalize(t) : Vec3{0, 1, 0};
                        }
                    }
                    if (bad) continue;
                    if (!hasN) {
                        Vec3 fn = cross(P3[1] - P3[0], P3[2] - P3[0]);
                        fn = dot(fn, fn) > 1e-18f ? normalize(fn) : Vec3{0, 1, 0};
                        N3[0] = N3[1] = N3[2] = fn;
                    }
                    for (int c = 0; c < 3; ++c)
                        out.insert(out.end(), {P3[c].x, P3[c].y, P3[c].z, N3[c].x, N3[c].y, N3[c].z, col[0], col[1], col[2]});
                    ok = true;
                }
            }
        }
        if (const JV* ch = nd->get("children"))
            for (const JV& c : ch->a) walk(size_t(c.num()), world, depth + 1);
    };
    const JV* scenes = root.get("scenes");
    const JV* sc = scenes ? scenes->at(size_t(root.get("scene") ? root.get("scene")->num() : 0)) : nullptr;
    if (const JV* roots = sc ? sc->get("nodes") : nullptr) {
        for (const JV& r : roots->a) walk(size_t(r.num()), identity(), 0);
    } else {
        for (size_t i = 0; i < nodes->a.size(); ++i) walk(i, identity(), 0);  // no scene: every node (children repeat; rare)
    }
    return ok;
}

// ------------------------------------------------------------------ state

struct SkyState {
    float rot[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};  // J2000 -> world, column-major mat3
    float zoomCentre[3] = {0, 0, -1};
    float zoom = 1.f;
    float limit = 6.5f;
    float brightness = 0.55f;
    int flags = 1 | 2 | 4;  // 1 night, 2 ground, 4 constellations
    float ground[4] = {0.07f, 0.f, 0.f, 1.f};
};

struct QuadSlot {
    bool wanted = false;     // Kotlin asked for a surface
    bool created = false;
    bool visible = false;
    int pw = 0, ph = 0;
    XrSwapchain sc = XR_NULL_HANDLE;
    jobject surface = nullptr;  // global ref: the android.view.Surface Kotlin draws into
    jobject stex = nullptr;     // global ref: the SurfaceTexture behind it
    GLuint oesTex = 0;
    bool srgb = false;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
    std::vector<GLuint> fbos;
    float c[3] = {0, 0, -1}, r[3] = {1, 0, 0}, u[3] = {0, 1, 0};
    float w = 1.f, h = 1.f;
};

struct Hand {
    XrSpace aim = XR_NULL_HANDLE, grip = XR_NULL_HANDLE;
    bool ok = false;
    Vec3 pos{0, 0, 0}, dir{0, 0, -1};
    Vec3 gripPos{0, 0, 0};
    XrQuaternionf aimQ{0, 0, 0, 1}, gripQ{0, 0, 0, 1};
    float trigger = 0, squeeze = 0, sx = 0, sy = 0, thumb = 0;
    bool stickClick = false;
    std::vector<float> mesh;  // the procedural hand: position and normal triangles in aim space, rebuilt as the fingers move
};

void pushVert(std::vector<float>& v, Vec3 p, Vec3 n) { v.insert(v.end(), {p.x, p.y, p.z, n.x, n.y, n.z}); }

void addTube(std::vector<float>& v, Vec3 a, Vec3 b, float ra, float rb) {
    Vec3 ax = b - a;
    float len = std::sqrt(dot(ax, ax));
    if (len < 1e-6f) return;
    ax = ax * (1.f / len);
    Vec3 t = std::fabs(ax.y) < 0.9f ? Vec3{0, 1, 0} : Vec3{1, 0, 0};
    Vec3 u = normalize(cross(ax, t)), w = cross(ax, u);
    const int n = 8;
    for (int i = 0; i < n; ++i) {
        float t0 = 2.f * float(kPi) * float(i) / n, t1 = 2.f * float(kPi) * float(i + 1) / n;
        Vec3 n0 = u * std::cos(t0) + w * std::sin(t0), n1 = u * std::cos(t1) + w * std::sin(t1);
        Vec3 p00 = a + n0 * ra, p01 = a + n1 * ra, p10 = b + n0 * rb, p11 = b + n1 * rb;
        pushVert(v, p00, n0); pushVert(v, p10, n0); pushVert(v, p01, n1);
        pushVert(v, p01, n1); pushVert(v, p10, n0); pushVert(v, p11, n1);
    }
}

void addEllipsoid(std::vector<float>& v, Vec3 c, float rx, float ry, float rz) {
    const int nu = 10, nv = 6;
    auto at = [&](int i, int j, Vec3& n) {
        float th = 2.f * float(kPi) * float(i) / nu, ph = float(kPi) * float(j) / nv - float(kPi) / 2.f;
        float cx = std::cos(ph) * std::cos(th), cy = std::sin(ph), cz = std::cos(ph) * std::sin(th);
        n = normalize(Vec3{cx / rx, cy / ry, cz / rz});
        return Vec3{c.x + rx * cx, c.y + ry * cy, c.z + rz * cz};
    };
    for (int j = 0; j < nv; ++j)
        for (int i = 0; i < nu; ++i) {
            Vec3 n00, n10, n01, n11;
            Vec3 p00 = at(i, j, n00), p10 = at(i + 1, j, n10), p01 = at(i, j + 1, n01), p11 = at(i + 1, j + 1, n11);
            pushVert(v, p00, n00); pushVert(v, p10, n10); pushVert(v, p01, n01);
            pushVert(v, p01, n01); pushVert(v, p10, n10); pushVert(v, p11, n11);
        }
}

// Hand space = the aim frame: +X right, +Y up, -Z forward. Thumb up, fingers forward, palm facing the other
// hand (-X for the right hand, +X for the left), which is how Touch controllers are held.
void buildHand(Hand& h, int side) {
    std::vector<float>& v = h.mesh;
    v.clear();
    const float s = side == 1 ? -1.f : 1.f;  // curl direction in X: toward the palm
    addEllipsoid(v, {0, 0, -0.005f}, 0.018f, 0.040f, 0.050f);   // palm
    addTube(v, {0, 0, 0.035f}, {0, 0, 0.11f}, 0.026f, 0.030f);  // forearm stub

    struct Finger { float y; float len[3]; float r; float curl; };
    const Finger fingers[4] = {
        {0.030f, {0.040f, 0.024f, 0.020f}, 0.0085f, std::max(0.2f, h.trigger)},   // index follows the trigger
        {0.010f, {0.044f, 0.027f, 0.022f}, 0.0090f, std::max(0.4f, h.squeeze)},   // the rest follow the grip
        {-0.010f, {0.040f, 0.025f, 0.020f}, 0.0085f, std::max(0.4f, h.squeeze)},
        {-0.028f, {0.032f, 0.019f, 0.016f}, 0.0075f, std::max(0.4f, h.squeeze)}};
    const float joint[3] = {1.35f, 1.6f, 1.1f};  // radians at full curl: ~232 degrees in total
    for (const Finger& f : fingers) {
        Vec3 p{0, f.y, -0.048f};
        addEllipsoid(v, p, f.r * 1.1f, f.r * 1.1f, f.r * 1.1f);  // knuckle
        float phi = 0.f;
        for (int j = 0; j < 3; ++j) {
            phi += f.curl * joint[j];
            Vec3 d{s * std::sin(phi), 0.f, -std::cos(phi)};
            Vec3 q = p + d * f.len[j];
            float r0 = f.r * (1.f - 0.12f * float(j)), r1 = f.r * (1.f - 0.12f * float(j + 1));
            addTube(v, p, q, r0, r1);
            addEllipsoid(v, q, r1 * 1.05f, r1 * 1.05f, r1 * 1.05f);
            p = q;
        }
    }

    const Vec3 ext = normalize(Vec3{s * 0.15f, 0.80f, -0.55f}), tuck = normalize(Vec3{s * 0.75f, 0.15f, -0.55f});
    const float tlen[2] = {0.036f, 0.030f}, trad[3] = {0.0105f, 0.0095f, 0.0085f};
    Vec3 p{0, 0.036f, -0.010f};
    for (int j = 0; j < 2; ++j) {
        float t = std::min(1.f, h.thumb * (j == 0 ? 0.7f : 1.f));
        Vec3 q = p + normalize(ext * (1.f - t) + tuck * t) * tlen[j];
        addTube(v, p, q, trad[j], trad[j + 1]);
        addEllipsoid(v, q, trad[j + 1] * 1.05f, trad[j + 1] * 1.05f, trad[j + 1] * 1.05f);
        p = q;
    }
}



struct EyeSwapchain {
    XrSwapchain handle = XR_NULL_HANDLE;
    int32_t w = 0, h = 0;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
    std::vector<GLuint> fbos;
    GLuint depth = 0;
};

struct G {
    android_app* android = nullptr;
    JNIEnv* env = nullptr;
    bool resumed = false, quit = false, initDone = false, sessionRunning = false;
    XrInstance inst = XR_NULL_HANDLE;
    XrSystemId sys = XR_NULL_SYSTEM_ID;
    XrSession sess = XR_NULL_HANDLE;
    XrSpace space = XR_NULL_HANDLE;
    XrSessionState state = XR_SESSION_STATE_UNKNOWN;
    EyeSwapchain eye[2];
    EGLDisplay dpy = EGL_NO_DISPLAY;
    EGLConfig cfg = nullptr;
    EGLContext ctx = EGL_NO_CONTEXT;
    EGLSurface surf = EGL_NO_SURFACE;

    PFN_xrCreateSwapchainAndroidSurfaceKHR xrCreateSurfaceSwapchain = nullptr;
    PFN_xrCreatePassthroughFB xrCreatePassthrough = nullptr;
    PFN_xrDestroyPassthroughFB xrDestroyPassthrough = nullptr;
    PFN_xrPassthroughStartFB xrPassthroughStart = nullptr;
    PFN_xrPassthroughPauseFB xrPassthroughPause = nullptr;
    PFN_xrCreatePassthroughLayerFB xrCreatePassthroughLayer = nullptr;
    PFN_xrDestroyPassthroughLayerFB xrDestroyPassthroughLayer = nullptr;
    PFN_xrEnumerateDisplayRefreshRatesFB xrEnumRates = nullptr;
    PFN_xrRequestDisplayRefreshRateFB xrRequestRate = nullptr;
    XrPassthroughFB passthrough = XR_NULL_HANDLE;
    XrPassthroughLayerFB passthroughLayer = XR_NULL_HANDLE;
    bool passthroughOk = false;

    // input
    XrActionSet set = XR_NULL_HANDLE;
    XrAction aStick = XR_NULL_HANDLE, aTrig = XR_NULL_HANDLE, aSqz = XR_NULL_HANDLE, aClick = XR_NULL_HANDLE,
             aAim = XR_NULL_HANDLE, aA = XR_NULL_HANDLE, aB = XR_NULL_HANDLE, aX = XR_NULL_HANDLE, aY = XR_NULL_HANDLE,
             aMenu = XR_NULL_HANDLE;
    XrPath handPath[2] = {0, 0};
    Hand hand[2];  // 0 left, 1 right
    bool btnA = false, btnB = false, btnX = false, btnY = false, btnMenu = false;
    Vec3 headPos{0, 0, 0}, headFwd{0, 0, -1}, headUp{0, 1, 0};

    // GL
    PFN_xrEnumerateRenderModelPathsFB xrEnumPaths = nullptr;
    PFN_xrGetRenderModelPropertiesFB xrModelProps = nullptr;
    PFN_xrLoadRenderModelFB xrLoadModel = nullptr;
    GLuint handProg = 0, ctlProg = 0, handVao = 0, handVbo = 0, ctlVao[2] = {0, 0}, ctlVbo[2] = {0, 0};
    int ctlVerts[2] = {0, 0}, ctlTries = 0;
    double ctlNext = 0;
    XrAction aGrip = XR_NULL_HANDLE, aThumbA = XR_NULL_HANDLE, aThumbB = XR_NULL_HANDLE;
    GLuint paneProg = 0, starProg = 0, skyLineProg = 0, markProg = 0, overProg = 0, solidProg = 0, labelProg = 0;
    GLuint starVao = 0, starVbo = 0, lineVao = 0, lineVbo = 0, markVao = 0, markVbo = 0;
    GLuint overVao = 0, overVbo = 0, dynVao = 0, dynVbo = 0, labelTex = 0, groundVao = 0, groundVbo = 0;
    int starCount = 0, lineVerts = 0, markCount = 0, overLineVerts = 0, overTriVerts = 0, labelCount = 0;
    float eyeHeight = kEyeHeight;

    // Kotlin -> native, guarded by mu; dirty flags tell the render thread what to upload.
    std::mutex mu;
    SkyState sky;
    std::vector<float> stars, lines, marks, overLines, overTris, labels;
    bool dStars = false, dLines = false, dMarks = false, dOver = false, dLabels = false;
    std::vector<uint8_t> atlas;
    int atlasW = 0, atlasH = 0;
    bool dAtlas = false;
    QuadSlot quad[kMaxQuads];
    bool wantPassthrough = false;
    float laserLen[2] = {-1.f, -1.f};  // metres, negative = hidden
    float snapshot[40] = {0};
    int rateHz = 72;
} g;

// ------------------------------------------------------------------ GL resources

bool initEgl() {
    g.dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(g.dpy, nullptr, nullptr);
    const EGLint attrs[] = {EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLint n = 0;
    if (!eglChooseConfig(g.dpy, attrs, &g.cfg, 1, &n) || n < 1) { LOGE("no EGL config"); return false; }
    const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    g.ctx = eglCreateContext(g.dpy, g.cfg, EGL_NO_CONTEXT, ctxAttrs);
    const EGLint sAttrs[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    g.surf = eglCreatePbufferSurface(g.dpy, g.cfg, sAttrs);
    if (g.ctx == EGL_NO_CONTEXT || g.surf == EGL_NO_SURFACE) { LOGE("EGL context failed"); return false; }
    return eglMakeCurrent(g.dpy, g.surf, g.surf, g.ctx) == EGL_TRUE;
}

void makeBuffer(GLuint& vao, GLuint& vbo) {
    glGenVertexArrays(1, &vao);
    glGenBuffers(1, &vbo);
}

void initGlResources() {
    g.starProg = program(kStarVS, kStarFS);
    g.skyLineProg = program(kSkyLineVS, kSkyLineFS);
    g.markProg = program(kMarkVS, kMarkFS);
    g.overProg = program(kOverVS, kOverFS);
    g.solidProg = program(kSolidVS, kSolidFS);
    g.labelProg = program(kLabelVS, kLabelFS);
    g.paneProg = program(kPaneVS, kPaneFS);
    g.handProg = program(kHandVS, kHandFS);
    g.ctlProg = program(kCtlVS, kCtlFS);
    glGenVertexArrays(1, &g.handVao);
    glGenBuffers(1, &g.handVbo);
    glBindVertexArray(g.handVao);
    glBindBuffer(GL_ARRAY_BUFFER, g.handVbo);
    for (int a = 0; a < 2; ++a) {
        glEnableVertexAttribArray(GLuint(a));
        glVertexAttribPointer(GLuint(a), 3, GL_FLOAT, GL_FALSE, 24, (void*)(uintptr_t(a) * 12));
    }
    glBindVertexArray(0);

    makeBuffer(g.starVao, g.starVbo);
    glBindVertexArray(g.starVao);
    glBindBuffer(GL_ARRAY_BUFFER, g.starVbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 16, nullptr);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 1, GL_FLOAT, GL_FALSE, 16, (void*)12);

    makeBuffer(g.lineVao, g.lineVbo);
    glBindVertexArray(g.lineVao);
    glBindBuffer(GL_ARRAY_BUFFER, g.lineVbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 12, nullptr);

    makeBuffer(g.markVao, g.markVbo);
    glBindVertexArray(g.markVao);
    glBindBuffer(GL_ARRAY_BUFFER, g.markVbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 20, nullptr);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 20, (void*)12);

    makeBuffer(g.overVao, g.overVbo);
    glBindVertexArray(g.overVao);
    glBindBuffer(GL_ARRAY_BUFFER, g.overVbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 28, nullptr);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, 28, (void*)12);

    // streaming buffer for the lasers and the label quads: position (+ uv)
    makeBuffer(g.dynVao, g.dynVbo);

    // the ground: a disc under the user
    makeBuffer(g.groundVao, g.groundVbo);
    glBindVertexArray(g.groundVao);
    glBindBuffer(GL_ARRAY_BUFFER, g.groundVbo);
    std::vector<float> disc = {0, 0, 0};
    for (int i = 0; i <= 48; ++i) {
        float a = 2.f * kPi * i / 48.f;
        disc.insert(disc.end(), {1500.f * std::cos(a), 0.f, 1500.f * std::sin(a)});
    }
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(disc.size() * 4), disc.data(), GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 12, nullptr);

    glGenTextures(1, &g.labelTex);
    glBindTexture(GL_TEXTURE_2D, g.labelTex);
    const uint8_t blank[4] = {0, 0, 0, 0};
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, blank);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindVertexArray(0);
}

void upload(GLuint vbo, const std::vector<float>& v) {
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(v.size() * 4), v.empty() ? nullptr : v.data(), GL_DYNAMIC_DRAW);
}

// What Kotlin has pushed since the last frame, copied to the GPU. Held only briefly under the mutex.
void applyPending(SkyState& sky, std::vector<float>& labelVerts) {
    std::lock_guard<std::mutex> lk(g.mu);
    sky = g.sky;
    if (g.dStars) { upload(g.starVbo, g.stars); g.starCount = int(g.stars.size() / 4); g.dStars = false; }
    if (g.dLines) { upload(g.lineVbo, g.lines); g.lineVerts = int(g.lines.size() / 3); g.dLines = false; }
    if (g.dMarks) { upload(g.markVbo, g.marks); g.markCount = int(g.marks.size() / 5); g.dMarks = false; }
    if (g.dOver) {
        std::vector<float> both = g.overLines;
        both.insert(both.end(), g.overTris.begin(), g.overTris.end());
        upload(g.overVbo, both);
        g.overLineVerts = int(g.overLines.size() / 7);
        g.overTriVerts = int(g.overTris.size() / 7);
        g.dOver = false;
    }
    if (g.dAtlas && g.atlasW > 0) {
        glBindTexture(GL_TEXTURE_2D, g.labelTex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, g.atlasW, g.atlasH, 0, GL_RGBA, GL_UNSIGNED_BYTE, g.atlas.data());
        g.dAtlas = false;
    }
    labelVerts = g.labels;  // entries, turned into quads below
}

// Displayed direction for a true one (the CPU twin of zoomFwd).
bool zoomFwdCpu(const SkyState& s, Vec3 w, Vec3& out) {
    if (s.zoom > 1.001f) {
        Vec3 c{s.zoomCentre[0], s.zoomCentre[1], s.zoomCentre[2]};
        float cs = std::clamp(dot(w, c), -1.f, 1.f);
        Vec3 p = w - c * cs;
        float pl = length(p);
        if (pl > 1e-6f) {
            float t = std::acos(cs) * s.zoom;
            if (t > 3.1f) return false;
            w = c * std::cos(t) + p * (std::sin(t) / pl);
        }
    }
    out = w;
    return true;
}

// ------------------------------------------------------------------ drawing

void setMat3(GLint loc, const float* m) { glUniformMatrix3fv(loc, 1, GL_FALSE, m); }
void setZoom(GLuint prog, const SkyState& s) {
    glUniform4f(glGetUniformLocation(prog, "uZoom"), s.zoomCentre[0], s.zoomCentre[1], s.zoomCentre[2], s.zoom);
}

void drawLaser(const Mat4& vpFull, const Hand& h, float len, Vec3 eye, bool night) {
    if (!h.ok || len <= 0.f) return;
    Vec3 a = h.pos, b = h.pos + h.dir * len;
    Vec3 side = normalize(cross(h.dir, eye - a)) * 0.004f;
    std::vector<float> v = {a.x - side.x, a.y - side.y, a.z - side.z, a.x + side.x, a.y + side.y, a.z + side.z,
                            b.x - side.x, b.y - side.y, b.z - side.z, b.x + side.x, b.y + side.y, b.z + side.z};
    glUseProgram(g.solidProg);
    glUniformMatrix4fv(glGetUniformLocation(g.solidProg, "uVP"), 1, GL_FALSE, vpFull.m);
    if (night) glUniform4f(glGetUniformLocation(g.solidProg, "uColor"), 1.f, 0.f, 0.f, 1.f);
    else glUniform4f(glGetUniformLocation(g.solidProg, "uColor"), 1.f, 1.f, 1.f, 1.f);
    glBindVertexArray(g.dynVao);
    glBindBuffer(GL_ARRAY_BUFFER, g.dynVbo);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(v.size() * 4), v.data(), GL_STREAM_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 12, nullptr);
    glDisableVertexAttribArray(1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    // a small ring where it lands
    std::vector<float> ring;
    Vec3 right = normalize(cross(h.dir, Vec3{0, 1, 0}));
    Vec3 up = normalize(cross(right, h.dir));
    const int seg = 16;
    float R = 0.012f + 0.012f * len * 0.1f;
    for (int i = 0; i <= seg; ++i) {
        float t = 2.f * kPi * i / seg;
        Vec3 p = b + right * (R * std::cos(t)) + up * (R * std::sin(t));
        ring.insert(ring.end(), {p.x, p.y, p.z});
    }
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(ring.size() * 4), ring.data(), GL_STREAM_DRAW);
    glDrawArrays(GL_LINE_STRIP, 0, GLsizei(ring.size() / 3));
}

void renderEye(int e, const XrView& view, GLuint fbo, const SkyState& sky, const std::vector<float>& labelVerts) {
    EyeSwapchain& sc = g.eye[e];
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glViewport(0, 0, sc.w, sc.h);
    const bool night = sky.flags & 1;
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClearDepthf(1.f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
    glDisable(GL_CULL_FACE);

    Mat4 proj = projection(view.fov, 0.05f, 4000.f);
    Mat4 vpSky = mul(proj, viewRotation(view.pose.orientation));
    Mat4 vpFull = mul(proj, viewFull(view.pose));
    const float px = float(sc.w) / 1800.f;
    const Vec3 eye{view.pose.position.x, view.pose.position.y, view.pose.position.z};

    // ground first: it hides what is below the horizon (depth) and gives the dome its scale
    if (sky.flags & 2) {
        glUseProgram(g.solidProg);
        Mat4 m = identity();
        m.m[12] = view.pose.position.x;  // follow the head across the plane, never rising or falling with it
        m.m[13] = -g.eyeHeight;
        m.m[14] = view.pose.position.z;
        glUniformMatrix4fv(glGetUniformLocation(g.solidProg, "uVP"), 1, GL_FALSE, mul(vpFull, m).m);
        glUniform4fv(glGetUniformLocation(g.solidProg, "uColor"), 1, sky.ground);
        glBindVertexArray(g.groundVao);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 50);
    }

    glDepthMask(GL_FALSE);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE);  // stars and lines add light

    if (g.starCount > 0) {
        glUseProgram(g.starProg);
        glUniformMatrix4fv(glGetUniformLocation(g.starProg, "uVP"), 1, GL_FALSE, vpSky.m);
        setMat3(glGetUniformLocation(g.starProg, "uRot"), sky.rot);
        setZoom(g.starProg, sky);
        glUniform1f(glGetUniformLocation(g.starProg, "uPx"), px);
        glUniform1f(glGetUniformLocation(g.starProg, "uLimit"), sky.limit);
        glUniform1f(glGetUniformLocation(g.starProg, "uBright"), sky.brightness);
        glUniform1f(glGetUniformLocation(g.starProg, "uNight"), night ? 1.f : 0.f);
        glBindVertexArray(g.starVao);
        glDrawArrays(GL_POINTS, 0, g.starCount);
    }
    if ((sky.flags & 4) && g.lineVerts > 0) {
        glUseProgram(g.skyLineProg);
        glUniformMatrix4fv(glGetUniformLocation(g.skyLineProg, "uVP"), 1, GL_FALSE, vpSky.m);
        setMat3(glGetUniformLocation(g.skyLineProg, "uRot"), sky.rot);
        setZoom(g.skyLineProg, sky);
        const float lc = sky.brightness * 0.55f;
        if (night) glUniform3f(glGetUniformLocation(g.skyLineProg, "uColor"), lc, 0.f, 0.f);
        else glUniform3f(glGetUniformLocation(g.skyLineProg, "uColor"), lc * 0.6f, lc * 0.75f, lc);
        glBindVertexArray(g.lineVao);
        glDrawArrays(GL_LINES, 0, g.lineVerts);
    }
    if (g.markCount > 0) {
        glUseProgram(g.markProg);
        glUniformMatrix4fv(glGetUniformLocation(g.markProg, "uVP"), 1, GL_FALSE, vpSky.m);
        setMat3(glGetUniformLocation(g.markProg, "uRot"), sky.rot);
        setZoom(g.markProg, sky);
        glUniform1f(glGetUniformLocation(g.markProg, "uPx"), px);
        const float mc = std::min(1.f, sky.brightness * 1.3f);
        if (night) glUniform3f(glGetUniformLocation(g.markProg, "uColor"), mc, 0.f, 0.f);
        else glUniform3f(glGetUniformLocation(g.markProg, "uColor"), mc * 0.6f, mc, mc * 0.8f);
        glBindVertexArray(g.markVao);
        glDrawArrays(GL_POINTS, 0, g.markCount);
    }

    // overlay shapes (alpha blended)
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    if (g.overLineVerts + g.overTriVerts > 0) {
        glUseProgram(g.overProg);
        glUniformMatrix4fv(glGetUniformLocation(g.overProg, "uVP"), 1, GL_FALSE, vpSky.m);
        setZoom(g.overProg, sky);
        glBindVertexArray(g.overVao);
        if (g.overTriVerts > 0) glDrawArrays(GL_TRIANGLES, g.overLineVerts, g.overTriVerts);
        if (g.overLineVerts > 0) glDrawArrays(GL_LINES, 0, g.overLineVerts);
    }

    // labels: billboards on the dome, kept at a readable size whatever the zoom
    if (!labelVerts.empty()) {
        std::vector<float> q;
        const size_t n = labelVerts.size() / 9;
        q.reserve(n * 30);
        for (size_t i = 0; i < n; ++i) {
            const float* l = &labelVerts[i * 9];
            Vec3 d;
            if (!zoomFwdCpu(sky, normalize({l[0], l[1], l[2]}), d)) continue;
            if (d.y < -0.01f) continue;  // the ground hides it
            // Seen from inside the dome, looking out along d: right = d x up (not up x d, which mirrors the text).
            Vec3 right = normalize(cross(d, Vec3{0, 1, 0}));
            Vec3 up = normalize(cross(right, d));
            float hw = kLabel * l[7] * kPi / 180.f * 0.5f, hh = kLabel * l[8] * kPi / 180.f * 0.5f;
            Vec3 c = d * kLabel;
            Vec3 p00 = c - right * hw + up * hh, p10 = c + right * hw + up * hh;  // top left, top right
            Vec3 p01 = c - right * hw - up * hh, p11 = c + right * hw - up * hh;
            const float u0 = l[3], v0 = l[4], u1 = l[5], v1 = l[6];
            auto push = [&](Vec3 p, float u, float v) { q.insert(q.end(), {p.x, p.y, p.z, u, v}); };
            push(p00, u0, v0); push(p10, u1, v0); push(p01, u0, v1);
            push(p10, u1, v0); push(p11, u1, v1); push(p01, u0, v1);
        }
        if (!q.empty()) {
            glUseProgram(g.labelProg);
            glUniformMatrix4fv(glGetUniformLocation(g.labelProg, "uVP"), 1, GL_FALSE, vpSky.m);
            const float lc = std::min(1.f, sky.brightness * 1.4f);
            if (night) glUniform3f(glGetUniformLocation(g.labelProg, "uColor"), lc, 0.f, 0.f);
            else glUniform3f(glGetUniformLocation(g.labelProg, "uColor"), lc, lc, lc);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, g.labelTex);
            glUniform1i(glGetUniformLocation(g.labelProg, "uTex"), 0);
            glBindVertexArray(g.dynVao);
            glBindBuffer(GL_ARRAY_BUFFER, g.dynVbo);
            glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(q.size() * 4), q.data(), GL_STREAM_DRAW);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 20, nullptr);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 20, (void*)12);
            glDrawArrays(GL_TRIANGLES, 0, GLsizei(q.size() / 5));
        }
    }

    // the controllers, opaque and depth-tested, in the real world
    {
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(GL_TRUE);
        glDisable(GL_BLEND);
        const float skin[3] = {night ? 0.50f : 0.38f, night ? 0.05f : 0.46f, night ? 0.f : 0.58f};
        for (int i = 0; i < 2; ++i) {
            const Hand& h = g.hand[i];
            if (!h.ok) continue;
            if (g.ctlVerts[i] > 0 && g.ctlVao[i]) {  // the runtime's controller model, in grip space
                // The model is turned 180 degrees about its pointing (z) axis, then pitched up 90 degrees in the controller's frame:
                // as loaded its front pointed at the floor with the laser leaving from its top; this brings the front onto the laser.
                Mat4 base = identity();
                base.m[0] = -1.f;
                base.m[5] = -1.f;
                Mat4 frontToLaser = identity();  // rotation about x by +90 degrees: -y (down) goes to -z (forward)
                frontToLaser.m[5] = 0.f;  frontToLaser.m[6] = 1.f;
                frontToLaser.m[9] = -1.f; frontToLaser.m[10] = 0.f;
                Mat4 model = mul(rotFromQuat(h.gripQ), mul(frontToLaser, base));
                model.m[12] = h.gripPos.x;
                model.m[13] = h.gripPos.y;
                model.m[14] = h.gripPos.z;
                Mat4 mvp = mul(vpFull, model);
                glUseProgram(g.ctlProg);
                glUniformMatrix4fv(glGetUniformLocation(g.ctlProg, "uMVP"), 1, GL_FALSE, mvp.m);
                glUniformMatrix4fv(glGetUniformLocation(g.ctlProg, "uModel"), 1, GL_FALSE, model.m);
                glUniform1f(glGetUniformLocation(g.ctlProg, "uNight"), night ? 1.f : 0.f);
                glBindVertexArray(g.ctlVao[i]);
                glDrawArrays(GL_TRIANGLES, 0, g.ctlVerts[i]);
                continue;
            }
            if (h.mesh.empty()) continue;
            Mat4 model = rotFromQuat(h.aimQ);
            model.m[12] = h.gripPos.x;
            model.m[13] = h.gripPos.y;
            model.m[14] = h.gripPos.z;
            Mat4 mvp = mul(vpFull, model);
            glUseProgram(g.handProg);
            glUniform3fv(glGetUniformLocation(g.handProg, "uColor"), 1, skin);
            glUniformMatrix4fv(glGetUniformLocation(g.handProg, "uMVP"), 1, GL_FALSE, mvp.m);
            glUniformMatrix4fv(glGetUniformLocation(g.handProg, "uModel"), 1, GL_FALSE, model.m);
            glBindVertexArray(g.handVao);
            glBindBuffer(GL_ARRAY_BUFFER, g.handVbo);
            glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(h.mesh.size() * 4), h.mesh.data(), GL_STREAM_DRAW);
            glDrawArrays(GL_TRIANGLES, 0, GLsizei(h.mesh.size() / 6));
        }
    }

    // the pointers, in the real world, over everything
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    for (int i = 0; i < 2; ++i) drawLaser(vpFull, g.hand[i], g.laserLen[i], eye, night);
    glBindVertexArray(0);
    glDepthMask(GL_TRUE);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

// ------------------------------------------------------------------ input

XrPath path(const char* s) { XrPath p = 0; xrStringToPath(g.inst, s, &p); return p; }

XrAction makeAction(const char* name, XrActionType t, bool perHand) {
    XrActionCreateInfo ci{XR_TYPE_ACTION_CREATE_INFO};
    ci.actionType = t;
    strcpy(ci.actionName, name);
    strcpy(ci.localizedActionName, name);
    if (perHand) {
        ci.countSubactionPaths = 2;
        ci.subactionPaths = g.handPath;
    }
    XrAction a = XR_NULL_HANDLE;
    xrCreateAction(g.set, &ci, &a);
    return a;
}

bool initInput() {
    XrActionSetCreateInfo si{XR_TYPE_ACTION_SET_CREATE_INFO};
    strcpy(si.actionSetName, "galileo");
    strcpy(si.localizedActionSetName, "Galileo");
    XR_OK(xrCreateActionSet(g.inst, &si, &g.set));
    g.handPath[0] = path("/user/hand/left");
    g.handPath[1] = path("/user/hand/right");
    g.aStick = makeAction("stick", XR_ACTION_TYPE_VECTOR2F_INPUT, true);
    g.aTrig = makeAction("trigger", XR_ACTION_TYPE_FLOAT_INPUT, true);
    g.aSqz = makeAction("squeeze", XR_ACTION_TYPE_FLOAT_INPUT, true);
    g.aClick = makeAction("stick_click", XR_ACTION_TYPE_BOOLEAN_INPUT, true);
    g.aAim = makeAction("aim", XR_ACTION_TYPE_POSE_INPUT, true);
    g.aGrip = makeAction("grip_pose", XR_ACTION_TYPE_POSE_INPUT, true);
    g.aThumbA = makeAction("thumb_stick_touch", XR_ACTION_TYPE_BOOLEAN_INPUT, true);
    g.aThumbB = makeAction("thumb_rest_touch", XR_ACTION_TYPE_BOOLEAN_INPUT, true);
    g.aA = makeAction("btn_a", XR_ACTION_TYPE_BOOLEAN_INPUT, false);
    g.aB = makeAction("btn_b", XR_ACTION_TYPE_BOOLEAN_INPUT, false);
    g.aX = makeAction("btn_x", XR_ACTION_TYPE_BOOLEAN_INPUT, false);
    g.aY = makeAction("btn_y", XR_ACTION_TYPE_BOOLEAN_INPUT, false);
    g.aMenu = makeAction("btn_menu", XR_ACTION_TYPE_BOOLEAN_INPUT, false);
    std::vector<XrActionSuggestedBinding> b = {
        {g.aA, path("/user/hand/right/input/a/click")}, {g.aB, path("/user/hand/right/input/b/click")},
        {g.aX, path("/user/hand/left/input/x/click")},  {g.aY, path("/user/hand/left/input/y/click")},
        {g.aMenu, path("/user/hand/left/input/menu/click")},
    };
    for (int i = 0; i < 2; ++i) {
        const std::string hp = i ? "/user/hand/right" : "/user/hand/left";
        b.push_back({g.aStick, path((hp + "/input/thumbstick").c_str())});
        b.push_back({g.aTrig, path((hp + "/input/trigger/value").c_str())});
        b.push_back({g.aSqz, path((hp + "/input/squeeze/value").c_str())});
        b.push_back({g.aClick, path((hp + "/input/thumbstick/click").c_str())});
        b.push_back({g.aAim, path((hp + "/input/aim/pose").c_str())});
        b.push_back({g.aGrip, path((hp + "/input/grip/pose").c_str())});
        b.push_back({g.aThumbA, path((hp + "/input/thumbstick/touch").c_str())});
        b.push_back({g.aThumbB, path((hp + "/input/thumbrest/touch").c_str())});
    }
    XrInteractionProfileSuggestedBinding sb{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    sb.interactionProfile = path("/interaction_profiles/oculus/touch_controller");
    sb.suggestedBindings = b.data();
    sb.countSuggestedBindings = uint32_t(b.size());
    XR_OK(xrSuggestInteractionProfileBindings(g.inst, &sb));
    XrSessionActionSetsAttachInfo ai{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    ai.countActionSets = 1;
    ai.actionSets = &g.set;
    XR_OK(xrAttachSessionActionSets(g.sess, &ai));
    for (int i = 0; i < 2; ++i) {
        XrActionSpaceCreateInfo asi{XR_TYPE_ACTION_SPACE_CREATE_INFO};
        asi.subactionPath = g.handPath[i];
        asi.poseInActionSpace.orientation.w = 1.f;
        asi.action = g.aAim;
        XR_OK(xrCreateActionSpace(g.sess, &asi, &g.hand[i].aim));
        asi.action = g.aGrip;
        XR_OK(xrCreateActionSpace(g.sess, &asi, &g.hand[i].grip));
    }
    return true;
}

bool boolState(XrAction a, XrPath sub = XR_NULL_PATH) {
    XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
    gi.action = a;
    gi.subactionPath = sub;
    XrActionStateBoolean s{XR_TYPE_ACTION_STATE_BOOLEAN};
    xrGetActionStateBoolean(g.sess, &gi, &s);
    return s.isActive && s.currentState;
}
float floatState(XrAction a, XrPath sub) {
    XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
    gi.action = a;
    gi.subactionPath = sub;
    XrActionStateFloat s{XR_TYPE_ACTION_STATE_FLOAT};
    xrGetActionStateFloat(g.sess, &gi, &s);
    return s.isActive ? s.currentState : 0.f;
}
XrVector2f vec2State(XrAction a, XrPath sub) {
    XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
    gi.action = a;
    gi.subactionPath = sub;
    XrActionStateVector2f s{XR_TYPE_ACTION_STATE_VECTOR2F};
    xrGetActionStateVector2f(g.sess, &gi, &s);
    return s.isActive ? s.currentState : XrVector2f{0, 0};
}

bool locate(XrSpace sp, XrTime t, XrPosef& out) {
    XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
    if (XR_FAILED(xrLocateSpace(sp, g.space, t, &loc))) return false;
    const XrSpaceLocationFlags need = XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR_SPACE_LOCATION_POSITION_VALID_BIT;
    if ((loc.locationFlags & need) != need) return false;
    out = loc.pose;
    return true;
}

// Snapshot layout (docs: NativeBridge.kt INPUT_*): [0] focused, [1] unused, [2..4] head position, [5..7] head forward,
// [8..10] head up, then right hand at 11 and left hand at 23: tracked, position(3), aim direction(3), trigger, squeeze,
// stick x, stick y, stick click; [35] buttons as a bit mask (a=1, b=2, x=4, y=8, menu=16).
void pollInput(XrTime t, const XrView views[2]) {
    g.headPos = {(views[0].pose.position.x + views[1].pose.position.x) * 0.5f,
                 (views[0].pose.position.y + views[1].pose.position.y) * 0.5f,
                 (views[0].pose.position.z + views[1].pose.position.z) * 0.5f};
    g.headFwd = aimDir(views[0].pose.orientation);
    g.headUp = upDir(views[0].pose.orientation);

    const bool focused = g.state == XR_SESSION_STATE_FOCUSED;
    if (focused) {
        XrActiveActionSet as{g.set, XR_NULL_PATH};
        XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
        sync.countActiveActionSets = 1;
        sync.activeActionSets = &as;
        if (XR_SUCCEEDED(xrSyncActions(g.sess, &sync))) {
            for (int i = 0; i < 2; ++i) {
                Hand& h = g.hand[i];
                XrPosef pose;
                h.ok = locate(h.aim, t, pose);
                if (h.ok) {
                    h.pos = {pose.position.x, pose.position.y, pose.position.z};
                    h.dir = aimDir(pose.orientation);
                    h.aimQ = pose.orientation;
                    XrPosef pg;
                    const bool gripOk = locate(h.grip, t, pg);
                    h.gripPos = gripOk ? Vec3{pg.position.x, pg.position.y, pg.position.z} : h.pos;
                    h.gripQ = gripOk ? pg.orientation : h.aimQ;
                }
                h.trigger = floatState(g.aTrig, g.handPath[i]);
                h.squeeze = floatState(g.aSqz, g.handPath[i]);
                const float thumbTarget = (boolState(g.aThumbA, g.handPath[i]) || boolState(g.aThumbB, g.handPath[i])) ? 1.f : 0.f;
                h.thumb += (thumbTarget - h.thumb) * 0.2f;
                if (h.ok && !g.ctlVerts[i]) buildHand(h, i);
                XrVector2f s = vec2State(g.aStick, g.handPath[i]);
                h.sx = s.x;
                h.sy = s.y;
                h.stickClick = boolState(g.aClick, g.handPath[i]);
            }
            g.btnA = boolState(g.aA); g.btnB = boolState(g.aB); g.btnX = boolState(g.aX);
            g.btnY = boolState(g.aY); g.btnMenu = boolState(g.aMenu);
        }
    } else {
        for (auto& h : g.hand) { h.ok = false; h.trigger = h.squeeze = h.sx = h.sy = 0; h.stickClick = false; }
        g.btnA = g.btnB = g.btnX = g.btnY = g.btnMenu = false;
    }

    std::lock_guard<std::mutex> lk(g.mu);
    float* s = g.snapshot;
    s[0] = focused ? 1.f : 0.f;
    s[1] = 0.f;
    s[2] = g.headPos.x; s[3] = g.headPos.y; s[4] = g.headPos.z;
    s[5] = g.headFwd.x; s[6] = g.headFwd.y; s[7] = g.headFwd.z;
    s[8] = g.headUp.x; s[9] = g.headUp.y; s[10] = g.headUp.z;
    for (int k = 0; k < 2; ++k) {
        const Hand& h = g.hand[k == 0 ? 1 : 0];  // right first
        float* o = s + 11 + 12 * k;
        o[0] = h.ok ? 1.f : 0.f;
        o[1] = h.pos.x; o[2] = h.pos.y; o[3] = h.pos.z;
        o[4] = h.dir.x; o[5] = h.dir.y; o[6] = h.dir.z;
        o[7] = h.trigger; o[8] = h.squeeze; o[9] = h.sx; o[10] = h.sy; o[11] = h.stickClick ? 1.f : 0.f;
    }
    s[35] = float((g.btnA ? 1 : 0) | (g.btnB ? 2 : 0) | (g.btnX ? 4 : 0) | (g.btnY ? 8 : 0) | (g.btnMenu ? 16 : 0));
}

// ------------------------------------------------------------------ OpenXR bring-up

bool createInstance() {
    PFN_xrInitializeLoaderKHR initLoader = nullptr;
    xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", (PFN_xrVoidFunction*)&initLoader);
    if (!initLoader) { LOGE("no xrInitializeLoaderKHR"); return false; }
    XrLoaderInitInfoAndroidKHR li{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
    li.applicationVM = g.android->activity->vm;
    li.applicationContext = g.android->activity->clazz;
    XR_OK(initLoader((const XrLoaderInitInfoBaseHeaderKHR*)&li));

    uint32_t n = 0;
    xrEnumerateInstanceExtensionProperties(nullptr, 0, &n, nullptr);
    std::vector<XrExtensionProperties> props(n, {XR_TYPE_EXTENSION_PROPERTIES});
    xrEnumerateInstanceExtensionProperties(nullptr, n, &n, props.data());
    auto has = [&](const char* name) {
        for (auto& p : props) if (!strcmp(p.extensionName, name)) return true;
        return false;
    };
    std::vector<const char*> exts = {XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
    const bool refresh = has(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME);
    if (refresh) exts.push_back(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME);
    const bool renderModel = has(XR_FB_RENDER_MODEL_EXTENSION_NAME);
    if (renderModel) exts.push_back(XR_FB_RENDER_MODEL_EXTENSION_NAME);
    const bool pass = has(XR_FB_PASSTHROUGH_EXTENSION_NAME);
    if (pass) exts.push_back(XR_FB_PASSTHROUGH_EXTENSION_NAME);

    XrInstanceCreateInfoAndroidKHR ai{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    ai.applicationVM = g.android->activity->vm;
    ai.applicationActivity = g.android->activity->clazz;
    XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO};
    ci.next = &ai;
    strcpy(ci.applicationInfo.applicationName, "Galileo");
    strcpy(ci.applicationInfo.engineName, "none");
    ci.applicationInfo.applicationVersion = 1;
    ci.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 34);
    ci.enabledExtensionCount = uint32_t(exts.size());
    ci.enabledExtensionNames = exts.data();
    XR_OK(xrCreateInstance(&ci, &g.inst));

    XrSystemGetInfo si{XR_TYPE_SYSTEM_GET_INFO};
    si.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XR_OK(xrGetSystem(g.inst, &si, &g.sys));

    auto proc = [&](const char* name, auto& fn) { xrGetInstanceProcAddr(g.inst, name, (PFN_xrVoidFunction*)&fn); };
    proc("xrCreateSwapchainAndroidSurfaceKHR", g.xrCreateSurfaceSwapchain);
    if (refresh) {
        proc("xrEnumerateDisplayRefreshRatesFB", g.xrEnumRates);
        proc("xrRequestDisplayRefreshRateFB", g.xrRequestRate);
    }
    if (renderModel) {
        proc("xrEnumerateRenderModelPathsFB", g.xrEnumPaths);
        proc("xrGetRenderModelPropertiesFB", g.xrModelProps);
        proc("xrLoadRenderModelFB", g.xrLoadModel);
    }
    if (pass) {
        proc("xrCreatePassthroughFB", g.xrCreatePassthrough);
        proc("xrDestroyPassthroughFB", g.xrDestroyPassthrough);
        proc("xrPassthroughStartFB", g.xrPassthroughStart);
        proc("xrPassthroughPauseFB", g.xrPassthroughPause);
        proc("xrCreatePassthroughLayerFB", g.xrCreatePassthroughLayer);
        proc("xrDestroyPassthroughLayerFB", g.xrDestroyPassthroughLayer);
    }
    LOGI("render model extension: %s", renderModel ? "yes" : "no");
    LOGI("surface swapchain: %s, passthrough: %s", g.xrCreateSurfaceSwapchain ? "yes" : "no", g.xrCreatePassthrough ? "yes" : "no");
    return true;
}

void createPassthrough() {
    if (!g.xrCreatePassthrough || !g.xrCreatePassthroughLayer) return;
    XrPassthroughCreateInfoFB pi{XR_TYPE_PASSTHROUGH_CREATE_INFO_FB};
    if (XR_FAILED(g.xrCreatePassthrough(g.sess, &pi, &g.passthrough))) { LOGE("passthrough create failed"); return; }
    XrPassthroughLayerCreateInfoFB li{XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB};
    li.passthrough = g.passthrough;
    li.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
    li.flags = 0;  // created paused: it is started when the wearer asks (MAPP-QST-070)
    if (XR_FAILED(g.xrCreatePassthroughLayer(g.sess, &li, &g.passthroughLayer))) { LOGE("passthrough layer failed"); return; }
    g.passthroughOk = true;
}

bool createSession() {
    PFN_xrGetOpenGLESGraphicsRequirementsKHR getReq = nullptr;
    xrGetInstanceProcAddr(g.inst, "xrGetOpenGLESGraphicsRequirementsKHR", (PFN_xrVoidFunction*)&getReq);
    XrGraphicsRequirementsOpenGLESKHR req{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
    XR_OK(getReq(g.inst, g.sys, &req));
    if (!initEgl()) return false;
    initGlResources();

    XrGraphicsBindingOpenGLESAndroidKHR gb{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    gb.display = g.dpy;
    gb.config = g.cfg;
    gb.context = g.ctx;
    XrSessionCreateInfo sci{XR_TYPE_SESSION_CREATE_INFO};
    sci.next = &gb;
    sci.systemId = g.sys;
    XR_OK(xrCreateSession(g.inst, &sci, &g.sess));

    XrReferenceSpaceCreateInfo rs{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    rs.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    rs.poseInReferenceSpace.orientation.w = 1.f;
    XR_OK(xrCreateReferenceSpace(g.sess, &rs, &g.space));

    uint32_t nv = 0;
    xrEnumerateViewConfigurationViews(g.inst, g.sys, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &nv, nullptr);
    std::vector<XrViewConfigurationView> vcv(nv, {XR_TYPE_VIEW_CONFIGURATION_VIEW});
    XR_OK(xrEnumerateViewConfigurationViews(g.inst, g.sys, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, nv, &nv, vcv.data()));
    if (nv != 2) { LOGE("expected 2 views, got %u", nv); return false; }

    uint32_t nf = 0;
    xrEnumerateSwapchainFormats(g.sess, 0, &nf, nullptr);
    std::vector<int64_t> fmts(nf);
    xrEnumerateSwapchainFormats(g.sess, nf, &nf, fmts.data());
    int64_t fmt = fmts.empty() ? GL_RGBA8 : fmts[0];
    for (int64_t f : fmts) if (f == GL_SRGB8_ALPHA8) fmt = f;

    for (int e = 0; e < 2; ++e) {
        EyeSwapchain& sc = g.eye[e];
        sc.w = int32_t(vcv[e].recommendedImageRectWidth);
        sc.h = int32_t(vcv[e].recommendedImageRectHeight);
        XrSwapchainCreateInfo ci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        ci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
        ci.format = fmt;
        ci.sampleCount = 1;
        ci.width = uint32_t(sc.w);
        ci.height = uint32_t(sc.h);
        ci.faceCount = 1;
        ci.arraySize = 1;
        ci.mipCount = 1;
        XR_OK(xrCreateSwapchain(g.sess, &ci, &sc.handle));
        uint32_t ni = 0;
        xrEnumerateSwapchainImages(sc.handle, 0, &ni, nullptr);
        sc.images.assign(ni, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        XR_OK(xrEnumerateSwapchainImages(sc.handle, ni, &ni, (XrSwapchainImageBaseHeader*)sc.images.data()));
        sc.fbos.resize(ni);
        glGenRenderbuffers(1, &sc.depth);
        glBindRenderbuffer(GL_RENDERBUFFER, sc.depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, sc.w, sc.h);
        glGenFramebuffers(ni, sc.fbos.data());
        for (uint32_t i = 0; i < ni; ++i) {
            glBindFramebuffer(GL_FRAMEBUFFER, sc.fbos[i]);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, sc.images[i].image, 0);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, sc.depth);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        LOGI("eye %d swapchain %dx%d, %u images", e, sc.w, sc.h, ni);
    }

    if (!initInput()) return false;
    createPassthrough();

    if (g.xrEnumRates && g.xrRequestRate) {
        uint32_t nr = 0;
        g.xrEnumRates(g.sess, 0, &nr, nullptr);
        std::vector<float> rates(nr);
        g.xrEnumRates(g.sess, nr, &nr, rates.data());
        float pick = 0.f;
        for (float r : rates) if (r == float(g.rateHz)) pick = r;  // 72 Hz, the Quest 2 budget the spike ran at
        if (pick > 0.f) { g.xrRequestRate(g.sess, pick); LOGI("requested %.0f Hz", pick); }
    }
    return true;
}

void destroyQuad(QuadSlot& q) {
    if (q.sc) xrDestroySwapchain(q.sc);
    if (!q.fbos.empty()) glDeleteFramebuffers(GLsizei(q.fbos.size()), q.fbos.data());
    if (q.oesTex) glDeleteTextures(1, &q.oesTex);
    if (g.env) {
        if (q.stex) {
            jmethodID rel = g.env->GetMethodID(g.env->GetObjectClass(q.stex), "release", "()V");
            if (rel) g.env->CallVoidMethod(q.stex, rel);
            g.env->ExceptionClear();
            g.env->DeleteGlobalRef(q.stex);
        }
        if (q.surface) g.env->DeleteGlobalRef(q.surface);
    }
    q.sc = XR_NULL_HANDLE;
    q.surface = nullptr;
    q.stex = nullptr;
    q.oesTex = 0;
    q.fbos.clear();
    q.images.clear();
    q.created = false;
    q.visible = false;
}

// Creates one pane: an OpenXR swapchain the compositor reads, and an Android surface (SurfaceTexture) Kotlin draws into.
bool createQuad(int i, QuadSlot& q) {
    if (!g.env) return false;
    uint32_t nf = 0;
    xrEnumerateSwapchainFormats(g.sess, 0, &nf, nullptr);
    std::vector<int64_t> fmts(nf);
    xrEnumerateSwapchainFormats(g.sess, nf, &nf, fmts.data());
    // Compose draws sRGB-encoded pixels: a plain RGBA8 target keeps them as they are, an sRGB one needs them made linear first.
    int64_t fmt = 0;
    for (int64_t f : fmts) if (f == GL_RGBA8) fmt = f;
    if (!fmt) for (int64_t f : fmts) if (f == GL_SRGB8_ALPHA8) fmt = f;
    if (!fmt) { LOGE("pane %d: no usable swapchain format", i); return false; }
    q.srgb = fmt == GL_SRGB8_ALPHA8;

    XrSwapchainCreateInfo ci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
    ci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
    ci.format = fmt;
    ci.sampleCount = 1;
    ci.width = uint32_t(q.pw);
    ci.height = uint32_t(q.ph);
    ci.faceCount = 1;
    ci.arraySize = 1;
    ci.mipCount = 1;
    XrResult r = xrCreateSwapchain(g.sess, &ci, &q.sc);
    if (XR_FAILED(r)) { LOGE("pane %d: %dx%d swapchain failed (%d)", i, q.pw, q.ph, (int)r); q.sc = XR_NULL_HANDLE; return false; }
    uint32_t ni = 0;
    xrEnumerateSwapchainImages(q.sc, 0, &ni, nullptr);
    q.images.assign(ni, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
    xrEnumerateSwapchainImages(q.sc, ni, &ni, (XrSwapchainImageBaseHeader*)q.images.data());
    q.fbos.resize(ni);
    glGenFramebuffers(GLsizei(ni), q.fbos.data());
    for (uint32_t k = 0; k < ni; ++k) {
        glBindFramebuffer(GL_FRAMEBUFFER, q.fbos[k]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, q.images[k].image, 0);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    glGenTextures(1, &q.oesTex);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, q.oesTex);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    JNIEnv* env = g.env;
    jclass stc = env->FindClass("android/graphics/SurfaceTexture");
    jclass sfc = env->FindClass("android/view/Surface");
    jobject st = nullptr, sf = nullptr;
    if (stc && sfc) {
        jmethodID stInit = env->GetMethodID(stc, "<init>", "(I)V");
        jmethodID setSize = env->GetMethodID(stc, "setDefaultBufferSize", "(II)V");
        jmethodID sfInit = env->GetMethodID(sfc, "<init>", "(Landroid/graphics/SurfaceTexture;)V");
        if (stInit && setSize && sfInit) {
            st = env->NewObject(stc, stInit, jint(q.oesTex));
            if (st) {
                env->CallVoidMethod(st, setSize, jint(q.pw), jint(q.ph));
                sf = env->NewObject(sfc, sfInit, st);
            }
        }
    }
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); st = sf = nullptr; }
    if (!st || !sf) { LOGE("pane %d: could not create the Android surface", i); destroyQuad(q); return false; }
    q.stex = env->NewGlobalRef(st);
    q.surface = env->NewGlobalRef(sf);
    env->DeleteLocalRef(st);
    env->DeleteLocalRef(sf);
    q.created = true;
    LOGI("pane %d: %dx%d texture pane ready (%s)", i, q.pw, q.ph, q.srgb ? "sRGB" : "RGBA8");
    return true;
}

// Panes: a swapchain and an Android surface per quad. Created on the render thread (it owns the session and the GL context).
void serviceQuads() {
    std::lock_guard<std::mutex> lk(g.mu);
    for (int i = 0; i < kMaxQuads; ++i) {
        QuadSlot& q = g.quad[i];
        if (!q.wanted && q.created) {
            destroyQuad(q);
        } else if (q.wanted && !q.created && q.pw > 0 && q.ph > 0) {
            if (!createQuad(i, q)) q.wanted = false;
        }
    }
}

// Copies each visible pane's newest Android frame into its swapchain image, ready for the compositor.
void updatePanes() {
    if (!g.env || !g.paneProg) return;
    std::lock_guard<std::mutex> lk(g.mu);
    JNIEnv* env = g.env;
    for (int i = 0; i < kMaxQuads; ++i) {
        QuadSlot& q = g.quad[i];
        if (!q.created || !q.visible || !q.stex) continue;
        jclass stc = env->GetObjectClass(q.stex);
        jmethodID update = env->GetMethodID(stc, "updateTexImage", "()V");
        jmethodID getMat = env->GetMethodID(stc, "getTransformMatrix", "([F)V");
        env->DeleteLocalRef(stc);
        if (!update || !getMat) continue;
        env->CallVoidMethod(q.stex, update);
        if (env->ExceptionCheck()) { env->ExceptionClear(); continue; }  // nothing drawn yet, or the surface is gone
        jfloatArray arr = env->NewFloatArray(16);
        env->CallVoidMethod(q.stex, getMat, arr);
        float tm[16];
        env->GetFloatArrayRegion(arr, 0, 16, tm);
        env->DeleteLocalRef(arr);

        uint32_t idx = 0;
        if (XR_FAILED(xrAcquireSwapchainImage(q.sc, nullptr, &idx))) continue;
        XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
        wi.timeout = XR_INFINITE_DURATION;
        xrWaitSwapchainImage(q.sc, &wi);
        glBindFramebuffer(GL_FRAMEBUFFER, q.fbos[idx]);
        glViewport(0, 0, q.pw, q.ph);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glClearColor(0.f, 0.f, 0.f, 0.f);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(g.paneProg);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, q.oesTex);
        glUniform1i(glGetUniformLocation(g.paneProg, "uTex"), 0);
        glUniformMatrix4fv(glGetUniformLocation(g.paneProg, "uTexMat"), 1, GL_FALSE, tm);
        glUniform1f(glGetUniformLocation(g.paneProg, "uToLinear"), q.srgb ? 1.f : 0.f);
        glBindVertexArray(0);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        xrReleaseSwapchainImage(q.sc, nullptr);
    }
}

void destroyAll() {
    for (auto& q : g.quad) if (q.created || q.sc) destroyQuad(q);
    for (auto& sc : g.eye) if (sc.handle) xrDestroySwapchain(sc.handle);
    if (g.passthroughLayer && g.xrDestroyPassthroughLayer) g.xrDestroyPassthroughLayer(g.passthroughLayer);
    if (g.passthrough && g.xrDestroyPassthrough) g.xrDestroyPassthrough(g.passthrough);
    if (g.space) xrDestroySpace(g.space);
    if (g.sess) xrDestroySession(g.sess);
    if (g.inst) xrDestroyInstance(g.inst);
    g.sess = XR_NULL_HANDLE; g.inst = XR_NULL_HANDLE; g.space = XR_NULL_HANDLE;
    if (g.dpy != EGL_NO_DISPLAY) {
        eglMakeCurrent(g.dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (g.surf != EGL_NO_SURFACE) eglDestroySurface(g.dpy, g.surf);
        if (g.ctx != EGL_NO_CONTEXT) eglDestroyContext(g.dpy, g.ctx);
        eglTerminate(g.dpy);
    }
}

void handleXrEvents() {
    XrEventDataBuffer ev{XR_TYPE_EVENT_DATA_BUFFER};
    while (xrPollEvent(g.inst, &ev) == XR_SUCCESS) {
        if (ev.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            auto* s = (XrEventDataSessionStateChanged*)&ev;
            g.state = s->state;
            LOGI("session state -> %d", (int)s->state);
            if (s->state == XR_SESSION_STATE_READY) {
                XrSessionBeginInfo bi{XR_TYPE_SESSION_BEGIN_INFO};
                bi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                if (XR_SUCCEEDED(xrBeginSession(g.sess, &bi))) g.sessionRunning = true;
            } else if (s->state == XR_SESSION_STATE_STOPPING) {
                xrEndSession(g.sess);
                g.sessionRunning = false;
            } else if (s->state == XR_SESSION_STATE_EXITING || s->state == XR_SESSION_STATE_LOSS_PENDING) {
                g.quit = true;
                ANativeActivity_finish(g.android->activity);
            }
        }
        ev = {XR_TYPE_EVENT_DATA_BUFFER};
    }
}

// ------------------------------------------------------------------ the frame

// Retried every few seconds until both controllers are in: the runtime may not have them ready at session start.
void loadControllerModels() {
    if (!g.xrLoadModel || !g.xrEnumPaths || !g.xrModelProps || g.ctlTries >= 8) return;
    if (g.ctlVerts[0] > 0 && g.ctlVerts[1] > 0) return;
    double now = std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
    if (now < g.ctlNext) return;
    g.ctlNext = now + 3.0;
    ++g.ctlTries;
    uint32_t n = 0;
    if (XR_FAILED(g.xrEnumPaths(g.sess, 0, &n, nullptr)) || n == 0) {
        LOGI("render models: none listed (try %d)", g.ctlTries);
        return;
    }
    std::vector<XrRenderModelPathInfoFB> paths(n, {XR_TYPE_RENDER_MODEL_PATH_INFO_FB});
    if (XR_FAILED(g.xrEnumPaths(g.sess, n, &n, paths.data()))) return;
    for (uint32_t k = 0; k < n; ++k) {
        char buf[256];
        uint32_t len = 0;
        if (XR_FAILED(xrPathToString(g.inst, paths[k].path, sizeof buf, &len, buf))) continue;
        std::string name = buf;
        int side = name.rfind("/model_fb/controller/left", 0) == 0 ? 0 : name.rfind("/model_fb/controller/right", 0) == 0 ? 1 : -1;
        if (side < 0 || g.ctlVerts[side] > 0) continue;
        XrRenderModelPropertiesFB pr{XR_TYPE_RENDER_MODEL_PROPERTIES_FB};
        if (XR_FAILED(g.xrModelProps(g.sess, paths[k].path, &pr)) || pr.modelKey == XR_NULL_RENDER_MODEL_KEY_FB) {
            LOGI("render model %s: not available yet", buf);
            continue;
        }
        XrRenderModelLoadInfoFB li{XR_TYPE_RENDER_MODEL_LOAD_INFO_FB};
        li.modelKey = pr.modelKey;
        XrRenderModelBufferFB rb{XR_TYPE_RENDER_MODEL_BUFFER_FB};
        if (XR_FAILED(g.xrLoadModel(g.sess, &li, &rb)) || rb.bufferCountOutput == 0) continue;
        std::vector<uint8_t> glb(rb.bufferCountOutput);
        rb.bufferCapacityInput = uint32_t(glb.size());
        rb.buffer = glb.data();
        if (XR_FAILED(g.xrLoadModel(g.sess, &li, &rb))) continue;
        std::vector<float> mesh;
        if (!buildGlbMesh(glb, mesh)) {
            LOGI("render model %s (%s): GLB not understood, %zu bytes", buf, pr.modelName, glb.size());
            continue;
        }
        float lo[3] = {1e9f, 1e9f, 1e9f}, hi[3] = {-1e9f, -1e9f, -1e9f};
        for (size_t v = 0; v + 8 < mesh.size(); v += 9)
            for (int c = 0; c < 3; ++c) { lo[c] = std::min(lo[c], mesh[v + size_t(c)]); hi[c] = std::max(hi[c], mesh[v + size_t(c)]); }
        if (!g.ctlVao[side]) {
            glGenVertexArrays(1, &g.ctlVao[side]);
            glGenBuffers(1, &g.ctlVbo[side]);
        }
        glBindVertexArray(g.ctlVao[side]);
        glBindBuffer(GL_ARRAY_BUFFER, g.ctlVbo[side]);
        glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(mesh.size() * 4), mesh.data(), GL_STATIC_DRAW);
        for (int a = 0; a < 3; ++a) {
            glEnableVertexAttribArray(GLuint(a));
            glVertexAttribPointer(GLuint(a), 3, GL_FLOAT, GL_FALSE, 36, (void*)(uintptr_t(a) * 12));
        }
        glBindVertexArray(0);
        g.ctlVerts[side] = int(mesh.size() / 9);
        LOGI("render model %s (%s): %d vertices, %zu-byte GLB, bounds x %.3f..%.3f y %.3f..%.3f z %.3f..%.3f", buf,
             pr.modelName, g.ctlVerts[side], glb.size(), lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]);
    }
}


void frame() {
    XrFrameState fs{XR_TYPE_FRAME_STATE};
    if (XR_FAILED(xrWaitFrame(g.sess, nullptr, &fs))) return;
    if (XR_FAILED(xrBeginFrame(g.sess, nullptr))) return;

    serviceQuads();
    loadControllerModels();

    bool passthroughOn;
    {
        std::lock_guard<std::mutex> lk(g.mu);
        passthroughOn = g.wantPassthrough && g.passthroughOk;
    }
    static bool passthroughRunning = false;
    if (passthroughOn != passthroughRunning && g.passthroughOk) {
        if (passthroughOn && g.xrPassthroughStart) g.xrPassthroughStart(g.passthrough);
        if (!passthroughOn && g.xrPassthroughPause) g.xrPassthroughPause(g.passthrough);
        passthroughRunning = passthroughOn;
    }

    XrCompositionLayerProjectionView pv[2] = {{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}, {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};
    XrCompositionLayerProjection projLayer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    XrCompositionLayerPassthroughFB passLayer{XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB};
    XrCompositionLayerQuad quads[kMaxQuads];
    std::vector<const XrCompositionLayerBaseHeader*> layers;

    if (fs.shouldRender) {
        XrViewLocateInfo li{XR_TYPE_VIEW_LOCATE_INFO};
        li.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
        li.displayTime = fs.predictedDisplayTime;
        li.space = g.space;
        XrViewState vs{XR_TYPE_VIEW_STATE};
        XrView views[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
        uint32_t nv = 0;
        if (XR_SUCCEEDED(xrLocateViews(g.sess, &li, &vs, 2, &nv, views)) && (vs.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT)) {
            pollInput(fs.predictedDisplayTime, views);

            if (passthroughOn) {
                passLayer.layerHandle = g.passthroughLayer;
                passLayer.flags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
                layers.push_back((XrCompositionLayerBaseHeader*)&passLayer);
            } else {
                SkyState sky;
                std::vector<float> labelEntries;
                applyPending(sky, labelEntries);
                for (int e = 0; e < 2; ++e) {
                    EyeSwapchain& sc = g.eye[e];
                    uint32_t idx = 0;
                    xrAcquireSwapchainImage(sc.handle, nullptr, &idx);
                    XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
                    wi.timeout = XR_INFINITE_DURATION;
                    xrWaitSwapchainImage(sc.handle, &wi);
                    renderEye(e, views[e], sc.fbos[idx], sky, labelEntries);
                    xrReleaseSwapchainImage(sc.handle, nullptr);
                    pv[e].pose = views[e].pose;
                    pv[e].fov = views[e].fov;
                    pv[e].subImage.swapchain = sc.handle;
                    pv[e].subImage.imageRect = {{0, 0}, {sc.w, sc.h}};
                    pv[e].subImage.imageArrayIndex = 0;
                }
                projLayer.space = g.space;
                projLayer.viewCount = 2;
                projLayer.views = pv;
                layers.push_back((XrCompositionLayerBaseHeader*)&projLayer);
            }

            updatePanes();

            // the dock and the panes, in front of everything, in the world
            std::lock_guard<std::mutex> lk(g.mu);
            int n = 0;
            for (int i = 0; i < kMaxQuads && n < kMaxQuads; ++i) {
                const QuadSlot& q = g.quad[i];
                if (!q.created || !q.visible) continue;
                XrCompositionLayerQuad& L = quads[n++];
                L = {XR_TYPE_COMPOSITION_LAYER_QUAD};
                L.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
                L.space = g.space;
                L.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
                L.subImage.swapchain = q.sc;
                L.subImage.imageRect = {{0, 0}, {q.pw, q.ph}};
                L.subImage.imageArrayIndex = 0;
                Vec3 r{q.r[0], q.r[1], q.r[2]}, u{q.u[0], q.u[1], q.u[2]};
                Vec3 nrm = normalize(cross(r, u));
                L.pose.orientation = quatFromBasis(normalize(r), normalize(u), nrm);
                L.pose.position = {q.c[0], q.c[1], q.c[2]};
                L.size = {q.w, q.h};
                layers.push_back((XrCompositionLayerBaseHeader*)&L);
            }
        }
    }

    XrFrameEndInfo ei{XR_TYPE_FRAME_END_INFO};
    ei.displayTime = fs.predictedDisplayTime;
    ei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    ei.layerCount = uint32_t(layers.size());
    ei.layers = layers.empty() ? nullptr : layers.data();
    xrEndFrame(g.sess, &ei);
}

void onCmd(android_app* app, int32_t cmd) {
    (void)app;
    if (cmd == APP_CMD_RESUME) { g.resumed = true; music::setPaused(false); }
    else if (cmd == APP_CMD_PAUSE) { g.resumed = false; music::setPaused(true); }
    else if (cmd == APP_CMD_DESTROY) g.quit = true;
}

}  // namespace

// ------------------------------------------------------------------ JNI: Kotlin -> native

extern "C" {

#define JNI(name) Java_com_galileo_mobileconnect_quest_NativeBridge_##name

static void copyFloats(JNIEnv* env, jfloatArray a, jint count, std::vector<float>& out, int perItem) {
    out.assign(size_t(std::max(0, count)) * size_t(perItem), 0.f);
    if (!a || count <= 0) return;
    env->GetFloatArrayRegion(a, 0, jsize(out.size()), out.data());
}

JNIEXPORT void JNICALL JNI(nativeSetStars)(JNIEnv* env, jclass, jfloatArray data, jint count) {
    std::lock_guard<std::mutex> lk(g.mu);
    copyFloats(env, data, count, g.stars, 4);
    g.dStars = true;
}
JNIEXPORT void JNICALL JNI(nativeSetConLines)(JNIEnv* env, jclass, jfloatArray data, jint vertexCount) {
    std::lock_guard<std::mutex> lk(g.mu);
    copyFloats(env, data, vertexCount, g.lines, 3);
    g.dLines = true;
}
JNIEXPORT void JNICALL JNI(nativeSetMarkers)(JNIEnv* env, jclass, jfloatArray data, jint count) {
    std::lock_guard<std::mutex> lk(g.mu);
    copyFloats(env, data, count, g.marks, 5);
    g.dMarks = true;
}
JNIEXPORT void JNICALL JNI(nativeSetOverlay)(JNIEnv* env, jclass, jfloatArray lines, jint lineVerts, jfloatArray tris, jint triVerts) {
    std::lock_guard<std::mutex> lk(g.mu);
    copyFloats(env, lines, lineVerts, g.overLines, 7);
    copyFloats(env, tris, triVerts, g.overTris, 7);
    g.dOver = true;
}
JNIEXPORT void JNICALL JNI(nativeSetLabels)(JNIEnv* env, jclass, jobject bitmap, jfloatArray entries, jint count) {
    std::lock_guard<std::mutex> lk(g.mu);
    copyFloats(env, entries, count, g.labels, 9);
    if (bitmap) {
        AndroidBitmapInfo info;
        void* px = nullptr;
        if (AndroidBitmap_getInfo(env, bitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS && info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
            AndroidBitmap_lockPixels(env, bitmap, &px) == ANDROID_BITMAP_RESULT_SUCCESS) {
            g.atlasW = int(info.width);
            g.atlasH = int(info.height);
            g.atlas.resize(size_t(info.width) * info.height * 4);
            for (uint32_t row = 0; row < info.height; ++row)
                memcpy(g.atlas.data() + size_t(row) * info.width * 4, (uint8_t*)px + size_t(row) * info.stride, size_t(info.width) * 4);
            AndroidBitmap_unlockPixels(env, bitmap);
            g.dAtlas = true;
        }
    }
}
// rot: 9 floats, column-major J2000 -> world; centre: 3 floats; flags: 1 night, 2 ground, 4 constellations
JNIEXPORT void JNICALL JNI(nativeSetSky)(JNIEnv* env, jclass, jfloatArray rot, jfloatArray centre, jfloat zoom, jfloat limit, jfloat brightness,
                                         jint flags, jfloatArray ground) {
    std::lock_guard<std::mutex> lk(g.mu);
    if (rot) env->GetFloatArrayRegion(rot, 0, 9, g.sky.rot);
    if (centre) env->GetFloatArrayRegion(centre, 0, 3, g.sky.zoomCentre);
    if (ground) env->GetFloatArrayRegion(ground, 0, 4, g.sky.ground);
    g.sky.zoom = zoom;
    g.sky.limit = limit;
    g.sky.brightness = brightness;
    g.sky.flags = flags;
}
JNIEXPORT void JNICALL JNI(nativeCreateQuad)(JNIEnv*, jclass, jint id, jint w, jint h) {
    if (id < 0 || id >= kMaxQuads) return;
    std::lock_guard<std::mutex> lk(g.mu);
    g.quad[id].wanted = true;
    g.quad[id].pw = w;
    g.quad[id].ph = h;
}
JNIEXPORT void JNICALL JNI(nativeDestroyQuad)(JNIEnv*, jclass, jint id) {
    if (id < 0 || id >= kMaxQuads) return;
    std::lock_guard<std::mutex> lk(g.mu);
    g.quad[id].wanted = false;
}
// The surface the pane draws into, once the render thread has made it; null until then.
JNIEXPORT jobject JNICALL JNI(nativeTakeSurface)(JNIEnv* env, jclass, jint id) {
    if (id < 0 || id >= kMaxQuads) return nullptr;
    std::lock_guard<std::mutex> lk(g.mu);
    const QuadSlot& q = g.quad[id];
    return (q.created && q.surface) ? env->NewLocalRef(q.surface) : nullptr;
}
// centre(3), right(3), up(3) in metres and world axes; size in metres
JNIEXPORT void JNICALL JNI(nativeSetQuad)(JNIEnv* env, jclass, jint id, jboolean visible, jfloatArray pose, jfloat w, jfloat h) {
    if (id < 0 || id >= kMaxQuads || !pose) return;
    std::lock_guard<std::mutex> lk(g.mu);
    QuadSlot& q = g.quad[id];
    float v[9];
    env->GetFloatArrayRegion(pose, 0, 9, v);
    memcpy(q.c, v, 12);
    memcpy(q.r, v + 3, 12);
    memcpy(q.u, v + 6, 12);
    q.w = w;
    q.h = h;
    q.visible = visible;
}
JNIEXPORT jboolean JNICALL JNI(nativeGetInput)(JNIEnv* env, jclass, jfloatArray out) {
    if (!out || env->GetArrayLength(out) < 40) return JNI_FALSE;
    std::lock_guard<std::mutex> lk(g.mu);
    env->SetFloatArrayRegion(out, 0, 40, g.snapshot);
    return JNI_TRUE;
}
JNIEXPORT void JNICALL JNI(nativeSetLaser)(JNIEnv*, jclass, jfloat right, jfloat left) {
    std::lock_guard<std::mutex> lk(g.mu);
    g.laserLen[1] = right;
    g.laserLen[0] = left;
}
JNIEXPORT void JNICALL JNI(nativeSetPassthrough)(JNIEnv*, jclass, jboolean on) {
    std::lock_guard<std::mutex> lk(g.mu);
    g.wantPassthrough = on;
}

}  // extern "C"

// ------------------------------------------------------------------ entry

void android_main(android_app* app) {
    g.android = app;
    app->onAppCmd = onCmd;
    app->activity->vm->AttachCurrentThread(&g.env, nullptr);
    LOGI("Galileo for Quest starting");
    music::start(app->activity->assetManager);  // the .mp3/.mp4/.m4a files in assets/music, shuffled, for as long as the app runs

    while (!app->destroyRequested) {
        int events;
        android_poll_source* src;
        while (ALooper_pollAll((g.sessionRunning || !g.initDone) ? 0 : -1, nullptr, &events, (void**)&src) >= 0) {
            if (src) src->process(app, src);
            if (app->destroyRequested) break;
        }
        if (app->destroyRequested) break;

        if (!g.initDone && g.resumed && app->window) {
            if (!createInstance() || !createSession()) { LOGE("OpenXR init failed"); break; }
            g.initDone = true;
        }
        if (g.initDone) {
            handleXrEvents();
            if (g.sessionRunning) frame();
        }
        if (g.quit) break;
    }
    music::stop();
    destroyAll();
    app->activity->vm->DetachCurrentThread();
    LOGI("exiting");
}

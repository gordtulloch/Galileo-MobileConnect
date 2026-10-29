// Galileo Quest star-atlas performance spike.
//
// Native OpenXR + OpenGL ES 3 dome of Hipparcos stars (docs/analysis/QuestVR.md
// sec. 5: "spike of the dome with 100,000 stars on a Quest 2"). Standalone: no
// connection to a Galileo Master. The star count, not the UI, is the risk, so
// this measures it: on-headset HUD plus a once-a-second logcat line
// (adb logcat -s GalileoAtlas).
//
// Controls (Touch controllers)
//   Left stick X   scrub sky time          Right stick Y   magnitude limit
//   A              star-count tier          B               night (red) mode
//   X              time back to now (real clock)      Y               cycle refresh rate
//   Right stick click  constellations on/off   Left stick click  DSS2 background on/off
//   Left menu      show/hide HUD
//   Trigger/grip/thumb-touch curl the virtual fingers; each controller has a laser pointer and the
//   HUD reads out what the right controller (else left) is pointing at.

#include <jni.h>
#include <android/asset_manager.h>
#include <android/log.h>
#include <android_native_app_glue.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cctype>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <ctime>
#include <random>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#define TAG "GalileoAtlas"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define XR_OK(call)                                                                   \
    do {                                                                              \
        XrResult r_ = (call);                                                         \
        if (XR_FAILED(r_)) {                                                          \
            LOGE("%s failed: %d (%s:%d)", #call, (int)r_, __FILE__, __LINE__);        \
            return false;                                                             \
        }                                                                             \
    } while (0)

// EXT_disjoint_timer_query (not in the ES3 headers).
#ifndef GL_TIME_ELAPSED_EXT
#define GL_TIME_ELAPSED_EXT 0x88BF
#define GL_QUERY_RESULT_EXT 0x8866
#define GL_QUERY_RESULT_AVAILABLE_EXT 0x8867
#define GL_GPU_DISJOINT_EXT 0x8FBB
#endif

#include "ephem.h"
#include "music.h"

namespace {

using Clock = std::chrono::steady_clock;
constexpr double kPi = 3.14159265358979323846;

// ---------------------------------------------------------------- maths

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

// Inverse of the head orientation only: the sky has no parallax, so the eye
// translation is deliberately dropped.
Mat4 viewRotation(const XrQuaternionf& q) {
    float x = q.x, y = q.y, z = q.z, w = q.w;
    float r[3][3] = {
        {1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)},
        {2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)},
        {2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)}};
    Mat4 m = identity();
    for (int c = 0; c < 3; ++c)
        for (int row = 0; row < 3; ++row) m.m[c * 4 + row] = r[c][row];  // transpose
    return m;
}

// ---------------------------------------------------------------- GL helpers

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

// Zoom: azimuthal-equidistant magnification about a centre (angular distance from it times uZoom.w), then a
// rotation that parks the centre at the direction it was locked in. World frame x=E y=up z=S.
#define ZOOM_GLSL R"(
uniform vec4 uZoom;                   // xyz: zoom centre (true sky), w: magnification
uniform mat3 uRot;                    // centre -> locked display direction (identity when unlocked)
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
    return uRot * w;
}
vec3 zoomInv(vec3 w) {                // displayed direction -> true direction
    w = w * uRot;                     // transpose(uRot) * w
    if (uZoom.w > 1.001) {
        float c = clamp(dot(w, uZoom.xyz), -1.0, 1.0);
        vec3 p = w - uZoom.xyz * c;
        float pl = length(p);
        if (pl > 1e-6) {
            float t = acos(c) / uZoom.w;
            w = uZoom.xyz * cos(t) + p * (sin(t) / pl);
        }
    }
    return w;
}
)"

const char* kStarVS = R"(#version 300 es)" ZOOM_GLSL R"(
layout(location=0) in vec3 aDir;      // J2000 equatorial unit vector
layout(location=1) in vec2 aMagBv;
uniform mat4 uVP;
uniform mat3 uPrec;                   // J2000 -> date precession
uniform vec4 uSky;                    // cos(LST), sin(LST), sin(lat), cos(lat)
uniform float uPx;                    // render-resolution scale for point size
uniform float uNight;
out vec3 vCol;
vec3 bvColor(float bv) {
    // blue-white -> white -> yellow -> orange -> red
    vec3 c0 = vec3(0.62, 0.72, 1.00), c1 = vec3(1.00, 0.97, 0.93);
    vec3 c2 = vec3(1.00, 0.80, 0.55), c3 = vec3(1.00, 0.55, 0.35);
    if (bv < 0.4) return mix(c0, c1, clamp((bv + 0.3) / 0.7, 0.0, 1.0));
    if (bv < 1.1) return mix(c1, c2, (bv - 0.4) / 0.7);
    return mix(c2, c3, clamp((bv - 1.1) / 0.8, 0.0, 1.0));
}
void main() {
    vec3 d = uPrec * aDir;
    float a = d.x * uSky.x + d.y * uSky.y;      // cos(dec) cos(H)
    float b = d.x * uSky.y - d.y * uSky.x;      // cos(dec) sin(H)
    float east = -b;
    float north = d.z * uSky.w - a * uSky.z;
    float up = d.z * uSky.z + a * uSky.w;
    vec3 w = zoomFwd(vec3(east, up, -north));                        // x=E y=up z=S
    bool hide = dot(w, w) < 0.25 || w.y < 0.0;                       // below the (opaque) ground
    gl_Position = hide ? vec4(2.0, 2.0, 2.0, 1.0) : uVP * vec4(w * 1000.0, 1.0);
    float m = aMagBv.x;
    gl_PointSize = hide ? 0.0 : min(max(1.5, 1.4 + 0.9 * (6.5 - m)), 14.0) * uPx;
    float lum = clamp(1.0 - 0.09 * (m + 0.5), 0.12, 1.0);
    lum *= smoothstep(0.0, 0.05, w.y);                               // extinction into the horizon haze
    vCol = (uNight > 0.5 ? vec3(1.0, 0.07, 0.0) : bvColor(aMagBv.y)) * lum;
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

const char* kConVS = R"(#version 300 es)" ZOOM_GLSL R"(
layout(location=0) in vec3 aDir;      // J2000 equatorial unit vector
uniform mat4 uVP;
uniform mat3 uPrec;                   // J2000 -> date precession
uniform vec4 uSky;                    // cos(LST), sin(LST), sin(lat), cos(lat)
out float vUp;
void main() {
    vec3 d = uPrec * aDir;
    float a = d.x * uSky.x + d.y * uSky.y;
    float b = d.x * uSky.y - d.y * uSky.x;
    float north = d.z * uSky.w - a * uSky.z;
    float up = d.z * uSky.z + a * uSky.w;
    vec3 t = vec3(-b, up, -north);
    vec3 w = zoomFwd(t);
    bool hide = dot(w, w) < 0.25;
    if (hide) w = t;
    vUp = hide ? -1.0 : w.y;
    gl_Position = uVP * vec4(w * 990.0, 1.0);
}
)";
const char* kConFS = R"(#version 300 es
precision mediump float;
in float vUp;
uniform vec3 uColor;
out vec4 o;
void main() {
    if (vUp < 0.0) discard;
    o = vec4(uColor * mix(0.4, 1.0, smoothstep(0.0, 0.1, vUp)), 1.0);
}
)";

const char* kBgVS = R"(#version 300 es
layout(location=0) in vec3 aPos;      // unit sphere, world frame x=E y=up z=S
uniform mat4 uVP;
out vec3 vW;
void main() { vW = aPos; gl_Position = uVP * vec4(aPos * 5000.0, 1.0); }
)";
const char* kBgFS = R"(#version 300 es
precision highp float;
)" ZOOM_GLSL R"(
in vec3 vW;
uniform sampler2D uTex;               // all-sky, equirectangular, RA increases to the left
uniform mat3 uPrec;
uniform vec4 uSky;                    // cos(LST), sin(LST), sin(lat), cos(lat)
uniform vec3 uEye;                    // this eye in the local space (the sky has no parallax; the ground does)
uniform float uFloor;                 // ground height in the local space
uniform float uExposure;
uniform float uNight;
uniform float uSkyOn;
out vec4 o;
void main() {
    vec3 w = normalize(vW);
    // sky: displayed -> true direction -> equatorial of date (inverse of the star shader) -> J2000
    vec3 ws = zoomInv(w);
    float east = ws.x, up = ws.y, north = -ws.z;
    float a = up * uSky.w - north * uSky.z;
    float zz = up * uSky.z + north * uSky.w;
    float b = -east;
    vec3 eq = vec3(a * uSky.x + b * uSky.y, a * uSky.y - b * uSky.x, zz) * uPrec;
    float ra = atan(eq.y, eq.x);
    float dec = asin(clamp(eq.z, -1.0, 1.0));
    vec2 uv = vec2(0.5 - ra / 6.2831853, 0.5 - dec / 3.14159265);
    // atan() jumps at RA 180: take mip derivatives from whichever copy is continuous
    vec2 uvAlt = vec2(fract(uv.x + 0.5) - 0.5, uv.y);
    vec2 dx = dFdx(uv), dy = dFdy(uv), dxa = dFdx(uvAlt), dya = dFdy(uvAlt);
    if (abs(dx.x) + abs(dy.x) > abs(dxa.x) + abs(dya.x)) { dx = dxa; dy = dya; }
    const vec3 haze = vec3(0.012, 0.014, 0.018);
    vec3 sky = textureGrad(uTex, uv, dx, dy).rgb * uExposure * uSkyOn;
    sky = mix(haze, sky, smoothstep(0.0, 0.05, w.y));       // thin into the horizon haze

    // ground: an opaque plane below the viewer with a 2 m grid, so the dome has a floor to be far from
    float t = (uFloor - uEye.y) / min(w.y, -1e-4);
    vec2 gp = (uEye.xz + w.xz * t) / 2.0;
    vec2 gw = fwidth(gp);
    vec2 gl = abs(fract(gp - 0.5) - 0.5) / max(gw, vec2(1e-5));
    float line = (1.0 - min(min(gl.x, gl.y), 1.0)) * (1.0 - smoothstep(0.15, 0.45, max(gw.x, gw.y)));
    vec3 ground = mix(haze, vec3(0.010, 0.012, 0.009) + vec3(0.030, 0.036, 0.030) * line * exp(-t / 120.0),
                      smoothstep(0.0, 0.05, -w.y));

    vec3 c = w.y < 0.0 ? ground : sky;
    float l = dot(c, vec3(0.30, 0.59, 0.11));
    if (uNight > 0.5) c = vec3(l, l * 0.06, 0.0);
    o = vec4(c, 1.0);
}
)";

// Messier / Caldwell / NGC objects as point sprites; the fragment shader draws the symbol for the type.
const char* kDsoVS = R"(#version 300 es)" ZOOM_GLSL R"(
layout(location=0) in vec3 aDir;      // J2000 equatorial unit vector
layout(location=1) in vec2 aMS;       // magnitude, major axis in arcmin
layout(location=2) in vec2 aTC;       // type, catalogue bits (1 Messier, 2 Caldwell, 4 NGC)
uniform mat4 uVP;
uniform mat3 uPrec;
uniform vec4 uSky;
uniform float uPx;
uniform float uNight;
uniform float uLim;                   // NGC objects fainter than this are left out
uniform vec2 uHide;                   // catalogue index whose symbol is fading out (its picture is showing), amount 0..1
out vec3 vCol;
flat out float vType;
void main() {
    vec3 d = uPrec * aDir;
    float a = d.x * uSky.x + d.y * uSky.y;
    float b = d.x * uSky.y - d.y * uSky.x;
    vec3 w = zoomFwd(vec3(-b, d.z * uSky.z + a * uSky.w, -(d.z * uSky.w - a * uSky.z)));
    bool body = aTC.y > 7.5;                               // Sun, Moon, planets, satellites (8); asteroids (16)
    bool mc = mod(aTC.y, 4.0) > 0.5 || mod(floor(aTC.y / 8.0), 2.0) > 0.5;
    bool hide = dot(w, w) < 0.25 || w.y < 0.0 || (!mc && aMS.x > uLim);
    float deg = aMS.y / 60.0 * max(uZoom.w, 1.0);
    float minPx = body ? (aTC.x > 8.5 ? 12.0 : 16.0) : (mc ? 16.0 : 11.0);
    gl_PointSize = hide ? 0.0 : clamp(max(minPx, deg * 14.0), 11.0, 220.0) * uPx;
    gl_Position = hide ? vec4(2.0, 2.0, 2.0, 1.0) : uVP * vec4(w * 1000.0, 1.0);
    vType = aTC.x;
    vec3 c = vec3(0.7);
    if (aTC.x < 0.5) c = vec3(1.0, 0.45, 0.25);           // galaxy
    else if (aTC.x < 2.5) c = vec3(1.0, 0.85, 0.35);      // open / globular cluster
    else if (aTC.x < 3.5) c = vec3(0.3, 0.9, 1.0);        // planetary nebula
    else if (aTC.x < 4.5) c = vec3(0.35, 1.0, 0.5);       // nebula, remnant
    else if (aTC.x > 5.5 && aTC.x < 6.5) c = vec3(1.0, 0.95, 0.7);    // Sun
    else if (aTC.x > 6.5 && aTC.x < 7.5) c = vec3(0.95, 0.95, 0.85);  // Moon
    else if (aTC.x > 7.5 && aTC.x < 8.5) c = vec3(1.0, 0.92, 0.75);   // planet
    else if (aTC.x > 8.5 && aTC.x < 9.5) c = vec3(0.85, 0.7, 1.0);    // asteroid
    else if (aTC.x > 9.5) c = vec3(0.5, 1.0, 0.65);                   // satellite
    float lum = mc ? 0.75 : 0.45;
    if (body) {
        lum = clamp(0.9 - 0.12 * aMS.x, 0.12, 1.0);                   // brighter magnitude, brighter symbol
        if (aTC.x > 8.5 && aTC.x < 9.5) lum = max(lum, 0.45);         // asteroids
        if (aTC.x > 6.5 && aTC.x < 7.5) lum = 0.25 + 0.75 * aMS.x;    // Moon: illuminated fraction
        if (aTC.x < 6.5) lum = 1.0;                                    // Sun
    }
    if (gl_VertexID == int(uHide.x)) lum *= 1.0 - uHide.y;
    lum *= smoothstep(0.0, 0.05, w.y);
    vCol = (uNight > 0.5 ? vec3(1.0, 0.07, 0.0) : c) * lum;
}
)";
const char* kDsoFS = R"(#version 300 es
precision mediump float;
in vec3 vCol;
flat in float vType;
out vec4 o;
float ring(float r) { return smoothstep(0.70, 0.80, r) * (1.0 - smoothstep(0.92, 1.0, r)); }
void main() {
    vec2 p = gl_PointCoord * 2.0 - 1.0;
    float r = length(p), s = 0.0;
    if (vType < 0.5) {                                    // galaxy: ellipse
        s = ring(length(vec2(p.x, p.y * 1.8)));
    } else if (vType < 1.5) {                             // open cluster: dashed circle
        s = ring(r) * step(0.5, fract(atan(p.y, p.x) * 1.2732395));
    } else if (vType < 2.5) {                             // globular: circle with a cross
        s = max(ring(r), (abs(p.x) < 0.07 || abs(p.y) < 0.07) && r < 0.95 ? 1.0 : 0.0);
    } else if (vType < 3.5) {                             // planetary nebula: small circle with four ticks
        s = smoothstep(0.30, 0.38, r) * (1.0 - smoothstep(0.46, 0.54, r));
        float tk = max(abs(p.x), abs(p.y)), tn = min(abs(p.x), abs(p.y));
        s = max(s, tn < 0.07 && tk > 0.62 && tk < 0.95 ? 1.0 : 0.0);
    } else if (vType < 4.5) {                             // nebula, remnant: square
        s = ring(max(abs(p.x), abs(p.y)));
    } else if (vType < 5.5) {                             // other: diamond
        s = ring((abs(p.x) + abs(p.y)) * 0.8);
    } else if (vType < 7.5) {                             // Sun, Moon: solid disc (the Sun with a glow)
        s = (1.0 - smoothstep(0.78, 0.92, r)) + (vType < 6.5 ? 0.35 * pow(max(1.0 - r, 0.0), 2.0) : 0.0);
    } else if (vType < 8.5) {                             // planet: soft dot
        s = (1.0 - smoothstep(0.25, 0.55, r)) + 0.3 * pow(max(1.0 - r, 0.0), 2.0);
    } else if (vType < 9.5) {                             // asteroid: small plus
        s = (abs(p.x) < 0.1 || abs(p.y) < 0.1) && r < 0.7 ? 1.0 : 0.0;
    } else {                                              // satellite: filled square
        s = step(max(abs(p.x), abs(p.y)), 0.5);
    }
    if (s < 0.02) discard;
    o = vec4(vCol * s, 1.0);
}
)";

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
// DSS2 cutout: a quad parked around the object's displayed direction. The fragment shader works backwards from each
// displayed ray -- undo the zoom, undo the sky rotation, precess to J2000 -- and projects it (gnomonic) onto the
// object's tangent plane, so the picture is oriented and scaled by the sky itself: north up and east left at the
// object, however the view is rotated by the lock, and exactly registered with the stars.
const char* kDssVS = R"(#version 300 es
layout(location=0) in vec3 aPos;      // quad corners in displayed space, x=E y=up z=S
uniform mat4 uVP;
out vec3 vW;
void main() { vW = aPos; gl_Position = uVP * vec4(aPos, 1.0); }
)";
const char* kDssFS = R"(#version 300 es
precision highp float;
)" ZOOM_GLSL R"(
in vec3 vW;
uniform sampler2D uTex;               // 8-bit DSS2 red, row 0 = north edge, column 0 = east edge
uniform mat3 uPrec;
uniform vec4 uSky;                    // cos(LST), sin(LST), sin(lat), cos(lat)
uniform vec3 uObj;                    // object, J2000
uniform vec3 uEast;                   // tangent-plane axes at the object, J2000
uniform vec3 uNorth;
uniform float uFov;                   // image width, radians
uniform float uAlpha;
uniform float uNight;
uniform float uBase;                  // the plate's sky level, 0..1
out vec4 o;
void main() {
    vec3 w = normalize(vW);
    vec3 ws = zoomInv(w);
    float east = ws.x, up = ws.y, north = -ws.z;
    float a = up * uSky.w - north * uSky.z;
    float zz = up * uSky.z + north * uSky.w;
    float b = -east;
    vec3 eq = vec3(a * uSky.x + b * uSky.y, a * uSky.y - b * uSky.x, zz) * uPrec;
    float c = dot(eq, uObj);
    vec2 uv = vec2(0.5 - dot(eq, uEast) / max(c, 0.05) / uFov, 0.5 - dot(eq, uNorth) / max(c, 0.05) / uFov);
    float g = texture(uTex, clamp(uv, 0.0, 1.0)).r;
    vec2 e = min(uv, 1.0 - uv);
    float edge = min(e.x, e.y);
    if (c <= 0.05 || edge <= 0.0 || w.y <= 0.0) discard;
    // The plate's own sky level is taken out and what is left is ADDED to the background, so the picture carries no frame:
    // where the plate is empty sky nothing changes, and a radial fade takes the outer part of the field to nothing.
    float v = pow(clamp((g - uBase) / max(1.0 - uBase, 0.05), 0.0, 1.0), 0.85);
    float fade = 1.0 - smoothstep(0.35, 1.0, length(uv - 0.5) * 2.0);
    vec3 col = vec3(v * 0.93, v * 0.96, v) * 1.1;
    if (uNight > 0.5) col = vec3(v, v * 0.06, 0.0);
    o = vec4(col * (uAlpha * fade * smoothstep(0.0, 0.05, w.y)), 1.0);
}
)";

const char* kFlatVS = R"(#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec4 aCol;
uniform mat4 uMVP;
out vec4 vCol;
void main() { vCol = aCol; gl_Position = uMVP * vec4(aPos, 1.0); }
)";
const char* kFlatFS = R"(#version 300 es
precision mediump float;
in vec4 vCol;
out vec4 o;
void main() { o = vec4(vCol.rgb * vCol.a, 1.0); }
)";

const char* kLineVS = R"(#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uMVP;
void main() { gl_Position = uMVP * vec4(aPos, 1.0); }
)";
const char* kLineFS = R"(#version 300 es
precision mediump float;
uniform vec3 uColor;
out vec4 o;
void main() { o = vec4(uColor, 1.0); }
)";

const char* kTextVS = R"(#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec2 aUv;
uniform mat4 uMVP;
out vec2 vUv;
void main() { vUv = aUv; gl_Position = uMVP * vec4(aPos, 1.0); }
)";
const char* kTextFS = R"(#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec3 uColor;
out vec4 o;
void main() { o = vec4(uColor * texture(uTex, vUv).r, 1.0); }
)";

// 5x7 glyphs: enough for a HUD and compass letters. Rows top to bottom, 5 bits.
struct Glyph { char c; uint8_t r[7]; };
const Glyph kGlyphs[] = {
    {' ', {0, 0, 0, 0, 0, 0, 0}},
    {'0', {14, 17, 19, 21, 25, 17, 14}}, {'1', {4, 12, 4, 4, 4, 4, 14}},
    {'2', {14, 17, 1, 2, 4, 8, 31}},     {'3', {30, 1, 1, 14, 1, 1, 30}},
    {'4', {2, 6, 10, 18, 31, 2, 2}},     {'5', {31, 16, 30, 1, 1, 17, 14}},
    {'6', {6, 8, 16, 30, 17, 17, 14}},   {'7', {31, 1, 2, 4, 8, 8, 8}},
    {'8', {14, 17, 17, 14, 17, 17, 14}}, {'9', {14, 17, 17, 15, 1, 2, 12}},
    {'A', {14, 17, 17, 31, 17, 17, 17}}, {'B', {30, 17, 17, 30, 17, 17, 30}},
    {'C', {14, 17, 16, 16, 16, 17, 14}}, {'D', {28, 18, 17, 17, 17, 18, 28}},
    {'E', {31, 16, 16, 30, 16, 16, 31}}, {'F', {31, 16, 16, 30, 16, 16, 16}},
    {'G', {14, 17, 16, 23, 17, 17, 15}}, {'H', {17, 17, 17, 31, 17, 17, 17}},
    {'I', {14, 4, 4, 4, 4, 4, 14}},      {'J', {7, 2, 2, 2, 2, 18, 12}},
    {'K', {17, 18, 20, 24, 20, 18, 17}}, {'L', {16, 16, 16, 16, 16, 16, 31}},
    {'M', {17, 27, 21, 21, 17, 17, 17}}, {'N', {17, 17, 25, 21, 19, 17, 17}},
    {'O', {14, 17, 17, 17, 17, 17, 14}}, {'P', {30, 17, 17, 30, 16, 16, 16}},
    {'Q', {14, 17, 17, 17, 21, 18, 13}}, {'R', {30, 17, 17, 30, 20, 18, 17}},
    {'S', {15, 16, 16, 14, 1, 1, 30}},   {'T', {31, 4, 4, 4, 4, 4, 4}},
    {'U', {17, 17, 17, 17, 17, 17, 14}}, {'V', {17, 17, 17, 17, 17, 10, 4}},
    {'W', {17, 17, 17, 21, 21, 21, 10}}, {'X', {17, 17, 10, 4, 10, 17, 17}},
    {'Y', {17, 17, 10, 4, 4, 4, 4}},     {'Z', {31, 1, 2, 4, 8, 16, 31}},
    {'.', {0, 0, 0, 0, 0, 12, 12}},      {':', {0, 12, 12, 0, 12, 12, 0}},
    {'-', {0, 0, 0, 31, 0, 0, 0}},       {'+', {0, 4, 4, 31, 4, 4, 0}},
    {'/', {1, 1, 2, 4, 8, 16, 16}},      {'<', {2, 4, 8, 16, 8, 4, 2}},
    {'=', {0, 0, 31, 0, 31, 0, 0}},      {'%', {24, 25, 2, 4, 8, 19, 3}},
};
constexpr int kGlyphCount = sizeof(kGlyphs) / sizeof(kGlyphs[0]);
constexpr int kCell = 6;  // 5 px + 1 px gap

struct Vec3 { float x, y, z; };
Vec3 operator+(Vec3 a, Vec3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
Vec3 operator-(Vec3 a, Vec3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
Vec3 operator*(Vec3 a, float k) { return {a.x * k, a.y * k, a.z * k}; }
float dot(Vec3 a, Vec3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
Vec3 cross(Vec3 a, Vec3 b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
Vec3 normalize(Vec3 a) {
    float l = std::sqrt(dot(a, a));
    return l > 1e-9f ? a * (1.f / l) : Vec3{0.f, 1.f, 0.f};
}

// ---------------------------------------------------------------- star data

// fontarial.bin glyph record (tools/make_font.py): atlas rect plus quad in font pixels, y up
struct FontGlyph { uint32_t code; float u0, v0, u1, v1, x0, y0, x1, y1, adv; };

struct Star { float x, y, z, mag, bv; };

struct StarSet {
    GLuint vbo = 0, vao = 0;
    std::vector<float> mags;  // ascending, for the magnitude-limit prefix
    uint32_t count = 0;
};

bool loadStars(AAssetManager* am, std::vector<Star>& out) {
    AAsset* a = AAssetManager_open(am, "stars.bin", AASSET_MODE_BUFFER);
    if (!a) { LOGE("stars.bin missing from assets"); return false; }
    const uint8_t* p = static_cast<const uint8_t*>(AAsset_getBuffer(a));
    size_t len = AAsset_getLength(a);
    bool ok = len >= 8 && memcmp(p, "GSTR", 4) == 0;
    if (ok) {
        uint32_t n;
        memcpy(&n, p + 4, 4);
        ok = len >= 8 + size_t(n) * sizeof(Star);
        if (ok) {
            out.resize(n);
            memcpy(out.data(), p + 8, size_t(n) * sizeof(Star));
        }
    }
    AAsset_close(a);
    if (!ok) LOGE("stars.bin malformed");
    return ok;
}

// Stress set: every real star spawned K fainter neighbours so the catalog can be
// pushed to ~2M points. Synthetic, only for the load test.
void buildStress(const std::vector<Star>& real, std::vector<Star>& out, int k) {
    std::mt19937 rng(42);
    std::uniform_real_distribution<float> u01(0.f, 1.f);
    std::normal_distribution<float> nd(0.f, 0.2f);
    out = real;
    out.reserve(real.size() * (k + 1));
    for (const Star& s : real) {
        Vec3 d{s.x, s.y, s.z};
        // any vector not parallel to d gives a tangent basis
        Vec3 t = std::fabs(d.z) < 0.9f ? Vec3{0, 0, 1} : Vec3{1, 0, 0};
        Vec3 e1{d.y * t.z - d.z * t.y, d.z * t.x - d.x * t.z, d.x * t.y - d.y * t.x};
        float l1 = std::sqrt(e1.x * e1.x + e1.y * e1.y + e1.z * e1.z);
        e1 = {e1.x / l1, e1.y / l1, e1.z / l1};
        Vec3 e2{d.y * e1.z - d.z * e1.y, d.z * e1.x - d.x * e1.z, d.x * e1.y - d.y * e1.x};
        for (int i = 0; i < k; ++i) {
            float ang = (0.2f + 1.8f * u01(rng)) * float(kPi / 180.0);
            float ph = 6.2831853f * u01(rng);
            float ca = std::cos(ang), sa = std::sin(ang), cp = std::cos(ph), sp = std::sin(ph);
            Star c;
            c.x = d.x * ca + (e1.x * cp + e2.x * sp) * sa;
            c.y = d.y * ca + (e1.y * cp + e2.y * sp) * sa;
            c.z = d.z * ca + (e1.z * cp + e2.z * sp) * sa;
            c.mag = s.mag + 0.4f + 4.6f * u01(rng);
            c.bv = s.bv + nd(rng);
            out.push_back(c);
        }
    }
    std::sort(out.begin(), out.end(), [](const Star& a, const Star& b) { return a.mag < b.mag; });
}

void uploadSet(StarSet& set, const std::vector<Star>& stars) {
    set.count = (uint32_t)stars.size();
    set.mags.resize(stars.size());
    for (size_t i = 0; i < stars.size(); ++i) set.mags[i] = stars[i].mag;
    glGenVertexArrays(1, &set.vao);
    glGenBuffers(1, &set.vbo);
    glBindVertexArray(set.vao);
    glBindBuffer(GL_ARRAY_BUFFER, set.vbo);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(stars.size() * sizeof(Star)), stars.data(), GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Star), (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, sizeof(Star), (void*)12);
    glBindVertexArray(0);
}

uint32_t prefixCount(const StarSet& set, float limit) {
    return (uint32_t)(std::upper_bound(set.mags.begin(), set.mags.end(), limit) - set.mags.begin());
}

// ---------------------------------------------------------------- app

struct EyeSwapchain {
    XrSwapchain handle = XR_NULL_HANDLE;
    GLuint depth = 0;  // depth is only needed for the hands
    int32_t w = 0, h = 0;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
    std::vector<GLuint> fbos;
};

struct Stats {
    Clock::time_point windowStart = Clock::now(), lastEnd{}, beginT{};
    int frames = 0;
    double worstMs = 0, cpuMs = 0, gpuNs = 0;
    double waitMs = 0, workMs = 0, endMs = 0;  // where a frame's time goes: xrWaitFrame, our work, xrEndFrame
    double swapMs = 0, drawMs = 0;             // within work: xrWaitSwapchainImage (both eyes) and renderEye (both)
    int gpuSamples = 0;
    double fps = 0, worst = 0, cpu = 0, gpu = 0;  // last published window
};

// One Touch controller: palm pose (grip), pointing ray (aim) and the inputs that curl the virtual fingers.
struct Hand {
    XrSpace grip = XR_NULL_HANDLE, aim = XR_NULL_HANDLE;
    bool gripOk = false, aimOk = false;
    Vec3 gripPos{0, 0, 0}, aimPos{0, 0, 0}, hitPt{0, 0, 0};
    XrQuaternionf aimQ{0, 0, 0, 1};
    XrQuaternionf gripQ{0, 0, 0, 1};
    float trigger = 0, squeeze = 0, thumb = 0;
    std::vector<float> mesh;  // pos+normal triangles in hand space, rebuilt every frame
    bool trigDown = false, trigEdge = false;
    bool sqzDown = false, sqzEdge = false;  // the second (grip) trigger
};

// One sky object the user can point at: a Messier / Caldwell / NGC object (deepsky.bin, tools/make_dso.py) or a
// solar-system body whose direction is recomputed every frame.
struct Dso {
    Vec3 d{0, 0, 0};  // J2000 unit vector
    float mag = 15.f, size = 0.f;  // size: major axis, arcmin
    uint8_t type = 0, cat = 0;     // type: 0 galaxy 1 open 2 globular 3 planetary 4 nebula 5 other 6 Sun 7 Moon 8 planet 9 asteroid 10 satellite
                                   // cat bits: M=1 C=2 NGC=4, 8 = always shown (Sun, Moon, planets, lit satellites), 16 = asteroid (magnitude-limited)
                                   // for the Moon "mag" holds the illuminated fraction
    char label[10] = {};
};
const char* const kDsoTypeName[11] = {"GALAXY", "OPEN CLUSTER", "GLOBULAR", "PLANETARY", "NEBULA", "OTHER",
                                      "SUN", "MOON", "PLANET", "ASTEROID", "SATELLITE"};

// Panel text for a catalogue object (deepinfo.txt, tools/make_info.py)
struct Info { std::string common, also, con, desc; };
struct PanelLine { std::string s; int style; };  // style 0 title, 1 body, 2 dim, 3 hint

constexpr float kDome = 1000.f;      // radius the sky is drawn at (stars, lines, labels); the background is 5x that
constexpr float kFloorY = -1.65f;    // ground plane in the LOCAL space, assuming the session starts at standing eye height
constexpr float kMaxZoom = 40.f;

constexpr int kTiers = 4;
const uint32_t kTierCap[kTiers] = {0, 500000, 1000000, 2000000};  // 0 = real catalog, uncapped

struct App {
    android_app* android = nullptr;
    bool resumed = false, initDone = false, sessionRunning = false, quit = false;

    EGLDisplay dpy = EGL_NO_DISPLAY;
    EGLContext ctx = EGL_NO_CONTEXT;
    EGLSurface surf = EGL_NO_SURFACE;
    EGLConfig cfg = nullptr;

    XrInstance inst = XR_NULL_HANDLE;
    XrSystemId sys = XR_NULL_SYSTEM_ID;
    XrSession sess = XR_NULL_HANDLE;
    XrSpace space = XR_NULL_HANDLE;
    XrSessionState state = XR_SESSION_STATE_UNKNOWN;
    EyeSwapchain eye[2];

    XrActionSet set = XR_NULL_HANDLE;
    XrAction aStickL = XR_NULL_HANDLE, aStickR = XR_NULL_HANDLE, aA = XR_NULL_HANDLE, aB = XR_NULL_HANDLE,
             aX = XR_NULL_HANDLE, aY = XR_NULL_HANDLE, aMenu = XR_NULL_HANDLE, aSel = XR_NULL_HANDLE,
             aBg = XR_NULL_HANDLE;

    PFN_xrEnumerateDisplayRefreshRatesFB xrEnumRates = nullptr;
    PFN_xrRequestDisplayRefreshRateFB xrRequestRate = nullptr;
    std::vector<float> rates;
    int rateIdx = 0;
    float targetHz = 72.f;

    GLuint starProg = 0, lineProg = 0, textProg = 0, fontTex = 0;
    GLuint ringVao = 0, ringVbo = 0, textVao = 0, textVbo = 0;
    int ringVerts = 0;
    std::string charset;

    // constellations
    GLuint conProg = 0, conVao = 0, conVbo = 0;
    int conVerts = 0;
    std::vector<float> conLines;                       // pairs of unit vectors
    struct ConName { Vec3 d; std::string s; };
    std::vector<ConName> conNames;
    bool showCon = true;

    StarSet real, stress;
    std::vector<Star> realStars;
    std::atomic<bool> stressReady{false};
    std::vector<Star> stressStars;
    std::thread stressThread;
    bool stressUploaded = false;

    // scene state
    int tier = 0;
    float magLimit = 5.f;
    double lst = 0.0;      // local sidereal time, radians
    double simOffset = 0.0;  // seconds added to the real clock by the time stick
    double lonDeg = 0.0, elevM = 0.0;
    std::string siteName = "NO SITE", siteTz = "UTC";
    float prec[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};  // J2000 -> date, column-major
    std::string hSite, hUtc, hLocal, hLst, hOff;

    // DSS2 background and Arial label atlas
    GLuint bgProg = 0, bgVao = 0, bgVbo = 0, bgEbo = 0, bgTex = 0;
    int bgIndices = 0;
    bool bgOn = true;
    float bgExposure = 0.6f;

    // Messier / Caldwell / NGC, zoom and lock
    GLuint dsoProg = 0, dsoVao = 0, dsoVbo = 0;
    std::vector<uint8_t> dsoRaw;
    std::vector<Dso> dso;
    std::vector<Vec3> dsoW;            // displayed (zoomed, locked) horizontal direction of each object, per frame
    bool showDso = true, showBodies = true;
    size_t nStatic = 0;                // dso[0, nStatic) are catalogue objects; after them Sun, Moon, planets, asteroids, satellites
    size_t astStart = 0, satStart = 0;
    // DSS2 cutouts (dss.bin) drawn over the sky once zoomed in, and the panel that pops up from the right controller
    std::vector<uint8_t> dssRaw;
    uint32_t dssCount = 0, dssSide = 0;
    size_t dssPix = 0;                 // offset of the first image in dssRaw
    std::vector<int> dssOf;            // catalogue index -> image index, -1 when there is none
    std::vector<float> dssFov;         // degrees, per image
    GLuint dssProg = 0, dssVao = 0, dssVbo = 0, dssTex = 0;
    int dssShown = -1, dssLoaded = -1; // catalogue index being drawn, image index in the texture
    float dssAlpha = 0.f, dssFovRad = 0.f, dssBase = 0.f;
    Vec3 dssObj{0, 0, 0}, dssEast{0, 0, 0}, dssNorth{0, 0, 0};
    float dssQuad[18] = {};
    std::unordered_map<std::string, Info> info;
    std::vector<PanelLine> panelLines;
    float panelT = 0.f;                // 0 closed .. 1 open
    Vec3 panelO{0, 0, 0}, panelR{1, 0, 0}, panelU{0, 1, 0};  // bottom centre, text direction and up of the open panel
    float panelScroll = 0.f, panelMaxScroll = 0.f;  // metres of content scrolled off the top, and the most it can be
    int panelFor = -1;                 // catalogue index the panel was last built for (scroll resets on a change)
    Vec3 headPos{0, 0, 0};
    // controller models from the runtime (XR_FB_render_model), drawn in grip space when available
    PFN_xrEnumerateRenderModelPathsFB xrEnumPaths = nullptr;
    PFN_xrGetRenderModelPropertiesFB xrModelProps = nullptr;
    PFN_xrLoadRenderModelFB xrLoadModel = nullptr;
    GLuint ctlProg = 0, ctlVao[2] = {0, 0}, ctlVbo[2] = {0, 0};
    int ctlVerts[2] = {0, 0}, ctlTries = 0;
    double ctlNext = 0;
    std::vector<ephem::Asteroid> ast;
    std::vector<uint8_t> satRaw;       // satellites.bin, sampled TEME tracks
    double satT0 = 0, tNow = 0;        // track start (unix); the simulated time
    float satStep = 300.f;
    uint32_t satN = 0, satCount = 0;
    GLuint bodyVao = 0, bodyVbo = 0;
    float zoom = 1.f, zoomLog = 0.f;
    int lock = -1, hover = -1;         // indices into dso
    bool zcSet = false, selectReq = false;
    Vec3 zc{0, 0, -1}, zd{0, 0, -1};   // zoom centre (true sky) and the display direction it is parked at
    float rot[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};  // column-major, rotates zc onto zd
    Vec3 headFwd{0, 0, -1};
    std::string hLock = "LOCK NONE", hOver = "OVER NONE";

    // controllers
    XrPath handPath[2] = {0, 0};
    XrAction aGrip = XR_NULL_HANDLE, aAim = XR_NULL_HANDLE, aTrig = XR_NULL_HANDLE, aSqz = XR_NULL_HANDLE,
             aThumbA = XR_NULL_HANDLE, aThumbB = XR_NULL_HANDLE;
    Hand hand[2];  // 0 = left, 1 = right
    GLuint handProg = 0, flatProg = 0, handVao = 0, handVbo = 0, flatVao = 0, flatVbo = 0;
    std::string hPoint1 = "POINT NONE", hPoint2 = " ";
    std::vector<uint8_t> bgRaw, fontRaw;
    GLuint arialTex = 0;
    std::vector<FontGlyph> glyphs;  // codes 32..126
    float fontPx = 56.f;
    int fontW = 0, fontH = 0;
    size_t fontAtlasOff = 0;
    bool night = false, hud = false;  // the diagnostic text is off by default; the menu button toggles it
    float latitude = 45.f * float(kPi / 180.0);

    // GPU timer
    bool timerOk = false;
    void (*glGenQ)(GLsizei, GLuint*) = nullptr;
    void (*glBeginQ)(GLenum, GLuint) = nullptr;
    void (*glEndQ)(GLenum) = nullptr;
    void (*glGetQ)(GLuint, GLenum, GLuint*) = nullptr;
    void (*glGetQ64)(GLuint, GLenum, uint64_t*) = nullptr;
    GLuint q[8] = {};
    bool qPending[8] = {};
    uint32_t qSlot = 0;

    uint32_t lastDrawn = 0;
    Stats st;
};

App g;

// Whole asset into memory; empty on failure.
std::vector<uint8_t> readAsset(AAssetManager* am, const char* name) {
    std::vector<uint8_t> out;
    AAsset* a = AAssetManager_open(am, name, AASSET_MODE_BUFFER);
    if (!a) { LOGE("%s missing from assets", name); return out; }
    const uint8_t* p = static_cast<const uint8_t*>(AAsset_getBuffer(a));
    out.assign(p, p + AAsset_getLength(a));
    AAsset_close(a);
    return out;
}

// Solar-system bodies appended after the catalogue objects: Sun, Moon, 7 planets, then asteroids and satellites.
void addBody(const char* name, uint8_t type, uint8_t cat, float mag) {
    Dso o;
    o.type = type;
    o.cat = cat;
    o.mag = mag;
    strncpy(o.label, name, 9);
    g.dso.push_back(o);
}
void loadBodies(AAssetManager* am) {
    g.nStatic = g.dso.size();
    static const char* const planets[9] = {"Sun", "Moon", "Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"};
    for (int i = 0; i < 9; ++i) addBody(planets[i], uint8_t(i == 0 ? 6 : i == 1 ? 7 : 8), 8, 0.f);

    g.astStart = g.dso.size();
    std::vector<uint8_t> a = readAsset(am, "asteroids.bin");  // "GAST", count, 80-byte records (tools/make_bodies.py)
    if (a.size() >= 8 && memcmp(a.data(), "GAST", 4) == 0) {
        uint32_t n;
        memcpy(&n, a.data() + 4, 4);
        if (a.size() >= 8 + size_t(n) * 80) {
            for (uint32_t i = 0; i < n; ++i) {
                const uint8_t* r = a.data() + 8 + size_t(i) * 80;
                char name[17] = {};
                memcpy(name, r, 16);
                float hg[2];
                memcpy(hg, r + 16, 8);
                double el[7];
                memcpy(el, r + 24, 56);
                g.ast.push_back({hg[0], hg[1], el[0], el[1], el[2], el[3], el[4], el[5], el[6]});
                addBody(name, 9, 16, 15.f);
            }
        }
    }

    g.satStart = g.dso.size();
    g.satRaw = readAsset(am, "satellites.bin");  // "GSAT", count, t0, step, samples, per sat: name[16] mag pad, samples x 6 floats
    if (g.satRaw.size() >= 24 && memcmp(g.satRaw.data(), "GSAT", 4) == 0) {
        memcpy(&g.satCount, g.satRaw.data() + 4, 4);
        memcpy(&g.satT0, g.satRaw.data() + 8, 8);
        memcpy(&g.satStep, g.satRaw.data() + 16, 4);
        memcpy(&g.satN, g.satRaw.data() + 20, 4);
        if (g.satRaw.size() >= 24 + size_t(g.satCount) * (24 + size_t(g.satN) * 24) && g.satN > 2) {
            for (uint32_t k = 0; k < g.satCount; ++k) {
                char name[17] = {};
                memcpy(name, g.satRaw.data() + 24 + size_t(k) * (24 + size_t(g.satN) * 24), 16);
                addBody(name, 10, 8, 99.f);
            }
        } else {
            g.satCount = 0;
        }
    }
    g.dsoW.assign(g.dso.size(), Vec3{0, 0, 0});
    LOGI("bodies: 9 sun/moon/planets, %zu asteroids, %u satellites", g.ast.size(), g.satCount);
}

// Observing site, DSS2 map and Arial atlas. All optional; the atlas degrades to
// a plain star field at 0N 0E without them.
// dss.bin: "GDSS", count, side, count x {label[12], fov}, count x side*side bytes (tools/make_dss.py). Optional.
void loadDss(AAssetManager* am) {
    g.dssRaw = readAsset(am, "dss.bin");
    g.dssCount = 0;
    if (g.dssRaw.size() < 12 || memcmp(g.dssRaw.data(), "GDSS", 4) != 0) { g.dssRaw.clear(); return; }
    uint32_t n, side;
    memcpy(&n, g.dssRaw.data() + 4, 4);
    memcpy(&side, g.dssRaw.data() + 8, 4);
    size_t px = size_t(side) * side;
    if (side == 0 || g.dssRaw.size() < 12 + size_t(n) * 16 + size_t(n) * px) { g.dssRaw.clear(); return; }
    g.dssSide = side;
    g.dssPix = 12 + size_t(n) * 16;
    g.dssOf.assign(g.nStatic, -1);
    g.dssFov.assign(n, 0.f);
    std::unordered_map<std::string, size_t> byLabel;
    for (size_t i = 0; i < g.nStatic; ++i) byLabel.emplace(g.dso[i].label, i);
    for (uint32_t k = 0; k < n; ++k) {
        const uint8_t* e = g.dssRaw.data() + 12 + size_t(k) * 16;
        char lab[13];
        memcpy(lab, e, 12);
        lab[12] = 0;
        memcpy(&g.dssFov[k], e + 12, 4);
        auto it = byLabel.find(lab);
        if (it != byLabel.end()) g.dssOf[it->second] = int(k);
    }
    g.dssCount = n;
    LOGI("DSS2 cutouts: %u images of %ux%u", n, side, side);
}

// deepinfo.txt: label, common name(s), other designations, constellation, description; tab separated, optional.
void loadInfo(AAssetManager* am) {
    std::vector<uint8_t> t = readAsset(am, "deepinfo.txt");
    size_t pos = 0;
    while (pos < t.size()) {
        size_t end = pos;
        while (end < t.size() && t[end] != '\n') ++end;
        std::string line(reinterpret_cast<const char*>(t.data()) + pos, end - pos);
        pos = end + 1;
        if (!line.empty() && line.back() == '\r') line.pop_back();
        std::string f[5];
        int k = 0;
        size_t a = 0;
        for (size_t i = 0; i <= line.size() && k < 5; ++i)
            if (i == line.size() || line[i] == '\t') { f[k++] = line.substr(a, i - a); a = i + 1; }
        if (k == 5 && !f[0].empty()) g.info[f[0]] = Info{f[1], f[2], f[3], f[4]};
    }
    LOGI("object info: %zu entries", g.info.size());
}

void loadSkyAssets(AAssetManager* am) {
    std::vector<uint8_t> t = readAsset(am, "site.txt");
    if (!t.empty()) {
        std::string text(t.begin(), t.end());
        char name[64] = {}, tz[64] = "UTC";
        double lat = 0, lon = 0, el = 0;
        if (sscanf(text.c_str(), "%63[^|]|%lf|%lf|%lf|%63[^\n\r]", name, &lat, &lon, &el, tz) >= 4) {
            g.siteName = name;
            g.siteTz = tz;
            g.latitude = float(lat * kPi / 180.0);
            g.lonDeg = lon;
            g.elevM = el;
        }
    }
    setenv("TZ", g.siteTz.c_str(), 1);  // local time is shown for the site, not the headset
    tzset();
    LOGI("site %s lat %.4f lon %.4f elev %.0f tz %s", g.siteName.c_str(), g.latitude * 180.0 / kPi, g.lonDeg,
         g.elevM, g.siteTz.c_str());

    g.bgRaw = readAsset(am, "dssbg.bin");
    g.fontRaw = readAsset(am, "fontarial.bin");
    const std::vector<uint8_t>& f = g.fontRaw;
    if (f.size() >= 12 && memcmp(f.data(), "GFNT", 4) == 0) {
        uint16_t hdr[4];
        memcpy(hdr, f.data() + 4, 8);
        size_t n = hdr[2];
        size_t off = 12 + n * sizeof(FontGlyph);
        if (f.size() >= off + size_t(hdr[0]) * hdr[1]) {
            g.glyphs.resize(n);
            memcpy(g.glyphs.data(), f.data() + 12, n * sizeof(FontGlyph));
            g.fontW = hdr[0];
            g.fontH = hdr[1];
            g.fontPx = float(hdr[3]);
            g.fontAtlasOff = off;
        }
    }
    LOGI("sky assets: background %zu bytes, %zu glyphs", g.bgRaw.size(), g.glyphs.size());

    // deepsky.bin: "GDSO", count, count x 32-byte records (tools/make_dso.py)
    std::vector<uint8_t> d = readAsset(am, "deepsky.bin");
    if (d.size() >= 8 && memcmp(d.data(), "GDSO", 4) == 0) {
        uint32_t n;
        memcpy(&n, d.data() + 4, 4);
        if (d.size() >= 8 + size_t(n) * 32) {
            g.dsoRaw.assign(d.begin() + 8, d.begin() + 8 + ptrdiff_t(n) * 32);
            g.dso.resize(n);
            g.dsoW.assign(n, Vec3{0, 0, 0});
            for (uint32_t i = 0; i < n; ++i) {
                const uint8_t* r = g.dsoRaw.data() + size_t(i) * 32;
                float f[5];
                memcpy(f, r, 20);
                Dso& o = g.dso[i];
                o.d = {f[0], f[1], f[2]};
                o.mag = f[3];
                o.size = f[4];
                o.type = r[20];
                o.cat = r[21];
                memcpy(o.label, r + 22, 9);
                o.label[9] = 0;
            }
        }
    }
    LOGI("deep-sky objects: %zu", g.dso.size());
    loadBodies(am);
    loadDss(am);
    loadInfo(am);
}

// constlines.bin ("GCLN", count, count x 6 floats) and constnames.txt ("x y z Abbr").
// Both optional: the atlas still runs without constellations.
void loadConstellations(AAssetManager* am) {
    std::vector<uint8_t> b = readAsset(am, "constlines.bin");
    if (b.size() >= 8 && memcmp(b.data(), "GCLN", 4) == 0) {
        uint32_t n;
        memcpy(&n, b.data() + 4, 4);
        if (b.size() >= 8 + size_t(n) * 24) {
            g.conLines.resize(size_t(n) * 6);
            memcpy(g.conLines.data(), b.data() + 8, size_t(n) * 24);
        }
    }
    {   // 8 short great-circle arcs per segment, so the lines curve correctly when the zoom bends the sky
        std::vector<float> fine;
        const int kSub = 8;
        for (size_t i = 0; i + 5 < g.conLines.size(); i += 6) {
            Vec3 a{g.conLines[i], g.conLines[i + 1], g.conLines[i + 2]};
            Vec3 b{g.conLines[i + 3], g.conLines[i + 4], g.conLines[i + 5]};
            Vec3 prev = a;
            for (int k = 1; k <= kSub; ++k) {
                float t = float(k) / kSub;
                Vec3 p = k == kSub ? b : normalize(a * (1.f - t) + b * t);
                fine.insert(fine.end(), {prev.x, prev.y, prev.z, p.x, p.y, p.z});
                prev = p;
            }
        }
        g.conLines.swap(fine);
    }
    std::vector<uint8_t> t = readAsset(am, "constnames.txt");
    std::string text(t.begin(), t.end());
    size_t pos = 0;
    while (pos < text.size()) {
        size_t e = text.find('\n', pos);
        if (e == std::string::npos) e = text.size();
        std::string line = text.substr(pos, e - pos);
        pos = e + 1;
        Vec3 d;
        int off = 0;
        if (sscanf(line.c_str(), "%f %f %f %n", &d.x, &d.y, &d.z, &off) >= 3 && off > 0)
            g.conNames.push_back({d, line.substr(size_t(off))});
    }
    LOGI("constellations: %zu segments, %zu names", g.conLines.size() / 6, g.conNames.size());
}


// -------- EGL / GL

bool initEgl() {
    g.dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(g.dpy, nullptr, nullptr);
    const EGLint attrs[] = {EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                            EGL_NONE};
    EGLint n = 0;
    if (!eglChooseConfig(g.dpy, attrs, &g.cfg, 1, &n) || n < 1) { LOGE("no EGL config"); return false; }
    const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    g.ctx = eglCreateContext(g.dpy, g.cfg, EGL_NO_CONTEXT, ctxAttrs);
    const EGLint sAttrs[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    g.surf = eglCreatePbufferSurface(g.dpy, g.cfg, sAttrs);
    if (g.ctx == EGL_NO_CONTEXT || g.surf == EGL_NO_SURFACE) { LOGE("EGL context failed"); return false; }
    return eglMakeCurrent(g.dpy, g.surf, g.surf, g.ctx) == EGL_TRUE;
}

void initGlResources() {
    g.starProg = program(kStarVS, kStarFS);
    g.lineProg = program(kLineVS, kLineFS);
    g.textProg = program(kTextVS, kTextFS);

    // font atlas
    for (int i = 0; i < kGlyphCount; ++i) g.charset.push_back(kGlyphs[i].c);
    int aw = kGlyphCount * kCell;
    std::vector<uint8_t> px(size_t(aw) * 7, 0);
    for (int i = 0; i < kGlyphCount; ++i)
        for (int row = 0; row < 7; ++row)
            for (int col = 0; col < 5; ++col)
                if (kGlyphs[i].r[row] & (1 << (4 - col))) px[size_t(row) * aw + i * kCell + col] = 255;
    glGenTextures(1, &g.fontTex);
    glBindTexture(GL_TEXTURE_2D, g.fontTex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, aw, 7, 0, GL_RED, GL_UNSIGNED_BYTE, px.data());
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    // reference rings: horizon plus 30 and 60 degrees altitude
    std::vector<float> ring;
    const int seg = 128;
    const float alts[3] = {0.f, 30.f, 60.f};
    for (float alt : alts) {
        float ar = alt * float(kPi / 180.0), r = kDome * 0.99f * std::cos(ar), y = kDome * 0.99f * std::sin(ar);
        for (int i = 0; i <= seg; ++i) {
            float a = 2.f * float(kPi) * i / seg;
            ring.insert(ring.end(), {r * std::cos(a), y, r * std::sin(a)});
        }
    }
    g.ringVerts = int(ring.size() / 3);
    glGenVertexArrays(1, &g.ringVao);
    glGenBuffers(1, &g.ringVbo);
    glBindVertexArray(g.ringVao);
    glBindBuffer(GL_ARRAY_BUFFER, g.ringVbo);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(ring.size() * 4), ring.data(), GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 12, nullptr);

    glGenVertexArrays(1, &g.textVao);
    glGenBuffers(1, &g.textVbo);
    glBindVertexArray(g.textVao);
    glBindBuffer(GL_ARRAY_BUFFER, g.textVbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 20, (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 20, (void*)12);
    glBindVertexArray(0);

    // constellation lines
    g.conProg = program(kConVS, kConFS);
    g.conVerts = int(g.conLines.size() / 3);
    glGenVertexArrays(1, &g.conVao);
    glGenBuffers(1, &g.conVbo);
    glBindVertexArray(g.conVao);
    glBindBuffer(GL_ARRAY_BUFFER, g.conVbo);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(g.conLines.size() * 4), g.conLines.data(), GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 12, nullptr);
    glBindVertexArray(0);

    // DSS2 background: RGB bytes -> sRGB texture (so filtering and blending are linear)
    g.handProg = program(kHandVS, kHandFS);
    g.ctlProg = program(kCtlVS, kCtlFS);
    g.flatProg = program(kFlatVS, kFlatFS);
    glGenVertexArrays(1, &g.handVao);
    glGenBuffers(1, &g.handVbo);
    glBindVertexArray(g.handVao);
    glBindBuffer(GL_ARRAY_BUFFER, g.handVbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 24, (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 24, (void*)12);
    glGenVertexArrays(1, &g.flatVao);
    glGenBuffers(1, &g.flatVbo);
    glBindVertexArray(g.flatVao);
    glBindBuffer(GL_ARRAY_BUFFER, g.flatVbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 28, (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, 28, (void*)12);
    glBindVertexArray(0);

    g.dssProg = program(kDssVS, kDssFS);
    glGenVertexArrays(1, &g.dssVao);
    glGenBuffers(1, &g.dssVbo);
    glBindVertexArray(g.dssVao);
    glBindBuffer(GL_ARRAY_BUFFER, g.dssVbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 12, (void*)0);
    glBindVertexArray(0);
    glGenTextures(1, &g.dssTex);
    glBindTexture(GL_TEXTURE_2D, g.dssTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    g.bgProg = program(kBgVS, kBgFS);
    if (g.bgRaw.size() > 8 && memcmp(g.bgRaw.data(), "GBKG", 4) == 0) {
        uint16_t wh[2];
        memcpy(wh, g.bgRaw.data() + 4, 4);
        size_t px = size_t(wh[0]) * wh[1];
        if (g.bgRaw.size() >= 8 + px * 3) {
            std::vector<uint8_t> rgba(px * 4);  // RGB8 sRGB is not mipmappable on ES 3.0; RGBA8 is
            const uint8_t* src = g.bgRaw.data() + 8;
            for (size_t i = 0; i < px; ++i) {
                rgba[i * 4] = src[i * 3];
                rgba[i * 4 + 1] = src[i * 3 + 1];
                rgba[i * 4 + 2] = src[i * 3 + 2];
                rgba[i * 4 + 3] = 255;
            }
            glGenTextures(1, &g.bgTex);
            glBindTexture(GL_TEXTURE_2D, g.bgTex);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_SRGB8_ALPHA8, wh[0], wh[1], 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
            glGenerateMipmap(GL_TEXTURE_2D);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            LOGI("DSS2 background %ux%u uploaded", wh[0], wh[1]);
        }
    }
    g.bgRaw.clear();
    g.bgRaw.shrink_to_fit();

    {   // unit sphere the background is drawn on
        const int kLon = 64, kLat = 32;
        std::vector<float> v;
        std::vector<uint16_t> idx;
        for (int j = 0; j <= kLat; ++j) {
            float ph = float(kPi) * j / kLat - float(kPi) / 2.f;
            for (int i = 0; i <= kLon; ++i) {
                float th = 2.f * float(kPi) * i / kLon;
                v.push_back(std::cos(ph) * std::cos(th));
                v.push_back(std::sin(ph));
                v.push_back(std::cos(ph) * std::sin(th));
            }
        }
        for (int j = 0; j < kLat; ++j)
            for (int i = 0; i < kLon; ++i) {
                int a = j * (kLon + 1) + i, b = a + kLon + 1;
                for (int k : {a, b, a + 1, a + 1, b, b + 1}) idx.push_back(uint16_t(k));
            }
        g.bgIndices = int(idx.size());
        glGenVertexArrays(1, &g.bgVao);
        glGenBuffers(1, &g.bgVbo);
        glGenBuffers(1, &g.bgEbo);
        glBindVertexArray(g.bgVao);
        glBindBuffer(GL_ARRAY_BUFFER, g.bgVbo);
        glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(v.size() * 4), v.data(), GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 12, nullptr);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, g.bgEbo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(idx.size() * 2), idx.data(), GL_STATIC_DRAW);
        glBindVertexArray(0);
    }

    // Messier / Caldwell / NGC point sprites
    g.dsoProg = program(kDsoVS, kDsoFS);
    if (!g.dsoRaw.empty()) {
        glGenVertexArrays(1, &g.dsoVao);
        glGenBuffers(1, &g.dsoVbo);
        glBindVertexArray(g.dsoVao);
        glBindBuffer(GL_ARRAY_BUFFER, g.dsoVbo);
        glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(g.dsoRaw.size()), g.dsoRaw.data(), GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 32, (void*)0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 32, (void*)12);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 2, GL_UNSIGNED_BYTE, GL_FALSE, 32, (void*)20);
        glBindVertexArray(0);
        g.dsoRaw.clear();
        g.dsoRaw.shrink_to_fit();
    }

    // moving bodies: same record layout as the catalogue, refilled every frame
    glGenVertexArrays(1, &g.bodyVao);
    glGenBuffers(1, &g.bodyVbo);
    glBindVertexArray(g.bodyVao);
    glBindBuffer(GL_ARRAY_BUFFER, g.bodyVbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 32, (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 32, (void*)12);
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 2, GL_UNSIGNED_BYTE, GL_FALSE, 32, (void*)20);
    glBindVertexArray(0);

    // Arial atlas
    if (!g.glyphs.empty()) {
        glGenTextures(1, &g.arialTex);
        glBindTexture(GL_TEXTURE_2D, g.arialTex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, g.fontW, g.fontH, 0, GL_RED, GL_UNSIGNED_BYTE,
                     g.fontRaw.data() + g.fontAtlasOff);
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
    g.fontRaw.clear();
    g.fontRaw.shrink_to_fit();

    // star sets
    uploadSet(g.real, g.realStars);
    g.stressThread = std::thread([] {
        auto t0 = Clock::now();
        buildStress(g.realStars, g.stressStars, 16);
        LOGI("stress catalog built: %zu stars in %.0f ms", g.stressStars.size(),
             std::chrono::duration<double, std::milli>(Clock::now() - t0).count());
        g.stressReady = true;
    });

    // GPU timer (optional)
    const char* ext = (const char*)glGetString(GL_EXTENSIONS);
    if (ext && strstr(ext, "GL_EXT_disjoint_timer_query")) {
        *(void**)&g.glGenQ = (void*)eglGetProcAddress("glGenQueriesEXT");
        *(void**)&g.glBeginQ = (void*)eglGetProcAddress("glBeginQueryEXT");
        *(void**)&g.glEndQ = (void*)eglGetProcAddress("glEndQueryEXT");
        *(void**)&g.glGetQ = (void*)eglGetProcAddress("glGetQueryObjectuivEXT");
        *(void**)&g.glGetQ64 = (void*)eglGetProcAddress("glGetQueryObjectui64vEXT");
        if (g.glGenQ && g.glBeginQ && g.glEndQ && g.glGetQ && g.glGetQ64) {
            g.glGenQ(8, g.q);
            g.timerOk = true;
        }
    }
    LOGI("GL: %s | %s | gpu timer %s", glGetString(GL_RENDERER), glGetString(GL_VERSION),
         g.timerOk ? "yes" : "no");
}

// Collects GPU time from finished queries; call once per frame.
void pollGpuTimer() {
    if (!g.timerOk) return;
    GLint disjoint = 0;
    glGetIntegerv(GL_GPU_DISJOINT_EXT, &disjoint);
    for (int i = 0; i < 8; ++i) {
        if (!g.qPending[i]) continue;
        GLuint avail = 0;
        g.glGetQ(g.q[i], GL_QUERY_RESULT_AVAILABLE_EXT, &avail);
        if (!avail) continue;
        uint64_t ns = 0;
        g.glGetQ64(g.q[i], GL_QUERY_RESULT_EXT, &ns);
        g.qPending[i] = false;
        if (!disjoint) { g.st.gpuNs += double(ns); g.st.gpuSamples++; }
    }
}

// Same equatorial -> horizontal maths as the vertex shaders, for CPU-placed labels.
Vec3 toWorld(Vec3 d0) {
    const float* P = g.prec;  // column-major J2000 -> date
    Vec3 d{P[0] * d0.x + P[3] * d0.y + P[6] * d0.z, P[1] * d0.x + P[4] * d0.y + P[7] * d0.z,
           P[2] * d0.x + P[5] * d0.y + P[8] * d0.z};
    float c = std::cos(float(g.lst)), s = std::sin(float(g.lst));
    float a = d.x * c + d.y * s, b = d.x * s - d.y * c;
    float sl = std::sin(g.latitude), cl = std::cos(g.latitude);
    float north = d.z * cl - a * sl, up = d.z * sl + a * cl;
    return {-b, up, -north};  // x=E y=up z=S
}

// Same zoom mapping as the shaders' zoomFwd/zoomInv. Hidden (past the antipode) comes back as (0,0,0).
Vec3 mulRot(Vec3 v) {
    const float* m = g.rot;
    return {m[0] * v.x + m[3] * v.y + m[6] * v.z, m[1] * v.x + m[4] * v.y + m[7] * v.z,
            m[2] * v.x + m[5] * v.y + m[8] * v.z};
}
Vec3 mulRotT(Vec3 v) {
    const float* m = g.rot;
    return {m[0] * v.x + m[1] * v.y + m[2] * v.z, m[3] * v.x + m[4] * v.y + m[5] * v.z,
            m[6] * v.x + m[7] * v.y + m[8] * v.z};
}
Vec3 zoomFwdCpu(Vec3 w) {
    if (g.zoom > 1.001f) {
        float c = std::clamp(dot(w, g.zc), -1.f, 1.f);
        Vec3 p = w - g.zc * c;
        float pl = std::sqrt(dot(p, p));
        if (pl > 1e-6f) {
            float t = std::acos(c) * g.zoom;
            if (t > 3.1f) return {0, 0, 0};
            w = g.zc * std::cos(t) + p * (std::sin(t) / pl);
        }
    }
    return mulRot(w);
}
Vec3 zoomInvCpu(Vec3 w) {
    w = mulRotT(w);
    if (g.zoom > 1.001f) {
        float c = std::clamp(dot(w, g.zc), -1.f, 1.f);
        Vec3 p = w - g.zc * c;
        float pl = std::sqrt(dot(p, p));
        if (pl > 1e-6f) {
            float t = std::acos(c) / g.zoom;
            w = g.zc * std::cos(t) + p * (std::sin(t) / pl);
        }
    }
    return w;
}

// Rotation taking unit vector a onto b (Rodrigues), column-major.
void setRotTo(float* m, Vec3 a, Vec3 b) {
    Vec3 v = cross(a, b);
    float s2 = dot(v, v), c = dot(a, b);
    double k[3][3] = {{0, -v.z, v.y}, {v.z, 0, -v.x}, {-v.y, v.x, 0}};
    double f = s2 > 1e-12f ? (1.0 - c) / s2 : 0.0;
    for (int col = 0; col < 3; ++col)
        for (int row = 0; row < 3; ++row) {
            double kk = 0;
            for (int i = 0; i < 3; ++i) kk += k[row][i] * k[i][col];
            m[col * 3 + row] = float((row == col ? 1.0 : 0.0) + (s2 > 1e-12f ? k[row][col] + kk * f : 0.0));
        }
}

// NGC objects fainter than this are hidden; Messier and Caldwell always show. Zooming in reveals fainter ones.
float dsoLimit() { return std::min(16.f, 9.5f + 3.f * std::log2(g.zoom)); }
bool dsoVisible(const Dso& o) { return (o.cat & 11) || o.mag <= dsoLimit(); }
bool dsoShown(size_t i) { return i < g.nStatic ? g.showDso : g.showBodies; }

// Once per frame: where the zoom is centred, and where every deep-sky object is displayed.
void updateZoomMap() {
    if (g.lock >= 0 && size_t(g.lock) < g.dso.size()) {
        g.zc = toWorld(g.dso[size_t(g.lock)].d);  // locked: tracks the object and pins it at zd
        setRotTo(g.rot, g.zc, g.zd);
    } else if (g.zoom <= 1.001f) {
        g.zcSet = false;
        g.rot[0] = g.rot[4] = g.rot[8] = 1.f;
        g.rot[1] = g.rot[2] = g.rot[3] = g.rot[5] = g.rot[6] = g.rot[7] = 0.f;
    } else if (!g.zcSet) {  // free zoom: about wherever the user was looking when it started
        g.zc = g.zd = g.headFwd;
        g.rot[0] = g.rot[4] = g.rot[8] = 1.f;
        g.rot[1] = g.rot[2] = g.rot[3] = g.rot[5] = g.rot[6] = g.rot[7] = 0.f;
        g.zcSet = true;
    }
    if (g.dso.empty()) return;
    const float* P = g.prec;
    float c = std::cos(float(g.lst)), s = std::sin(float(g.lst)), sl = std::sin(g.latitude), cl = std::cos(g.latitude);
    for (size_t i = 0; i < g.dso.size(); ++i) {
        Vec3 d0 = g.dso[i].d;
        Vec3 d{P[0] * d0.x + P[3] * d0.y + P[6] * d0.z, P[1] * d0.x + P[4] * d0.y + P[7] * d0.z,
               P[2] * d0.x + P[5] * d0.y + P[8] * d0.z};
        float a = d.x * c + d.y * s, b = d.x * s - d.y * c;
        g.dsoW[i] = zoomFwdCpu({-b, d.z * sl + a * cl, -(d.z * cl - a * sl)});
    }
}

// Sun, Moon, planets, asteroids and satellites at the simulated time; the records are uploaded for drawing.
void updateBodies() {
    if (g.nStatic >= g.dso.size()) return;
    using namespace ephem;
    const double jd = jdFromUnix(g.tNow);
    MoonPos mp = moonGeo(jd);
    V3 earth = earthHelioEcl(jd, mp);
    const float* P = g.prec;  // column-major J2000 -> date
    auto toDate = [&](V3 v) {
        return V3{P[0] * v.x + P[3] * v.y + P[6] * v.z, P[1] * v.x + P[4] * v.y + P[7] * v.z,
                  P[2] * v.x + P[5] * v.y + P[8] * v.z};
    };
    auto toJ2000 = [&](V3 v) {
        return V3{P[0] * v.x + P[1] * v.y + P[2] * v.z, P[3] * v.x + P[4] * v.y + P[5] * v.z,
                  P[6] * v.x + P[7] * v.y + P[8] * v.z};
    };
    auto setDir = [](Dso& o, V3 d) { o.d = {float(d.x), float(d.y), float(d.z)}; };
    const double arcmin = 60.0 / kD2R;
    Dso* b = &g.dso[g.nStatic];

    Seen sun = seenFrom(V3{}, earth);
    setDir(b[0], sun.dir);
    b[0].size = float(2 * std::asin(696000.0 / (sun.distAu * kAuKm)) * arcmin);

    V3 obs = observerEr(g.latitude, g.elevM, g.lst);  // topocentric: the Moon's parallax is up to a degree
    V3 topo = mp.eqDate - obs;
    setDir(b[1], toJ2000(unit(topo)));
    b[1].size = float(2 * std::asin(1737.4 / (norm(topo) * kEarthRadiusKm)) * arcmin);
    V3 sunDate = toDate(sun.dir);
    b[1].mag = float((1.0 - dot(unit(mp.eqDate), sunDate)) / 2.0);

    static const int kPlanetIdx[7] = {MERCURY, VENUS, MARS, JUPITER, SATURN, URANUS, NEPTUNE};
    for (int k = 0; k < 7; ++k) {
        Seen s = seenFrom(planetHelioEcl(kPlanetIdx[k], jd), earth);
        setDir(b[2 + k], s.dir);
        b[2 + k].mag = float(planetMag(kPlanetIdx[k], s.rAu, s.distAu, s.phaseDeg));
        b[2 + k].size = float(2 * std::asin(planetRadiusKm(kPlanetIdx[k]) / (s.distAu * kAuKm)) * arcmin);
    }
    for (size_t i = 0; i < g.ast.size(); ++i) {
        Seen s = seenFrom(asteroidHelioEcl(g.ast[i], jd), earth);
        Dso& o = g.dso[g.astStart + i];
        setDir(o, s.dir);
        o.mag = float(asteroidMag(g.ast[i], s.rAu, s.distAu, s.phaseDeg));
    }

    // satellites: Hermite-interpolate the baked TEME track, then take the topocentric direction
    V3 obsKm = obs * kEarthRadiusKm;
    double u = (g.tNow - g.satT0) / double(g.satStep);
    const double h = g.satStep;
    for (uint32_t k = 0; k < g.satCount; ++k) {
        Dso& o = g.dso[g.satStart + k];
        if (u < 0.0 || u >= double(g.satN) - 1.0) { o.cat = 0; o.mag = 99.f; continue; }  // outside the baked window
        const uint8_t* rec = g.satRaw.data() + 24 + size_t(k) * (24 + size_t(g.satN) * 24);
        float nominal;
        memcpy(&nominal, rec + 16, 4);
        size_t i = size_t(u);
        double f = u - double(i), f2 = f * f, f3 = f2 * f;
        double h00 = 2 * f3 - 3 * f2 + 1, h10 = f3 - 2 * f2 + f, h01 = -2 * f3 + 3 * f2, h11 = f3 - f2;
        float s0[6], s1[6];
        memcpy(s0, rec + 24 + i * 24, 24);
        memcpy(s1, rec + 24 + (i + 1) * 24, 24);
        V3 r{h00 * s0[0] + h10 * h * s0[3] + h01 * s1[0] + h11 * h * s1[3],
             h00 * s0[1] + h10 * h * s0[4] + h01 * s1[1] + h11 * h * s1[4],
             h00 * s0[2] + h10 * h * s0[5] + h01 * s1[2] + h11 * h * s1[5]};
        setDir(o, toJ2000(unit(r - obsKm)));
        double along = dot(r, sunDate);
        bool shadow = along < 0.0 && norm(r - sunDate * along) < 6371.0;  // cylindrical Earth shadow
        o.cat = 8;
        o.mag = shadow ? 9.f : nominal;
    }

    std::vector<uint8_t> rec((g.dso.size() - g.nStatic) * 32);
    for (size_t i = g.nStatic; i < g.dso.size(); ++i) {
        const Dso& o = g.dso[i];
        uint8_t* r = rec.data() + (i - g.nStatic) * 32;
        float f[5] = {o.d.x, o.d.y, o.d.z, o.mag, o.size};
        memcpy(r, f, 20);
        r[20] = o.type;
        r[21] = o.cat;
        memcpy(r + 22, o.label, 9);
    }
    glBindBuffer(GL_ARRAY_BUFFER, g.bodyVbo);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(rec.size()), rec.data(), GL_STREAM_DRAW);
}

// The visible deep-sky object nearest the displayed direction `wd`, within a few degrees of screen; -1 if none.
int findDso(Vec3 wd) {
    int best = -1;
    float bestScore = 1.f;
    for (size_t i = 0; i < g.dso.size(); ++i) {
        const Vec3& w = g.dsoW[i];
        if (!dsoShown(i) || dot(w, w) < 0.25f || w.y < 0.f) continue;
        float cd = dot(w, wd);
        if (cd < 0.97f) continue;  // beyond ~14 degrees
        const Dso& o = g.dso[i];
        if (!dsoVisible(o)) continue;
        float deg = std::acos(std::min(cd, 1.f)) * float(180.0 / kPi);
        float tol = 1.5f + std::min(o.size / 120.f * g.zoom, 6.f);
        float score = deg / tol;
        if (score < bestScore) { bestScore = score; best = int(i); }
    }
    return best;
}

// -------- text

void addText(std::vector<float>& v, const char* s, Vec3 origin, Vec3 right, Vec3 up, float px) {
    int aw = kGlyphCount * kCell;
    float pen = 0.f;
    for (; *s; ++s) {
        size_t idx = g.charset.find(*s);
        if (idx == std::string::npos) idx = 0;
        float u0 = float(idx * kCell) / aw, u1 = float(idx * kCell + 5) / aw;
        float x0 = pen, x1 = pen + 5 * px, y0 = 0.f, y1 = 7 * px;
        auto P = [&](float x, float y) { return Vec3{origin.x + right.x * x + up.x * y,
                                                      origin.y + right.y * x + up.y * y,
                                                      origin.z + right.z * x + up.z * y}; };
        Vec3 a = P(x0, y1), b = P(x1, y1), c = P(x1, y0), d = P(x0, y0);  // TL TR BR BL
        auto push = [&](Vec3 p, float u, float vv) { v.insert(v.end(), {p.x, p.y, p.z, u, vv}); };
        push(a, u0, 0); push(b, u1, 0); push(c, u1, 1);
        push(a, u0, 0); push(c, u1, 1); push(d, u0, 1);
        pen += kCell * px;
    }
}

// Centred Arial label: `scale` is world units per font pixel, origin is the middle of the text.
void addTextArial(std::vector<float>& v, const char* s, Vec3 origin, Vec3 right, Vec3 up, float scale) {
    auto glyph = [](char c) -> const FontGlyph* {
        int i = int((unsigned char)c) - 32;
        return i >= 0 && size_t(i) < g.glyphs.size() ? &g.glyphs[size_t(i)] : nullptr;
    };
    float width = 0.f;
    for (const char* p = s; *p; ++p)
        if (const FontGlyph* gl = glyph(*p)) width += gl->adv;
    float pen = -width * scale / 2.f, dy = -0.36f * g.fontPx * scale;  // 0.36 em: half a cap height
    for (; *s; ++s) {
        const FontGlyph* gl = glyph(*s);
        if (!gl) continue;
        float x0 = pen + gl->x0 * scale, x1 = pen + gl->x1 * scale, y0 = dy + gl->y0 * scale, y1 = dy + gl->y1 * scale;
        auto P = [&](float x, float y) { return Vec3{origin.x + right.x * x + up.x * y, origin.y + right.y * x + up.y * y,
                                                      origin.z + right.z * x + up.z * y}; };
        auto push = [&](Vec3 p, float u, float vv) { v.insert(v.end(), {p.x, p.y, p.z, u, vv}); };
        Vec3 tl = P(x0, y1), tr = P(x1, y1), br = P(x1, y0), bl = P(x0, y0);
        push(tl, gl->u0, gl->v0); push(tr, gl->u1, gl->v0); push(br, gl->u1, gl->v1);
        push(tl, gl->u0, gl->v0); push(br, gl->u1, gl->v1); push(bl, gl->u0, gl->v1);
        pen += gl->adv * scale;
    }
}

float arialWidth(const char* s) {  // font pixels
    float w = 0.f;
    for (; *s; ++s) {
        int i = int((unsigned char)*s) - 32;
        if (i >= 0 && size_t(i) < g.glyphs.size()) w += g.glyphs[size_t(i)].adv;
    }
    return w;
}

// Left-aligned: `left` is the start of the text, vertically centred on it.
void addTextArialLeft(std::vector<float>& v, const char* s, Vec3 left, Vec3 right, Vec3 up, float scale) {
    float w = arialWidth(s) * scale;
    addTextArial(v, s, left + right * (w * 0.5f), right, up, scale);
}

void drawText(const std::vector<float>& v, const Mat4& mvp, const float color[3], GLuint tex = 0) {
    if (v.empty()) return;
    glUseProgram(g.textProg);
    glUniformMatrix4fv(glGetUniformLocation(g.textProg, "uMVP"), 1, GL_FALSE, mvp.m);
    glUniform3fv(glGetUniformLocation(g.textProg, "uColor"), 1, color);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex ? tex : g.fontTex);
    glUniform1i(glGetUniformLocation(g.textProg, "uTex"), 0);
    glBindVertexArray(g.textVao);
    glBindBuffer(GL_ARRAY_BUFFER, g.textVbo);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(v.size() * 4), v.data(), GL_STREAM_DRAW);
    glDrawArrays(GL_TRIANGLES, 0, GLsizei(v.size() / 5));
}

// -------- controllers: procedural hands and laser pointers

Mat4 rotFromQuat(const XrQuaternionf& q) {
    float x = q.x, y = q.y, z = q.z, w = q.w;
    Mat4 m = identity();
    m.m[0] = 1 - 2 * (y * y + z * z); m.m[4] = 2 * (x * y - z * w);     m.m[8] = 2 * (x * z + y * w);
    m.m[1] = 2 * (x * y + z * w);     m.m[5] = 1 - 2 * (x * x + z * z); m.m[9] = 2 * (y * z - x * w);
    m.m[2] = 2 * (x * z - y * w);     m.m[6] = 2 * (y * z + x * w);     m.m[10] = 1 - 2 * (x * x + y * y);
    return m;
}

// Full eye view (rotation and translation): hands live in LOCAL space, unlike the sky.
Mat4 viewFull(const XrPosef& p) {
    Mat4 m = viewRotation(p.orientation);  // R^T
    for (int r = 0; r < 3; ++r)
        m.m[12 + r] = -(m.m[r] * p.position.x + m.m[4 + r] * p.position.y + m.m[8 + r] * p.position.z);
    return m;
}

// The aim pose points down -Z.
Vec3 aimDir(const XrQuaternionf& q) {
    return {-2 * (q.x * q.z + q.y * q.w), -2 * (q.y * q.z - q.x * q.w), -(1 - 2 * (q.x * q.x + q.y * q.y))};
}

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

// Camera-facing ribbon from the controller to the dome plus a ring where it lands.
void addLaser(std::vector<float>& v, const Hand& h, Vec3 eye, const float rgb[3]) {
    auto vert = [&](Vec3 p, float a) { v.insert(v.end(), {p.x, p.y, p.z, rgb[0], rgb[1], rgb[2], a}); };
    Vec3 o = h.aimPos, e = h.hitPt, d = e - o;
    float len = std::sqrt(dot(d, d));
    if (len < 1e-3f) return;
    d = d * (1.f / len);
    Vec3 side = normalize(cross(d, eye - o));
    float w0 = 0.0015f, w1 = 0.0012f * len;  // keeps ~constant angular width, so it stays visible far away
    Vec3 a0 = o - side * w0, a1 = o + side * w0, b0 = e - side * w1, b1 = e + side * w1;
    vert(a0, 0.9f); vert(a1, 0.9f); vert(b1, 0.35f);
    vert(a0, 0.9f); vert(b1, 0.35f); vert(b0, 0.35f);

    Vec3 n = normalize(eye - e);
    Vec3 u = cross(n, Vec3{0, 1, 0});
    u = dot(u, u) > 1e-6f ? normalize(u) : Vec3{1, 0, 0};
    Vec3 w = cross(n, u);
    Vec3 de = eye - e;
    float R = std::sqrt(dot(de, de)) * 0.0122f, Rin = R * 0.78f;  // ~0.7 degrees
    const int seg = 28;
    for (int i = 0; i < seg; ++i) {
        float t0 = 2.f * float(kPi) * float(i) / seg, t1 = 2.f * float(kPi) * float(i + 1) / seg;
        Vec3 r0 = u * std::cos(t0) + w * std::sin(t0), r1 = u * std::cos(t1) + w * std::sin(t1);
        vert(e + r0 * Rin, 0.9f); vert(e + r0 * R, 0.9f); vert(e + r1 * R, 0.9f);
        vert(e + r0 * Rin, 0.9f); vert(e + r1 * R, 0.9f); vert(e + r1 * Rin, 0.9f);
    }
}

// Billboard ring around `c` (radius in world units), facing the eye.
void addRing(std::vector<float>& v, Vec3 c, Vec3 eye, float R, float width, const float rgb[3], float a) {
    auto vert = [&](Vec3 p) { v.insert(v.end(), {p.x, p.y, p.z, rgb[0], rgb[1], rgb[2], a}); };
    Vec3 n = normalize(eye - c);
    Vec3 u = cross(n, Vec3{0, 1, 0});
    u = dot(u, u) > 1e-6f ? normalize(u) : Vec3{1, 0, 0};
    Vec3 w = cross(n, u);
    const int seg = 48;
    float Rin = R - width;
    for (int i = 0; i < seg; ++i) {
        float t0 = 2.f * float(kPi) * float(i) / seg, t1 = 2.f * float(kPi) * float(i + 1) / seg;
        Vec3 r0 = u * std::cos(t0) + w * std::sin(t0), r1 = u * std::cos(t1) + w * std::sin(t1);
        vert(c + r0 * Rin); vert(c + r0 * R); vert(c + r1 * R);
        vert(c + r0 * Rin); vert(c + r1 * R); vert(c + r1 * Rin);
    }
}

// The object panel: a small card riding on the right controller, its bottom edge along the pointer axis and facing the
// head, so turning the palm towards you reads it like a wrist display. The rows are clipped to a fixed-height window
// that the right stick scrolls; a thin bar on the right edge shows the position. Everything is built in the card's own
// metres and scaled about the middle of its bottom edge by the pop animation.
void drawPanel(const Mat4& vpFull) {
    if (g.panelT < 0.01f || g.panelLines.empty() || g.glyphs.empty()) return;
    const float k = g.panelT * g.panelT * (3.f - 2.f * g.panelT);
    const float W = 0.11f, Hmax = 0.11f, pad = 0.0045f, em = 0.00525f;
    const float bs = em / std::max(g.fontPx, 1.f);

    struct Row { std::string s; int style; float scale, h; };
    std::vector<Row> rows;
    for (const PanelLine& pl : g.panelLines) {
        const float sc = pl.style == 0 ? bs * 1.35f : bs;
        if (pl.s.empty()) { rows.push_back({"", pl.style, sc, 0.5f * em}); continue; }
        std::string line;
        size_t i = 0;
        while (i < pl.s.size()) {
            size_t j = pl.s.find(' ', i);
            if (j == std::string::npos) j = pl.s.size();
            std::string word = pl.s.substr(i, j - i);
            std::string trial = line.empty() ? word : line + " " + word;
            if (!line.empty() && arialWidth(trial.c_str()) * sc > W - 2.f * pad - 0.004f) {
                rows.push_back({line, pl.style, sc, 1.3f * em * (pl.style == 0 ? 1.35f : 1.f)});
                line = word;
            } else {
                line = trial;
            }
            i = j + 1;
        }
        if (!line.empty()) rows.push_back({line, pl.style, sc, 1.3f * em * (pl.style == 0 ? 1.35f : 1.f)});
    }
    float content = 0.f;
    for (const Row& r : rows) content += r.h;
    const float H = std::min(content + 2.f * pad, Hmax);
    const float view = H - 2.f * pad;
    g.panelMaxScroll = std::max(content - view, 0.f);
    g.panelScroll = std::min(std::max(g.panelScroll, 0.f), g.panelMaxScroll);

    const Vec3 R = g.panelR * k, U = g.panelU * k;
    auto at = [&](float x, float y) { return g.panelO + R * x + U * y; };
    std::vector<float> bg;
    auto quad = [&](float x0, float y0, float x1, float y1, const float c[3]) {
        auto vert = [&](float x, float y) { Vec3 p = at(x, y); bg.insert(bg.end(), {p.x, p.y, p.z, c[0], c[1], c[2], 1.f}); };
        vert(x0, y0); vert(x1, y0); vert(x1, y1);
        vert(x0, y0); vert(x1, y1); vert(x0, y1);
    };
    const float rim[3] = {g.night ? 0.30f : 0.16f, g.night ? 0.03f : 0.30f, g.night ? 0.f : 0.52f};
    const float fill[3] = {g.night ? 0.04f : 0.015f, g.night ? 0.f : 0.025f, g.night ? 0.f : 0.06f};
    const float bar[3] = {g.night ? 0.60f : 0.45f, g.night ? 0.06f : 0.62f, g.night ? 0.f : 0.90f};
    quad(-W / 2 - 0.001f, -0.001f, W / 2 + 0.001f, H + 0.001f, rim);
    quad(-W / 2, 0.f, W / 2, H, fill);
    if (g.panelMaxScroll > 0.f) {  // scroll bar: thumb length is the visible fraction of the content
        const float th = std::max(view * view / content, 0.01f);
        const float top = H - pad - (view - th) * (g.panelScroll / g.panelMaxScroll);
        quad(W / 2 - 0.004f, top - th, W / 2 - 0.0025f, top, bar);
    }

    glDisable(GL_DEPTH_TEST);
    glEnable(GL_BLEND);
    glBlendColor(0.f, 0.f, 0.f, 0.9f);
    glBlendFunc(GL_CONSTANT_ALPHA, GL_ONE_MINUS_CONSTANT_ALPHA);
    glUseProgram(g.flatProg);
    glUniformMatrix4fv(glGetUniformLocation(g.flatProg, "uMVP"), 1, GL_FALSE, vpFull.m);
    glBindVertexArray(g.flatVao);
    glBindBuffer(GL_ARRAY_BUFFER, g.flatVbo);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(bg.size() * 4), bg.data(), GL_STREAM_DRAW);
    glDrawArrays(GL_TRIANGLES, 0, GLsizei(bg.size() / 7));
    glBlendFunc(GL_ONE, GL_ONE);

    std::vector<float> tv[4];
    float y = H - pad + g.panelScroll;  // top of the first row, shifted up by the scroll
    for (const Row& r : rows) {
        const float top = y, bottom = y - r.h;
        y = bottom;
        if (r.s.empty()) continue;
        if (top > H - pad + 0.0005f || bottom < pad - 0.0005f) continue;  // only whole rows inside the window
        addTextArialLeft(tv[std::min(std::max(r.style, 0), 3)], r.s.c_str(), at(-W / 2 + pad, bottom + r.h * 0.5f), R, U, r.scale);
    }
    const float cols[4][3] = {{g.night ? 1.0f : 1.0f, g.night ? 0.10f : 0.86f, g.night ? 0.f : 0.40f},
                              {g.night ? 0.85f : 0.88f, g.night ? 0.06f : 0.90f, g.night ? 0.f : 0.96f},
                              {g.night ? 0.55f : 0.50f, g.night ? 0.04f : 0.58f, g.night ? 0.f : 0.70f},
                              {g.night ? 0.70f : 0.35f, g.night ? 0.05f : 0.85f, g.night ? 0.f : 0.95f}};
    for (int i = 0; i < 4; ++i) drawText(tv[i], vpFull, cols[i], g.arialTex);
}

// -------- scene
// -------- scene

void setZoomUniforms(GLuint prog) {
    glUniform4f(glGetUniformLocation(prog, "uZoom"), g.zc.x, g.zc.y, g.zc.z, g.zoom);
    glUniformMatrix3fv(glGetUniformLocation(prog, "uRot"), 1, GL_FALSE, g.rot);
}

void renderEye(int e, const XrView& view, uint32_t fbo) {
    EyeSwapchain& sc = g.eye[e];
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glViewport(0, 0, sc.w, sc.h);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glDepthMask(GL_TRUE);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE);

    Mat4 proj = projection(view.fov, 0.1f, 10000.f);
    Mat4 vp = mul(proj, viewRotation(view.pose.orientation));
    const float lineCol[3] = {g.night ? 0.20f : 0.10f, g.night ? 0.02f : 0.16f, g.night ? 0.f : 0.22f};
    const float textCol[3] = {g.night ? 0.9f : 0.75f, g.night ? 0.06f : 0.85f, g.night ? 0.f : 0.95f};

    bool timing = false;  // a result still unread in this slot means we skip the sample
    if (g.timerOk) {
        uint32_t slot = g.qSlot++ & 7;
        if (!g.qPending[slot]) {
            g.glBeginQ(GL_TIME_ELAPSED_EXT, g.q[slot]);
            g.qPending[slot] = true;
            timing = true;
        }
    }

    // sky behind everything, with the opaque ground
    {
        glUseProgram(g.bgProg);
        glUniformMatrix4fv(glGetUniformLocation(g.bgProg, "uVP"), 1, GL_FALSE, vp.m);
        glUniformMatrix3fv(glGetUniformLocation(g.bgProg, "uPrec"), 1, GL_FALSE, g.prec);
        glUniform4f(glGetUniformLocation(g.bgProg, "uSky"), std::cos(float(g.lst)), std::sin(float(g.lst)),
                    std::sin(g.latitude), std::cos(g.latitude));
        glUniform3f(glGetUniformLocation(g.bgProg, "uEye"), view.pose.position.x, view.pose.position.y,
                    view.pose.position.z);
        glUniform1f(glGetUniformLocation(g.bgProg, "uFloor"), kFloorY);
        glUniform1f(glGetUniformLocation(g.bgProg, "uExposure"), g.bgExposure);
        glUniform1f(glGetUniformLocation(g.bgProg, "uNight"), g.night ? 1.f : 0.f);
        glUniform1f(glGetUniformLocation(g.bgProg, "uSkyOn"), g.bgOn && g.bgTex ? 1.f : 0.f);
        setZoomUniforms(g.bgProg);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, g.bgTex);
        glUniform1i(glGetUniformLocation(g.bgProg, "uTex"), 0);
        glBindVertexArray(g.bgVao);
        glDrawElements(GL_TRIANGLES, g.bgIndices, GL_UNSIGNED_SHORT, nullptr);
    }

    // DSS2 cutout of the locked (or centred) object once zoomed far enough that it is worth a picture
    if (g.dssShown >= 0 && g.dssAlpha > 0.003f && g.dssProg) {
        const int k = g.dssOf[size_t(g.dssShown)];
        if (g.dssLoaded != k) {
            glBindTexture(GL_TEXTURE_2D, g.dssTex);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, GLsizei(g.dssSide), GLsizei(g.dssSide), 0, GL_RED, GL_UNSIGNED_BYTE,
                         g.dssRaw.data() + g.dssPix + size_t(k) * g.dssSide * g.dssSide);
            glGenerateMipmap(GL_TEXTURE_2D);
            {  // sky level: the 20th percentile of the plate (an object rarely covers most of it, the sky always does)
                const uint8_t* img = g.dssRaw.data() + g.dssPix + size_t(k) * g.dssSide * g.dssSide;
                std::vector<uint8_t> tmp(img, img + size_t(g.dssSide) * g.dssSide);
                std::nth_element(tmp.begin(), tmp.begin() + ptrdiff_t(tmp.size() / 5), tmp.end());
                g.dssBase = float(tmp[tmp.size() / 5]) / 255.f;
            }
            g.dssLoaded = k;
        }
        glUseProgram(g.dssProg);
        glUniformMatrix4fv(glGetUniformLocation(g.dssProg, "uVP"), 1, GL_FALSE, vp.m);
        glUniformMatrix3fv(glGetUniformLocation(g.dssProg, "uPrec"), 1, GL_FALSE, g.prec);
        glUniform4f(glGetUniformLocation(g.dssProg, "uSky"), std::cos(float(g.lst)), std::sin(float(g.lst)),
                    std::sin(g.latitude), std::cos(g.latitude));
        glUniform3f(glGetUniformLocation(g.dssProg, "uObj"), g.dssObj.x, g.dssObj.y, g.dssObj.z);
        glUniform3f(glGetUniformLocation(g.dssProg, "uEast"), g.dssEast.x, g.dssEast.y, g.dssEast.z);
        glUniform3f(glGetUniformLocation(g.dssProg, "uNorth"), g.dssNorth.x, g.dssNorth.y, g.dssNorth.z);
        glUniform1f(glGetUniformLocation(g.dssProg, "uFov"), g.dssFovRad);
        glUniform1f(glGetUniformLocation(g.dssProg, "uAlpha"), g.dssAlpha);
        glUniform1f(glGetUniformLocation(g.dssProg, "uBase"), g.dssBase);
        glUniform1f(glGetUniformLocation(g.dssProg, "uNight"), g.night ? 1.f : 0.f);
        setZoomUniforms(g.dssProg);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, g.dssTex);
        glUniform1i(glGetUniformLocation(g.dssProg, "uTex"), 0);
        glBindVertexArray(g.dssVao);
        glBindBuffer(GL_ARRAY_BUFFER, g.dssVbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof g.dssQuad, g.dssQuad, GL_STREAM_DRAW);
        glBlendFunc(GL_ONE, GL_ONE);  // additive onto the sky
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    // stars
    const StarSet* set = &g.real;
    uint32_t n = prefixCount(g.real, g.magLimit);
    if (g.tier > 0 && g.stressUploaded) {
        set = &g.stress;
        n = std::min(prefixCount(g.stress, g.magLimit), kTierCap[g.tier]);
    }
    glUseProgram(g.starProg);
    glUniformMatrix4fv(glGetUniformLocation(g.starProg, "uVP"), 1, GL_FALSE, vp.m);
    glUniform4f(glGetUniformLocation(g.starProg, "uSky"), std::cos(float(g.lst)), std::sin(float(g.lst)),
                std::sin(g.latitude), std::cos(g.latitude));
    glUniformMatrix3fv(glGetUniformLocation(g.starProg, "uPrec"), 1, GL_FALSE, g.prec);
    glUniform1f(glGetUniformLocation(g.starProg, "uPx"), float(sc.w) / 1832.f);
    glUniform1f(glGetUniformLocation(g.starProg, "uNight"), g.night ? 1.f : 0.f);
    setZoomUniforms(g.starProg);
    glBindVertexArray(set->vao);
    glDrawArrays(GL_POINTS, 0, GLsizei(n));
    g.lastDrawn = n;

    // Messier / Caldwell / NGC symbols
    if ((g.showDso && g.dsoVao) || (g.showBodies && g.nStatic < g.dso.size())) {
        glUseProgram(g.dsoProg);
        glUniformMatrix4fv(glGetUniformLocation(g.dsoProg, "uVP"), 1, GL_FALSE, vp.m);
        glUniform4f(glGetUniformLocation(g.dsoProg, "uSky"), std::cos(float(g.lst)), std::sin(float(g.lst)),
                    std::sin(g.latitude), std::cos(g.latitude));
        glUniformMatrix3fv(glGetUniformLocation(g.dsoProg, "uPrec"), 1, GL_FALSE, g.prec);
        glUniform1f(glGetUniformLocation(g.dsoProg, "uPx"), float(sc.w) / 1832.f);
        glUniform1f(glGetUniformLocation(g.dsoProg, "uNight"), g.night ? 1.f : 0.f);
        glUniform1f(glGetUniformLocation(g.dsoProg, "uLim"), dsoLimit());
        glUniform2f(glGetUniformLocation(g.dsoProg, "uHide"), float(g.dssShown), std::min(g.dssAlpha * 1.5f, 1.f));
        setZoomUniforms(g.dsoProg);
        if (g.showDso && g.dsoVao) {
            glBindVertexArray(g.dsoVao);
            glDrawArrays(GL_POINTS, 0, GLsizei(g.nStatic));
        }
        if (g.showBodies && g.nStatic < g.dso.size()) {
            glUniform2f(glGetUniformLocation(g.dsoProg, "uHide"), -1.f, 0.f);  // vertex ids restart at 0 for the bodies
            glBindVertexArray(g.bodyVao);
            glDrawArrays(GL_POINTS, 0, GLsizei(g.dso.size() - g.nStatic));
        }
    }

    // reference rings
    glUseProgram(g.lineProg);
    glUniformMatrix4fv(glGetUniformLocation(g.lineProg, "uMVP"), 1, GL_FALSE, vp.m);
    glUniform3fv(glGetUniformLocation(g.lineProg, "uColor"), 1, lineCol);
    glBindVertexArray(g.ringVao);
    for (int r = 0; r < 3; ++r) glDrawArrays(GL_LINE_STRIP, r * (g.ringVerts / 3), g.ringVerts / 3);

    if (g.showCon && g.conVerts > 0) {
        const float conCol[3] = {g.night ? 0.30f : 0.12f, g.night ? 0.03f : 0.28f, g.night ? 0.f : 0.55f};
        glUseProgram(g.conProg);
        glUniformMatrix4fv(glGetUniformLocation(g.conProg, "uVP"), 1, GL_FALSE, vp.m);
        glUniform4f(glGetUniformLocation(g.conProg, "uSky"), std::cos(float(g.lst)), std::sin(float(g.lst)),
                    std::sin(g.latitude), std::cos(g.latitude));
        glUniformMatrix3fv(glGetUniformLocation(g.conProg, "uPrec"), 1, GL_FALSE, g.prec);
        glUniform3fv(glGetUniformLocation(g.conProg, "uColor"), 1, conCol);
        setZoomUniforms(g.conProg);
        glBindVertexArray(g.conVao);
        glDrawArrays(GL_LINES, 0, g.conVerts);

        // names: billboards facing the centre, above-horizon only
        std::vector<float> nv;
        const float scale = 0.08f * kDome / 100.f;  // world units per font pixel at radius 0.95 dome: ~2 degrees of cap height
        for (const App::ConName& cn : g.conNames) {
            Vec3 w = zoomFwdCpu(toWorld(cn.d));
            if (dot(w, w) < 0.25f || w.y < 0.02f) continue;
            Vec3 r{-w.z, 0.f, w.x};
            float rl = std::sqrt(r.x * r.x + r.z * r.z);
            if (rl < 1e-3f) continue;  // straight overhead: no stable "right"
            r = {r.x / rl, 0.f, r.z / rl};
            Vec3 u{r.y * w.z - r.z * w.y, r.z * w.x - r.x * w.z, r.x * w.y - r.y * w.x};
            addTextArial(nv, cn.s.c_str(), {w.x * 0.95f * kDome, w.y * 0.95f * kDome, w.z * 0.95f * kDome}, r, u, scale);
        }
        const float nameCol[3] = {textCol[0] * 0.7f, textCol[1] * 0.7f, textCol[2] * 0.7f};
        drawText(nv, vp, nameCol, g.arialTex);
    }

    // deep-sky labels: Messier and Caldwell always, NGC numbers once zoomed in; only those near the gaze
    if ((g.showDso || g.showBodies) && !g.glyphs.empty()) {
        std::vector<float> dv;
        const float scale = 0.05f * kDome / 100.f;
        for (size_t i = 0; i < g.dso.size(); ++i) {
            const Dso& o = g.dso[i];
            Vec3 w = g.dsoW[i];
            if (!dsoShown(i) || dot(w, w) < 0.25f || w.y < 0.02f || dot(w, g.headFwd) < 0.5f || !dsoVisible(o)) continue;
            if (o.type == 10) {  // satellites: lit ones only, and just the stations until zoomed in
                if (o.mag > 8.f || (o.mag > 1.f && g.zoom < 2.f)) continue;
            } else if (o.type == 9) {
                if (g.zoom < 2.f) continue;
            } else if (!(o.cat & 11) && g.zoom < 3.f) {
                continue;
            }
            Vec3 r{-w.z, 0.f, w.x};
            float rl = std::sqrt(r.x * r.x + r.z * r.z);
            if (rl < 1e-3f) continue;
            r = {r.x / rl, 0.f, r.z / rl};
            Vec3 u{r.y * w.z - r.z * w.y, r.z * w.x - r.x * w.z, r.x * w.y - r.y * w.x};
            float below = std::min(std::max(o.size / 120.f * g.zoom, 0.4f), 8.f) + 0.9f;  // degrees under the marker
            Vec3 p = w * (0.95f * kDome) - u * (0.95f * kDome * below * float(kPi / 180.0));
            addTextArial(dv, o.label, p, r, u, scale);
        }
        const float dsoCol[3] = {g.night ? 0.7f : 0.85f, g.night ? 0.05f : 0.70f, g.night ? 0.f : 0.35f};
        drawText(dv, vp, dsoCol, g.arialTex);
    }

    // compass letters on the horizon (world-locked), x=E y=up z=S
    std::vector<float> tv;
    struct Dir { const char* s; Vec3 d; };
    const Dir dirs[4] = {{"N", {0, 0, -1}}, {"E", {1, 0, 0}}, {"S", {0, 0, 1}}, {"W", {-1, 0, 0}}};
    for (const Dir& d : dirs) {
        Vec3 right{-d.d.z, 0.f, d.d.x};  // d x up, up = (0,1,0): reads left-to-right from the centre
        float px = 10.0f, w = 5 * px;
        Vec3 o{d.d.x * 0.97f * kDome - right.x * w / 2, 0.03f * kDome, d.d.z * 0.97f * kDome - right.z * w / 2};
        addText(tv, d.s, o, right, {0, 1, 0}, px);
    }
    drawText(tv, vp, textCol);

    // head-locked HUD in view space
    if (g.hud) {
        char line[17][96];
        const Stats& s = g.st;
        snprintf(line[0], 96, "%s", g.hSite.c_str());
        snprintf(line[1], 96, "%s", g.hUtc.c_str());
        snprintf(line[2], 96, "%s", g.hLocal.c_str());
        snprintf(line[3], 96, "%s", g.hLst.c_str());
        snprintf(line[4], 96, "%s", g.hOff.c_str());
        snprintf(line[5], 96, "%s", g.hPoint1.c_str());
        snprintf(line[6], 96, "%s", g.hPoint2.c_str());
        snprintf(line[7], 96, "%s", g.hOver.c_str());
        snprintf(line[8], 96, "%s  ZOOM %.1fX", g.hLock.c_str(), g.zoom);
        snprintf(line[9], 96, "STARS %u  MAG<=%.1f  DSO %zu", g.lastDrawn, g.magLimit, g.showDso ? g.nStatic : size_t(0));
        snprintf(line[10], 96, "TIER %d %s  %.1f/%.0f FPS", g.tier, g.tier == 0 ? "HIP" : "SYN", s.fps, g.targetHz);
        snprintf(line[11], 96, "WORST %.1f CPU %.1f GPU %.1f MS", s.worst, s.cpu, s.gpu);
        snprintf(line[12], 96, "%s", g.tier > 0 && !g.stressUploaded ? "BUILDING STRESS SET" : " ");
        snprintf(line[13], 96, "L STICK X TIME  Y ZOOM  R STICK Y MAG/SCROLL");
        snprintf(line[14], 96, "TRIGGER OR R CLICK: LOCK / RELEASE");
        snprintf(line[15], 96, "A TIER B NIGHT X NOW Y CONSTEL");
        snprintf(line[16], 96, "L GRIP + X DSO  B BODIES  Y HZ");
        std::vector<float> hv;
        float px = 0.0045f;
        for (int i = 0; i < 17; ++i)
            addText(hv, line[i], {-0.42f, -0.70f + (16 - i) * 8 * px, -1.5f}, {1, 0, 0}, {0, 1, 0}, px);
        drawText(hv, proj, textCol);
    }

    // controllers: opaque depth-tested hands, then additive laser pointers
    {
        Mat4 vpFull = mul(proj, viewFull(view.pose));
        Vec3 eye{view.pose.position.x, view.pose.position.y, view.pose.position.z};
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(GL_TRUE);
        glDisable(GL_BLEND);
        const float skin[3] = {g.night ? 0.50f : 0.38f, g.night ? 0.05f : 0.46f, g.night ? 0.f : 0.58f};
        glUseProgram(g.handProg);
        glUniform3fv(glGetUniformLocation(g.handProg, "uColor"), 1, skin);
        for (int i = 0; i < 2; ++i) {
            const Hand& h = g.hand[i];
            if (!h.aimOk) continue;
            if (g.ctlVerts[i] > 0 && g.ctlVao[i]) {  // the runtime's controller model, in grip space
                Mat4 model = rotFromQuat(h.gripQ);
                for (int c : {0, 1})  // the model is turned 180 degrees about its pointing (z) axis: columns x and y negated
                    for (int r = 0; r < 3; ++r) model.m[c * 4 + r] = -model.m[c * 4 + r];
                model.m[12] = h.gripPos.x;
                model.m[13] = h.gripPos.y;
                model.m[14] = h.gripPos.z;
                Mat4 mvp = mul(vpFull, model);
                glUseProgram(g.ctlProg);
                glUniformMatrix4fv(glGetUniformLocation(g.ctlProg, "uMVP"), 1, GL_FALSE, mvp.m);
                glUniformMatrix4fv(glGetUniformLocation(g.ctlProg, "uModel"), 1, GL_FALSE, model.m);
                glUniform1f(glGetUniformLocation(g.ctlProg, "uNight"), g.night ? 1.f : 0.f);
                glBindVertexArray(g.ctlVao[i]);
                glDrawArrays(GL_TRIANGLES, 0, g.ctlVerts[i]);
                glUseProgram(g.handProg);
                continue;
            }
            if (h.mesh.empty()) continue;
            if (!h.aimOk || h.mesh.empty()) continue;
            Mat4 model = rotFromQuat(h.aimQ);
            model.m[12] = h.gripPos.x;
            model.m[13] = h.gripPos.y;
            model.m[14] = h.gripPos.z;
            Mat4 mvp = mul(vpFull, model);
            glUniformMatrix4fv(glGetUniformLocation(g.handProg, "uMVP"), 1, GL_FALSE, mvp.m);
            glUniformMatrix4fv(glGetUniformLocation(g.handProg, "uModel"), 1, GL_FALSE, model.m);
            glBindVertexArray(g.handVao);
            glBindBuffer(GL_ARRAY_BUFFER, g.handVbo);
            glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(h.mesh.size() * 4), h.mesh.data(), GL_STREAM_DRAW);
            glDrawArrays(GL_TRIANGLES, 0, GLsizei(h.mesh.size() / 6));
        }
        std::vector<float> lv;
        const float laserCol[3] = {g.night ? 0.8f : 0.2f, g.night ? 0.1f : 0.7f, g.night ? 0.f : 0.9f};
        if (g.hand[1].aimOk) addLaser(lv, g.hand[1], eye, laserCol);  // right hand only; the left hand has no pointer
        if (g.showDso || g.showBodies) {
            auto mark = [&](int idx, const float rgb[3], float a, float extra) {
                if (!dsoShown(size_t(idx))) return;
                if (idx == g.dssShown) a *= std::max(0.f, 1.f - g.dssAlpha * 1.5f);  // the picture is the highlight
                if (a < 0.02f) return;
                const Dso& o = g.dso[size_t(idx)];
                const Vec3& w = g.dsoW[size_t(idx)];
                if (dot(w, w) < 0.25f || w.y < 0.f) return;
                float deg = std::min(std::max(o.size / 120.f * g.zoom, 0.8f), 15.f) + 0.8f + extra;
                float R = 0.9f * kDome * std::tan(deg * float(kPi / 180.0));
                addRing(lv, w * (0.9f * kDome), eye, R, R * 0.06f, rgb, a);
            };
            const float lockCol[3] = {g.night ? 0.9f : 1.0f, g.night ? 0.1f : 0.85f, g.night ? 0.f : 0.2f};
            const float hovCol[3] = {g.night ? 0.5f : 0.6f, g.night ? 0.05f : 0.6f, g.night ? 0.f : 0.7f};
            if (g.lock >= 0) {
                mark(g.lock, lockCol, 0.9f, 0.f);
                mark(g.lock, lockCol, 0.5f, 0.9f);
            }
            if (g.hover >= 0 && g.hover != g.lock) mark(g.hover, hovCol, 0.6f, 0.f);
        }
        if (!lv.empty()) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_ONE, GL_ONE);
            glDepthMask(GL_FALSE);
            glUseProgram(g.flatProg);
            glUniformMatrix4fv(glGetUniformLocation(g.flatProg, "uMVP"), 1, GL_FALSE, vpFull.m);
            glBindVertexArray(g.flatVao);
            glBindBuffer(GL_ARRAY_BUFFER, g.flatVbo);
            glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(lv.size() * 4), lv.data(), GL_STREAM_DRAW);
            glDrawArrays(GL_TRIANGLES, 0, GLsizei(lv.size() / 7));
        }
        drawPanel(vpFull);
        glDepthMask(GL_TRUE);
        glDisable(GL_DEPTH_TEST);
        const GLenum depthAtt = GL_DEPTH_ATTACHMENT;  // tiler: never write depth back to memory
        glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, &depthAtt);
    }

    if (timing) g.glEndQ(GL_TIME_ELAPSED_EXT);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

// -------- input

struct Input { float stickLx = 0, stickRy = 0; bool a = false, b = false, x = false, y = false, menu = false; };

XrAction makeAction(const char* name, XrActionType t) {
    XrActionCreateInfo ci{XR_TYPE_ACTION_CREATE_INFO};
    ci.actionType = t;
    strcpy(ci.actionName, name);
    strcpy(ci.localizedActionName, name);
    XrAction a = XR_NULL_HANDLE;
    xrCreateAction(g.set, &ci, &a);
    return a;
}

// Per-hand action (left/right subaction paths), for poses and finger inputs.
XrAction makeActionSub(const char* name, XrActionType t) {
    XrActionCreateInfo ci{XR_TYPE_ACTION_CREATE_INFO};
    ci.actionType = t;
    strcpy(ci.actionName, name);
    strcpy(ci.localizedActionName, name);
    ci.countSubactionPaths = 2;
    ci.subactionPaths = g.handPath;
    XrAction a = XR_NULL_HANDLE;
    xrCreateAction(g.set, &ci, &a);
    return a;
}

XrPath path(const char* s) { XrPath p = 0; xrStringToPath(g.inst, s, &p); return p; }

bool initInput() {
    XrActionSetCreateInfo si{XR_TYPE_ACTION_SET_CREATE_INFO};
    strcpy(si.actionSetName, "atlas");
    strcpy(si.localizedActionSetName, "Atlas");
    XR_OK(xrCreateActionSet(g.inst, &si, &g.set));
    g.aStickL = makeAction("stick_l", XR_ACTION_TYPE_VECTOR2F_INPUT);
    g.aStickR = makeAction("stick_r", XR_ACTION_TYPE_VECTOR2F_INPUT);
    g.aA = makeAction("btn_a", XR_ACTION_TYPE_BOOLEAN_INPUT);
    g.aB = makeAction("btn_b", XR_ACTION_TYPE_BOOLEAN_INPUT);
    g.aX = makeAction("btn_x", XR_ACTION_TYPE_BOOLEAN_INPUT);
    g.aY = makeAction("btn_y", XR_ACTION_TYPE_BOOLEAN_INPUT);
    g.aMenu = makeAction("btn_menu", XR_ACTION_TYPE_BOOLEAN_INPUT);
    g.aSel = makeAction("btn_sel", XR_ACTION_TYPE_BOOLEAN_INPUT);
    g.aBg = makeAction("btn_bg", XR_ACTION_TYPE_BOOLEAN_INPUT);
    g.handPath[0] = path("/user/hand/left");
    g.handPath[1] = path("/user/hand/right");
    g.aGrip = makeActionSub("grip_pose", XR_ACTION_TYPE_POSE_INPUT);
    g.aAim = makeActionSub("aim_pose", XR_ACTION_TYPE_POSE_INPUT);
    g.aTrig = makeActionSub("trigger", XR_ACTION_TYPE_FLOAT_INPUT);
    g.aSqz = makeActionSub("squeeze", XR_ACTION_TYPE_FLOAT_INPUT);
    g.aThumbA = makeActionSub("thumb_stick_touch", XR_ACTION_TYPE_BOOLEAN_INPUT);
    g.aThumbB = makeActionSub("thumb_rest_touch", XR_ACTION_TYPE_BOOLEAN_INPUT);
    std::vector<XrActionSuggestedBinding> b = {
        {g.aStickL, path("/user/hand/left/input/thumbstick")},
        {g.aStickR, path("/user/hand/right/input/thumbstick")},
        {g.aA, path("/user/hand/right/input/a/click")},
        {g.aB, path("/user/hand/right/input/b/click")},
        {g.aX, path("/user/hand/left/input/x/click")},
        {g.aY, path("/user/hand/left/input/y/click")},
        {g.aMenu, path("/user/hand/left/input/menu/click")},
        {g.aSel, path("/user/hand/right/input/thumbstick/click")},
        {g.aBg, path("/user/hand/left/input/thumbstick/click")},
    };
    for (int i = 0; i < 2; ++i) {
        const std::string hp = i ? "/user/hand/right" : "/user/hand/left";
        b.push_back({g.aGrip, path((hp + "/input/grip/pose").c_str())});
        b.push_back({g.aAim, path((hp + "/input/aim/pose").c_str())});
        b.push_back({g.aTrig, path((hp + "/input/trigger/value").c_str())});
        b.push_back({g.aSqz, path((hp + "/input/squeeze/value").c_str())});
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
        XrActionSpaceCreateInfo si{XR_TYPE_ACTION_SPACE_CREATE_INFO};
        si.subactionPath = g.handPath[i];
        si.poseInActionSpace.orientation.w = 1.f;
        si.action = g.aGrip;
        XR_OK(xrCreateActionSpace(g.sess, &si, &g.hand[i].grip));
        si.action = g.aAim;
        XR_OK(xrCreateActionSpace(g.sess, &si, &g.hand[i].aim));
    }
    return true;
}

bool boolEdge(XrAction a) {
    XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
    gi.action = a;
    XrActionStateBoolean s{XR_TYPE_ACTION_STATE_BOOLEAN};
    xrGetActionStateBoolean(g.sess, &gi, &s);
    return s.isActive && s.changedSinceLastSync && s.currentState;
}

XrVector2f vec2(XrAction a) {
    XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
    gi.action = a;
    XrActionStateVector2f s{XR_TYPE_ACTION_STATE_VECTOR2F};
    xrGetActionStateVector2f(g.sess, &gi, &s);
    return s.isActive ? s.currentState : XrVector2f{0, 0};
}

void requestRate() {
    if (g.xrRequestRate && !g.rates.empty()) {
        g.xrRequestRate(g.sess, g.rates[g.rateIdx]);
        LOGI("requested display refresh rate %.0f Hz", g.rates[g.rateIdx]);
    }
}

void pollInput(double dt) {
    if (g.state != XR_SESSION_STATE_FOCUSED) return;
    XrActiveActionSet as{g.set, XR_NULL_PATH};
    XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
    sync.countActiveActionSets = 1;
    sync.activeActionSets = &as;
    if (XR_FAILED(xrSyncActions(g.sess, &sync))) return;

    XrVector2f l = vec2(g.aStickL), r = vec2(g.aStickR);
    {   // time: rate is proportional to deflection (after a small deadzone); full stick = 1 hour of sky per second
        float ax = std::fabs(l.x);
        if (ax > 0.1f) {
            float k = (ax - 0.1f) / 0.9f;
            g.simOffset += double(l.x < 0.f ? -k : k) * dt * 3600.0;
        }
        // zoom: forward magnifies, back reduces; full stick doubles the magnification about every 0.6 s
        float ay = std::fabs(l.y);
        if (ay > 0.12f) {
            float k = (ay - 0.12f) / 0.88f;
            g.zoomLog = std::clamp(g.zoomLog + (l.y < 0.f ? -k : k) * float(dt) * 1.2f, 0.f, std::log(kMaxZoom));
            g.zoom = std::exp(g.zoomLog);
        }
    }
    const bool panelOpen = g.lock >= 0 && g.panelT > 0.5f;
    if (panelOpen) {
        if (std::fabs(r.y) > 0.15f) g.panelScroll = std::clamp(g.panelScroll - r.y * float(dt) * 0.08f, 0.f, g.panelMaxScroll);
    } else if (std::fabs(r.y) > 0.15f) g.magLimit = std::clamp(g.magLimit + r.y * float(dt) * 4.f, 1.f, 20.f);
    if (boolEdge(g.aA)) {
        g.tier = (g.tier + 1) % kTiers;
        g.magLimit = g.tier == 0 ? 5.f : 20.f;  // stress tiers are bounded by their count cap instead
        LOGI("tier -> %d", g.tier);
    }
    if (boolEdge(g.aB)) {
        if (g.hand[0].squeeze > 0.7f) g.showBodies = !g.showBodies;  // left grip held: Sun, Moon, planets, asteroids, satellites
        else g.night = !g.night;
    }
    const bool lgrip = g.hand[0].squeeze > 0.7f;  // left grip held: second layer for X and Y
    if (boolEdge(g.aX)) {
        if (lgrip) g.showDso = !g.showDso;
        else g.simOffset = 0.0;  // back to the real clock
    }
    if (boolEdge(g.aY)) {
        if (lgrip) {
            if (!g.rates.empty()) {
                g.rateIdx = (g.rateIdx + 1) % int(g.rates.size());
                requestRate();
            }
        } else {
            g.showCon = !g.showCon;
        }
    }
    if (boolEdge(g.aMenu)) g.hud = !g.hud;
    if (boolEdge(g.aSel)) g.selectReq = true;
    if (boolEdge(g.aBg)) g.bgOn = !g.bgOn;
}

// -------- OpenXR bring-up

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
    std::vector<const char*> exts = {XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
                                     XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
    bool refresh = has(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME);
    if (refresh) exts.push_back(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME);
    {
        std::string all;
        for (auto& p : props) all += std::string(p.extensionName) + " ";
        LOGI("runtime extensions: %s", all.c_str());
    }
    bool renderModel = has(XR_FB_RENDER_MODEL_EXTENSION_NAME);
    if (renderModel) exts.push_back(XR_FB_RENDER_MODEL_EXTENSION_NAME);

    XrInstanceCreateInfoAndroidKHR ai{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    ai.applicationVM = g.android->activity->vm;
    ai.applicationActivity = g.android->activity->clazz;
    XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO};
    ci.next = &ai;
    strcpy(ci.applicationInfo.applicationName, "Galileo Atlas Spike");
    strcpy(ci.applicationInfo.engineName, "none");
    ci.applicationInfo.applicationVersion = 1;
    ci.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 34);
    ci.enabledExtensionCount = uint32_t(exts.size());
    ci.enabledExtensionNames = exts.data();
    XR_OK(xrCreateInstance(&ci, &g.inst));

    XrInstanceProperties ip{XR_TYPE_INSTANCE_PROPERTIES};
    xrGetInstanceProperties(g.inst, &ip);
    LOGI("OpenXR runtime: %s %u.%u.%u", ip.runtimeName, XR_VERSION_MAJOR(ip.runtimeVersion),
         XR_VERSION_MINOR(ip.runtimeVersion), XR_VERSION_PATCH(ip.runtimeVersion));

    XrSystemGetInfo si{XR_TYPE_SYSTEM_GET_INFO};
    si.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XR_OK(xrGetSystem(g.inst, &si, &g.sys));
    XrSystemProperties sp{XR_TYPE_SYSTEM_PROPERTIES};
    xrGetSystemProperties(g.inst, g.sys, &sp);
    LOGI("system: %s", sp.systemName);

    if (refresh) {
        xrGetInstanceProcAddr(g.inst, "xrEnumerateDisplayRefreshRatesFB", (PFN_xrVoidFunction*)&g.xrEnumRates);
        xrGetInstanceProcAddr(g.inst, "xrRequestDisplayRefreshRateFB", (PFN_xrVoidFunction*)&g.xrRequestRate);
    }
    if (renderModel) {
        xrGetInstanceProcAddr(g.inst, "xrEnumerateRenderModelPathsFB", (PFN_xrVoidFunction*)&g.xrEnumPaths);
        xrGetInstanceProcAddr(g.inst, "xrGetRenderModelPropertiesFB", (PFN_xrVoidFunction*)&g.xrModelProps);
        xrGetInstanceProcAddr(g.inst, "xrLoadRenderModelFB", (PFN_xrVoidFunction*)&g.xrLoadModel);
    }
    LOGI("render model extension: %s", renderModel ? "yes" : "no");
    return true;
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
    XR_OK(xrEnumerateViewConfigurationViews(g.inst, g.sys, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, nv, &nv,
                                            vcv.data()));
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
        LOGI("eye %d swapchain %dx%d, %u images, format 0x%llx", e, sc.w, sc.h, ni, (unsigned long long)fmt);
    }

    if (!initInput()) return false;

    if (g.xrEnumRates) {
        uint32_t nr = 0;
        g.xrEnumRates(g.sess, 0, &nr, nullptr);
        g.rates.resize(nr);
        g.xrEnumRates(g.sess, nr, &nr, g.rates.data());
        std::sort(g.rates.begin(), g.rates.end());
        std::string s;
        for (float r : g.rates) s += std::to_string(int(r)) + " ";
        LOGI("supported refresh rates: %s", s.c_str());
        // start at 72 Hz, the standard Quest 2 budget (the runtime also lists 60); first rate if 72 is absent
        g.rateIdx = 0;
        for (size_t i = 0; i < g.rates.size(); ++i)
            if (g.rates[i] == 72.f) g.rateIdx = int(i);
        requestRate();
    }
    return true;
}

void destroyAll() {
    if (g.stressThread.joinable()) g.stressThread.join();
    for (auto& sc : g.eye) if (sc.handle) xrDestroySwapchain(sc.handle);
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

void publishStats(const XrFrameState& fs, double cpuMs) {
    Stats& s = g.st;
    auto now = Clock::now();
    if (s.lastEnd.time_since_epoch().count() != 0) {
        double dtMs = std::chrono::duration<double, std::milli>(now - s.lastEnd).count();
        s.worstMs = std::max(s.worstMs, dtMs);
    }
    s.lastEnd = now;
    s.frames++;
    s.cpuMs += cpuMs;
    g.targetHz = fs.predictedDisplayPeriod > 0 ? float(1e9 / double(fs.predictedDisplayPeriod)) : g.targetHz;
    double win = std::chrono::duration<double>(now - s.windowStart).count();
    if (win >= 1.0) {
        s.fps = s.frames / win;
        s.worst = s.worstMs;
        s.cpu = s.cpuMs / s.frames;
        s.gpu = s.gpuSamples ? (s.gpuNs / s.gpuSamples) * 2.0 / 1e6 : 0.0;  // two eyes per frame
        LOGI("fps=%.1f/%.0f worst=%.1fms cpu=%.2fms gpu=%.2fms (wait=%.1f work=%.1f [swap=%.1f draw=%.1f] end=%.1f) "
             "stars=%u tier=%d mag<=%.1f night=%d",
             s.fps, g.targetHz, s.worst, s.cpu, s.gpu, s.waitMs / s.frames, s.workMs / s.frames, s.swapMs / s.frames,
             s.drawMs / s.frames, s.endMs / s.frames, g.lastDrawn, g.tier, g.magLimit, g.night);
        s.windowStart = now;
        s.frames = 0; s.worstMs = 0; s.cpuMs = 0; s.gpuNs = 0; s.gpuSamples = 0;
        s.waitMs = 0; s.workMs = 0; s.endMs = 0; s.swapMs = 0; s.drawMs = 0;
    }
}

bool locateSpace(XrSpace sp, XrTime t, XrPosef& out) {
    XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
    if (XR_FAILED(xrLocateSpace(sp, g.space, t, &loc))) return false;
    const XrSpaceLocationFlags need = XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR_SPACE_LOCATION_POSITION_VALID_BIT;
    if ((loc.locationFlags & need) != need) return false;
    out = loc.pose;
    return true;
}

// Poses, finger inputs, the laser's landing point on the dome, and the "what am I pointing at" HUD text.
void updateHands(XrTime t, const XrView views[2], double dt) {
    g.headFwd = aimDir(views[0].pose.orientation);  // forward is -Z for a head pose too
    Vec3 head{(views[0].pose.position.x + views[1].pose.position.x) * 0.5f,
              (views[0].pose.position.y + views[1].pose.position.y) * 0.5f,
              (views[0].pose.position.z + views[1].pose.position.z) * 0.5f};
    g.headPos = head;
    for (int i = 0; i < 2; ++i) {
        Hand& h = g.hand[i];
        h.aimOk = h.gripOk = false;
        h.trigEdge = false;
        h.sqzEdge = false;
        XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
        gi.subactionPath = g.handPath[i];
        gi.action = g.aAim;
        XrActionStatePose ps{XR_TYPE_ACTION_STATE_POSE};
        if (XR_FAILED(xrGetActionStatePose(g.sess, &gi, &ps)) || !ps.isActive) continue;
        XrPosef pa{}, pg{};
        h.aimOk = locateSpace(h.aim, t, pa);
        h.gripOk = locateSpace(h.grip, t, pg);
        if (!h.aimOk) continue;
        h.aimPos = {pa.position.x, pa.position.y, pa.position.z};
        h.aimQ = pa.orientation;
        h.gripPos = h.gripOk ? Vec3{pg.position.x, pg.position.y, pg.position.z} : h.aimPos;
        h.gripQ = h.gripOk ? pg.orientation : h.aimQ;

        auto flt = [&](XrAction a) {
            gi.action = a;
            XrActionStateFloat st{XR_TYPE_ACTION_STATE_FLOAT};
            xrGetActionStateFloat(g.sess, &gi, &st);
            return st.isActive ? st.currentState : 0.f;
        };
        auto bln = [&](XrAction a) {
            gi.action = a;
            XrActionStateBoolean st{XR_TYPE_ACTION_STATE_BOOLEAN};
            xrGetActionStateBoolean(g.sess, &gi, &st);
            return st.isActive && st.currentState;
        };
        h.trigger = flt(g.aTrig);
        h.trigEdge = h.trigger > 0.85f && !h.trigDown;
        h.trigDown = h.trigger > 0.85f;
        h.squeeze = flt(g.aSqz);
        h.sqzEdge = h.squeeze > 0.7f && !h.sqzDown;
        h.sqzDown = h.squeeze > 0.7f;
        float target = (bln(g.aThumbA) || bln(g.aThumbB)) ? 1.f : 0.f;
        h.thumb += (target - h.thumb) * float(std::min(1.0, dt * 12.0));

        // ray from the controller to a sphere just inside the star dome
        Vec3 dir = aimDir(h.aimQ);
        const float R = 0.9f * kDome;
        float b = dot(h.aimPos, dir), c = dot(h.aimPos, h.aimPos) - R * R;
        h.hitPt = h.aimPos + dir * (-b + std::sqrt(std::max(b * b - c, 0.f)));
        if (!g.ctlVerts[i]) buildHand(h, i);
    }

    // readout: the right controller is the only pointer
    const Hand* ph = g.hand[1].aimOk ? &g.hand[1] : nullptr;
    if (!ph) {
        g.hPoint1 = "POINT NONE";
        g.hPoint2 = " ";
        g.hOver = "OVER NONE";
        g.hover = -1;
        g.selectReq = false;
        return;
    }
    Vec3 wd = normalize(ph->hitPt - head);  // as displayed, seen from the head; x=E y=up z=S
    Vec3 w = zoomInvCpu(wd);                // the true sky direction under the pointer
    double alt = std::asin(double(w.y)) * 180.0 / kPi;
    double az = std::fmod(std::atan2(double(w.x), double(-w.z)) * 180.0 / kPi + 360.0, 360.0);
    // horizontal -> equatorial of date -> J2000 (inverse of the star shader and updateSky's precession)
    double cl = std::cos(g.lst), sn = std::sin(g.lst), sl = std::sin(g.latitude), cs = std::cos(g.latitude);
    double up = w.y, north = -w.z, east = w.x;
    double a = up * cs - north * sl, zz = up * sl + north * cs, bb = -east;
    double X = a * cl + bb * sn, Y = a * sn - bb * cl;
    const float* P = g.prec;
    double xj = P[0] * X + P[1] * Y + P[2] * zz, yj = P[3] * X + P[4] * Y + P[5] * zz,
           zj = P[6] * X + P[7] * Y + P[8] * zz;
    double ra = std::fmod(std::atan2(yj, xj) * 12.0 / kPi + 24.0, 24.0);
    double dec = std::asin(std::clamp(zj, -1.0, 1.0)) * 180.0 / kPi;
    std::string near = "---";
    float best = -2.f;
    for (const App::ConName& cn : g.conNames) {
        float d = float(cn.d.x * xj + cn.d.y * yj + cn.d.z * zj);
        if (d > best) { best = d; near = cn.s; }
    }
    for (char& ch : near) ch = char(toupper((unsigned char)ch));
    char buf[96];
    snprintf(buf, sizeof buf, "POINT ALT %.1f AZ %.1f", alt, az);
    g.hPoint1 = buf;
    snprintf(buf, sizeof buf, "RA %.2fH DEC %+.1f NEAR %s", ra, dec, near.c_str());
    g.hPoint2 = buf;

    // deep-sky object under the pointer, and the lock / release request
    g.hover = findDso(wd);
    if (ph->trigEdge) g.selectReq = true;
    if (g.selectReq) {
        g.selectReq = false;
        if (g.hover >= 0 && g.hover != g.lock) {
            g.zd = g.dsoW[size_t(g.hover)];  // park it where it is displayed now: nothing jumps
            g.lock = g.hover;
            g.zcSet = true;
        } else if (g.lock >= 0) {
            g.lock = -1;  // clicked the locked object again, or empty sky: release (a zoom in progress stays put)
            g.zcSet = g.zoom > 1.001f;
        }
    }
    auto describe = [](const char* tag, int idx, std::string& out) {
        const Dso& o = g.dso[size_t(idx)];
        char t[96];
        if (o.type == 7) snprintf(t, sizeof t, "%s MOON %.0f%% LIT", tag, o.mag * 100.0);
        else if (o.type == 6) snprintf(t, sizeof t, "%s SUN", tag);
        else if (o.type >= 8) snprintf(t, sizeof t, "%s %s %s MAG%.1f", tag, o.label, kDsoTypeName[std::min<int>(o.type, 10)], o.mag);
        else snprintf(t, sizeof t, "%s %s %s MAG%.1f %.0f ARCMIN", tag, o.label, kDsoTypeName[std::min<int>(o.type, 5)], o.mag, o.size);
        out = t;
    };
    if (g.hover >= 0) describe("OVER", g.hover, g.hOver); else g.hOver = "OVER NONE";
    if (g.lock >= 0) {
        g.hLock = std::string("LOCK ") + g.dso[size_t(g.lock)].label;
    } else {
        g.hLock = "LOCK NONE";
    }
}

// Real clock + the stick's offset -> local sidereal time, precession and the HUD strings.
void updateSky() {
    using namespace std::chrono;
    double now = duration<double>(system_clock::now().time_since_epoch()).count();
    double t = now + g.simOffset;
    g.tNow = t;
    double d = t / 86400.0 + 2440587.5 - 2451545.0;  // days since J2000.0 (UT)
    double gmst = std::fmod(280.46061837 + 360.98564736629 * d, 360.0);
    double lstDeg = std::fmod(gmst + g.lonDeg, 360.0);
    if (lstDeg < 0) lstDeg += 360.0;
    g.lst = lstDeg * kPi / 180.0;

    // IAU 1976 precession, J2000 -> mean equator of date (checked against astropy to <0.1 arcsec)
    double T = d / 36525.0, as = kPi / (180.0 * 3600.0);
    double zeta = (2306.2181 * T + 0.30188 * T * T + 0.017998 * T * T * T) * as;
    double z = (2306.2181 * T + 1.09468 * T * T + 0.018203 * T * T * T) * as;
    double th = (2004.3109 * T - 0.42665 * T * T - 0.041833 * T * T * T) * as;
    double cz = std::cos(zeta), sz = std::sin(zeta), cp = std::cos(z), sp = std::sin(z), ct = std::cos(th),
           st = std::sin(th);
    double M[3][3] = {{cz * ct * cp - sz * sp, -sz * ct * cp - cz * sp, -st * cp},
                      {cz * ct * sp + sz * cp, -sz * ct * sp + cz * cp, -st * sp},
                      {cz * st, -sz * st, ct}};
    for (int c = 0; c < 3; ++c)
        for (int r = 0; r < 3; ++r) g.prec[c * 3 + r] = float(M[r][c]);

    time_t tt = time_t(std::floor(t));
    struct tm u {}, l {};
    gmtime_r(&tt, &u);
    localtime_r(&tt, &l);
    char buf[96], a[48], b[48];
    std::string name = g.siteName;
    for (char& ch : name) ch = char(toupper((unsigned char)ch));
    double lat = g.latitude * 180.0 / kPi;
    snprintf(buf, sizeof buf, "%s %.2f%c %.2f%c %.0fM", name.c_str(), std::fabs(lat), lat >= 0 ? 'N' : 'S',
             std::fabs(g.lonDeg), g.lonDeg >= 0 ? 'E' : 'W', g.elevM);
    g.hSite = buf;
    strftime(a, sizeof a, "%Y-%m-%d %H:%M:%S", &u);
    g.hUtc = std::string("UTC ") + a;
    strftime(b, sizeof b, "%H:%M:%S %Z", &l);
    g.hLocal = std::string("LOCAL ") + b;
    for (char& ch : g.hLocal) ch = char(toupper((unsigned char)ch));
    int ls = int(lstDeg / 15.0 * 3600.0);
    snprintf(buf, sizeof buf, "LST %02dH %02dM %02dS", ls / 3600, (ls / 60) % 60, ls % 60);
    g.hLst = buf;
    long off = std::lround(g.simOffset);
    if (std::labs(off) < 2) {
        g.hOff = "LIVE";
    } else {
        long ao = std::labs(off);
        long days = ao / 86400, hrs = (ao / 3600) % 24, mins = (ao / 60) % 60;
        if (days) snprintf(buf, sizeof buf, "TIME %c%ldD %02ldH %02ldM", off < 0 ? '-' : '+', days, hrs, mins);
        else snprintf(buf, sizeof buf, "TIME %c%ldH %02ldM", off < 0 ? '-' : '+', hrs, mins);
        g.hOff = buf;
    }
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

// Retried every few seconds until both controllers are in: the runtime may not have them ready at session start.
void loadControllerModels() {
    if (!g.xrLoadModel || !g.xrEnumPaths || !g.xrModelProps || g.ctlTries >= 8) return;
    if (g.ctlVerts[0] > 0 && g.ctlVerts[1] > 0) return;
    double now = std::chrono::duration<double>(Clock::now().time_since_epoch()).count();
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

// Which DSS2 cutout to draw this frame and where: the locked object, or with nothing locked the one nearest the gaze.
// It fades in as the object's displayed size grows (and not at all below a modest zoom).
void updateDss() {
    g.dssShown = -1;
    g.dssAlpha = 0.f;
    if (!g.dssCount || g.zoom < 1.2f) return;
    auto sstep = [](float a, float b, float x) {
        float t = std::min(std::max((x - a) / (b - a), 0.f), 1.f);
        return t * t * (3.f - 2.f * t);
    };
    auto alphaFor = [&](size_t i) {
        return sstep(2.5f, 5.f, std::max(g.dso[i].size / 60.f, 0.15f) * g.zoom) * sstep(1.2f, 2.f, g.zoom);
    };
    int best = -1;
    float bestA = 0.f;
    if (g.lock >= 0) {
        size_t i = size_t(g.lock);
        if (i < g.nStatic && g.dssOf[i] >= 0) { best = int(i); bestA = alphaFor(i); }
    } else {
        float bd = 0.85f;  // within ~32 degrees of the gaze
        for (size_t i = 0; i < g.nStatic; ++i) {
            if (g.dssOf[i] < 0) continue;
            const Vec3& w = g.dsoW[i];
            if (dot(w, w) < 0.25f || w.y < 0.f) continue;
            float d = dot(w, g.headFwd);
            if (d <= bd) continue;
            float a = alphaFor(i);
            if (a > 0.01f) { bd = d; best = int(i); bestA = a; }
        }
    }
    if (best < 0 || bestA <= 0.003f) return;
    const Dso& o = g.dso[size_t(best)];
    const Vec3 w = g.dsoW[size_t(best)];
    if (dot(w, w) < 0.25f) return;
    g.dssShown = best;
    g.dssAlpha = bestA;
    g.dssFovRad = g.dssFov[size_t(g.dssOf[size_t(best)])] * float(kPi / 180.0);
    // tangent-plane axes at the object in J2000: east = z x n, north = n x east
    Vec3 n = o.d;
    Vec3 e{-n.y, n.x, 0.f};
    float el = std::sqrt(e.x * e.x + e.y * e.y);
    e = el > 1e-6f ? Vec3{e.x / el, e.y / el, 0.f} : Vec3{1.f, 0.f, 0.f};
    g.dssObj = n;
    g.dssEast = e;
    g.dssNorth = cross(n, e);
    // a quad generously larger than the picture's displayed size, tangent to the dome at the object
    Vec3 r = cross(Vec3{0.f, 1.f, 0.f}, w);
    r = dot(r, r) > 1e-4f ? normalize(r) : Vec3{1.f, 0.f, 0.f};
    Vec3 u = cross(w, r);
    float ha = std::min(0.5f * g.dssFovRad * g.zoom * 1.6f + 0.02f, 1.2f);
    float h = 995.f * std::tan(ha);
    Vec3 c = w * 995.f;
    Vec3 q[4] = {c - r * h - u * h, c + r * h - u * h, c + r * h + u * h, c - r * h + u * h};
    const int idx[6] = {0, 1, 2, 0, 2, 3};
    for (int i = 0; i < 6; ++i) {
        g.dssQuad[i * 3] = q[idx[i]].x;
        g.dssQuad[i * 3 + 1] = q[idx[i]].y;
        g.dssQuad[i * 3 + 2] = q[idx[i]].z;
    }
}

// The panel above the right controller describes the locked object. The lines are rebuilt every frame while it is
// open (altitude and azimuth move); on release they are kept so it can shrink away.
void updatePanel(double dt) {
    const bool want = g.lock >= 0 && size_t(g.lock) < g.dso.size() && g.hand[1].aimOk;
    g.panelT += ((want ? 1.f : 0.f) - g.panelT) * float(std::min(1.0, dt * 10.0));
    if (!want) {
        if (music::speaking()) music::stopSpeech();  // the panel is gone: stop reading it
        if (g.panelT < 0.01f) g.panelT = 0.f;
        return;
    }
    const Hand& h = g.hand[1];
    // The card lies along the pointer axis A and faces the head: n is the direction to the head with A removed, the text
    // runs along +-A (whichever reads left to right for the viewer) and up is n x right. The bottom edge sits just
    // beside the controller body, towards the user.
    const Vec3 up{0.f, 1.f, 0.f};
    Vec3 A = aimDir(h.aimQ);
    Vec3 toHead = g.headPos - h.gripPos;
    Vec3 perp = toHead - A * dot(toHead, A);
    perp = dot(perp, perp) > 1e-6f ? normalize(perp) : normalize(cross(A, up));
    Vec3 hr = cross(g.headFwd, up);
    hr = dot(hr, hr) > 1e-6f ? normalize(hr) : Vec3{1.f, 0.f, 0.f};
    Vec3 r = dot(A, hr) >= 0.f ? A : A * -1.f;
    g.panelR = r;
    g.panelU = normalize(cross(perp, r));
    g.panelO = h.gripPos + A * 0.02f + perp * 0.03f;

    const size_t idx = size_t(g.lock);
    if (g.panelFor != g.lock) {
        g.panelFor = g.lock;
        g.panelScroll = 0.f;
        music::stopSpeech();  // a different object: whatever was being read is stale
    }
    const Dso& o = g.dso[idx];
    const Info* inf = nullptr;
    {
        auto it = g.info.find(o.label);  // catalogue objects and the Sun, Moon and planets have panel text
        if (it != g.info.end() && (idx < g.nStatic || o.type <= 8)) inf = &it->second;
    }
    // second trigger (right grip) reads the panel aloud, or stops it
    if (h.sqzEdge && g.panelT > 0.5f) {
        if (music::speaking()) music::stopSpeech();
        else music::say(g.android->activity->assetManager, o.label);
    }
    auto title = [](const char* up) {
        std::string t = up;
        for (size_t i = 1; i < t.size(); ++i) t[i] = char(tolower((unsigned char)t[i]));
        return t;
    };
    auto fmt = [](const char* f, auto... a) {
        char b[160];
        snprintf(b, sizeof b, f, a...);
        return std::string(b);
    };
    std::vector<PanelLine>& L = g.panelLines;
    L.clear();
    std::string name = o.label;
    if (inf && !inf->common.empty()) name += "  " + inf->common;
    L.push_back({name, 0});
    std::string kind = title(kDsoTypeName[std::min<int>(o.type, 10)]);
    if (inf && !inf->con.empty()) kind += " in " + inf->con;
    L.push_back({kind, 1});
    if (inf && !inf->also.empty()) L.push_back({"Also " + inf->also, 2});
    L.push_back({"", 2});

    if (o.type == 7) {
        L.push_back({fmt("Phase %.0f%% illuminated", o.mag * 100.0), 1});
    } else if (o.type != 6) {
        std::string m = o.mag >= 14.95f ? "Magnitude unknown" : fmt("Magnitude %.1f", o.mag);
        if (o.type <= 5 || o.size > 0.f) {
            if (o.size >= 1.f) m += fmt("    Size %.1f arcmin", o.size);
            else if (o.size > 0.f) m += fmt("    Size %.0f arcsec", o.size * 60.f);
        }
        L.push_back({m, 1});
    } else {
        L.push_back({fmt("Apparent size %.1f arcmin", o.size), 1});
    }
    double ra = std::fmod(std::atan2(double(o.d.y), double(o.d.x)) * 12.0 / kPi + 24.0, 24.0);
    double dec = std::asin(std::min(std::max(double(o.d.z), -1.0), 1.0)) * 180.0 / kPi;
    int rh = int(ra), dd = int(std::fabs(dec));
    L.push_back({fmt("RA %dh %04.1fm    Dec %c%d deg %02d min", rh, (ra - rh) * 60.0, dec < 0 ? '-' : '+', dd,
                     int((std::fabs(dec) - dd) * 60.0)), 1});
    Vec3 w = toWorld(o.d);
    double alt = std::asin(double(std::min(std::max(w.y, -1.f), 1.f))) * 180.0 / kPi;
    double az = std::fmod(std::atan2(double(w.x), double(-w.z)) * 180.0 / kPi + 360.0, 360.0);
    L.push_back({fmt("Altitude %.1f deg    Azimuth %.0f deg%s", alt, az, alt < 0 ? "    below the horizon" : ""), 1});
    if (inf && !inf->desc.empty()) {
        L.push_back({"", 2});
        L.push_back({inf->desc, 1});
    }
    if (music::hasSpeech(o.label)) {
        L.push_back({"", 2});
        L.push_back({music::speaking() ? "Reading aloud. Press the right grip to stop." : "Press the right grip to hear this read aloud.", 3});
    }
    if (idx < g.nStatic && g.dssOf[idx] >= 0) {
        L.push_back({"", 2});
        if (g.dssShown == int(idx) && g.dssAlpha > 0.5f) L.push_back({"DSS2 survey image, north up, east left.", 3});
        else L.push_back({"Zoom in with the left stick to see the DSS2 survey image.", 3});
    }
}

void frame() {
    auto t0 = Clock::now();
    XrFrameState fs{XR_TYPE_FRAME_STATE};
    if (XR_FAILED(xrWaitFrame(g.sess, nullptr, &fs))) return;
    auto t1 = Clock::now();  // CPU time is measured from here: waiting for the compositor is not our cost
    g.st.waitMs += std::chrono::duration<double, std::milli>(t1 - t0).count();
    if (XR_FAILED(xrBeginFrame(g.sess, nullptr))) return;

    static Clock::time_point prev = t0;
    double dt = std::chrono::duration<double>(t0 - prev).count();
    prev = t0;
    pollInput(dt);
    updateSky();
    updateBodies();
    updateZoomMap();
    if (g.sessionRunning) loadControllerModels();

    if (g.tier > 0 && g.stressReady && !g.stressUploaded) {
        uploadSet(g.stress, g.stressStars);
        g.stressStars.clear();
        g.stressStars.shrink_to_fit();
        g.stressUploaded = true;
    }
    pollGpuTimer();

    XrCompositionLayerProjectionView pv[2] = {{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW},
                                              {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};
    XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    const XrCompositionLayerBaseHeader* layers[1] = {(XrCompositionLayerBaseHeader*)&layer};
    uint32_t layerCount = 0;

    if (fs.shouldRender) {
        XrViewLocateInfo li{XR_TYPE_VIEW_LOCATE_INFO};
        li.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
        li.displayTime = fs.predictedDisplayTime;
        li.space = g.space;
        XrViewState vs{XR_TYPE_VIEW_STATE};
        XrView views[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
        uint32_t nv = 0;
        if (XR_SUCCEEDED(xrLocateViews(g.sess, &li, &vs, 2, &nv, views)) &&
            (vs.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT)) {
            updateHands(fs.predictedDisplayTime, views, dt);
            updateDss();
            updatePanel(dt);
            for (int e = 0; e < 2; ++e) {
                EyeSwapchain& sc = g.eye[e];
                uint32_t idx = 0;
                xrAcquireSwapchainImage(sc.handle, nullptr, &idx);
                XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
                wi.timeout = XR_INFINITE_DURATION;
                auto ts0 = Clock::now();
                xrWaitSwapchainImage(sc.handle, &wi);
                auto ts1 = Clock::now();
                renderEye(e, views[e], sc.fbos[idx]);
                g.st.swapMs += std::chrono::duration<double, std::milli>(ts1 - ts0).count();
                g.st.drawMs += std::chrono::duration<double, std::milli>(Clock::now() - ts1).count();
                xrReleaseSwapchainImage(sc.handle, nullptr);
                pv[e].pose = views[e].pose;
                pv[e].fov = views[e].fov;
                pv[e].subImage.swapchain = sc.handle;
                pv[e].subImage.imageRect = {{0, 0}, {sc.w, sc.h}};
                pv[e].subImage.imageArrayIndex = 0;
            }
            layer.space = g.space;
            layer.viewCount = 2;
            layer.views = pv;
            layerCount = 1;
        }
    }

    XrFrameEndInfo ei{XR_TYPE_FRAME_END_INFO};
    ei.displayTime = fs.predictedDisplayTime;
    ei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    ei.layerCount = layerCount;
    ei.layers = layers;
    auto tE = Clock::now();
    xrEndFrame(g.sess, &ei);
    auto tEnd = Clock::now();
    g.st.workMs += std::chrono::duration<double, std::milli>(tE - t1).count();
    g.st.endMs += std::chrono::duration<double, std::milli>(tEnd - tE).count();
    publishStats(fs, std::chrono::duration<double, std::milli>(tEnd - t1).count());
}

void onCmd(android_app* app, int32_t cmd) {
    (void)app;
    if (cmd == APP_CMD_RESUME) { g.resumed = true; music::setPaused(false); }
    else if (cmd == APP_CMD_PAUSE) { g.resumed = false; music::setPaused(true); }
    else if (cmd == APP_CMD_DESTROY) g.quit = true;
}

}  // namespace

void android_main(android_app* app) {
    g.android = app;
    app->onAppCmd = onCmd;
    LOGI("Galileo Atlas Spike starting");

    while (!app->destroyRequested) {
        int events;
        android_poll_source* src;
        while (ALooper_pollAll((g.sessionRunning || !g.initDone) ? 0 : -1, nullptr, &events, (void**)&src) >= 0) {
            if (src) src->process(app, src);
            if (app->destroyRequested) break;
        }
        if (app->destroyRequested) break;

        if (!g.initDone && g.resumed && app->window) {
            if (!loadStars(app->activity->assetManager, g.realStars)) break;
            LOGI("loaded %zu stars", g.realStars.size());
            loadConstellations(app->activity->assetManager);
            loadSkyAssets(app->activity->assetManager);
            music::start(app->activity->assetManager);
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
    LOGI("exiting");
}

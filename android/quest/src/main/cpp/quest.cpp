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

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

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
    jobject surface = nullptr;  // global ref
    float c[3] = {0, 0, -1}, r[3] = {1, 0, 0}, u[3] = {0, 1, 0};
    float w = 1.f, h = 1.f;
};

struct Hand {
    XrSpace aim = XR_NULL_HANDLE;
    bool ok = false;
    Vec3 pos{0, 0, 0}, dir{0, 0, -1};
    float trigger = 0, squeeze = 0, sx = 0, sy = 0;
    bool stickClick = false;
};

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
    GLuint starProg = 0, skyLineProg = 0, markProg = 0, overProg = 0, solidProg = 0, labelProg = 0;
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
            Vec3 right = normalize(cross(Vec3{0, 1, 0}, d));
            Vec3 up = normalize(cross(d, right));
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
                }
                h.trigger = floatState(g.aTrig, g.handPath[i]);
                h.squeeze = floatState(g.aSqz, g.handPath[i]);
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
    if (!has(XR_KHR_ANDROID_SURFACE_SWAPCHAIN_EXTENSION_NAME)) {
        LOGE("this runtime has no Android surface swapchains: panes cannot be shown");
    } else {
        exts.push_back(XR_KHR_ANDROID_SURFACE_SWAPCHAIN_EXTENSION_NAME);
    }
    const bool refresh = has(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME);
    if (refresh) exts.push_back(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME);
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
    if (pass) {
        proc("xrCreatePassthroughFB", g.xrCreatePassthrough);
        proc("xrDestroyPassthroughFB", g.xrDestroyPassthrough);
        proc("xrPassthroughStartFB", g.xrPassthroughStart);
        proc("xrPassthroughPauseFB", g.xrPassthroughPause);
        proc("xrCreatePassthroughLayerFB", g.xrCreatePassthroughLayer);
        proc("xrDestroyPassthroughLayerFB", g.xrDestroyPassthroughLayer);
    }
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

// Panes: a surface swapchain per quad. Created on the render thread (it owns the session) when Kotlin has asked for one.
void serviceQuads() {
    if (!g.xrCreateSurfaceSwapchain) return;
    std::lock_guard<std::mutex> lk(g.mu);
    for (int i = 0; i < kMaxQuads; ++i) {
        QuadSlot& q = g.quad[i];
        if (!q.wanted && q.created) {
            if (q.sc) xrDestroySwapchain(q.sc);
            if (q.surface && g.env) g.env->DeleteGlobalRef(q.surface);
            q.sc = XR_NULL_HANDLE;
            q.surface = nullptr;
            q.created = false;
            q.visible = false;
        } else if (q.wanted && !q.created && q.pw > 0 && q.ph > 0) {
            XrSwapchainCreateInfo ci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
            ci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
            ci.sampleCount = 1;
            ci.width = uint32_t(q.pw);
            ci.height = uint32_t(q.ph);
            ci.faceCount = 1;
            ci.arraySize = 1;
            ci.mipCount = 1;
            jobject surface = nullptr;
            XrResult r = XR_ERROR_RUNTIME_FAILURE;
            for (int64_t format : {int64_t(0), int64_t(GL_RGBA8), int64_t(GL_SRGB8_ALPHA8)}) {
                ci.format = format;
                r = g.xrCreateSurfaceSwapchain(g.sess, &ci, &q.sc, &surface);
                if (XR_SUCCEEDED(r)) break;
            }
            if (XR_FAILED(r) || !surface) {
                LOGE("pane %d: surface swapchain failed (%d)", i, (int)r);
                q.wanted = false;
                continue;
            }
            q.surface = g.env ? g.env->NewGlobalRef(surface) : surface;
            q.created = true;
            LOGI("pane %d: %dx%d surface ready", i, q.pw, q.ph);
        }
    }
}

void destroyAll() {
    for (auto& q : g.quad) if (q.sc) xrDestroySwapchain(q.sc);
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

void frame() {
    XrFrameState fs{XR_TYPE_FRAME_STATE};
    if (XR_FAILED(xrWaitFrame(g.sess, nullptr, &fs))) return;
    if (XR_FAILED(xrBeginFrame(g.sess, nullptr))) return;

    serviceQuads();

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
    if (cmd == APP_CMD_RESUME) g.resumed = true;
    else if (cmd == APP_CMD_PAUSE) g.resumed = false;
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
    destroyAll();
    app->activity->vm->DetachCurrentThread();
    LOGI("exiting");
}

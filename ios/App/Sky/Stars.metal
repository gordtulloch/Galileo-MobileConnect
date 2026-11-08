#include <metal_stdlib>
using namespace metal;

// The star field (ADR-IOS-004): each star is a unit vector, turned into the view on the GPU, so that panning and
// zooming through tens of thousands of them needs no work on the CPU.

struct Star {
    float4 positionAndMagnitude;  // xyz: equatorial unit vector, w: magnitude
};

struct Uniforms {
    float3x3 equatorialToHorizon;
    float3x3 horizonToView;
    float2 viewport;              // points
    float scale;                  // points per unit of the stereographic plane
    float limitingMagnitude;
    float contentScale;
    float4 color;
    float4 backgroundTint;
    float4 horizonFill;           // premultiplied
    float4 landNear;              // w: 1 to hide what's below the horizon behind the land, 0 to leave it showing
    float4 landFar;
};

struct Fragment {
    float4 position [[position]];
    float pointSize [[point_size]];
    float alpha;
};

vertex Fragment starVertex(const device Star *stars [[buffer(0)]],
                           constant Uniforms &u [[buffer(1)]],
                           uint id [[vertex_id]]) {
    float3 horizon = u.equatorialToHorizon * stars[id].positionAndMagnitude.xyz;
    float3 view = u.horizonToView * horizon;
    float magnitude = stars[id].positionAndMagnitude.w;
    Fragment out;
    if (view.z < -0.85 || magnitude > u.limitingMagnitude) {  // SkyCamera.farLimit
        out.position = float4(2.0, 2.0, 0.0, 1.0);  // off screen
        out.pointSize = 0.0;
        out.alpha = 0.0;
        return out;
    }
    float k = 2.0 / (1.0 + view.z) * u.scale;
    out.position = float4(view.x * k / (u.viewport.x / 2.0), view.y * k / (u.viewport.y / 2.0), 0.0, 1.0);
    float brightness = u.limitingMagnitude - magnitude;
    out.pointSize = clamp(1.2 + 0.5 * brightness, 1.2, 8.0) * u.contentScale;
    out.alpha = clamp(0.3 + 0.16 * brightness, 0.3, 1.0) * (horizon.z < 0.0 ? 0.25 : 1.0);  // below the horizon: faint
    return out;
}

// Survey tiles behind the stars (`MAPP-SKY-070`): a mesh of real sky directions, through the stars' own projection, so that
// a tile can't disagree with them. Later tiles replace earlier ones, so a finer one covers its parent.

struct TileVertex {
    float3 direction;
    float2 place;
};

struct TilePixel {
    float4 position [[position]];
    float2 place;
    float facing;
};

vertex TilePixel tileVertex(const device TileVertex *vertices [[buffer(0)]],
                            constant Uniforms &u [[buffer(1)]],
                            uint id [[vertex_id]]) {
    float3 horizon = u.equatorialToHorizon * vertices[id].direction;
    float3 view = u.horizonToView * horizon;
    float k = 2.0 / max(1.0 + view.z, 0.02) * u.scale;
    TilePixel out;
    out.position = float4(view.x * k / (u.viewport.x / 2.0), view.y * k / (u.viewport.y / 2.0), 0.0, 1.0);
    out.place = vertices[id].place;
    out.facing = view.z;
    return out;
}

fragment float4 tileFragment(TilePixel in [[stage_in]],
                             constant Uniforms &u [[buffer(1)]],
                             texture2d<float> image [[texture(0)]]) {
    if (in.facing < -0.85) discard_fragment();  // the far side of the projection runs off to infinity
    constexpr sampler smooth(filter::linear, address::clamp_to_edge);
    return float4(image.sample(smooth, in.place).rgb * u.backgroundTint.rgb, 1.0);
}

// The ground, after everything else in this view, per pixel so that its edge holds at any zoom (`MAPP-SKY-080`): the stars'
// projection run backwards to an altitude and azimuth. Below 0 degrees, an opaque landscape; from there up to the horizon
// profile, transparent red.

struct SkyPixel {
    float4 position [[position]];
    float2 plane;                 // on the stereographic plane, as `k * view.xy / scale` is for a star
};

static float landHash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }

static float landNoise(float2 p) {
    float2 cell = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(landHash(cell), landHash(cell + float2(1, 0)), f.x),
               mix(landHash(cell + float2(0, 1)), landHash(cell + float2(1, 1)), f.x), f.y);
}

// Patches on a flat ground plane seen from above it, as `landscape_rgb` in `galileo.planning.star_atlas`: they recede as real
// fields do, each scale fades out before it would glitter, and the distance pales into haze.
static float3 land(float down, float azimuth, constant Uniforms &u) {
    down = max(down, 0.002);
    float distance = 1.0 / tan(down);
    float2 place = distance * float2(sin(azimuth), cos(azimuth));
    float patches = 0.0;
    patches += 0.55 * clamp(2.0 / (distance * 0.7), 0.0, 1.0) * (landNoise(place * 0.7) - 0.5);
    patches += 0.30 * clamp(2.0 / (distance * 3.0), 0.0, 1.0) * (landNoise(place * 3.0) - 0.5);
    patches += 0.15 * clamp(2.0 / (distance * 13.0), 0.0, 1.0) * (landNoise(place * 13.0) - 0.5);
    float closeness = sqrt(clamp(down / 0.5236, 0.0, 1.0));
    // A dark line of trees and hills along the far edge. All below 0 degrees, so it hides no sky.
    float2 round = float2(sin(azimuth), cos(azimuth));
    float ridge = 0.5 + 2.5 * landNoise(round * 8.0 + 40.0) + 1.2 * landNoise(round * 40.0 + 90.0);
    if (down * 57.29578 < ridge) return u.landFar.rgb * 0.5;
    return mix(u.landFar.rgb, u.landNear.rgb, closeness) * (1.0 + 1.8 * patches);
}

vertex SkyPixel groundVertex(uint id [[vertex_id]], constant Uniforms &u [[buffer(1)]]) {
    float2 clip = float2(id == 1 ? 3.0 : -1.0, id == 2 ? 3.0 : -1.0);  // one triangle over the whole view
    SkyPixel out;
    out.position = float4(clip, 0.0, 1.0);
    out.plane = clip * u.viewport / (2.0 * u.scale);
    return out;
}

fragment float4 groundFragment(SkyPixel in [[stage_in]],
                               constant Uniforms &u [[buffer(1)]],
                               constant float *skyline [[buffer(2)]]) {
    float r = dot(in.plane, in.plane) / 4.0;
    float3 view = float3(in.plane, 1.0 - r) / (1.0 + r);
    float3 horizon = transpose(u.horizonToView) * view;
    float azimuth = atan2(horizon.x, horizon.y);
    float steps = fract(azimuth / (2.0 * M_PI_F) + 1.0) * 72.0;  // one height every 5 degrees of azimuth
    int low = int(steps) % 72;
    float height = mix(skyline[low], skyline[(low + 1) % 72], fract(steps));
    float altitude = asin(clamp(horizon.z, -1.0, 1.0));
    if (altitude < 0.0) {
        if (u.landNear.w < 0.5) discard_fragment();
        return float4(land(-altitude, azimuth, u), 1.0);
    }
    if (altitude >= height) discard_fragment();
    return u.horizonFill;
}

fragment float4 starFragment(Fragment in [[stage_in]],
                             float2 point [[point_coord]],
                             constant Uniforms &u [[buffer(1)]]) {
    float distance = length(point - float2(0.5));
    float disc = 1.0 - smoothstep(0.35, 0.5, distance);
    return float4(u.color.rgb, u.color.a * in.alpha * disc);
}

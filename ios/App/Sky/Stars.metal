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
    if (view.z < -0.9 || magnitude > u.limitingMagnitude) {
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

fragment float4 starFragment(Fragment in [[stage_in]],
                             float2 point [[point_coord]],
                             constant Uniforms &u [[buffer(1)]]) {
    float distance = length(point - float2(0.5));
    float disc = 1.0 - smoothstep(0.35, 0.5, distance);
    return float4(u.color.rgb, u.color.a * in.alpha * disc);
}

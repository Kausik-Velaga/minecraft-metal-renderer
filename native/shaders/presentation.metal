#include <metal_stdlib>
using namespace metal;

struct PresentationVertex {
    float4 position [[position]];
    float2 uv;
};

vertex PresentationVertex present_v(uint vertexId [[vertex_id]]) {
    float2 uv = float2((vertexId << 1) & 2, vertexId & 2);
    return {float4(uv.x * 2 - 1, 1 - uv.y * 2, 0, 1), uv};
}

fragment float4 present_f(PresentationVertex input [[stage_in]],
                          texture2d<float> source [[texture(0)]],
                          sampler sampling [[sampler(0)]]) {
    // Blaze3D's Vulkan framebuffer convention flips vertically at presentation.
    return source.sample(sampling, float2(input.uv.x, 1 - input.uv.y));
}

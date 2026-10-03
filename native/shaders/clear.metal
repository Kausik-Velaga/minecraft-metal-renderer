#include <metal_stdlib>
using namespace metal;

struct ClearParameters {
    float4 color;
    float depth;
};

struct ClearVertex {
    float4 position [[position]];
};

vertex ClearVertex clear_v(uint vertexId [[vertex_id]],
                          constant ClearParameters& params [[buffer(0)]]) {
    float2 position = float2((vertexId << 1) & 2, vertexId & 2);
    return {float4(position * 2 - 1, params.depth, 1)};
}

fragment float4 clear_f(constant ClearParameters& params [[buffer(0)]]) {
    return params.color;
}

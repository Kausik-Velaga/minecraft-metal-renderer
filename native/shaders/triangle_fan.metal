#include <metal_stdlib>
using namespace metal;

// Expands an indexed triangle fan (Metal has no fan topology) into a triangle list.
// The source index buffer is bound at offset 0 and read as 16-bit words, so both index widths
// work without an aligned buffer offset.
struct FanParams { uint first; uint triangles; uint index32; };

static uint fanIndex(device const ushort* source, constant FanParams& params, uint corner) {
    uint n = params.first + corner;
    return params.index32 ? uint(source[2 * n]) | (uint(source[2 * n + 1]) << 16) : uint(source[n]);
}

kernel void expand_triangle_fan(device const ushort* source [[buffer(0)]],
                                device uint* triangles [[buffer(1)]],
                                constant FanParams& params [[buffer(2)]],
                                uint triangle [[thread_position_in_grid]]) {
    if (triangle >= params.triangles) return;
    triangles[3 * triangle] = fanIndex(source, params, 0);
    triangles[3 * triangle + 1] = fanIndex(source, params, triangle + 1);
    triangles[3 * triangle + 2] = fanIndex(source, params, triangle + 2);
}

#include "MetalFormats.hpp"
#include <unordered_map>

namespace metal {
MTLPixelFormat pixelFormat(const std::string& name) {
#define P(j, m) {#j, MTLPixelFormat##m}
    static const std::unordered_map<std::string, MTLPixelFormat> formats = {
        P(R8_UNORM, R8Unorm), P(R8_SNORM, R8Snorm), P(RG8_UNORM, RG8Unorm), P(RG8_SNORM, RG8Snorm),
        P(RGBA8_UNORM, RGBA8Unorm), P(RGBA8_SNORM, RGBA8Snorm), P(R16_UNORM, R16Unorm), P(R16_SNORM, R16Snorm),
        P(RG16_UNORM, RG16Unorm), P(RG16_SNORM, RG16Snorm), P(RGBA16_UNORM, RGBA16Unorm), P(RGBA16_SNORM, RGBA16Snorm),
        P(R8_UINT, R8Uint), P(R8_SINT, R8Sint), P(RG8_UINT, RG8Uint), P(RG8_SINT, RG8Sint),
        P(RGBA8_UINT, RGBA8Uint), P(RGBA8_SINT, RGBA8Sint), P(R16_UINT, R16Uint), P(R16_SINT, R16Sint),
        P(RG16_UINT, RG16Uint), P(RG16_SINT, RG16Sint), P(RGBA16_UINT, RGBA16Uint), P(RGBA16_SINT, RGBA16Sint),
        P(R32_UINT, R32Uint), P(R32_SINT, R32Sint), P(RG32_UINT, RG32Uint), P(RG32_SINT, RG32Sint),
        P(RGBA32_UINT, RGBA32Uint), P(RGBA32_SINT, RGBA32Sint), P(R16_FLOAT, R16Float), P(RG16_FLOAT, RG16Float),
        P(RGBA16_FLOAT, RGBA16Float), P(R32_FLOAT, R32Float), P(RG32_FLOAT, RG32Float), P(RGBA32_FLOAT, RGBA32Float),
        P(RGB10A2_UNORM, RGB10A2Unorm), P(RGB10A2_UINT, RGB10A2Uint), P(RG11B10_FLOAT, RG11B10Float),
        P(D32_FLOAT, Depth32Float), P(D32_FLOAT_S8_UINT, Depth32Float_Stencil8),
        P(D16_UNORM, Depth16Unorm), P(S8_UINT, Stencil8), P(BGRA8_UNORM, BGRA8Unorm)
    };
#undef P
    auto found = formats.find(name);
    if (found == formats.end()) throw std::invalid_argument("Unsupported Metal texture format: " + name);
    return found->second;
}
NSUInteger bytesPerPixel(MTLPixelFormat f) {
    switch (f) {
        case MTLPixelFormatR8Unorm: case MTLPixelFormatR8Snorm: case MTLPixelFormatR8Uint: case MTLPixelFormatR8Sint: case MTLPixelFormatStencil8: return 1;
        case MTLPixelFormatRG8Unorm: case MTLPixelFormatRG8Snorm: case MTLPixelFormatRG8Uint: case MTLPixelFormatRG8Sint:
        case MTLPixelFormatR16Unorm: case MTLPixelFormatR16Snorm: case MTLPixelFormatR16Uint: case MTLPixelFormatR16Sint: case MTLPixelFormatR16Float: case MTLPixelFormatDepth16Unorm: return 2;
        case MTLPixelFormatRGBA8Unorm: case MTLPixelFormatRGBA8Snorm: case MTLPixelFormatRGBA8Uint: case MTLPixelFormatRGBA8Sint: case MTLPixelFormatBGRA8Unorm:
        case MTLPixelFormatRG16Unorm: case MTLPixelFormatRG16Snorm: case MTLPixelFormatRG16Uint: case MTLPixelFormatRG16Sint: case MTLPixelFormatRG16Float:
        case MTLPixelFormatR32Uint: case MTLPixelFormatR32Sint: case MTLPixelFormatR32Float: case MTLPixelFormatDepth32Float:
        case MTLPixelFormatRGB10A2Unorm: case MTLPixelFormatRGB10A2Uint: case MTLPixelFormatRG11B10Float: return 4;
        case MTLPixelFormatRGBA16Unorm: case MTLPixelFormatRGBA16Snorm: case MTLPixelFormatRGBA16Uint: case MTLPixelFormatRGBA16Sint: case MTLPixelFormatRGBA16Float:
        case MTLPixelFormatRG32Uint: case MTLPixelFormatRG32Sint: case MTLPixelFormatRG32Float: case MTLPixelFormatDepth32Float_Stencil8: return 8;
        case MTLPixelFormatRGBA32Uint: case MTLPixelFormatRGBA32Sint: case MTLPixelFormatRGBA32Float: return 16;
        default: throw std::invalid_argument("Unsupported pixel byte size");
    }
}
MTLCompareFunction compareFunction(int value) {
    if (value < 0 || value > 7) throw std::invalid_argument("Invalid Metal depth comparison");
    return static_cast<MTLCompareFunction>(value);
}
MTLPrimitiveType primitiveType(int value) {
    if (value < 0 || value > 4) throw std::invalid_argument("Invalid Metal primitive topology");
    return static_cast<MTLPrimitiveType>(value);
}
}

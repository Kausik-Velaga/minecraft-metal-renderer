#pragma once
#import <Metal/Metal.h>
#include <stdexcept>
#include <string>

namespace metal {
// The bridge consumes stable enum names rather than coupling to enum ordinals.
MTLPixelFormat pixelFormat(const std::string& format);
NSUInteger bytesPerPixel(MTLPixelFormat format);
MTLCompareFunction compareFunction(int value);
MTLPrimitiveType primitiveType(int value);
}

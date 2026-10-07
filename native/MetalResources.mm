#include "MetalContext.hpp"
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>

using namespace metal;
#define NATIVE(name) Java_dev_kausik_metal_MetalNative_##name
static jlong createSamplerState(jlong device, bool repeatU, bool repeatV, bool linearMin, bool linearMag, jint anisotropy, jdouble maxLod, bool comparison) {
    MTLSamplerDescriptor* desc = [MTLSamplerDescriptor new];
    desc.supportArgumentBuffers = YES;
    desc.sAddressMode = repeatU ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeClampToEdge;
    desc.tAddressMode = repeatV ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeClampToEdge;
    desc.rAddressMode = MTLSamplerAddressModeClampToEdge;
    desc.minFilter = linearMin ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
    desc.magFilter = linearMag ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
    desc.mipFilter = linearMin ? MTLSamplerMipFilterLinear : MTLSamplerMipFilterNearest;
    desc.compareFunction = comparison ? MTLCompareFunctionLessEqual : MTLCompareFunctionNever;
    desc.maxAnisotropy = std::clamp<int>(anisotropy, 1, 16);
    desc.lodMaxClamp = std::isfinite(maxLod) ? std::max(0.0, maxLod) : FLT_MAX;
    auto sampler = [get<Device>(device).object newSamplerStateWithDescriptor:desc];
    if (!sampler) throw std::runtime_error("Could not create Metal sampler");
    return retainResource(std::make_unique<Sampler>(sampler));
}
extern "C" {
JNIEXPORT jint JNICALL NATIVE(liveResourceCount)(JNIEnv* env, jclass) { return guarded(env, [&]() -> jint { return static_cast<jint>(metal::liveResourceCount()); }); }
JNIEXPORT jlong JNICALL NATIVE(createDevice)(JNIEnv* env, jclass) {
    return guarded(env, [&]() -> jlong {
        auto device = std::make_unique<Device>(MTLCreateSystemDefaultDevice());
        // Compile the same presentation program during headless GPU verification.
        preparePresentation(*device);
        return retainResource(std::move(device));
    });
}
JNIEXPORT jstring JNICALL NATIVE(deviceName)(JNIEnv* env, jclass, jlong handle) {
    return guarded(env, [&]() -> jstring { return env->NewStringUTF(get<Device>(handle).object.name.UTF8String); });
}
JNIEXPORT void JNICALL NATIVE(destroyDevice)(JNIEnv* env, jclass, jlong handle) { guarded(env, [&] { releaseResource(handle); }); }
JNIEXPORT void JNICALL NATIVE(release)(JNIEnv* env, jclass, jlong handle) { guarded(env, [&] { releaseResource(handle); }); }
JNIEXPORT jlong JNICALL NATIVE(createBuffer)(JNIEnv* env, jclass, jlong device, jlong size, jboolean shared, jstring label) {
    return guarded(env, [&]() -> jlong {
        auto& d = get<Device>(device);
        if (size < 0 || static_cast<uint64_t>(size) > d.object.maxBufferLength) throw std::invalid_argument("Invalid Metal buffer size");
        NSUInteger allocation = (std::max<jlong>(size, 1) + 15) & ~NSUInteger(15);
        id<MTLBuffer> buffer = [d.object newBufferWithLength:allocation options:shared ? MTLResourceStorageModeShared : MTLResourceStorageModePrivate];
        if (!buffer) throw std::runtime_error("Could not allocate Metal buffer");
        d.requireTracked(buffer);
        buffer.label = nsString(env, label);
        if (shared) memset(buffer.contents, 0, allocation);
        else if (allocation > static_cast<NSUInteger>(size) && !d.renderEncoder)
            [d.blit() fillBuffer:buffer range:NSMakeRange(0, allocation) value:0];
        return retainResource(std::make_unique<Buffer>(buffer));
    });
}
JNIEXPORT jobject JNICALL NATIVE(mapBuffer)(JNIEnv* env, jclass, jlong buffer, jlong offset, jlong length) {
    return guarded(env, [&]() -> jobject {
        auto& resource = get<Buffer>(buffer);
        auto b = resource.object;
        validateRange(b.length, offset, length);
        if (b.storageMode != MTLStorageModeShared) throw std::invalid_argument("Cannot map a private Metal buffer");
        // External pointers have no publication/lifetime callback. Never trust an old snapshot
        // after one has escaped, even if this particular caller only intended to read it.
        resource.invalidateCpuIndirect(true);
        return env->NewDirectByteBuffer(static_cast<uint8_t*>(b.contents) + offset, length);
    });
}
JNIEXPORT jboolean JNICALL NATIVE(enableCpuIndirectSnapshots)(JNIEnv* env, jclass, jlong device, jlong buffer) {
    return guarded(env, [&]() -> jboolean {
        auto& b = get<Buffer>(buffer);
        auto& d = get<Device>(device);
        b.cpuIndirectTracking = (d.indirectReuseEnabled || d.cpuIndirectEnabled) &&
            b.object.storageMode == MTLStorageModeShared && !b.cpuIndirectPoisoned;
        return b.cpuIndirectTracking;
    });
}
JNIEXPORT jobject JNICALL NATIVE(mapCpuIndirectBuffer)(JNIEnv* env, jclass, jlong buffer, jlong offset, jlong length) {
    return guarded(env, [&]() -> jobject {
        auto& b = get<Buffer>(buffer);
        validateRange(b.object.length, offset, length);
        if (!b.cpuIndirectTracking) throw std::logic_error("Buffer does not support CPU indirect publication");
        // Mapping grants write access only to this slice. Every overlapping snapshot loses
        // authority immediately, while independently published append ranges remain unchanged.
        b.invalidateCpuIndirectRange(offset, length);
        ++b.cpuIndirectMappings;
        return env->NewDirectByteBuffer(static_cast<uint8_t*>(b.object.contents) + offset, length);
    });
}
JNIEXPORT void JNICALL NATIVE(publishCpuIndirectBuffer)(JNIEnv* env, jclass, jlong device, jlong buffer, jlong offset, jlong length) {
    guarded(env, [&] {
        auto& b = get<Buffer>(buffer);
        validateRange(b.object.length, offset, length);
        if (!b.cpuIndirectTracking || !b.cpuIndirectMappings) throw std::logic_error("No CPU indirect mapping to publish");
        --b.cpuIndirectMappings;
        // Publish only after every mapped view is closed. Earlier closes in a nested mapping
        // scope stay unpublished. GPU destinations/exposed aliases still poison the whole buffer.
        if (b.cpuIndirectPoisoned || b.cpuIndirectMappings || length <= 0 || length > Buffer::MaxPublishedBytes) return;
        auto bytes = static_cast<const uint8_t*>(b.object.contents) + offset;
        auto& d = get<Device>(device);
        d.indirectSnapshotEvicted += b.publishCpuIndirect(offset, bytes, length);
        ++d.indirectSnapshotPublished;
        d.indirectPublishedBytes += length;
    });
}
JNIEXPORT void JNICALL NATIVE(writeBuffer)(JNIEnv* env, jclass, jlong device, jlong buffer, jlong offset, jobject data, jint position, jint length) {
    guarded(env, [&] {
        auto& d = get<Device>(device); auto target = get<Buffer>(buffer).object;
        validateRange(target.length, offset, length);
        if (!length) return;
        get<Buffer>(buffer).invalidateCpuIndirect(true);
        auto source = d.upload(directBytes(env, data, position, length), length);
        [d.blit() copyFromBuffer:source.buffer sourceOffset:source.offset toBuffer:target destinationOffset:offset size:length];
    });
}
JNIEXPORT void JNICALL NATIVE(copyBuffer)(JNIEnv* env, jclass, jlong device, jlong source, jlong sourceOffset, jlong target, jlong targetOffset, jlong length) {
    guarded(env, [&] {
        auto& d = get<Device>(device); auto src = get<Buffer>(source).object; auto dst = get<Buffer>(target).object;
        validateRange(src.length, sourceOffset, length); validateRange(dst.length, targetOffset, length);
        if (length) {
            get<Buffer>(target).invalidateCpuIndirect(true);
            [d.blit() copyFromBuffer:src sourceOffset:sourceOffset toBuffer:dst destinationOffset:targetOffset size:length];
        }
    });
}
JNIEXPORT jlong JNICALL NATIVE(createTexture)(JNIEnv* env, jclass, jlong device, jstring format, jint width, jint height, jint layers, jint mips, jint usage, jstring label) {
    return guarded(env, [&]() -> jlong {
        auto& d = get<Device>(device);
        if (width <= 0 || height <= 0 || layers <= 0 || mips <= 0) throw std::invalid_argument("Invalid Metal texture dimensions");
        MTLTextureDescriptor* desc = [MTLTextureDescriptor new];
        desc.pixelFormat = pixelFormat(utf8(env, format));
        desc.width = width; desc.height = height; desc.mipmapLevelCount = mips;
        if (usage & 16) {
            if (layers % 6 || width != height) throw std::invalid_argument("Cubemap textures need square faces and a multiple of six layers");
            desc.textureType = layers == 6 ? MTLTextureTypeCube : MTLTextureTypeCubeArray;
            desc.arrayLength = layers / 6;
        } else {
            desc.textureType = layers > 1 ? MTLTextureType2DArray : MTLTextureType2D;
            desc.arrayLength = layers;
        }
        desc.storageMode = MTLStorageModePrivate;
        // Every view we expose preserves the component layout. Requesting reinterpretation
        // unnecessarily disables Apple's lossless texture compression. Keep a control switch
        // for equal-quality benchmark comparisons; actual texture usage remains explicit.
        static const bool formatViews = [] {
            const char* value = std::getenv("MINECRAFT_METAL_TEXTURE_FORMAT_VIEWS");
            return value && std::strcmp(value, "1") == 0;
        }();
        desc.usage = formatViews ? MTLTextureUsagePixelFormatView : MTLTextureUsageUnknown;
        if (usage & 4) desc.usage |= MTLTextureUsageShaderRead;
        if (usage & 8) desc.usage |= MTLTextureUsageRenderTarget;
        id<MTLTexture> texture = [d.object newTextureWithDescriptor:desc];
        if (!texture) throw std::runtime_error("Could not allocate Metal texture");
        d.requireTracked(texture);
        texture.label = nsString(env, label);
        return retainResource(std::make_unique<Texture>(texture));
    });
}
JNIEXPORT jlong JNICALL NATIVE(createTextureView)(JNIEnv* env, jclass, jlong texture, jint baseMip, jint mipCount) {
    return guarded(env, [&]() -> jlong {
        auto t = get<Texture>(texture).object;
        validateRange(t.mipmapLevelCount, baseMip, mipCount);
        NSUInteger slices = t.arrayLength * ((t.textureType == MTLTextureTypeCube || t.textureType == MTLTextureTypeCubeArray) ? 6 : 1);
        id<MTLTexture> view = [t newTextureViewWithPixelFormat:t.pixelFormat textureType:t.textureType levels:NSMakeRange(baseMip, mipCount) slices:NSMakeRange(0, slices)];
        if (!view) throw std::runtime_error("Could not create Metal texture view");
        // Views share their parent's tracked backing allocation.
        if (view.hazardTrackingMode != t.hazardTrackingMode)
            throw std::logic_error("Metal texture view changed hazard tracking mode");
        return retainResource(std::make_unique<Texture>(view));
    });
}
JNIEXPORT void JNICALL NATIVE(generateMipmaps)(JNIEnv* env, jclass, jlong device, jlong texture, jint levels) {
    guarded(env, [&] {
        auto& d = get<Device>(device);
        auto target = get<Texture>(texture).object;
        if (levels < 1 || static_cast<NSUInteger>(levels) > target.mipmapLevelCount)
            throw std::invalid_argument("Invalid native mip generation level count");
        if (levels <= 1) return;
        if (static_cast<NSUInteger>(levels) < target.mipmapLevelCount) {
            NSUInteger slices = target.arrayLength * ((target.textureType == MTLTextureTypeCube || target.textureType == MTLTextureTypeCubeArray) ? 6 : 1);
            target = [target newTextureViewWithPixelFormat:target.pixelFormat textureType:target.textureType levels:NSMakeRange(0, levels) slices:NSMakeRange(0, slices)];
            if (!target) throw std::runtime_error("Could not create native mip generation view");
        }
        d.requireTracked(target);
        [d.blit() generateMipmapsForTexture:target];
    });
}
JNIEXPORT jlong JNICALL NATIVE(createSampler)(JNIEnv* env, jclass, jlong device, jboolean repeatU, jboolean repeatV, jboolean linearMin, jboolean linearMag, jint anisotropy, jdouble maxLod) {
    return guarded(env, [&]() -> jlong { return createSamplerState(device, repeatU, repeatV, linearMin, linearMag, anisotropy, maxLod, false); });
}
JNIEXPORT jlong JNICALL NATIVE(createComparisonSampler)(JNIEnv* env, jclass, jlong device, jboolean repeatU, jboolean repeatV, jboolean linearMin, jboolean linearMag, jint anisotropy, jdouble maxLod) {
    return guarded(env, [&]() -> jlong { return createSamplerState(device, repeatU, repeatV, linearMin, linearMag, anisotropy, maxLod, true); });
}
JNIEXPORT void JNICALL NATIVE(uploadTexture)(JNIEnv* env, jclass, jlong device, jlong texture, jobject data, jint position, jint mip, jint layer, jint x, jint y, jint width, jint height) {
    guarded(env, [&] {
        auto& d = get<Device>(device); auto target = get<Texture>(texture).object;
        if (width <= 0 || height <= 0) return;
        NSUInteger rowBytes = width * bytesPerPixel(target.pixelFormat);
        NSUInteger rowAligned = (rowBytes + 255) & ~NSUInteger(255);
        auto bytes = static_cast<const uint8_t*>(directBytes(env, data, position, rowBytes * height));
        auto upload = d.upload(nullptr, rowAligned * height);
        auto staging = static_cast<uint8_t*>(upload.buffer.contents) + upload.offset;
        for (int row=0; row<height; ++row) memcpy(staging + row * rowAligned, bytes + row * rowBytes, rowBytes);
        [d.blit() copyFromBuffer:upload.buffer sourceOffset:upload.offset sourceBytesPerRow:rowAligned sourceBytesPerImage:rowAligned * height
            sourceSize:MTLSizeMake(width, height, 1) toTexture:target destinationSlice:layer destinationLevel:mip destinationOrigin:MTLOriginMake(x,y,0)];
    });
}
JNIEXPORT void JNICALL NATIVE(bufferToTexture)(JNIEnv* env, jclass, jlong device, jlong buffer, jlong offset, jint bytesPerRow, jint rowsPerImage, jlong texture, jint mip, jint layer, jint x, jint y, jint width, jint height) {
    guarded(env, [&] {
        auto& d = get<Device>(device); auto source = get<Buffer>(buffer).object; auto target = get<Texture>(texture).object;
        if (width <= 0 || height <= 0) return;
        NSUInteger rowBytes = width * bytesPerPixel(target.pixelFormat);
        NSUInteger pitch = bytesPerRow ? bytesPerRow : rowBytes;
        validateRange(source.length, offset, (height - 1) * pitch + rowBytes);
        [d.blit() copyFromBuffer:source sourceOffset:offset sourceBytesPerRow:pitch sourceBytesPerImage:pitch * (rowsPerImage ? rowsPerImage : height)
            sourceSize:MTLSizeMake(width,height,1) toTexture:target destinationSlice:layer destinationLevel:mip destinationOrigin:MTLOriginMake(x,y,0)];
    });
}
JNIEXPORT void JNICALL NATIVE(textureToBuffer)(JNIEnv* env, jclass, jlong device, jlong texture, jint mip, jint x, jint y, jint width, jint height, jlong buffer, jlong offset) {
    guarded(env, [&] {
        auto& d = get<Device>(device); auto source = get<Texture>(texture).object; auto target = get<Buffer>(buffer).object;
        if (width <= 0 || height <= 0) return;
        NSUInteger rowBytes = width * bytesPerPixel(source.pixelFormat);
        validateRange(target.length, offset, rowBytes * height);
        get<Buffer>(buffer).invalidateCpuIndirect(true);
        [d.blit() copyFromTexture:source sourceSlice:0 sourceLevel:mip sourceOrigin:MTLOriginMake(x,y,0) sourceSize:MTLSizeMake(width,height,1)
            toBuffer:target destinationOffset:offset destinationBytesPerRow:rowBytes destinationBytesPerImage:rowBytes*height];
    });
}
JNIEXPORT void JNICALL NATIVE(copyTexture)(JNIEnv* env, jclass, jlong device, jlong source, jlong target, jint mip, jint sourceX, jint sourceY, jint targetX, jint targetY, jint width, jint height) {
    guarded(env, [&] {
        if (width <= 0 || height <= 0) return;
        auto& d = get<Device>(device);
        [d.blit() copyFromTexture:get<Texture>(source).object sourceSlice:0 sourceLevel:mip sourceOrigin:MTLOriginMake(sourceX,sourceY,0) sourceSize:MTLSizeMake(width,height,1)
            toTexture:get<Texture>(target).object destinationSlice:0 destinationLevel:mip destinationOrigin:MTLOriginMake(targetX,targetY,0)];
    });
}
}

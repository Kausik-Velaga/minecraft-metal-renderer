#pragma once
#import <Cocoa/Cocoa.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#include <jni.h>
#include <atomic>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include "MetalFormats.hpp"

namespace metal {
struct Resource { virtual ~Resource() = default; };
jlong retainResource(std::unique_ptr<Resource> object);
void releaseResource(jlong handle);
size_t liveResourceCount();
Resource* resource(jlong handle);
template<typename T> T& get(jlong handle) {
    T* object = dynamic_cast<T*>(resource(handle));
    if (!object) throw std::invalid_argument("Metal resource handle has the wrong type");
    return *object;
}
std::string utf8(JNIEnv* env, jstring value);
NSString* nsString(JNIEnv* env, jstring value);
void* directBytes(JNIEnv* env, jobject buffer, jlong offset, jlong size);
void validateRange(NSUInteger total, jlong offset, jlong length);
void throwJava(JNIEnv* env, const std::string& message);
template<typename F> auto guarded(JNIEnv* env, F&& fn) -> decltype(fn()) {
    using R = decltype(fn());
    @autoreleasepool {
        @try {
            try { return fn(); }
            catch (const std::exception& e) { throwJava(env, e.what()); }
        } @catch (NSException* exception) {
            throwJava(env, std::string(exception.name.UTF8String) + ": " + exception.reason.UTF8String);
        }
    }
    if constexpr (!std::is_void_v<R>) return R{};
}
struct Buffer final : Resource {
    id<MTLBuffer> object;
    std::unordered_map<std::string, id<MTLTexture>> texelViews;
    explicit Buffer(id<MTLBuffer> value) : object(value) {}
};
struct Texture final : Resource {
    id<MTLTexture> object;
    explicit Texture(id<MTLTexture> value) : object(value) {}
};
struct Sampler final : Resource {
    id<MTLSamplerState> object;
    explicit Sampler(id<MTLSamplerState> value) : object(value) {}
};
struct Pipeline final : Resource {
    MTLRenderPipelineDescriptor* descriptor;
    id<MTLFunction> fragmentWithoutDepth;
    id<MTLDepthStencilState> depthState;
    id<MTLDepthStencilState> noDepthState;
    std::unordered_map<uint64_t, id<MTLRenderPipelineState>> variants;
    bool cull = false, wireframe = false;
    float depthBias = 0, depthSlope = 0;
    id<MTLRenderPipelineState> state(id<MTLDevice> device, MTLPixelFormat depthFormat, const std::vector<MTLPixelFormat>& colorFormats);
};
struct QueryState {
    id<MTLCounterSampleBuffer> samples[3];
    std::mutex mutex;
    std::vector<int64_t> values;
    std::vector<uint64_t> generations;
    explicit QueryState(size_t size) : values(size, -1), generations(size, 0) {}
};
struct QueryPool final : Resource {
    std::shared_ptr<QueryState> state;
};
struct QueryWrite { NSUInteger index; uint64_t generation; };
struct PendingQueries { std::shared_ptr<QueryState> state; std::vector<QueryWrite> writes; id<MTLCounterSampleBuffer> samples; };
struct UploadFrame {
    dispatch_semaphore_t available;
    std::vector<id<MTLBuffer>> buffers;
    size_t cursor = 0;
    NSUInteger offset = 0;
};
struct UploadSlice { id<MTLBuffer> buffer; NSUInteger offset; };
struct RenderState {
    id<MTLRenderPipelineState> pipeline;
    id<MTLDepthStencilState> depth;
    MTLCullMode cull = MTLCullModeNone;
    MTLTriangleFillMode fill = MTLTriangleFillModeFill;
    float bias = 0, slope = 0;
    id<MTLBuffer> vertexBuffers[31] = {};
    id<MTLBuffer> fragmentBuffers[31] = {};
    NSUInteger vertexOffsets[31] = {}, fragmentOffsets[31] = {};
    id<MTLTexture> vertexTextures[128] = {}, fragmentTextures[128] = {};
    id<MTLSamplerState> vertexSamplers[16] = {}, fragmentSamplers[16] = {};
    MTLScissorRect scissor = {0,0,1,1};
    std::vector<NSString*> debugGroups;
};
struct FenceState;
struct Device final : Resource {
    id<MTLDevice> object;
    id<MTLCommandQueue> queue;
    id<MTLCommandBuffer> command;
    id<MTLCommandBuffer> lastSubmitted;
    id<MTLBlitCommandEncoder> blitEncoder;
    id<MTLRenderCommandEncoder> renderEncoder;
    MTLRenderPassDescriptor* renderDescriptor;
    RenderState renderState;
    id<MTLBuffer> timestampScratch;
    id<MTLFence> encoderFence;
    bool hasEncoderFence = false;
    MTLPixelFormat depthFormat = MTLPixelFormatInvalid;
    std::vector<MTLPixelFormat> colorFormats;
    NSUInteger renderWidth = 0, renderHeight = 0;
    bool emptyScissor = false;
    uint64_t serial = 0;
    UploadFrame uploads[3];
    UploadFrame* currentUploads = nullptr;
    std::vector<PendingQueries> pendingQueries;
    std::vector<std::shared_ptr<FenceState>> pendingFences;
    id<MTLLibrary> clearLibrary;
    std::unordered_map<uint64_t, id<MTLRenderPipelineState>> clearPipelines;
    id<MTLRenderPipelineState> presentationPipeline;
    id<MTLSamplerState> presentationSampler;
    explicit Device(id<MTLDevice> value);
    ~Device() override;
    id<MTLCommandBuffer> commands();
    id<MTLBlitCommandEncoder> blit();
    void endBlit();
    void endRender();
    void suspendRender();
    void resumeRender();
    void submit();
    void requireRender();
    void waitForRenderFence(id<MTLRenderCommandEncoder> encoder);
    void updateRenderFence(id<MTLRenderCommandEncoder> encoder);
    void waitForBlitFence(id<MTLBlitCommandEncoder> encoder);
    void updateBlitFence(id<MTLBlitCommandEncoder> encoder);
    UploadSlice upload(const void* bytes, NSUInteger size, NSUInteger alignment = 256);
};
struct Surface final : Resource {
    // SDL owns the NSView; this wrapper retains its layer only for its own lifetime.
    CAMetalLayer* layer;
    id<CAMetalDrawable> drawable;
};
struct FenceState {
    std::mutex mutex;
    std::condition_variable condition;
    bool completed = false;
    std::string error;
};
struct Fence final : Resource { std::shared_ptr<FenceState> state; id<MTLCommandBuffer> command; };
void preparePresentation(Device& device);
void beginPass(Device& device, NSString* label, const std::vector<id<MTLTexture>>& colors,
    const std::vector<float>& clears, id<MTLTexture> depth, double clearDepth,
    int x, int y, int width, int height);
void setScissor(Device& device, int x, int y, int width, int height);
}

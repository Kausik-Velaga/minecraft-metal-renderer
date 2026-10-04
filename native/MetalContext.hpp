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
#include "MetalStageTimings.hpp"

namespace metal {
struct Resource { virtual ~Resource() = default; };
jlong retainResource(std::unique_ptr<Resource> object);
void releaseResource(jlong handle);
size_t liveResourceCount();
uint64_t monotonicNanos();
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
    bool cpuIndirectTracking = false, cpuIndirectPoisoned = false;
    unsigned cpuIndirectMappings = 0;
    struct PublishedRange { NSUInteger offset; std::vector<uint8_t> bytes; };
    static constexpr size_t MaxPublishedRanges = 64, MaxPublishedBytes = 8 * 1024 * 1024;
    std::vector<PublishedRange> cpuIndirectRanges;
    size_t cpuIndirectRangeBytes = 0;
    void invalidateCpuIndirect(bool permanently) {
        cpuIndirectRanges.clear();
        cpuIndirectRangeBytes = 0;
        cpuIndirectPoisoned |= permanently;
    }
    void invalidateCpuIndirectRange(NSUInteger offset, NSUInteger length) {
        if (!length) return;
        for (auto it = cpuIndirectRanges.begin(); it != cpuIndirectRanges.end();) {
            if (offset < it->offset + it->bytes.size() && it->offset < offset + length) {
                cpuIndirectRangeBytes -= it->bytes.size();
                it = cpuIndirectRanges.erase(it);
            } else ++it;
        }
    }
    const uint8_t* publishedCpuIndirect(NSUInteger offset, NSUInteger length) const {
        for (const auto& range : cpuIndirectRanges)
            if (offset >= range.offset && offset - range.offset <= range.bytes.size() &&
                length <= range.bytes.size() - (offset - range.offset))
                return range.bytes.data() + offset - range.offset;
        return nullptr;
    }
    size_t publishCpuIndirect(NSUInteger offset, const uint8_t* bytes, NSUInteger length) {
        // No stitching or partial retention of overlapping publications: a draw must fit in
        // one immutable map-close snapshot. Disjoint camera/shadow append ranges stay valid.
        invalidateCpuIndirectRange(offset, length);
        if (!length || length > MaxPublishedBytes) return 0;
        size_t evicted = 0;
        while (!cpuIndirectRanges.empty() &&
               (cpuIndirectRanges.size() >= MaxPublishedRanges || cpuIndirectRangeBytes + length > MaxPublishedBytes)) {
            cpuIndirectRangeBytes -= cpuIndirectRanges.front().bytes.size();
            cpuIndirectRanges.erase(cpuIndirectRanges.begin());
            ++evicted;
        }
        cpuIndirectRanges.push_back({offset, std::vector<uint8_t>(bytes, bytes + length)});
        cpuIndirectRangeBytes += length;
        return evicted;
    }
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
struct TextureArguments {
    id<MTLArgumentEncoder> encoder;
    std::vector<NSUInteger> textures, samplers, residentTextures;
};
struct Pipeline final : Resource {
    MTLRenderPipelineDescriptor* descriptor;
    int vertexMathMode = 0, fragmentMathMode = 0;
    TextureArguments vertexArguments, fragmentArguments;
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
struct IndirectBatch {
    id<MTLIndirectCommandBuffer> commands;
    id<MTLBuffer> arguments;
    NSUInteger capacity = 0;
    // Immutable while submitted. Ring reuse is gated by GPU completion, just like uploads.
    std::vector<uint8_t> cachedParameters;
    id<MTLBuffer> cachedIndices;
    MTLPrimitiveType cachedTopology = MTLPrimitiveTypeTriangle;
    bool cachedIndex32 = false;
};
struct UploadFrame {
    dispatch_semaphore_t available;
    std::vector<id<MTLBuffer>> buffers;
    size_t cursor = 0;
    NSUInteger offset = 0;
    std::vector<IndirectBatch> indirectBatches;
    size_t indirectCursor = 0;
    StageTimingFrame stageTimings;
};
struct UploadSlice { id<MTLBuffer> buffer; NSUInteger offset; };
struct RenderState {
    TextureArguments vertexArguments, fragmentArguments;
    bool textureArgumentsDirty = true, textureArgumentsResident = false;
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
// CPU-only telemetry. Completion handlers and surfaces can outlive their Device wrapper.
// The GPU tuple is locked during publication/snapshot; independent CPU counters stay atomic.
struct ExecutionStatistics {
    std::mutex gpuMutex;
    std::atomic<uint64_t> submittedBuffers{0}, completedBuffers{0}, timedBuffers{0};
    std::atomic<uint64_t> gpuSpanNanos{0}, failedBuffers{0}, unavailableGpuTimes{0};
    std::atomic<uint64_t> uploadRingAcquires{0}, uploadRingAcquireNanos{0}, uploadRingBlockedAcquires{0};
    std::atomic<uint64_t> drawableAcquires{0}, drawableAcquireNanos{0}, drawableTimeouts{0};
    std::atomic<uint64_t> fenceWaitCalls{0}, fenceWaitBlockedCalls{0}, fenceWaitNanos{0};
};
struct Device final : Resource {
    id<MTLDevice> object;
    id<MTLCommandQueue> queue;
    id<MTLCommandBuffer> command;
    id<MTLCommandBuffer> lastSubmitted;
    std::shared_ptr<ExecutionStatistics> executionStats = std::make_shared<ExecutionStatistics>();
    std::shared_ptr<StageTimingStatistics> stageTimingStats = std::make_shared<StageTimingStatistics>();
    id<MTLBlitCommandEncoder> blitEncoder;
    id<MTLRenderCommandEncoder> renderEncoder;
    MTLRenderPassDescriptor* renderDescriptor;
    NSString* renderLabel;
    RenderState renderState;
    id<MTLBuffer> timestampScratch;
    id<MTLFence> encoderFence;
    bool hasEncoderFence = false;
    bool trackedHazardsRequested = false, timestampFencesRequired = false;
    id<MTLEvent> timestampOrderingEvent;
    MTLPixelFormat depthFormat = MTLPixelFormatInvalid;
    std::vector<MTLPixelFormat> colorFormats;
    NSUInteger renderWidth = 0, renderHeight = 0;
    bool emptyScissor = false;
    bool renderEncoderHasDrawn = false;
    uint64_t serial = 0;
    UploadFrame uploads[3];
    UploadFrame* currentUploads = nullptr;
    std::vector<PendingQueries> pendingQueries;
    std::vector<std::shared_ptr<FenceState>> pendingFences;
    bool indirectEnabled = false, indirectSupported = false;
    bool indirectReuseEnabled = false;
    bool cpuIndirectEnabled = false;
    NSUInteger indirectThreshold = 64;
    uint64_t indirectExecutions = 0;
    uint64_t indirectFirstDrawSplits = 0, indirectAfterDrawSplits = 0;
    uint64_t indirectFirstDrawAttachmentBytes = 0, indirectAfterDrawAttachmentBytes = 0;
    uint64_t indirectReuseEligible = 0, indirectReuseHits = 0, indirectReuseMisses = 0;
    uint64_t indirectReuseIneligible = 0, indirectPublishedBytes = 0;
    uint64_t cpuIndirectBatches = 0, cpuIndirectCommands = 0, cpuIndirectPopulateNanos = 0;
    uint64_t indirectSnapshotCovered = 0, indirectSnapshotUntracked = 0, indirectSnapshotPoisoned = 0;
    uint64_t indirectSnapshotMapped = 0, indirectSnapshotUncovered = 0;
    uint64_t indirectSnapshotEvicted = 0, indirectSnapshotPublished = 0;
    uint64_t discardedLoadPasses = 0, discardedAttachmentLoads = 0;
    id<MTLComputePipelineState> indirectEncoderPipeline;
    id<MTLArgumentEncoder> indirectArgumentEncoder;
    id<MTLLibrary> clearLibrary;
    std::unordered_map<uint64_t, id<MTLRenderPipelineState>> clearPipelines;
    id<MTLComputePipelineState> triangleFanPipeline;
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
    void waitForComputeFence(id<MTLComputeCommandEncoder> encoder);
    void updateComputeFence(id<MTLComputeCommandEncoder> encoder);
    bool usesGlobalEncoderFences() const;
    void requireTimestampOrdering();
    void requireTracked(id<MTLResource> resource) const;
    UploadSlice upload(const void* bytes, NSUInteger size, NSUInteger alignment = 256);
};
struct Surface final : Resource {
    // SDL owns the NSView; this wrapper retains its layer only for its own lifetime.
    CAMetalLayer* layer;
    id<CAMetalDrawable> drawable;
    std::shared_ptr<ExecutionStatistics> executionStats;
    // Explicit throughput diagnostic: retain final compositing, omit display presentation.
    bool offscreenPresent = false, offscreenAcquired = false;
    std::vector<id<MTLTexture>> offscreenTargets;
    size_t offscreenIndex = 0;
};
struct FenceState {
    std::mutex mutex;
    std::condition_variable condition;
    bool completed = false;
    std::string error;
};
struct Fence final : Resource {
    std::shared_ptr<FenceState> state;
    std::shared_ptr<ExecutionStatistics> executionStats;
    id<MTLCommandBuffer> command;
};
void preparePresentation(Device& device);
void beginPass(Device& device, NSString* label, const std::vector<id<MTLTexture>>& colors,
    const std::vector<float>& clears, id<MTLTexture> depth, double clearDepth,
    int x, int y, int width, int height, int discardColorMask = 0);
void setScissor(Device& device, int x, int y, int width, int height);
}

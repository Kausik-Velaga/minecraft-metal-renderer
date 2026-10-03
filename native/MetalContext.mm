#include "MetalContext.hpp"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <limits>

namespace metal {
static std::mutex registryMutex;
static std::unordered_map<jlong, std::unique_ptr<Resource>> registry;
static std::atomic<jlong> nextHandle{1};
jlong retainResource(std::unique_ptr<Resource> object) {
    std::lock_guard lock(registryMutex);
    jlong handle = nextHandle.fetch_add(1);
    registry.emplace(handle, std::move(object));
    return handle;
}
size_t liveResourceCount() {
    std::lock_guard lock(registryMutex);
    return registry.size();
}
Resource* resource(jlong handle) {
    std::lock_guard lock(registryMutex);
    auto found = registry.find(handle);
    if (found == registry.end()) throw std::invalid_argument("Invalid or already released Metal resource handle");
    return found->second.get();
}
void releaseResource(jlong handle) {
    if (!handle) return;
    std::unique_ptr<Resource> removed;
    {
        std::lock_guard lock(registryMutex);
        auto found = registry.find(handle);
        if (found == registry.end()) throw std::invalid_argument("Metal resource already released");
        removed = std::move(found->second);
        registry.erase(found);
    }
}
std::string utf8(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* text = env->GetStringUTFChars(value, nullptr);
    if (!text) throw std::runtime_error("Could not read Java string");
    std::string result(text);
    env->ReleaseStringUTFChars(value, text);
    return result;
}
NSString* nsString(JNIEnv* env, jstring value) { return [NSString stringWithUTF8String:utf8(env, value).c_str()]; }
void validateRange(NSUInteger total, jlong offset, jlong length) {
    if (offset < 0 || length < 0 || static_cast<uint64_t>(offset) > total || static_cast<uint64_t>(length) > total - static_cast<uint64_t>(offset))
        throw std::out_of_range("Metal buffer range is out of bounds");
}
void* directBytes(JNIEnv* env, jobject buffer, jlong offset, jlong size) {
    auto capacity = env->GetDirectBufferCapacity(buffer);
    auto bytes = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (capacity < 0 || !bytes) throw std::invalid_argument("Metal upload requires a direct ByteBuffer");
    validateRange(static_cast<NSUInteger>(capacity), offset, size);
    return bytes + offset;
}
void throwJava(JNIEnv* env, const std::string& message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
}
Device::Device(id<MTLDevice> value) : object(value) {
    if (!object) throw std::runtime_error("No Apple Metal GPU is available");
    queue = [object newCommandQueue];
    if (!queue) throw std::runtime_error("Unable to create Metal command queue");
    queue.label = @"Minecraft Metal queue";
    for (auto& frame : uploads) frame.available = dispatch_semaphore_create(1);
    encoderFence = [object newFence];
    if (!encoderFence) throw std::runtime_error("Could not create Metal encoder ordering fence");
}
Device::~Device() {
    @autoreleasepool {
        @try {
            try {
                endRender();
                submit();
                if (lastSubmitted) [lastSubmitted waitUntilCompleted];
            } catch (const std::exception& error) {
                // Destructors cannot throw: a lost GPU must not terminate the JVM during cleanup.
                fprintf(stderr, "[Minecraft Metal] Device shutdown: %s\n", error.what());
            }
        } @catch (NSException* error) {
            fprintf(stderr, "[Minecraft Metal] Device shutdown: %s\n", error.reason.UTF8String);
        }
    }
}
id<MTLCommandBuffer> Device::commands() {
    if (!command) {
        currentUploads = &uploads[serial % 3];
        if (dispatch_semaphore_wait(currentUploads->available, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC)))
            throw std::runtime_error("Metal command queue did not complete a submission within 10 seconds");
        command = [queue commandBuffer];
        if (!command) {
            dispatch_semaphore_signal(currentUploads->available);
            throw std::runtime_error("Could not allocate Metal command buffer");
        }
        hasEncoderFence = false;
        currentUploads->cursor = 0;
        currentUploads->offset = 0;
        command.label = [NSString stringWithFormat:@"Minecraft submission %llu", ++serial];
    }
    return command;
}
UploadSlice Device::upload(const void* bytes, NSUInteger size, NSUInteger alignment) {
    commands();
    auto& frame = *currentUploads;
    NSUInteger offset = (frame.offset + alignment - 1) / alignment * alignment;
    if (frame.cursor < frame.buffers.size() && offset + size > frame.buffers[frame.cursor].length) {
        ++frame.cursor; offset = 0;
    }
    if (frame.cursor == frame.buffers.size()) {
        id<MTLBuffer> buffer = [object newBufferWithLength:std::max<NSUInteger>(4*1024*1024, size) options:MTLResourceStorageModeShared];
        if (!buffer) throw std::runtime_error("Could not allocate transient Metal upload buffer");
        buffer.label = @"Minecraft transient upload ring";
        frame.buffers.push_back(buffer);
    } else if (size > frame.buffers[frame.cursor].length) {
        frame.buffers[frame.cursor] = [object newBufferWithLength:size options:MTLResourceStorageModeShared];
        if (!frame.buffers[frame.cursor]) throw std::runtime_error("Could not grow transient Metal upload buffer");
    }
    auto buffer = frame.buffers[frame.cursor];
    if (bytes && size) memcpy(static_cast<uint8_t*>(buffer.contents) + offset, bytes, size);
    frame.offset = offset + size;
    return {buffer, offset};
}
id<MTLBlitCommandEncoder> Device::blit() {
    if (renderEncoder) throw std::logic_error("Cannot blit while a render pass is open");
    if (!blitEncoder) {
        blitEncoder = [commands() blitCommandEncoder];
        waitForBlitFence(blitEncoder);
    }
    return blitEncoder;
}
void Device::endBlit() { if (blitEncoder) { updateBlitFence(blitEncoder); [blitEncoder endEncoding]; blitEncoder = nil; } }
void Device::endRender() { if (renderEncoder) { updateRenderFence(renderEncoder); [renderEncoder endEncoding]; renderEncoder = nil; } }
void Device::waitForRenderFence(id<MTLRenderCommandEncoder> encoder) {
    if (hasEncoderFence) [encoder waitForFence:encoderFence beforeStages:MTLRenderStageVertex | MTLRenderStageFragment];
}
void Device::updateRenderFence(id<MTLRenderCommandEncoder> encoder) {
    [encoder updateFence:encoderFence afterStages:MTLRenderStageVertex | MTLRenderStageFragment];
    hasEncoderFence = true;
}
void Device::waitForBlitFence(id<MTLBlitCommandEncoder> encoder) {
    if (hasEncoderFence) [encoder waitForFence:encoderFence];
}
void Device::updateBlitFence(id<MTLBlitCommandEncoder> encoder) {
    [encoder updateFence:encoderFence];
    hasEncoderFence = true;
}
void Device::suspendRender() {
    requireRender();
    for (size_t i=0; i<renderState.debugGroups.size(); ++i) [renderEncoder popDebugGroup];
    endRender();
}
void Device::resumeRender() {
    endBlit();
    MTLRenderPassDescriptor* desc = [renderDescriptor copy];
    for (NSUInteger i=0; i<8; ++i) if (desc.colorAttachments[i].texture) desc.colorAttachments[i].loadAction = MTLLoadActionLoad;
    if (desc.depthAttachment.texture) desc.depthAttachment.loadAction = MTLLoadActionLoad;
    renderEncoder = [commands() renderCommandEncoderWithDescriptor:desc];
    if (!renderEncoder) throw std::runtime_error("Could not resume Metal render pass");
    waitForRenderFence(renderEncoder);
    [renderEncoder setViewport:MTLViewport{0,0,static_cast<double>(renderWidth),static_cast<double>(renderHeight),0,1}];
    [renderEncoder setFrontFacingWinding:MTLWindingClockwise];
    auto& s = renderState;
    if (s.pipeline) [renderEncoder setRenderPipelineState:s.pipeline];
    if (s.depth) [renderEncoder setDepthStencilState:s.depth];
    [renderEncoder setCullMode:s.cull]; [renderEncoder setTriangleFillMode:s.fill];
    [renderEncoder setDepthBias:s.bias slopeScale:s.slope clamp:0];
    if (!emptyScissor) [renderEncoder setScissorRect:s.scissor];
    for (NSUInteger i=0; i<31; ++i) {
        if (s.vertexBuffers[i]) [renderEncoder setVertexBuffer:s.vertexBuffers[i] offset:s.vertexOffsets[i] atIndex:i];
        if (s.fragmentBuffers[i]) [renderEncoder setFragmentBuffer:s.fragmentBuffers[i] offset:s.fragmentOffsets[i] atIndex:i];
    }
    for (NSUInteger i=0; i<128; ++i) {
        if (s.vertexTextures[i]) [renderEncoder setVertexTexture:s.vertexTextures[i] atIndex:i];
        if (s.fragmentTextures[i]) [renderEncoder setFragmentTexture:s.fragmentTextures[i] atIndex:i];
    }
    for (NSUInteger i=0; i<16; ++i) {
        if (s.vertexSamplers[i]) [renderEncoder setVertexSamplerState:s.vertexSamplers[i] atIndex:i];
        if (s.fragmentSamplers[i]) [renderEncoder setFragmentSamplerState:s.fragmentSamplers[i] atIndex:i];
    }
    for (NSString* label : s.debugGroups) [renderEncoder pushDebugGroup:label];
}
void Device::requireRender() { if (!renderEncoder) throw std::logic_error("No active Metal render pass"); }
void Device::submit() {
    if (!command) return;
    if (renderEncoder) throw std::logic_error("Cannot submit while a render pass is open");
    auto queryCopies = pendingQueries;
    pendingQueries.clear();
    endBlit();
    auto fenceCopies = pendingFences;
    pendingFences.clear();
    dispatch_semaphore_t completion = currentUploads->available;
    [command addCompletedHandler:^(id<MTLCommandBuffer> completed) {
        @autoreleasepool {
            if (completed.status == MTLCommandBufferStatusError)
                fprintf(stderr, "[Minecraft Metal] GPU submission failed: %s\n", completed.error.localizedDescription.UTF8String);
            for (auto& pending : queryCopies) {
                // Resolve shared counters after GPU completion. A GPU blit resolve may observe
                // stale end-of-encoder samples on Apple tile-based GPUs.
                NSData* data = [pending.samples resolveCounterRange:NSMakeRange(0, pending.state->values.size())];
                auto values = static_cast<const MTLCounterResultTimestamp*>(data.bytes);
                std::lock_guard lock(pending.state->mutex);
                for (auto write : pending.writes) {
                    if (pending.state->generations[write.index] != write.generation) continue;
                    bool valid = completed.status == MTLCommandBufferStatusCompleted &&
                        data.length >= (write.index + 1) * sizeof(MTLCounterResultTimestamp) &&
                        values[write.index].timestamp != MTLCounterErrorValue;
                    pending.state->values[write.index] = valid
                        ? static_cast<int64_t>(values[write.index].timestamp) : -1;
                }
            }
            for (auto& fence : fenceCopies) {
                {
                    std::lock_guard lock(fence->mutex);
                    fence->completed = true;
                    if (completed.error) fence->error = completed.error.localizedDescription.UTF8String;
                }
                fence->condition.notify_all();
            }
            dispatch_semaphore_signal(completion);
        }
    }];
    [command commit];
    lastSubmitted = command;
    command = nil;
}
id<MTLRenderPipelineState> Pipeline::state(id<MTLDevice> device, MTLPixelFormat depthFormat, const std::vector<MTLPixelFormat>& colorFormats) {
    uint64_t key = depthFormat;
    for (auto format : colorFormats) key = key * 1315423911ULL + format;
    auto found = variants.find(key);
    if (found != variants.end()) return found->second;
    MTLRenderPipelineDescriptor* variant = [descriptor copy];
    variant.depthAttachmentPixelFormat = depthFormat;
    if (depthFormat == MTLPixelFormatInvalid && fragmentWithoutDepth)
        variant.fragmentFunction = fragmentWithoutDepth;
    if (depthFormat == MTLPixelFormatDepth32Float_Stencil8) variant.stencilAttachmentPixelFormat = depthFormat;
    for (NSUInteger index = 0; index < 8; ++index)
        variant.colorAttachments[index].pixelFormat = index < colorFormats.size() ? colorFormats[index] : MTLPixelFormatInvalid;
    NSError* error = nil;
    id<MTLRenderPipelineState> state = [device newRenderPipelineStateWithDescriptor:variant error:&error];
    if (!state) throw std::runtime_error(std::string("Metal pipeline '") + descriptor.label.UTF8String + "': " + error.localizedDescription.UTF8String);
    variants.emplace(key, state);
    return state;
}
void setScissor(Device& d, int x, int y, int width, int height) {
    d.requireRender();
    int64_t left = std::clamp<int64_t>(x, 0, d.renderWidth);
    int64_t top = std::clamp<int64_t>(y, 0, d.renderHeight);
    int64_t right = std::clamp<int64_t>(static_cast<int64_t>(x) + std::max(width, 0), left, d.renderWidth);
    int64_t bottom = std::clamp<int64_t>(static_cast<int64_t>(y) + std::max(height, 0), top, d.renderHeight);
    d.emptyScissor = left == right || top == bottom;
    d.renderState.scissor = MTLScissorRect{static_cast<NSUInteger>(left), static_cast<NSUInteger>(top), static_cast<NSUInteger>(right-left), static_cast<NSUInteger>(bottom-top)};
    if (!d.emptyScissor) [d.renderEncoder setScissorRect:d.renderState.scissor];
}
void beginPass(Device& d, NSString* label, const std::vector<id<MTLTexture>>& colors,
               const std::vector<float>& clears, id<MTLTexture> depth, double clearDepth,
               int x, int y, int width, int height) {
    if (d.renderEncoder) throw std::logic_error("A Metal render pass is already open");
    d.endBlit();
    MTLRenderPassDescriptor* desc = [MTLRenderPassDescriptor renderPassDescriptor];
    d.colorFormats.clear();
    d.renderWidth = d.renderHeight = 0;
    for (NSUInteger i=0; i<colors.size(); ++i) {
        auto texture = colors[i];
        auto target = desc.colorAttachments[i];
        target.texture = texture;
        target.storeAction = MTLStoreActionStore;
        bool clear = texture && clears.size() >= (i+1)*4 && !std::isnan(clears[i*4]);
        target.loadAction = clear ? MTLLoadActionClear : MTLLoadActionLoad;
        if (clear) target.clearColor = MTLClearColorMake(clears[i*4], clears[i*4+1], clears[i*4+2], clears[i*4+3]);
        d.colorFormats.push_back(texture ? texture.pixelFormat : MTLPixelFormatInvalid);
        if (texture) { d.renderWidth = texture.width; d.renderHeight = texture.height; }
    }
    d.depthFormat = depth ? depth.pixelFormat : MTLPixelFormatInvalid;
    if (depth) {
        desc.depthAttachment.texture = depth;
        desc.depthAttachment.loadAction = std::isnan(clearDepth) ? MTLLoadActionLoad : MTLLoadActionClear;
        desc.depthAttachment.clearDepth = std::isnan(clearDepth) ? 0 : clearDepth;
        desc.depthAttachment.storeAction = MTLStoreActionStore;
        d.renderWidth = depth.width; d.renderHeight = depth.height;
    }
    if (!d.renderWidth || !d.renderHeight) throw std::invalid_argument("Render pass needs an attachment");
    d.renderDescriptor = desc;
    d.renderState = RenderState{};
    d.renderEncoder = [d.commands() renderCommandEncoderWithDescriptor:desc];
    if (!d.renderEncoder) throw std::runtime_error("Failed to create Metal render encoder");
    d.waitForRenderFence(d.renderEncoder);
    d.renderEncoder.label = label;
    [d.renderEncoder setViewport:MTLViewport{0, 0, static_cast<double>(d.renderWidth), static_cast<double>(d.renderHeight), 0, 1}];
    [d.renderEncoder setFrontFacingWinding:MTLWindingClockwise];
    setScissor(d, x, y, width, height);
}
}

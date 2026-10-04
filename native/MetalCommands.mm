#include "MetalContext.hpp"
#include "MetalShaders.hpp"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <limits>
#include <sstream>

using namespace metal;
#define NATIVE(name) Java_dev_kausik_metal_MetalNative_##name
static std::vector<jint> integers(JNIEnv* env, jintArray values) {
    std::vector<jint> result(values ? env->GetArrayLength(values) : 0);
    if (!result.empty()) env->GetIntArrayRegion(values, 0, result.size(), result.data());
    return result;
}
static id<MTLLibrary> compile(id<MTLDevice> device, NSString* source, const char* stage) {
    MTLCompileOptions* options = [MTLCompileOptions new];
    options.languageVersion = MTLLanguageVersion3_1;
    if (@available(macOS 15.0, *)) options.mathMode = MTLMathModeSafe;
    else {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
        options.fastMathEnabled = NO;
#pragma clang diagnostic pop
    }
    NSError* error = nil;
    auto library = [device newLibraryWithSource:source options:options error:&error];
    if (!library) throw std::runtime_error(std::string("Metal ") + stage + " shader compilation failed: " + error.localizedDescription.UTF8String);
    return library;
}
static void clearRegion(Device& d, id<MTLTexture> colorTexture, id<MTLTexture> depthTexture,
                        float r, float g, float b, float a, double depthValue,
                        int x, int y, int width, int height) {
    auto attachment = colorTexture ? colorTexture : depthTexture;
    if (!attachment) throw std::invalid_argument("Clear requires a color or depth texture");
    if (width<=0 || height<=0) return;
    std::vector<id<MTLTexture>> colors = colorTexture ? std::vector<id<MTLTexture>>{colorTexture} : std::vector<id<MTLTexture>>{};
    bool full = x==0 && y==0 && static_cast<NSUInteger>(width)==attachment.width && static_cast<NSUInteger>(height)==attachment.height;
    beginPass(d, @"Clear", colors, full ? std::vector<float>{r,g,b,a} : std::vector<float>{NAN,0,0,0}, depthTexture, full ? depthValue : NAN, x,y,width,height);
    if (!full && !d.emptyScissor) {
        if (!d.clearLibrary) d.clearLibrary = compile(d.object, [NSString stringWithUTF8String:clearShader], "clear");
        uint64_t key = static_cast<uint64_t>(d.depthFormat)*1024 + (colorTexture ? colorTexture.pixelFormat : 0);
        auto found = d.clearPipelines.find(key);
        id<MTLRenderPipelineState> state;
        if (found != d.clearPipelines.end()) state = found->second;
        else {
            MTLRenderPipelineDescriptor* desc = [MTLRenderPipelineDescriptor new];
            desc.vertexFunction = [d.clearLibrary newFunctionWithName:@"clear_v"];
            if (colorTexture) { desc.fragmentFunction = [d.clearLibrary newFunctionWithName:@"clear_f"]; desc.colorAttachments[0].pixelFormat = colorTexture.pixelFormat; }
            desc.depthAttachmentPixelFormat = d.depthFormat;
            NSError* error = nil; state = [d.object newRenderPipelineStateWithDescriptor:desc error:&error];
            if (!state) throw std::runtime_error(std::string("Could not create rectangle clear pipeline: ") + error.localizedDescription.UTF8String);
            d.clearPipelines.emplace(key,state);
        }
        struct { float color[4]; float depth; float padding[3]; } params = {{r,g,b,a},static_cast<float>(depthValue),{0,0,0}};
        [d.renderEncoder setRenderPipelineState:state];
        MTLDepthStencilDescriptor* dd = [MTLDepthStencilDescriptor new]; dd.depthCompareFunction = MTLCompareFunctionAlways; dd.depthWriteEnabled = depthTexture != nil;
        [d.renderEncoder setDepthStencilState:[d.object newDepthStencilStateWithDescriptor:dd]];
        [d.renderEncoder setVertexBytes:&params length:sizeof(params) atIndex:0];
        if (colorTexture) [d.renderEncoder setFragmentBytes:&params length:sizeof(params) atIndex:0];
        [d.renderEncoder setCullMode:MTLCullModeNone];
        [d.renderEncoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    }
    d.endRender();
}
// Metal has no fan topology. Expand an indexed fan into triangle-list indices on the GPU: the index
// buffer is usually private and filled by an earlier blit, so the CPU cannot read it here.
static UploadSlice expandTriangleFan(Device& d, id<MTLBuffer> indices, bool index32, uint32_t first, uint32_t triangles) {
    if (!d.triangleFanPipeline) {
        auto library = compile(d.object, [NSString stringWithUTF8String:triangleFanShader], "triangle fan");
        NSError* error = nil;
        d.triangleFanPipeline = [d.object newComputePipelineStateWithFunction:[library newFunctionWithName:@"expand_triangle_fan"] error:&error];
        if (!d.triangleFanPipeline) throw std::runtime_error(std::string("Could not create triangle fan pipeline: ") + error.localizedDescription.UTF8String);
    }
    auto target = d.upload(nullptr, NSUInteger(triangles) * 3 * sizeof(uint32_t), sizeof(uint32_t));
    struct { uint32_t first, triangles, index32; } params = {first, triangles, index32 ? 1u : 0u};
    d.suspendRender();
    auto encoder = [d.commands() computeCommandEncoder];
    if (!encoder) throw std::runtime_error("Could not create Metal compute encoder for triangle fan");
    if (d.hasEncoderFence) [encoder waitForFence:d.encoderFence];
    [encoder setComputePipelineState:d.triangleFanPipeline];
    [encoder setBuffer:indices offset:0 atIndex:0];
    [encoder setBuffer:target.buffer offset:target.offset atIndex:1];
    [encoder setBytes:&params length:sizeof(params) atIndex:2];
    NSUInteger width = std::min<NSUInteger>(d.triangleFanPipeline.maxTotalThreadsPerThreadgroup, 64);
    [encoder dispatchThreads:MTLSizeMake(triangles, 1, 1) threadsPerThreadgroup:MTLSizeMake(width, 1, 1)];
    [encoder updateFence:d.encoderFence];
    d.hasEncoderFence = true;
    [encoder endEncoding];
    d.resumeRender();
    return target;
}
extern "C" {
JNIEXPORT jlong JNICALL NATIVE(createPipelineWithDepthVariants)(JNIEnv* env, jclass, jlong device, jstring label, jstring vertexMsl, jstring fragmentMsl, jstring fragmentWithoutDepthMsl, jintArray attributes, jintArray layouts, jintArray colors, jint depthCompare, jboolean depthWrite, jboolean cull, jboolean wireframe, jfloat depthBias, jfloat depthSlope) {
    return guarded(env, [&]() -> jlong {
        auto& d = get<Device>(device);
        auto pipeline = std::make_unique<Pipeline>();
        auto vertex = compile(d.object, nsString(env, vertexMsl), "vertex");
        auto fragment = compile(d.object, nsString(env, fragmentMsl), "fragment");
        MTLRenderPipelineDescriptor* desc = [MTLRenderPipelineDescriptor new];
        desc.label = nsString(env, label);
        desc.vertexFunction = [vertex newFunctionWithName:@"main0"];
        desc.fragmentFunction = [fragment newFunctionWithName:@"main0"];
        if (!desc.vertexFunction || !desc.fragmentFunction) throw std::invalid_argument("Translated Metal shader has no main0 entrypoint");
        if (fragmentWithoutDepthMsl) {
            auto noDepthFragment = compile(d.object, nsString(env, fragmentWithoutDepthMsl), "fragment without depth");
            pipeline->fragmentWithoutDepth = [noDepthFragment newFunctionWithName:@"main0"];
            if (!pipeline->fragmentWithoutDepth) throw std::invalid_argument("Translated Metal shader has no main0 entrypoint for rendering without depth");
        }
        MTLVertexDescriptor* vertexDesc = [MTLVertexDescriptor vertexDescriptor];
        auto attr = integers(env, attributes), layout = integers(env, layouts), targets = integers(env, colors);
        if (attr.size()%4 || layout.size()%3 || targets.size()%9) throw std::invalid_argument("Invalid pipeline layout tuple size");
        for (size_t i=0; i<attr.size(); i+=4) {
            if (attr[i]<0 || attr[i]>=31 || attr[i+1]<0 || attr[i+1]>=16 || attr[i+2]<0) throw std::invalid_argument("Invalid vertex attribute");
            auto a = vertexDesc.attributes[attr[i]];
            a.bufferIndex = attr[i+1]; a.offset = attr[i+2]; a.format = static_cast<MTLVertexFormat>(attr[i+3]);
        }
        for (size_t i=0; i<layout.size(); i+=3) {
            if (layout[i]<0 || layout[i]>=16 || layout[i+1]<=0) throw std::invalid_argument("Invalid vertex buffer layout");
            auto l = vertexDesc.layouts[layout[i]];
            l.stride = layout[i+1]; l.stepFunction = layout[i+2] ? MTLVertexStepFunctionPerInstance : MTLVertexStepFunctionPerVertex;
            l.stepRate = layout[i+2] ? layout[i+2] : 1;
        }
        desc.vertexDescriptor = vertexDesc;
        for (size_t i=0; i<targets.size(); i+=9) {
            auto target = desc.colorAttachments[i/9];
            target.pixelFormat = static_cast<MTLPixelFormat>(targets[i]);
            target.writeMask = static_cast<MTLColorWriteMask>(targets[i+1]);
            target.blendingEnabled = targets[i+2];
            if (target.blendingEnabled) {
                target.sourceRGBBlendFactor = static_cast<MTLBlendFactor>(targets[i+3]);
                target.destinationRGBBlendFactor = static_cast<MTLBlendFactor>(targets[i+4]);
                target.rgbBlendOperation = static_cast<MTLBlendOperation>(targets[i+5]);
                target.sourceAlphaBlendFactor = static_cast<MTLBlendFactor>(targets[i+6]);
                target.destinationAlphaBlendFactor = static_cast<MTLBlendFactor>(targets[i+7]);
                target.alphaBlendOperation = static_cast<MTLBlendOperation>(targets[i+8]);
            }
        }
        MTLDepthStencilDescriptor* depth = [MTLDepthStencilDescriptor new];
        depth.depthCompareFunction = depthCompare < 0 ? MTLCompareFunctionAlways : compareFunction(depthCompare);
        depth.depthWriteEnabled = depthCompare >= 0 && depthWrite;
        pipeline->depthState = [d.object newDepthStencilStateWithDescriptor:depth];
        depth.depthCompareFunction = MTLCompareFunctionAlways; depth.depthWriteEnabled = NO;
        pipeline->noDepthState = [d.object newDepthStencilStateWithDescriptor:depth];
        pipeline->descriptor = desc;
        pipeline->cull = cull; pipeline->wireframe = wireframe; pipeline->depthBias = depthBias; pipeline->depthSlope = depthSlope;
        std::vector<MTLPixelFormat> colorFormats;
        for (size_t i=0; i<targets.size(); i+=9) colorFormats.push_back(static_cast<MTLPixelFormat>(targets[i]));
        // A shader can write depth even when depth testing/writes are disabled in
        // the pipeline state (RenderPearl's explicit-depth workaround does this).
        // Validate with a compatible depth attachment; bindPipeline creates and
        // caches the variant for the actual render pass when it is needed.
        pipeline->state(d.object, MTLPixelFormatDepth32Float, colorFormats);
        return retainResource(std::move(pipeline));
    });
}
JNIEXPORT jlong JNICALL NATIVE(createPipeline)(JNIEnv* env, jclass cls, jlong device, jstring label, jstring vertexMsl, jstring fragmentMsl, jintArray attributes, jintArray layouts, jintArray colors, jint depthCompare, jboolean depthWrite, jboolean cull, jboolean wireframe, jfloat depthBias, jfloat depthSlope) {
    return NATIVE(createPipelineWithDepthVariants)(env, cls, device, label, vertexMsl, fragmentMsl, nullptr,
        attributes, layouts, colors, depthCompare, depthWrite, cull, wireframe, depthBias, depthSlope);
}
JNIEXPORT void JNICALL NATIVE(beginRenderPass)(JNIEnv* env, jclass, jlong device, jstring label, jlongArray colors, jfloatArray clearColors, jlong depth, jdouble clearDepth, jint x, jint y, jint width, jint height) {
    guarded(env, [&] {
        std::vector<jlong> handles(colors ? env->GetArrayLength(colors) : 0);
        if (handles.size()>8) throw std::invalid_argument("Metal supports up to eight color attachments");
        if (!handles.empty()) env->GetLongArrayRegion(colors, 0, handles.size(), handles.data());
        std::vector<id<MTLTexture>> textures;
        for (auto h : handles) textures.push_back(h ? get<Texture>(h).object : nil);
        std::vector<float> clears(clearColors ? env->GetArrayLength(clearColors) : 0);
        if (!clears.empty()) env->GetFloatArrayRegion(clearColors, 0, clears.size(), clears.data());
        beginPass(get<Device>(device), nsString(env, label), textures, clears, depth ? get<Texture>(depth).object : nil, clearDepth, x,y,width,height);
    });
}
JNIEXPORT void JNICALL NATIVE(endRenderPass)(JNIEnv* env, jclass, jlong device) { guarded(env, [&] { get<Device>(device).endRender(); }); }
JNIEXPORT void JNICALL NATIVE(pushDebugGroup)(JNIEnv* env, jclass, jlong device, jstring label) { guarded(env, [&] { auto& d = get<Device>(device); d.requireRender(); NSString* text = nsString(env, label); d.renderState.debugGroups.push_back(text); [d.renderEncoder pushDebugGroup:text]; }); }
JNIEXPORT void JNICALL NATIVE(popDebugGroup)(JNIEnv* env, jclass, jlong device) { guarded(env, [&] { auto& d = get<Device>(device); d.requireRender(); if (d.renderState.debugGroups.empty()) throw std::logic_error("No Metal debug group to pop"); d.renderState.debugGroups.pop_back(); [d.renderEncoder popDebugGroup]; }); }
JNIEXPORT void JNICALL NATIVE(bindPipeline)(JNIEnv* env, jclass, jlong device, jlong pipeline) {
    guarded(env, [&] {
        auto& d = get<Device>(device); auto& p = get<Pipeline>(pipeline); d.requireRender();
        d.renderState.pipeline = p.state(d.object, d.depthFormat, d.colorFormats);
        d.renderState.depth = d.depthFormat == MTLPixelFormatInvalid ? p.noDepthState : p.depthState;
        d.renderState.cull = p.cull ? MTLCullModeBack : MTLCullModeNone;
        d.renderState.fill = p.wireframe ? MTLTriangleFillModeLines : MTLTriangleFillModeFill;
        d.renderState.bias = p.depthBias; d.renderState.slope = p.depthSlope;
        [d.renderEncoder setRenderPipelineState:d.renderState.pipeline];
        [d.renderEncoder setDepthStencilState:d.depthFormat == MTLPixelFormatInvalid ? p.noDepthState : p.depthState];
        [d.renderEncoder setCullMode:p.cull ? MTLCullModeBack : MTLCullModeNone];
        [d.renderEncoder setTriangleFillMode:p.wireframe ? MTLTriangleFillModeLines : MTLTriangleFillModeFill];
        [d.renderEncoder setDepthBias:p.depthBias slopeScale:p.depthSlope clamp:0];
    });
}
JNIEXPORT void JNICALL NATIVE(bindUniform)(JNIEnv* env, jclass, jlong device, jint stageMask, jint index, jlong buffer, jlong offset, jlong length) {
    guarded(env, [&] {
        auto& d = get<Device>(device); d.requireRender();
        auto value = buffer ? get<Buffer>(buffer).object : nil;
        if (value) validateRange(value.length, offset, length);
        if (index<0 || index>=31) throw std::invalid_argument("Invalid Metal uniform index");
        if (stageMask & 1) { d.renderState.vertexBuffers[index] = value; d.renderState.vertexOffsets[index] = offset; [d.renderEncoder setVertexBuffer:value offset:offset atIndex:index]; }
        if (stageMask & 2) { d.renderState.fragmentBuffers[index] = value; d.renderState.fragmentOffsets[index] = offset; [d.renderEncoder setFragmentBuffer:value offset:offset atIndex:index]; }
    });
}
JNIEXPORT void JNICALL NATIVE(pushConstants)(JNIEnv* env, jclass, jlong device, jint stageMask, jint index, jobject data, jint position, jint length) {
    guarded(env, [&] {
        auto& d = get<Device>(device); d.requireRender();
        if (index < 0 || index >= 31 || length < 0 || length > 4096)
            throw std::invalid_argument("Invalid Metal push constant binding or size");
        if (!length) return;
        NSUInteger padded = (length + 15) & ~NSUInteger(15);
        auto upload = d.upload(nullptr, padded);
        auto target = static_cast<uint8_t*>(upload.buffer.contents) + upload.offset;
        memcpy(target, directBytes(env, data, position, length), length);
        memset(target + length, 0, padded - length);
        if (stageMask & 1) {
            d.renderState.vertexBuffers[index] = upload.buffer;
            d.renderState.vertexOffsets[index] = upload.offset;
            [d.renderEncoder setVertexBuffer:upload.buffer offset:upload.offset atIndex:index];
        }
        if (stageMask & 2) {
            d.renderState.fragmentBuffers[index] = upload.buffer;
            d.renderState.fragmentOffsets[index] = upload.offset;
            [d.renderEncoder setFragmentBuffer:upload.buffer offset:upload.offset atIndex:index];
        }
    });
}
JNIEXPORT void JNICALL NATIVE(bindTexture)(JNIEnv* env, jclass, jlong device, jint stageMask, jint index, jlong textureView, jlong sampler) {
    guarded(env, [&] {
        auto& d = get<Device>(device); d.requireRender();
        auto texture = textureView ? get<Texture>(textureView).object : nil;
        auto sampling = sampler ? get<Sampler>(sampler).object : nil;
        if (index<0 || index>=16) throw std::invalid_argument("Invalid Metal texture/sampler index");
        if (stageMask & 1) { d.renderState.vertexTextures[index] = texture; d.renderState.vertexSamplers[index] = sampling; [d.renderEncoder setVertexTexture:texture atIndex:index]; [d.renderEncoder setVertexSamplerState:sampling atIndex:index]; }
        if (stageMask & 2) { d.renderState.fragmentTextures[index] = texture; d.renderState.fragmentSamplers[index] = sampling; [d.renderEncoder setFragmentTexture:texture atIndex:index]; [d.renderEncoder setFragmentSamplerState:sampling atIndex:index]; }
    });
}
JNIEXPORT void JNICALL NATIVE(bindTexelBuffer)(JNIEnv* env, jclass, jlong device, jint stageMask, jint index, jlong buffer, jlong offset, jlong length, jstring format) {
    guarded(env, [&] {
        auto& d = get<Device>(device); d.requireRender();
        auto& b = get<Buffer>(buffer);
        validateRange(b.object.length, offset, length);
        auto pixel = pixelFormat(utf8(env, format));
        auto bpp = bytesPerPixel(pixel);
        if (length%bpp || length==0) throw std::invalid_argument("Texel buffer size must be a positive whole number of pixels");
        std::string key = std::to_string(pixel) + ":" + std::to_string(offset) + ":" + std::to_string(length);
        auto found = b.texelViews.find(key);
        id<MTLTexture> texture;
        if (found != b.texelViews.end()) texture = found->second;
        else {
            auto desc = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:pixel width:length/bpp resourceOptions:b.object.resourceOptions usage:MTLTextureUsageShaderRead];
            NSUInteger alignment = [d.object minimumTextureBufferAlignmentForPixelFormat:pixel];
            if (offset % alignment) throw std::invalid_argument("Texel buffer offset does not meet Metal device alignment");
            NSUInteger rowBytes = (length + alignment - 1) / alignment * alignment;
            texture = [b.object newTextureWithDescriptor:desc offset:offset bytesPerRow:rowBytes];
            if (!texture) throw std::runtime_error("Could not create Metal texel buffer view");
            b.texelViews.emplace(key, texture);
        }
        if (stageMask & 1) { d.renderState.vertexTextures[index] = texture; [d.renderEncoder setVertexTexture:texture atIndex:index]; }
        if (stageMask & 2) { d.renderState.fragmentTextures[index] = texture; [d.renderEncoder setFragmentTexture:texture atIndex:index]; }
    });
}
JNIEXPORT void JNICALL NATIVE(bindVertexBuffer)(JNIEnv* env, jclass, jlong device, jint index, jlong buffer, jlong offset) {
    guarded(env, [&] { auto& d = get<Device>(device); d.requireRender(); auto b = buffer ? get<Buffer>(buffer).object : nil; if (b) validateRange(b.length, offset, 0); if(index<0 || index>=16) throw std::out_of_range("Invalid vertex buffer index"); d.renderState.vertexBuffers[index] = b; d.renderState.vertexOffsets[index] = offset; [d.renderEncoder setVertexBuffer:b offset:offset atIndex:index]; });
}
JNIEXPORT void JNICALL NATIVE(scissor)(JNIEnv* env, jclass, jlong device, jint x, jint y, jint width, jint height) { guarded(env, [&] { setScissor(get<Device>(device), x,y,width,height); }); }
JNIEXPORT void JNICALL NATIVE(draw)(JNIEnv* env, jclass, jlong device, jint topology, jint firstVertex, jint vertexCount, jint instances, jint baseInstance) {
    guarded(env, [&] {
        auto& d = get<Device>(device); d.requireRender();
        if (d.emptyScissor || vertexCount<=0 || instances<=0) return;
        if (topology == 5) {
            if (vertexCount<3) return;
            std::vector<uint32_t> indices((vertexCount-2)*3);
            for (int n=0; n<vertexCount-2; ++n) { indices[n*3]=firstVertex; indices[n*3+1]=firstVertex+n+1; indices[n*3+2]=firstVertex+n+2; }
            auto upload = d.upload(indices.data(), indices.size()*sizeof(uint32_t));
            [d.renderEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:indices.size() indexType:MTLIndexTypeUInt32 indexBuffer:upload.buffer indexBufferOffset:upload.offset instanceCount:instances baseVertex:0 baseInstance:baseInstance];
        } else [d.renderEncoder drawPrimitives:primitiveType(topology) vertexStart:firstVertex vertexCount:vertexCount instanceCount:instances baseInstance:baseInstance];
    });
}
JNIEXPORT void JNICALL NATIVE(drawIndexed)(JNIEnv* env, jclass, jlong device, jint topology, jlong indices, jboolean index32, jlong indexOffset, jint indexCount, jint baseVertex, jint instances, jint baseInstance) {
    guarded(env, [&] {
        auto& d = get<Device>(device); d.requireRender(); if (d.emptyScissor || indexCount<=0 || instances<=0) return;
        auto buffer = get<Buffer>(indices).object;
        validateRange(buffer.length, indexOffset, static_cast<int64_t>(indexCount)*(index32 ? 4 : 2));
        if (topology == 5) {
            if (indexCount < 3) return;
            NSUInteger size = index32 ? 4 : 2;
            if (indexOffset % size) throw std::invalid_argument("Metal index offset is not aligned to the index size");
            uint32_t triangles = static_cast<uint32_t>(indexCount - 2);
            auto expanded = expandTriangleFan(d, buffer, index32, static_cast<uint32_t>(indexOffset / size), triangles);
            [d.renderEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:NSUInteger(triangles) * 3 indexType:MTLIndexTypeUInt32 indexBuffer:expanded.buffer indexBufferOffset:expanded.offset instanceCount:instances baseVertex:baseVertex baseInstance:baseInstance];
            return;
        }
        [d.renderEncoder drawIndexedPrimitives:primitiveType(topology) indexCount:indexCount indexType:index32 ? MTLIndexTypeUInt32 : MTLIndexTypeUInt16 indexBuffer:buffer indexBufferOffset:indexOffset instanceCount:instances baseVertex:baseVertex baseInstance:baseInstance];
    });
}
JNIEXPORT void JNICALL NATIVE(drawIndirect)(JNIEnv* env, jclass, jlong device, jint topology, jlong parameters, jlong offset, jint count, jlong indices, jboolean index32) {
    guarded(env, [&] {
        auto& d = get<Device>(device); d.requireRender(); if (d.emptyScissor || count<=0) return;
        if (topology == 5) throw std::invalid_argument("Metal indirect triangle fans require triangle-list indices");
        auto buffer = get<Buffer>(parameters).object;
        NSUInteger stride = indices ? sizeof(MTLDrawIndexedPrimitivesIndirectArguments) : sizeof(MTLDrawPrimitivesIndirectArguments);
        validateRange(buffer.length, offset, stride*count);
        auto indexBuffer = indices ? get<Buffer>(indices).object : nil;
        for (int i=0; i<count; ++i) {
            if (indexBuffer) [d.renderEncoder drawIndexedPrimitives:primitiveType(topology) indexType:index32 ? MTLIndexTypeUInt32 : MTLIndexTypeUInt16 indexBuffer:indexBuffer indexBufferOffset:0 indirectBuffer:buffer indirectBufferOffset:offset+i*stride];
            else [d.renderEncoder drawPrimitives:primitiveType(topology) indirectBuffer:buffer indirectBufferOffset:offset+i*stride];
        }
    });
}
JNIEXPORT void JNICALL NATIVE(submit)(JNIEnv* env, jclass, jlong device) { guarded(env, [&] { get<Device>(device).submit(); }); }
JNIEXPORT jlong JNICALL NATIVE(createFence)(JNIEnv* env, jclass, jlong device) {
    return guarded(env, [&]() -> jlong {
        auto& d = get<Device>(device);
        auto fence = std::make_unique<Fence>(); fence->state = std::make_shared<FenceState>();
        fence->command = d.commands();
        d.pendingFences.push_back(fence->state);
        return retainResource(std::move(fence));
    });
}
JNIEXPORT jboolean JNICALL NATIVE(awaitFence)(JNIEnv* env, jclass, jlong fence, jlong timeoutNanos) {
    return guarded(env, [&]() -> jboolean {
        auto& f = get<Fence>(fence);
        auto state = f.state;
        if (timeoutNanos != 0 && f.command.status < MTLCommandBufferStatusCommitted)
            throw std::logic_error("Cannot wait for a Metal fence before submitting its command encoder");
        std::unique_lock lock(state->mutex);
        if (timeoutNanos<0 || timeoutNanos == std::numeric_limits<jlong>::max()) state->condition.wait(lock, [&] { return state->completed; });
        else if (!state->condition.wait_for(lock, std::chrono::nanoseconds(timeoutNanos), [&] { return state->completed; })) return false;
        if (!state->error.empty()) throw std::runtime_error("GPU fence failed: " + state->error);
        return true;
    });
}
JNIEXPORT jlong JNICALL NATIVE(createQueryPool)(JNIEnv* env, jclass, jlong device, jint size) {
    return guarded(env, [&]() -> jlong {
        auto& d = get<Device>(device);
        if (size<=0) throw std::invalid_argument("Timestamp query count must be positive");
        id<MTLCounterSet> timestamps = nil;
        for (id<MTLCounterSet> set in d.object.counterSets) if ([set.name isEqualToString:MTLCommonCounterSetTimestamp]) { timestamps = set; break; }
        if (!timestamps) throw std::runtime_error("This Metal device does not support GPU timestamp counters");
        auto pool = std::make_unique<QueryPool>(); pool->state = std::make_shared<QueryState>(size);
        MTLCounterSampleBufferDescriptor* desc = [MTLCounterSampleBufferDescriptor new];
        desc.counterSet = timestamps; desc.storageMode = MTLStorageModeShared; desc.sampleCount = size;
        NSError* error = nil;
        for (int slot = 0; slot < 3; ++slot) {
            pool->state->samples[slot] = [d.object newCounterSampleBufferWithDescriptor:desc error:&error];
            if (!pool->state->samples[slot]) throw std::runtime_error(std::string("Unable to create timestamp query pool: ") + error.localizedDescription.UTF8String);
        }
        return retainResource(std::move(pool));
    });
}
JNIEXPORT void JNICALL NATIVE(writeTimestamp)(JNIEnv* env, jclass, jlong device, jlong pool, jint index) {
    guarded(env, [&] {
        auto& d = get<Device>(device); auto state = get<QueryPool>(pool).state;
        if (index<0 || static_cast<size_t>(index)>=state->values.size()) throw std::out_of_range("Timestamp query index out of bounds");
        d.commands();
        id<MTLCounterSampleBuffer> samples = state->samples[(d.serial - 1) % 3];
        uint64_t generation;
        { std::lock_guard lock(state->mutex); state->values[index] = -1; generation = ++state->generations[index]; }
        if ([d.object supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) {
            bool resume = d.renderEncoder != nil;
            if (resume) d.suspendRender(); else d.endBlit();
            MTLBlitPassDescriptor* pass = [MTLBlitPassDescriptor blitPassDescriptor];
            pass.sampleBufferAttachments[0].sampleBuffer = samples;
            pass.sampleBufferAttachments[0].startOfEncoderSampleIndex = MTLCounterDontSample;
            pass.sampleBufferAttachments[0].endOfEncoderSampleIndex = index;
            auto encoder = [d.commands() blitCommandEncoderWithDescriptor:pass];
            d.waitForBlitFence(encoder);
            if (!d.timestampScratch) d.timestampScratch = [d.object newBufferWithLength:4 options:MTLResourceStorageModePrivate];
            [encoder fillBuffer:d.timestampScratch range:NSMakeRange(0,4) value:0];
            d.updateBlitFence(encoder);
            [encoder endEncoding];
            if (resume) d.resumeRender();
        } else if (d.renderEncoder && [d.object supportsCounterSampling:MTLCounterSamplingPointAtDrawBoundary]) {
            [d.renderEncoder sampleCountersInBuffer:samples atSampleIndex:index withBarrier:YES];
        } else if (!d.renderEncoder && [d.object supportsCounterSampling:MTLCounterSamplingPointAtBlitBoundary]) {
            [d.blit() sampleCountersInBuffer:samples atSampleIndex:index withBarrier:YES];
        } else throw std::runtime_error("GPU does not support timestamp sampling at this command boundary");
        auto found = std::find_if(d.pendingQueries.begin(), d.pendingQueries.end(), [&](const PendingQueries& item) { return item.state == state; });
        if (found == d.pendingQueries.end()) d.pendingQueries.push_back({state, {{static_cast<NSUInteger>(index), generation}}, samples});
        else found->writes.push_back({static_cast<NSUInteger>(index), generation});
    });
}
JNIEXPORT jlong JNICALL NATIVE(queryValue)(JNIEnv* env, jclass, jlong pool, jint index) {
    return guarded(env, [&]() -> jlong {
        auto state = get<QueryPool>(pool).state; std::lock_guard lock(state->mutex);
        if (index<0 || static_cast<size_t>(index)>=state->values.size()) throw std::out_of_range("Timestamp query index out of bounds");
        return state->values[index];
    });
}
JNIEXPORT jlong JNICALL NATIVE(timestampNow)(JNIEnv* env, jclass, jlong device) {
    return guarded(env, [&]() -> jlong { MTLTimestamp cpu=0, gpu=0; [get<Device>(device).object sampleTimestamps:&cpu gpuTimestamp:&gpu]; return gpu; });
}
JNIEXPORT void JNICALL NATIVE(clearAll)(JNIEnv* env, jclass, jlong device, jlong color, jfloat r, jfloat g, jfloat b, jfloat a, jlong depth, jdouble depthValue) {
    guarded(env, [&] {
        auto& d = get<Device>(device);
        if (d.renderEncoder) throw std::logic_error("Cannot clear an image inside an open render pass");
        if (!color && !depth) throw std::invalid_argument("Clear requires a color or depth texture");
        d.endBlit();
        auto clearTexture = [&](id<MTLTexture> texture, bool depthAttachment) {
            for (NSUInteger mip = 0; mip < texture.mipmapLevelCount; ++mip) {
                MTLRenderPassDescriptor* pass = [MTLRenderPassDescriptor renderPassDescriptor];
                if (depthAttachment) {
                    pass.depthAttachment.texture = texture;
                    pass.depthAttachment.level = mip;
                    pass.depthAttachment.loadAction = MTLLoadActionClear;
                    pass.depthAttachment.storeAction = MTLStoreActionStore;
                    pass.depthAttachment.clearDepth = depthValue;
                } else {
                    pass.colorAttachments[0].texture = texture;
                    pass.colorAttachments[0].level = mip;
                    pass.colorAttachments[0].loadAction = MTLLoadActionClear;
                    pass.colorAttachments[0].storeAction = MTLStoreActionStore;
                    pass.colorAttachments[0].clearColor = MTLClearColorMake(r,g,b,a);
                }
                auto encoder = [d.commands() renderCommandEncoderWithDescriptor:pass];
                if (!encoder) throw std::runtime_error("Could not create mip clear encoder");
                encoder.label = @"Clear texture mip";
                d.waitForRenderFence(encoder);
                d.updateRenderFence(encoder);
                [encoder endEncoding];
            }
        };
        if (color) clearTexture(get<Texture>(color).object, false);
        if (depth) clearTexture(get<Texture>(depth).object, true);
    });
}
JNIEXPORT void JNICALL NATIVE(clear)(JNIEnv* env, jclass, jlong device, jlong color, jfloat r, jfloat g, jfloat b, jfloat a, jlong depth, jdouble depthValue, jint x, jint y, jint width, jint height) {
    guarded(env, [&] {
        clearRegion(get<Device>(device), color ? get<Texture>(color).object : nil,
                    depth ? get<Texture>(depth).object : nil, r,g,b,a,depthValue,x,y,width,height);
    });
}
JNIEXPORT void JNICALL NATIVE(clearMip)(JNIEnv* env, jclass, jlong device, jlong color, jfloat r, jfloat g, jfloat b, jfloat a, jlong depth, jdouble depthValue, jint x, jint y, jint width, jint height, jint mip) {
    guarded(env, [&] {
        auto mipView = [&](jlong handle) -> id<MTLTexture> {
            if (!handle) return nil;
            auto source = get<Texture>(handle).object;
            if (mip < 0 || static_cast<NSUInteger>(mip) >= source.mipmapLevelCount)
                throw std::out_of_range("Texture clear mip is out of bounds");
            if (mip == 0) return source;
            auto view = [source newTextureViewWithPixelFormat:source.pixelFormat
                textureType:MTLTextureType2D levels:NSMakeRange(mip,1) slices:NSMakeRange(0,1)];
            if (!view) throw std::runtime_error("Could not create texture clear mip view");
            return view;
        };
        clearRegion(get<Device>(device), mipView(color), mipView(depth), r,g,b,a,depthValue,x,y,width,height);
    });
}
}

#include "MetalContext.hpp"
#include "MetalShaders.hpp"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <sstream>

using namespace metal;
#define NATIVE(name) Java_dev_kausik_metal_MetalNative_##name
static std::vector<jint> integers(JNIEnv* env, jintArray values) {
    std::vector<jint> result(values ? env->GetArrayLength(values) : 0);
    if (!result.empty()) env->GetIntArrayRegion(values, 0, result.size(), result.data());
    return result;
}
static int effectiveMathMode(id<MTLDevice> device, int requested) {
    if (requested != 0 && requested != 1) throw std::invalid_argument("Invalid Metal arithmetic mode");
    if (@available(macOS 15.0, *)) {
        if (requested == 1 && [device supportsFamily:MTLGPUFamilyApple4]) return 1;
    }
    return 0;
}
static int legacyMathMode() {
    const char* mode = std::getenv("MINECRAFT_METAL_MATH_MODE");
    return mode && std::strcmp(mode, "relaxed") == 0 ? 1 : 0;
}
static id<MTLLibrary> compile(id<MTLDevice> device, NSString* source, const char* stage, int mathMode = 0) {
    MTLCompileOptions* options = [MTLCompileOptions new];
    options.languageVersion = MTLLanguageVersion3_1;
    // Paired depth/color passes need identical vertex position calculations. Metal applies
    // this restriction only to position outputs carrying [[invariant]] from source SPIR-V.
    options.preserveInvariance = YES;
    if (@available(macOS 15.0, *)) {
        options.mathMode = effectiveMathMode(device, mathMode) == 1 ? MTLMathModeRelaxed : MTLMathModeSafe;
        // Keep the pre-existing FP32 intrinsic selection independent of arithmetic policy.
        options.mathFloatingPointFunctions = MTLMathFloatingPointFunctionsFast;
    }
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
// Texture/sampler argument IDs match MetalShaderCompiler's dedicated descriptor set.
static void prepareTextureArguments(Device& d) {
    auto& state = d.renderState;
    for (int stage = 0; stage < 2; ++stage) {
        auto& arguments = stage ? state.fragmentArguments : state.vertexArguments;
        if (!arguments.encoder) continue;
        auto* textures = stage ? state.fragmentTextures : state.vertexTextures;
        auto* samplers = stage ? state.fragmentSamplers : state.vertexSamplers;
        if (state.textureArgumentsDirty) {
            auto upload = d.upload(nullptr, arguments.encoder.encodedLength);
            memset(static_cast<uint8_t*>(upload.buffer.contents) + upload.offset, 0, arguments.encoder.encodedLength);
            [arguments.encoder setArgumentBuffer:upload.buffer offset:upload.offset];
            for (NSUInteger index : arguments.textures) [arguments.encoder setTexture:textures[index] atIndex:index];
            for (NSUInteger index : arguments.samplers) [arguments.encoder setSamplerState:samplers[index] atIndex:128 + index];
            if (stage) {
                state.fragmentBuffers[29] = upload.buffer; state.fragmentOffsets[29] = upload.offset;
                [d.renderEncoder setFragmentBuffer:upload.buffer offset:upload.offset atIndex:29];
            } else {
                state.vertexBuffers[29] = upload.buffer; state.vertexOffsets[29] = upload.offset;
                [d.renderEncoder setVertexBuffer:upload.buffer offset:upload.offset atIndex:29];
            }
        }
        if (state.textureArgumentsDirty || !state.textureArgumentsResident)
            for (NSUInteger index : arguments.residentTextures)
                if (textures[index]) [d.renderEncoder useResource:textures[index] usage:MTLResourceUsageRead stages:stage ? MTLRenderStageFragment : MTLRenderStageVertex];
    }
    state.textureArgumentsDirty = false;
    state.textureArgumentsResident = true;
}

// This is a command-format conversion only: visibility, command order and draw arguments are
// unchanged. Keep it opt-in until CPU savings outweigh the extra tile store/load on real scenes.
static constexpr const char* indirectEncoderSource = R"METAL(
#include <metal_stdlib>
#include <metal_command_buffer>
using namespace metal;
struct IndirectContainer { command_buffer commands [[id(0)]]; };
struct EncodeParameters { uint count, primitive, indexed, index32; };
kernel void encode_indirect(device IndirectContainer& output [[buffer(0)]],
                            const device uint* arguments [[buffer(1)]],
                            device uchar* indices [[buffer(2)]],
                            constant EncodeParameters& params [[buffer(3)]],
                            uint index [[thread_position_in_grid]]) {
    if (index >= params.count) return;
    render_command command(output.commands, index);
    command.reset();
    const device uint* args = arguments + index * (params.indexed ? 5 : 4);
    uint count = args[0], instances = args[1];
    if (!count || !instances) return;
    auto primitive = static_cast<primitive_type>(params.primitive);
    if (!params.indexed) command.draw_primitives(primitive, args[2], count, instances, args[3]);
    else if (params.index32)
        command.draw_indexed_primitives(primitive, count, reinterpret_cast<device uint*>(indices) + args[2], instances, args[3], args[4]);
    else
        command.draw_indexed_primitives(primitive, count, reinterpret_cast<device ushort*>(indices) + args[2], instances, args[3], args[4]);
}
)METAL";
// Diagnostic footprint, not measured bus traffic: assume a full uncompressed store and reload
// of each attachment, independent of scissor, coverage, compression, or tile reuse.
static uint64_t indirectSplitAttachmentBytes(MTLRenderPassDescriptor* descriptor) {
    uint64_t result = 0;
    auto add = [&](MTLRenderPassAttachmentDescriptor* attachment) {
        auto texture = attachment.texture;
        if (!texture) return;
        uint64_t width = std::max<NSUInteger>(1, texture.width >> attachment.level);
        uint64_t height = std::max<NSUInteger>(1, texture.height >> attachment.level);
        result += 2 * width * height * bytesPerPixel(texture.pixelFormat) * texture.sampleCount;
    };
    for (NSUInteger i = 0; i < 8; ++i) add(descriptor.colorAttachments[i]);
    add(descriptor.depthAttachment);
    return result;
}

static void populateCpuIndirect(IndirectBatch& batch, const uint8_t* published,
                                NSUInteger count, MTLPrimitiveType topology,
                                id<MTLBuffer> indices, bool index32) {
    // Only map-close snapshots are accepted. Never inspect a GPU-written or escaped mapping.
    // The caller owns a completed submission-ring slot; no encoded command is still in flight.
    for (NSUInteger i = 0; i < count; ++i) {
        auto command = [batch.commands indirectRenderCommandAtIndex:i];
        [command reset];
        if (indices) {
            MTLDrawIndexedPrimitivesIndirectArguments args;
            std::memcpy(&args, published + i * sizeof(args), sizeof(args));
            if (!args.indexCount || !args.instanceCount) continue;
            NSUInteger indexStride = index32 ? 4 : 2;
            NSUInteger indexOffset = static_cast<NSUInteger>(args.indexStart) * indexStride;
            try {
                validateRange(indices.length, indexOffset, static_cast<uint64_t>(args.indexCount) * indexStride);
            } catch (const std::out_of_range& error) {
                throw std::out_of_range(std::string("CPU indirect index command ") + std::to_string(i) +
                    " of " + std::to_string(count) + " (buffer=" +
                    (indices.label ? indices.label.UTF8String : "unlabeled") +
                    ", indexCount=" + std::to_string(args.indexCount) +
                    ", indexStart=" + std::to_string(args.indexStart) +
                    ", indexWidth=" + std::to_string(indexStride) +
                    ", baseVertex=" + std::to_string(args.baseVertex) +
                    ", baseInstance=" + std::to_string(args.baseInstance) + "): " + error.what());
            }
            [command drawIndexedPrimitives:topology indexCount:args.indexCount
                indexType:index32 ? MTLIndexTypeUInt32 : MTLIndexTypeUInt16
                indexBuffer:indices indexBufferOffset:indexOffset instanceCount:args.instanceCount
                baseVertex:args.baseVertex baseInstance:args.baseInstance];
        } else {
            MTLDrawPrimitivesIndirectArguments args;
            std::memcpy(&args, published + i * sizeof(args), sizeof(args));
            if (!args.vertexCount || !args.instanceCount) continue;
            [command drawPrimitives:topology vertexStart:args.vertexStart vertexCount:args.vertexCount
                instanceCount:args.instanceCount baseInstance:args.baseInstance];
        }
    }
}

static bool drawWithIndirectCommands(Device& d, MTLPrimitiveType topology,
                                     Buffer& parameterResource, NSUInteger offset, NSUInteger count,
                                     id<MTLBuffer> indices, bool index32) {
    // 16,384 is the portable Mac2 limit; oversized batches retain the existing draw loop.
    if (!d.indirectEnabled || !d.indirectSupported || count > 16384 || count < d.indirectThreshold ||
        !d.renderState.pipeline.supportIndirectCommandBuffers) return false;
    auto parameters = parameterResource.object;
    NSUInteger parameterBytes = count * (indices ? 20 : 16);
    const uint8_t* published = nullptr;
    if (d.indirectReuseEnabled || d.cpuIndirectEnabled) {
        auto& source = parameterResource;
        if (!source.cpuIndirectTracking) ++d.indirectSnapshotUntracked;
        else if (source.cpuIndirectPoisoned) ++d.indirectSnapshotPoisoned;
        else if (source.cpuIndirectMappings) ++d.indirectSnapshotMapped;
        else {
            published = source.publishedCpuIndirect(offset, parameterBytes);
            if (published) ++d.indirectSnapshotCovered;
            else ++d.indirectSnapshotUncovered;
        }
        if (published) {
            if (d.indirectReuseEnabled) ++d.indirectReuseEligible;
        } else if (d.indirectReuseEnabled) ++d.indirectReuseIneligible;
    }
    // Each batch gets a separate slot; the existing three-submission ring only reuses slots
    // after its completion semaphore. Never overwrite commands or pointers still in flight.
    auto& frame = *d.currentUploads;
    if (frame.indirectCursor == frame.indirectBatches.size()) frame.indirectBatches.emplace_back();
    auto& batch = frame.indirectBatches[frame.indirectCursor++];
    if (d.indirectReuseEnabled && published && batch.commands && batch.cachedIndices == indices && batch.cachedTopology == topology &&
        batch.cachedIndex32 == index32 && batch.cachedParameters.size() == parameterBytes &&
        std::memcmp(batch.cachedParameters.data(), published, parameterBytes) == 0) {
        // Arguments are baked into this completed ring slot, but all pipeline/vertex/uniform/
        // texture bindings remain inherited from the current encoder. Index contents stay live.
        if (indices) [d.renderEncoder useResource:indices usage:MTLResourceUsageRead stages:MTLRenderStageVertex];
        [d.renderEncoder executeCommandsInBuffer:batch.commands withRange:NSMakeRange(0, count)];
        d.renderEncoderHasDrawn = true;
        ++d.indirectReuseHits;
        ++d.indirectExecutions;
        return true;
    }
    if (published && d.indirectReuseEnabled) ++d.indirectReuseMisses;
    batch.cachedParameters.clear();
    batch.cachedIndices = nil;
    bool cpuEncode = d.cpuIndirectEnabled && published;
    MTLStorageMode storage = cpuEncode ? MTLStorageModeShared : MTLStorageModePrivate;
    if (batch.capacity < count || (batch.commands && batch.commands.storageMode != storage)) {
        NSUInteger capacity = 64;
        while (capacity < count) capacity *= 2;
        MTLIndirectCommandBufferDescriptor* descriptor = [MTLIndirectCommandBufferDescriptor new];
        descriptor.commandTypes = MTLIndirectCommandTypeDraw | MTLIndirectCommandTypeDrawIndexed;
        descriptor.inheritPipelineState = YES;
        descriptor.inheritBuffers = YES;
        descriptor.maxVertexBufferBindCount = 0;
        descriptor.maxFragmentBufferBindCount = 0;
        batch.commands = [d.object newIndirectCommandBufferWithDescriptor:descriptor maxCommandCount:capacity
            options:cpuEncode ? MTLResourceStorageModeShared : MTLResourceStorageModePrivate];
        if (!batch.commands) throw std::runtime_error("Could not allocate indirect command buffer");
        d.requireTracked(batch.commands);
        batch.capacity = capacity;
    }
    if (cpuEncode) {
        uint64_t start = monotonicNanos();
        populateCpuIndirect(batch, published, count, topology, indices, index32);
        d.cpuIndirectPopulateNanos += monotonicNanos() - start;
        ++d.cpuIndirectBatches;
        d.cpuIndirectCommands += count;
        if (d.indirectReuseEnabled) {
            batch.cachedParameters.assign(published, published + parameterBytes);
            batch.cachedIndices = indices;
            batch.cachedTopology = topology;
            batch.cachedIndex32 = index32;
        }
        // CPU publication precedes submission. No compute conversion, encoder boundary, or
        // extra GPU synchronization is needed; inherited bindings are the current live state.
        if (indices) [d.renderEncoder useResource:indices usage:MTLResourceUsageRead stages:MTLRenderStageVertex];
        [d.renderEncoder executeCommandsInBuffer:batch.commands withRange:NSMakeRange(0, count)];
        d.renderEncoderHasDrawn = true;
        ++d.indirectExecutions;
        return true;
    }
    if (!d.indirectEncoderPipeline) {
        auto library = compile(d.object, [NSString stringWithUTF8String:indirectEncoderSource], "indirect command encoder");
        auto function = [library newFunctionWithName:@"encode_indirect"];
        NSError* error = nil;
        d.indirectEncoderPipeline = [d.object newComputePipelineStateWithFunction:function error:&error];
        if (!d.indirectEncoderPipeline) throw std::runtime_error(std::string("Could not create indirect command encoder: ") + error.localizedDescription.UTF8String);
        d.indirectArgumentEncoder = [function newArgumentEncoderWithBufferIndex:0];
        if (!d.indirectArgumentEncoder) throw std::runtime_error("Could not create indirect command argument encoder");
    }
    if (!batch.arguments) {
        batch.arguments = [d.object newBufferWithLength:d.indirectArgumentEncoder.encodedLength options:MTLResourceStorageModeShared];
        if (!batch.arguments) throw std::runtime_error("Could not allocate indirect command argument buffer");
        d.requireTracked(batch.arguments);
    }
    [d.indirectArgumentEncoder setArgumentBuffer:batch.arguments offset:0];
    [d.indirectArgumentEncoder setIndirectCommandBuffer:batch.commands atIndex:0];
    struct { uint32_t count, primitive, indexed, index32; } values = {
        static_cast<uint32_t>(count), static_cast<uint32_t>(topology), indices ? 1u : 0u, index32 ? 1u : 0u};
    bool afterDraw = d.renderEncoderHasDrawn;
    uint64_t attachmentBytes = indirectSplitAttachmentBytes(d.renderDescriptor);
    d.suspendRender();
    auto compute = [d.commands() computeCommandEncoder];
    if (!compute) throw std::runtime_error("Could not start indirect command encoding");
    d.waitForComputeFence(compute);
    [compute setComputePipelineState:d.indirectEncoderPipeline];
    [compute setBuffer:batch.arguments offset:0 atIndex:0];
    [compute setBuffer:parameters offset:offset atIndex:1];
    // The non-indexed branch never dereferences this pointer, but the argument remains bound.
    [compute setBuffer:indices ? indices : parameters offset:0 atIndex:2];
    [compute setBytes:&values length:sizeof(values) atIndex:3];
    [compute useResource:batch.commands usage:MTLResourceUsageWrite];
    NSUInteger width = d.indirectEncoderPipeline.threadExecutionWidth;
    [compute dispatchThreadgroups:MTLSizeMake((count + width - 1) / width, 1, 1) threadsPerThreadgroup:MTLSizeMake(width, 1, 1)];
    d.updateComputeFence(compute);
    [compute endEncoding];
    if (published) {
        batch.cachedParameters.assign(published, published + parameterBytes);
        batch.cachedIndices = indices;
        batch.cachedTopology = topology;
        batch.cachedIndex32 = index32;
    }
    d.resumeRender();
    prepareTextureArguments(d);
    // The index pointer is encoded by the GPU, so declare its indirect residency explicitly.
    // Parent encoder bindings retain all inherited vertex/fragment buffers and sampled textures.
    if (indices) [d.renderEncoder useResource:indices usage:MTLResourceUsageRead stages:MTLRenderStageVertex];
    [d.renderEncoder executeCommandsInBuffer:batch.commands withRange:NSMakeRange(0, count)];
    d.renderEncoderHasDrawn = true;
    ++d.indirectExecutions;
    if (afterDraw) { ++d.indirectAfterDrawSplits; d.indirectAfterDrawAttachmentBytes += attachmentBytes; }
    else { ++d.indirectFirstDrawSplits; d.indirectFirstDrawAttachmentBytes += attachmentBytes; }
    return true;
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
        d.renderEncoderHasDrawn = true;
    }
    d.endRender();
}
extern "C" {
static jlong createPipelineWithPolicies(JNIEnv* env, jlong device, jstring label, jstring vertexMsl, jstring fragmentMsl, jstring fragmentWithoutDepthMsl, jintArray attributes, jintArray layouts, jintArray colors, jint depthCompare, jboolean depthWrite, jboolean cull, jboolean wireframe, jfloat depthBias, jfloat depthSlope, jintArray argumentResources, int vertexMathMode, int fragmentMathMode) {
    return guarded(env, [&]() -> jlong {
        auto& d = get<Device>(device);
        auto pipeline = std::make_unique<Pipeline>();
        pipeline->vertexMathMode = effectiveMathMode(d.object, vertexMathMode);
        pipeline->fragmentMathMode = effectiveMathMode(d.object, fragmentMathMode);
        auto vertex = compile(d.object, nsString(env, vertexMsl), "vertex", pipeline->vertexMathMode);
        auto fragment = compile(d.object, nsString(env, fragmentMsl), "fragment", pipeline->fragmentMathMode);
        MTLRenderPipelineDescriptor* desc = [MTLRenderPipelineDescriptor new];
        desc.label = nsString(env, label);
        desc.supportIndirectCommandBuffers = d.indirectEnabled && d.indirectSupported;
        desc.vertexFunction = [vertex newFunctionWithName:@"main0"];
        desc.fragmentFunction = [fragment newFunctionWithName:@"main0"];
        if (!desc.vertexFunction || !desc.fragmentFunction) throw std::invalid_argument("Translated Metal shader has no main0 entrypoint");
        if (fragmentWithoutDepthMsl) {
            auto noDepthFragment = compile(d.object, nsString(env, fragmentWithoutDepthMsl), "fragment without depth", pipeline->fragmentMathMode);
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
        auto resourceArguments = integers(env, argumentResources);
        if (resourceArguments.size() % 4) throw std::invalid_argument("Invalid argument buffer resource metadata");
        for (size_t i = 0; i < resourceArguments.size(); i += 4) {
            int stages = resourceArguments[i], active = resourceArguments[i + 1], index = resourceArguments[i + 2];
            bool sampler = resourceArguments[i + 3] != 0;
            if (!stages || (stages & ~3) || active < 0 || (active & ~stages))
                throw std::invalid_argument("Invalid active argument buffer stages");
            if (index < 0 || index >= 128 || (sampler && index >= 16)) throw std::invalid_argument("Invalid texture argument index");
            if (stages & 1) {
                pipeline->vertexArguments.textures.push_back(index);
                if (active & 1) pipeline->vertexArguments.residentTextures.push_back(index);
                if (sampler) pipeline->vertexArguments.samplers.push_back(index);
            }
            if (stages & 2) {
                pipeline->fragmentArguments.textures.push_back(index);
                if (active & 2) pipeline->fragmentArguments.residentTextures.push_back(index);
                if (sampler) pipeline->fragmentArguments.samplers.push_back(index);
            }
        }
        if (!pipeline->vertexArguments.textures.empty()) pipeline->vertexArguments.encoder = [desc.vertexFunction newArgumentEncoderWithBufferIndex:29];
        if (!pipeline->fragmentArguments.textures.empty()) pipeline->fragmentArguments.encoder = [desc.fragmentFunction newArgumentEncoderWithBufferIndex:29];
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
JNIEXPORT jlong JNICALL NATIVE(createPipelineWithArgumentBuffers)(JNIEnv* env, jclass, jlong device, jstring label, jstring vertexMsl, jstring fragmentMsl, jstring fragmentWithoutDepthMsl, jintArray attributes, jintArray layouts, jintArray colors, jint depthCompare, jboolean depthWrite, jboolean cull, jboolean wireframe, jfloat depthBias, jfloat depthSlope, jintArray argumentResources) {
    int mathMode = legacyMathMode();
    return createPipelineWithPolicies(env, device, label, vertexMsl, fragmentMsl, fragmentWithoutDepthMsl,
        attributes, layouts, colors, depthCompare, depthWrite, cull, wireframe, depthBias, depthSlope,
        argumentResources, mathMode, mathMode);
}
JNIEXPORT jlong JNICALL NATIVE(createPipelineWithMathPolicy)(JNIEnv* env, jclass, jlong device, jstring label, jstring vertexMsl, jstring fragmentMsl, jstring fragmentWithoutDepthMsl, jintArray attributes, jintArray layouts, jintArray colors, jint depthCompare, jboolean depthWrite, jboolean cull, jboolean wireframe, jfloat depthBias, jfloat depthSlope, jintArray argumentResources, jint fragmentMathMode) {
    return createPipelineWithPolicies(env, device, label, vertexMsl, fragmentMsl, fragmentWithoutDepthMsl,
        attributes, layouts, colors, depthCompare, depthWrite, cull, wireframe, depthBias, depthSlope,
        argumentResources, 0, fragmentMathMode);
}
JNIEXPORT jintArray JNICALL NATIVE(pipelineMathModes)(JNIEnv* env, jclass, jlong pipeline) {
    return guarded(env, [&]() -> jintArray {
        auto& p = get<Pipeline>(pipeline);
        jint modes[] = {p.vertexMathMode, p.fragmentMathMode};
        auto result = env->NewIntArray(2);
        if (result) env->SetIntArrayRegion(result, 0, 2, modes);
        return result;
    });
}
JNIEXPORT jlong JNICALL NATIVE(createPipelineWithDepthVariants)(JNIEnv* env, jclass cls, jlong device, jstring label, jstring vertexMsl, jstring fragmentMsl, jstring fragmentWithoutDepthMsl, jintArray attributes, jintArray layouts, jintArray colors, jint depthCompare, jboolean depthWrite, jboolean cull, jboolean wireframe, jfloat depthBias, jfloat depthSlope) {
    return NATIVE(createPipelineWithArgumentBuffers)(env, cls, device, label, vertexMsl, fragmentMsl, fragmentWithoutDepthMsl,
        attributes, layouts, colors, depthCompare, depthWrite, cull, wireframe, depthBias, depthSlope, nullptr);
}
JNIEXPORT jlong JNICALL NATIVE(createPipeline)(JNIEnv* env, jclass cls, jlong device, jstring label, jstring vertexMsl, jstring fragmentMsl, jintArray attributes, jintArray layouts, jintArray colors, jint depthCompare, jboolean depthWrite, jboolean cull, jboolean wireframe, jfloat depthBias, jfloat depthSlope) {
    return NATIVE(createPipelineWithDepthVariants)(env, cls, device, label, vertexMsl, fragmentMsl, nullptr,
        attributes, layouts, colors, depthCompare, depthWrite, cull, wireframe, depthBias, depthSlope);
}
JNIEXPORT void JNICALL NATIVE(beginRenderPassWithDiscard)(JNIEnv* env, jclass, jlong device, jstring label, jlongArray colors, jfloatArray clearColors, jlong depth, jdouble clearDepth, jint x, jint y, jint width, jint height, jint discardColorMask) {
    guarded(env, [&] {
        std::vector<jlong> handles(colors ? env->GetArrayLength(colors) : 0);
        if (handles.size()>8) throw std::invalid_argument("Metal supports up to eight color attachments");
        if (!handles.empty()) env->GetLongArrayRegion(colors, 0, handles.size(), handles.data());
        std::vector<id<MTLTexture>> textures;
        for (auto h : handles) textures.push_back(h ? get<Texture>(h).object : nil);
        std::vector<float> clears(clearColors ? env->GetArrayLength(clearColors) : 0);
        if (!clears.empty()) env->GetFloatArrayRegion(clearColors, 0, clears.size(), clears.data());
        beginPass(get<Device>(device), nsString(env, label), textures, clears, depth ? get<Texture>(depth).object : nil, clearDepth, x,y,width,height,discardColorMask & 255);
    });
}
JNIEXPORT void JNICALL NATIVE(beginRenderPass)(JNIEnv* env, jclass cls, jlong device, jstring label, jlongArray colors, jfloatArray clearColors, jlong depth, jdouble clearDepth, jint x, jint y, jint width, jint height) {
    NATIVE(beginRenderPassWithDiscard)(env, cls, device, label, colors, clearColors, depth, clearDepth, x, y, width, height, 0);
}
JNIEXPORT void JNICALL NATIVE(endRenderPass)(JNIEnv* env, jclass, jlong device) { guarded(env, [&] { get<Device>(device).endRender(); }); }
JNIEXPORT void JNICALL NATIVE(pushDebugGroup)(JNIEnv* env, jclass, jlong device, jstring label) { guarded(env, [&] { auto& d = get<Device>(device); d.requireRender(); NSString* text = nsString(env, label); d.renderState.debugGroups.push_back(text); [d.renderEncoder pushDebugGroup:text]; }); }
JNIEXPORT void JNICALL NATIVE(popDebugGroup)(JNIEnv* env, jclass, jlong device) { guarded(env, [&] { auto& d = get<Device>(device); d.requireRender(); if (d.renderState.debugGroups.empty()) throw std::logic_error("No Metal debug group to pop"); d.renderState.debugGroups.pop_back(); [d.renderEncoder popDebugGroup]; }); }
JNIEXPORT void JNICALL NATIVE(bindPipeline)(JNIEnv* env, jclass, jlong device, jlong pipeline) {
    guarded(env, [&] {
        auto& d = get<Device>(device); auto& p = get<Pipeline>(pipeline); d.requireRender();
        d.renderState.pipeline = p.state(d.object, d.depthFormat, d.colorFormats);
        d.renderState.vertexArguments = p.vertexArguments;
        d.renderState.fragmentArguments = p.fragmentArguments;
        d.renderState.textureArgumentsDirty = true;
        d.renderState.textureArgumentsResident = false;
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
        d.renderState.textureArgumentsDirty = true;
        if (stageMask & 1) { d.renderState.vertexTextures[index] = texture; d.renderState.vertexSamplers[index] = sampling; [d.renderEncoder setVertexTexture:texture atIndex:index]; [d.renderEncoder setVertexSamplerState:sampling atIndex:index]; }
        if (stageMask & 2) { d.renderState.fragmentTextures[index] = texture; d.renderState.fragmentSamplers[index] = sampling; [d.renderEncoder setFragmentTexture:texture atIndex:index]; [d.renderEncoder setFragmentSamplerState:sampling atIndex:index]; }
    });
}
JNIEXPORT void JNICALL NATIVE(bindTexelBuffer)(JNIEnv* env, jclass, jlong device, jint stageMask, jint index, jlong buffer, jlong offset, jlong length, jstring format) {
    guarded(env, [&] {
        auto& d = get<Device>(device); d.requireRender();
        auto& b = get<Buffer>(buffer);
        d.renderState.textureArgumentsDirty = true;
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
            d.requireTracked(texture);
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
        prepareTextureArguments(d);
        if (topology == 5) {
            if (vertexCount<3) return;
            std::vector<uint32_t> indices((vertexCount-2)*3);
            for (int n=0; n<vertexCount-2; ++n) { indices[n*3]=firstVertex; indices[n*3+1]=firstVertex+n+1; indices[n*3+2]=firstVertex+n+2; }
            auto upload = d.upload(indices.data(), indices.size()*sizeof(uint32_t));
            [d.renderEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:indices.size() indexType:MTLIndexTypeUInt32 indexBuffer:upload.buffer indexBufferOffset:upload.offset instanceCount:instances baseVertex:0 baseInstance:baseInstance];
        } else [d.renderEncoder drawPrimitives:primitiveType(topology) vertexStart:firstVertex vertexCount:vertexCount instanceCount:instances baseInstance:baseInstance];
        d.renderEncoderHasDrawn = true;
    });
}
JNIEXPORT void JNICALL NATIVE(drawIndexed)(JNIEnv* env, jclass, jlong device, jint topology, jlong indices, jboolean index32, jlong indexOffset, jint indexCount, jint baseVertex, jint instances, jint baseInstance) {
    guarded(env, [&] {
        auto& d = get<Device>(device); d.requireRender(); if (d.emptyScissor || indexCount<=0 || instances<=0) return;
        if (topology == 5) throw std::invalid_argument("Metal indexed triangle fans require triangle-list indices");
        prepareTextureArguments(d);
        auto buffer = get<Buffer>(indices).object;
        validateRange(buffer.length, indexOffset, static_cast<int64_t>(indexCount)*(index32 ? 4 : 2));
        [d.renderEncoder drawIndexedPrimitives:primitiveType(topology) indexCount:indexCount indexType:index32 ? MTLIndexTypeUInt32 : MTLIndexTypeUInt16 indexBuffer:buffer indexBufferOffset:indexOffset instanceCount:instances baseVertex:baseVertex baseInstance:baseInstance];
        d.renderEncoderHasDrawn = true;
    });
}
JNIEXPORT jboolean JNICALL NATIVE(indirectCommandsEnabled)(JNIEnv* env, jclass, jlong device) {
    return guarded(env, [&]() -> jboolean { auto& d = get<Device>(device); return d.indirectEnabled && d.indirectSupported; });
}
JNIEXPORT void JNICALL NATIVE(configureIndirectCommands)(JNIEnv* env, jclass, jlong device, jboolean enabled, jint threshold) {
    guarded(env, [&] {
        if (threshold < 1) throw std::invalid_argument("Indirect command threshold must be positive");
        auto& d = get<Device>(device);
        d.indirectEnabled = enabled;
        d.indirectThreshold = threshold;
        const char* cpu = std::getenv("MINECRAFT_METAL_CPU_ICB");
        d.cpuIndirectEnabled = cpu && std::strcmp(cpu, "1") == 0;
    });
}
JNIEXPORT jlong JNICALL NATIVE(indirectCommandExecutions)(JNIEnv* env, jclass, jlong device) {
    return guarded(env, [&]() -> jlong { return get<Device>(device).indirectExecutions; });
}
JNIEXPORT jlongArray JNICALL NATIVE(indirectSplitStatistics)(JNIEnv* env, jclass, jlong device) {
    return guarded(env, [&]() -> jlongArray {
        auto& d = get<Device>(device);
        jlong values[] = {static_cast<jlong>(d.indirectFirstDrawSplits), static_cast<jlong>(d.indirectAfterDrawSplits),
            static_cast<jlong>(d.indirectFirstDrawAttachmentBytes), static_cast<jlong>(d.indirectAfterDrawAttachmentBytes)};
        jlongArray result = env->NewLongArray(4);
        if (result) env->SetLongArrayRegion(result, 0, 4, values);
        return result;
    });
}
JNIEXPORT jlongArray JNICALL NATIVE(indirectReuseStatistics)(JNIEnv* env, jclass, jlong device) {
    return guarded(env, [&]() -> jlongArray {
        auto& d = get<Device>(device);
        jlong values[] = {d.indirectReuseEnabled ? 1 : 0, static_cast<jlong>(d.indirectReuseEligible),
            static_cast<jlong>(d.indirectReuseHits), static_cast<jlong>(d.indirectReuseMisses),
            static_cast<jlong>(d.indirectReuseIneligible), static_cast<jlong>(d.indirectPublishedBytes)};
        jlongArray result = env->NewLongArray(6);
        if (result) env->SetLongArrayRegion(result, 0, 6, values);
        return result;
    });
}
JNIEXPORT jlongArray JNICALL NATIVE(cpuIndirectStatistics)(JNIEnv* env, jclass, jlong device) {
    return guarded(env, [&]() -> jlongArray {
        auto& d = get<Device>(device);
        jlong values[] = {d.cpuIndirectEnabled ? 1 : 0, static_cast<jlong>(d.cpuIndirectBatches),
            static_cast<jlong>(d.cpuIndirectCommands), static_cast<jlong>(d.cpuIndirectPopulateNanos)};
        jlongArray result = env->NewLongArray(4);
        if (result) env->SetLongArrayRegion(result, 0, 4, values);
        return result;
    });
}
JNIEXPORT jlongArray JNICALL NATIVE(indirectSnapshotStatistics)(JNIEnv* env, jclass, jlong device) {
    return guarded(env, [&]() -> jlongArray {
        auto& d = get<Device>(device);
        jlong values[] = {static_cast<jlong>(d.indirectSnapshotCovered), static_cast<jlong>(d.indirectSnapshotUntracked),
            static_cast<jlong>(d.indirectSnapshotPoisoned), static_cast<jlong>(d.indirectSnapshotMapped),
            static_cast<jlong>(d.indirectSnapshotUncovered), static_cast<jlong>(d.indirectSnapshotEvicted),
            static_cast<jlong>(d.indirectSnapshotPublished)};
        jlongArray result = env->NewLongArray(7);
        if (result) env->SetLongArrayRegion(result, 0, 7, values);
        return result;
    });
}
JNIEXPORT jstring JNICALL NATIVE(hazardSynchronizationMode)(JNIEnv* env, jclass, jlong device) {
    return guarded(env, [&]() -> jstring {
        auto& d = get<Device>(device);
        return env->NewStringUTF(d.timestampFencesRequired ? "global-fences-timestamps" :
            d.trackedHazardsRequested ? "tracked-resources" : "global-fences");
    });
}
JNIEXPORT void JNICALL NATIVE(drawIndirect)(JNIEnv* env, jclass, jlong device, jint topology, jlong parameters, jlong offset, jint count, jlong indices, jboolean index32) {
    guarded(env, [&] {
        auto& d = get<Device>(device); d.requireRender(); if (d.emptyScissor || count<=0) return;
        if (topology == 5) throw std::invalid_argument("Metal indirect triangle fans require triangle-list indices");
        auto buffer = get<Buffer>(parameters).object;
        NSUInteger stride = indices ? sizeof(MTLDrawIndexedPrimitivesIndirectArguments) : sizeof(MTLDrawPrimitivesIndirectArguments);
        try {
            validateRange(buffer.length, offset, stride*count);
        } catch (const std::out_of_range& error) {
            throw std::out_of_range(std::string("Indirect argument commands (buffer=") +
                (buffer.label ? buffer.label.UTF8String : "unlabeled") +
                ", count=" + std::to_string(count) + ", stride=" + std::to_string(stride) +
                "): " + error.what());
        }
        auto indexBuffer = indices ? get<Buffer>(indices).object : nil;
        prepareTextureArguments(d);
        if (drawWithIndirectCommands(d, primitiveType(topology), get<Buffer>(parameters), offset, count, indexBuffer, index32)) return;
        for (int i=0; i<count; ++i) {
            if (indexBuffer) [d.renderEncoder drawIndexedPrimitives:primitiveType(topology) indexType:index32 ? MTLIndexTypeUInt32 : MTLIndexTypeUInt16 indexBuffer:indexBuffer indexBufferOffset:0 indirectBuffer:buffer indirectBufferOffset:offset+i*stride];
            else [d.renderEncoder drawPrimitives:primitiveType(topology) indirectBuffer:buffer indirectBufferOffset:offset+i*stride];
        }
        d.renderEncoderHasDrawn = true;
    });
}
JNIEXPORT void JNICALL NATIVE(submit)(JNIEnv* env, jclass, jlong device) { guarded(env, [&] { get<Device>(device).submit(); }); }
JNIEXPORT jlong JNICALL NATIVE(createFence)(JNIEnv* env, jclass, jlong device) {
    return guarded(env, [&]() -> jlong {
        auto& d = get<Device>(device);
        auto fence = std::make_unique<Fence>(); fence->state = std::make_shared<FenceState>();
        fence->executionStats = d.executionStats;
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
        const uint64_t start = timeoutNanos != 0 ? monotonicNanos() : 0;
        std::unique_lock lock(state->mutex);
        const bool blocked = !state->completed;
        bool completed = true;
        if (timeoutNanos<0 || timeoutNanos == std::numeric_limits<jlong>::max()) state->condition.wait(lock, [&] { return state->completed; });
        else completed = state->condition.wait_for(lock, std::chrono::nanoseconds(timeoutNanos), [&] { return state->completed; });
        // Zero-timeout availability probes are not waits. Capture CPU elapsed time only;
        // no extra fence, submission, or polling is introduced by these counters.
        if (timeoutNanos != 0) {
            auto stats = f.executionStats;
            stats->fenceWaitCalls.fetch_add(1, std::memory_order_relaxed);
            if (blocked) stats->fenceWaitBlockedCalls.fetch_add(1, std::memory_order_relaxed);
            stats->fenceWaitNanos.fetch_add(monotonicNanos() - start, std::memory_order_relaxed);
        }
        if (!completed) return false;
        if (!state->error.empty()) throw std::runtime_error("GPU fence failed: " + state->error);
        return true;
    });
}
JNIEXPORT jlongArray JNICALL NATIVE(fenceWaitStatistics)(JNIEnv* env, jclass, jlong device) {
    return guarded(env, [&]() -> jlongArray {
        auto stats = get<Device>(device).executionStats;
        jlong values[] = {static_cast<jlong>(stats->fenceWaitCalls.load(std::memory_order_relaxed)),
            static_cast<jlong>(stats->fenceWaitBlockedCalls.load(std::memory_order_relaxed)),
            static_cast<jlong>(stats->fenceWaitNanos.load(std::memory_order_relaxed))};
        jlongArray result = env->NewLongArray(3);
        if (result) env->SetLongArrayRegion(result, 0, 3, values);
        return result;
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
        d.requireTimestampOrdering();
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

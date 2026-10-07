#include "MetalContext.hpp"
#include "MetalShaders.hpp"
#import <QuartzCore/CATransaction.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>

using namespace metal;
#define NATIVE(name) Java_dev_kausik_metal_MetalNative_##name
static void mainThread(dispatch_block_t block) {
    if ([NSThread isMainThread]) block(); else dispatch_sync(dispatch_get_main_queue(), block);
}
void metal::preparePresentation(Device& d) {
    if (d.presentationPipeline) return;
    NSString* source = [NSString stringWithUTF8String:presentationShader];
    NSError* error = nil;
    auto library = [d.object newLibraryWithSource:source options:nil error:&error];
    if (!library) throw std::runtime_error(std::string("Presentation shader compilation failed: ") + error.localizedDescription.UTF8String);
    MTLRenderPipelineDescriptor* desc = [MTLRenderPipelineDescriptor new];
    desc.label = @"Minecraft direct Metal presentation";
    desc.vertexFunction = [library newFunctionWithName:@"present_v"];
    desc.fragmentFunction = [library newFunctionWithName:@"present_f"];
    desc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    d.presentationPipeline = [d.object newRenderPipelineStateWithDescriptor:desc error:&error];
    if (!d.presentationPipeline) throw std::runtime_error(std::string("Presentation pipeline compilation failed: ") + error.localizedDescription.UTF8String);
    MTLSamplerDescriptor* sample = [MTLSamplerDescriptor new];
    sample.minFilter = MTLSamplerMinMagFilterLinear; sample.magFilter = MTLSamplerMinMagFilterLinear;
    sample.sAddressMode = MTLSamplerAddressModeClampToEdge; sample.tAddressMode = MTLSamplerAddressModeClampToEdge;
    d.presentationSampler = [d.object newSamplerStateWithDescriptor:sample];
}
extern "C" {
JNIEXPORT jlong JNICALL NATIVE(createSurface)(JNIEnv* env, jclass, jlong device, jlong metalLayer) {
    return guarded(env, [&]() -> jlong {
        if (!metalLayer) throw std::invalid_argument("SDL returned a null CAMetalLayer");
        auto& d = get<Device>(device);
        auto surface = std::make_unique<Surface>();
        Surface* s = surface.get();
        s->executionStats = d.executionStats;
        const char* offscreen = std::getenv("MINECRAFT_METAL_OFFSCREEN_PRESENT");
        s->offscreenPresent = offscreen && std::strcmp(offscreen, "1") == 0;
        CAMetalLayer* layer = (__bridge CAMetalLayer*)reinterpret_cast<void*>(metalLayer);
        if (![layer isKindOfClass:[CAMetalLayer class]]) throw std::invalid_argument("SDL Metal view did not expose a CAMetalLayer");
        id<MTLDevice> gpu = d.object;
        mainThread(^{
            s->layer = layer;
            layer.device = gpu;
            layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
            layer.framebufferOnly = YES;
            layer.opaque = YES;
            layer.maximumDrawableCount = 3;
            layer.allowsNextDrawableTimeout = YES;
        });
        preparePresentation(d);
        return retainResource(std::move(surface));
    });
}
JNIEXPORT void JNICALL NATIVE(configureSurface)(JNIEnv* env, jclass, jlong surface, jint width, jint height, jboolean vsync) {
    guarded(env, [&] {
        auto& s = get<Surface>(surface);
        if (width<=0 || height<=0) return;
        CAMetalLayer* layer = s.layer;
        mainThread(^{
            [CATransaction begin]; [CATransaction setDisableActions:YES];
            layer.drawableSize = CGSizeMake(width,height);
            layer.displaySyncEnabled = vsync;
            [CATransaction commit];
        });
        if (s.offscreenPresent && (s.offscreenTargets.empty()
                || s.offscreenTargets[0].width != static_cast<NSUInteger>(width)
                || s.offscreenTargets[0].height != static_cast<NSUInteger>(height))) {
            MTLTextureDescriptor* desc = [MTLTextureDescriptor
                texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                width:width height:height mipmapped:NO];
            desc.storageMode = MTLStorageModePrivate;
            desc.hazardTrackingMode = MTLHazardTrackingModeTracked;
            desc.usage = MTLTextureUsageRenderTarget;
            std::vector<id<MTLTexture>> replacements;
            for (NSUInteger i = 0; i < 3; ++i) {
                id<MTLTexture> target = [layer.device newTextureWithDescriptor:desc];
                if (!target) throw std::runtime_error("Could not allocate offscreen presentation diagnostic target");
                target.label = [NSString stringWithFormat:@"Offscreen presentation diagnostic %lu", i];
                replacements.push_back(target);
            }
            // Submitted command buffers retain replaced targets until their GPU work completes.
            // Tracked hazards order later reuse of a ring slot without display-pacing waits.
            s.offscreenTargets = std::move(replacements);
            s.offscreenIndex = 0;
            s.offscreenAcquired = false;
            fprintf(stderr, "[Minecraft Metal] OFFSCREEN PRESENT DIAGNOSTIC active: %d x %d, "
                "BGRA8Unorm, 3 private targets; final compositing retained, "
                "CAMetalLayer acquire/present bypassed. Throughput is not visible FPS.\n", width, height);
        }
    });
}
JNIEXPORT void JNICALL NATIVE(acquireSurface)(JNIEnv* env, jclass, jlong surface) {
    guarded(env, [&] {
        auto& s = get<Surface>(surface);
        if (s.offscreenPresent) {
            if (s.offscreenTargets.empty())
                throw std::runtime_error("Offscreen presentation diagnostic surface is not configured");
            s.offscreenAcquired = true;
            return;
        }
        if (!s.drawable) {
            uint64_t start = monotonicNanos();
            s.drawable = [s.layer nextDrawable];
            s.executionStats->drawableAcquireNanos.fetch_add(monotonicNanos() - start, std::memory_order_relaxed);
            s.executionStats->drawableAcquires.fetch_add(1, std::memory_order_relaxed);
            if (!s.drawable) s.executionStats->drawableTimeouts.fetch_add(1, std::memory_order_relaxed);
        }
        if (!s.drawable) throw std::runtime_error("Timed out acquiring an SDL Metal drawable");
    });
}
JNIEXPORT void JNICALL NATIVE(blitSurface)(JNIEnv* env, jclass, jlong device, jlong surface, jlong textureView) {
    guarded(env, [&] {
        auto& d = get<Device>(device); auto& s = get<Surface>(surface);
        if (s.offscreenPresent ? !s.offscreenAcquired : !s.drawable) return;
        auto source = get<Texture>(textureView).object;
        id<MTLTexture> target = s.offscreenPresent
            ? s.offscreenTargets[s.offscreenIndex] : s.drawable.texture;
        preparePresentation(d);
        // The fullscreen triangle overwrites every pixel; do not load a recycled drawable.
        beginPass(d, s.offscreenPresent ? @"Offscreen presentation diagnostic" : @"Present to CAMetalLayer",
            {target}, {0,0,0,0}, nil, NAN, 0,0,target.width,target.height);
        [d.renderEncoder setRenderPipelineState:d.presentationPipeline];
        [d.renderEncoder setCullMode:MTLCullModeNone];
        [d.renderEncoder setFragmentTexture:source atIndex:0];
        [d.renderEncoder setFragmentSamplerState:d.presentationSampler atIndex:0];
        [d.renderEncoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        d.renderEncoderHasDrawn = true;
        d.endRender();
        if (!s.offscreenPresent) [d.commands() presentDrawable:s.drawable];
    });
}
JNIEXPORT void JNICALL NATIVE(presentSurface)(JNIEnv* env, jclass, jlong surface) {
    guarded(env, [&] {
        auto& s = get<Surface>(surface);
        if (s.offscreenPresent) {
            if (s.offscreenAcquired) {
                s.offscreenAcquired = false;
                s.offscreenIndex = (s.offscreenIndex + 1) % s.offscreenTargets.size();
            }
        } else s.drawable = nil;
    });
}
}

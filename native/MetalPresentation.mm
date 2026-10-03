#include "MetalContext.hpp"
#include "MetalShaders.hpp"
#import <QuartzCore/CATransaction.h>

#include <algorithm>
#include <cmath>

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
    });
}
JNIEXPORT void JNICALL NATIVE(acquireSurface)(JNIEnv* env, jclass, jlong surface) {
    guarded(env, [&] {
        auto& s = get<Surface>(surface);
        if (!s.drawable) s.drawable = [s.layer nextDrawable];
        if (!s.drawable) throw std::runtime_error("Timed out acquiring an SDL Metal drawable");
    });
}
JNIEXPORT void JNICALL NATIVE(blitSurface)(JNIEnv* env, jclass, jlong device, jlong surface, jlong textureView) {
    guarded(env, [&] {
        auto& d = get<Device>(device); auto& s = get<Surface>(surface);
        if (!s.drawable) return;
        auto source = get<Texture>(textureView).object;
        auto target = s.drawable.texture;
        preparePresentation(d);
        beginPass(d, @"Present to CAMetalLayer", {target}, {NAN,0,0,0}, nil, NAN, 0,0,target.width,target.height);
        [d.renderEncoder setRenderPipelineState:d.presentationPipeline];
        [d.renderEncoder setCullMode:MTLCullModeNone];
        [d.renderEncoder setFragmentTexture:source atIndex:0];
        [d.renderEncoder setFragmentSamplerState:d.presentationSampler atIndex:0];
        [d.renderEncoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        d.endRender();
        [d.commands() presentDrawable:s.drawable];
    });
}
JNIEXPORT void JNICALL NATIVE(presentSurface)(JNIEnv* env, jclass, jlong surface) {
    guarded(env, [&] { get<Surface>(surface).drawable = nil; });
}
}

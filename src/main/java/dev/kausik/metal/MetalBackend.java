package dev.kausik.metal;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.lwjgl.sdl.SDLVideo;

/** A first-class RenderPearl backend with an SDL Metal window and no OpenGL context. */
public final class MetalBackend implements GpuBackend {
  @Override
  public String getName() {
    return "Metal";
  }

  @Override
  public void loadLibrary() throws BackendCreationException {
    try {
      MetalNative.load();
    } catch (RuntimeException | LinkageError failure) {
      throw failure("Unable to load native Metal", failure);
    }
  }

  @Override
  public void unloadLibrary() {
    // The JVM owns the JNI library handle. Devices and SDL surfaces release their own resources.
  }

  @Override
  public long createWindow(String title, int width, int height, long flags) {
    return SDLVideo.SDL_CreateWindow(title, width, height, flags | SDLVideo.SDL_WINDOW_METAL);
  }

  @Override
  public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
    try {
      return new FrontendGpuDevice(new MetalDevice());
    } catch (RuntimeException | LinkageError failure) {
      throw failure("Unable to initialize native Metal", failure);
    }
  }

  private static BackendCreationException failure(String message, Throwable cause) {
    BackendCreationException exception =
        new BackendCreationException(
            message + ": " + cause.getMessage(), BackendCreationException.Reason.OTHER);
    exception.initCause(cause);
    return exception;
  }
}

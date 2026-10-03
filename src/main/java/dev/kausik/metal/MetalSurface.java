package dev.kausik.metal;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLMetal;

/** Presents directly into the CAMetalLayer managed by SDL's native Metal view. */
public final class MetalSurface implements GpuSurfaceBackend {
  private static final Set<GpuSurface.PresentMode> PRESENT_MODES =
      Set.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE);
  private final MetalDevice device;
  private final BooleanSupplier isIconified;
  private long metalView;
  private long handle;

  public MetalSurface(MetalDevice device, long sdlWindow, BooleanSupplier isIconified) {
    this.device = device;
    this.isIconified = isIconified;
    metalView = SDLMetal.SDL_Metal_CreateView(sdlWindow);
    if (metalView == 0)
      throw new IllegalStateException(
          "Failed to create SDL Metal view: " + SDLError.SDL_GetError());
    try {
      long layer = SDLMetal.SDL_Metal_GetLayer(metalView);
      if (layer == 0)
        throw new IllegalStateException(
            "SDL Metal view has no CAMetalLayer: " + SDLError.SDL_GetError());
      handle = MetalNative.createSurface(device.handle(), layer);
      if (handle == 0) throw new IllegalStateException("Failed to configure SDL's CAMetalLayer");
    } catch (RuntimeException | LinkageError failure) {
      SDLMetal.SDL_Metal_DestroyView(metalView);
      metalView = 0;
      throw failure;
    }
  }

  public long handle() {
    if (handle == 0) throw new IllegalStateException("Surface is closed");
    return handle;
  }

  @Override
  public void configure(GpuSurface.Configuration configuration) throws SurfaceException {
    if (!PRESENT_MODES.contains(configuration.presentMode())) {
      throw new SurfaceException("Unsupported Metal present mode: " + configuration.presentMode());
    }
    if (configuration.width() <= 0 || configuration.height() <= 0) {
      throw new SurfaceException("Cannot configure a zero-sized Metal surface");
    }
    try {
      MetalNative.configureSurface(
          handle(),
          configuration.width(),
          configuration.height(),
          configuration.presentMode() == GpuSurface.PresentMode.FIFO);
    } catch (RuntimeException error) {
      throw new SurfaceException(error);
    }
  }

  @Override
  public boolean isSuboptimal() {
    return false;
  }

  @Override
  public void acquireNextTexture() throws SurfaceException {
    if (isIconified.getAsBoolean()) throw new SurfaceException("Cannot acquire minimized window");
    try {
      MetalNative.acquireSurface(handle());
    } catch (RuntimeException error) {
      throw new SurfaceException(error);
    }
  }

  @Override
  public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView texture) {
    MetalNative.blitSurface(device.handle(), handle(), ((MetalGpuTextureView) texture).handle());
  }

  @Override
  public void present() {
    MetalNative.presentSurface(handle());
  }

  @Override
  public Collection<GpuSurface.PresentMode> supportedPresentModes() {
    return PRESENT_MODES;
  }

  @Override
  public void close() {
    if (handle == 0 && metalView == 0) return;
    try {
      if (handle != 0) MetalNative.release(handle);
    } finally {
      handle = 0;
      if (metalView != 0) SDLMetal.SDL_Metal_DestroyView(metalView);
      metalView = 0;
    }
  }
}

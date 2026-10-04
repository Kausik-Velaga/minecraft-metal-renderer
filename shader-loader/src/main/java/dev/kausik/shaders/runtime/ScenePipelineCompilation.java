package dev.kausik.shaders.runtime;

import java.util.concurrent.Executor;

/** A scene cache miss must not wait for chunk workers that need this frame's upload to finish. */
public final class ScenePipelineCompilation {
  private static final Executor CALLER = Runnable::run;

  private ScenePipelineCompilation() {}

  public static Executor executor(Executor background, boolean renderingPack, boolean renderThread) {
    return renderingPack && renderThread ? CALLER : background;
  }
}

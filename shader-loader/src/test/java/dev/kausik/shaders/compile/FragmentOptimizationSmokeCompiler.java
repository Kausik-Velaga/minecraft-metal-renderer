package dev.kausik.shaders.compile;

import static org.lwjgl.util.shaderc.Shaderc.*;

import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.ShaderDefines;
import org.lwjgl.system.MemoryUtil;

/**
 * Plain JavaExec does not launch Fabric mixins. This test-only compiler applies the production
 * options helper at the equivalent point, then leaves real frontend reflection/linking and Metal
 * compilation in place. It only accepts the loader's fully expanded pack sources.
 */
public final class FragmentOptimizationSmokeCompiler extends GlslCompiler {
  private static final Method BASE_OPTIONS = baseOptions();
  private long compiler;
  private int optimizedFragments;
  private int compiledVertices;
  private boolean closed;

  private FragmentOptimizationSmokeCompiler(DeviceInfo info) {
    super(info.isZZeroToOne(), info.features().shaderDrawParameters());
  }

  /**
   * Must run before the test compiles any pipelines. The device owns and closes the replacement.
   */
  public static FragmentOptimizationSmokeCompiler install(FrontendGpuDevice device) {
    if (!ShadercFragmentOptimization.enabled())
      throw new IllegalStateException(
          "Only install the opt-in test compiler when the option is on");
    var replacement = new FragmentOptimizationSmokeCompiler(device.getDeviceInfo());
    try {
      Field builderField = FrontendGpuDevice.class.getDeclaredField("pipelineBuilder");
      builderField.setAccessible(true);
      Object builder = builderField.get(device);
      Field compilerField = builder.getClass().getDeclaredField("compiler");
      compilerField.setAccessible(true);
      GlslCompiler previous = (GlslCompiler) compilerField.get(builder);
      compilerField.set(builder, replacement);
      previous.close();
      return replacement;
    } catch (ReflectiveOperationException failure) {
      replacement.close();
      throw new IllegalStateException("Cannot install optimized-fragment smoke compiler", failure);
    }
  }

  @Override
  public synchronized SpvModule compileToSpv(
      String name, String source, ShaderType type, ShaderDefines defines, ShaderSource includes)
      throws ShaderCompileException {
    if (closed) throw new IllegalStateException("Test compiler is closed");
    if ((type != ShaderType.FRAGMENT
            && !(type == ShaderType.VERTEX && ShadercFragmentOptimization.vertexEnabled()))
        || !ShadercFragmentOptimization.PACK_SHADER.equals(name)) {
      SpvModule result = super.compileToSpv(name, source, type, defines, includes);
      if (type == ShaderType.VERTEX) compiledVertices++;
      return result;
    }
    // Production pack sources have already passed through include expansion. Fail closed if a
    // future smoke fixture adds includes rather than silently testing a different include path.
    if (source.matches("(?s).*#\\s*include\\b.*"))
      throw new ShaderCompileException("Optimized smoke requires fully expanded pack source");
    long options;
    try {
      // Use Minecraft's actual base options, including debug info and device-specific macros.
      options = (long) BASE_OPTIONS.invoke(this);
    } catch (ReflectiveOperationException failure) {
      Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
      throw new ShaderCompileException("Cannot obtain actual frontend shader options: " + cause);
    }
    try {
      ShadercFragmentOptimization.configure(options, name, type);
      defines
          .values()
          .forEach(
              (key, value) -> shaderc_compile_options_add_macro_definition(options, key, value));
      defines
          .flags()
          .forEach(flag -> shaderc_compile_options_add_macro_definition(options, flag, ""));
      if (compiler == 0) compiler = shaderc_compiler_initialize();
      if (compiler == 0) throw new ShaderCompileException("Cannot initialize smoke shaderc");
      long result =
          shaderc_compile_into_spv(
              compiler,
              source,
              type == ShaderType.VERTEX ? shaderc_vertex_shader : shaderc_fragment_shader,
              name,
              "main",
              options);
      try {
        if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
          throw new ShaderCompileException(shaderc_result_get_error_message(result));
        ByteBuffer bytes = shaderc_result_get_bytes(result);
        ByteBuffer owned = MemoryUtil.memAlloc(bytes.remaining()).order(ByteOrder.nativeOrder());
        owned.put(bytes).flip();
        if (type == ShaderType.FRAGMENT) optimizedFragments++;
        else compiledVertices++;
        return new SPIRVModule(owned, type);
      } finally {
        shaderc_result_release(result);
      }
    } finally {
      shaderc_compile_options_release(options);
    }
  }

  public synchronized void assertActivated(int expectedPipelines) {
    if (optimizedFragments != expectedPipelines || compiledVertices != expectedPipelines)
      throw new AssertionError(
          "Optimization smoke did not exercise every pipeline: optimized fragments="
              + optimizedFragments
              + ", compiled vertices="
              + compiledVertices
              + ", expected pipelines="
              + expectedPipelines);
    System.out.println(
        "Verified actual frontend optimization: "
            + optimizedFragments
            + " optimized fragments, "
            + compiledVertices
            + " vertex shaders (optimized="
            + ShadercFragmentOptimization.vertexEnabled()
            + ")");
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    if (compiler != 0) shaderc_compiler_release(compiler);
    compiler = 0;
    super.close();
  }

  private static Method baseOptions() {
    try {
      Method method = GlslCompiler.class.getDeclaredMethod("createBaseShaderOptions");
      method.setAccessible(true);
      return method;
    } catch (ReflectiveOperationException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }
}

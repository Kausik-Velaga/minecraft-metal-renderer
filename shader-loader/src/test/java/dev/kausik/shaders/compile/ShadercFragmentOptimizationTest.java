package dev.kausik.shaders.compile;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spvc.*;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/**
 * CPU-only checks for opt-in scope, surviving interfaces, std140 layout, and relinkable modules.
 */
public final class ShadercFragmentOptimizationTest {
  private static final String VERTEX =
      """
      #version 450
      layout(location=0) in vec3 Position;
      layout(location=1) in vec2 UnusedPosition;
      layout(location=0) out vec2 uv;
      layout(location=1) flat out int material;
      layout(std140) uniform Transform { mat4 matrix; };
      void main() { uv=Position.xy; material=3; gl_Position=matrix*vec4(Position,1.0); }
      """;
  private static final String FRAGMENT =
      """
      #version 450
      layout(location=0) in vec2 uv;
      layout(location=1) flat in int material;
      layout(location=0) out vec4 result;
      layout(std140) uniform Live {
        vec4 tint;
        float unusedMember;
        mat3 matrix;
        vec4 offsets[2];
      };
      layout(std140) uniform Unused { vec4 neverRead; };
      uniform sampler2D atlas;
      uniform sampler2D unusedSampler;
      vec4 shade(vec4 value) {
        vec4 scratch=value+tint;
        for (int i=0; i<2; ++i) scratch+=offsets[i];
        return vec4(matrix*scratch.rgb,scratch.a);
      }
      void main() {
        vec4 base=texture(atlas,uv);
        result=shade(base)+vec4(dFdx(uv.x),dFdy(uv.y),float(material),0.0);
      }
      """;

  public static void main(String[] args) throws Exception {
    long compiler = shaderc_compiler_initialize();
    if (compiler == 0) throw new AssertionError("Cannot initialize shaderc");
    try {
      String name = ShadercFragmentOptimization.PACK_SHADER;
      byte[] original = compile(compiler, name, ShaderType.FRAGMENT, FRAGMENT, false, false);
      byte[] optimized = compile(compiler, name, ShaderType.FRAGMENT, FRAGMENT, true, false);
      if (Arrays.equals(original, optimized))
        throw new AssertionError("Eligible fragment stayed opt0");
      if (count(optimized, 57) != 0 || count(original, 57) == 0)
        throw new AssertionError("Performance optimization did not inline the fixture's function");
      if (!Arrays.equals(
          optimized, compile(compiler, name, ShaderType.FRAGMENT, FRAGMENT, true, false)))
        throw new AssertionError("Identical compiler inputs produced different SPIR-V cache bytes");
      // The backend's translation cache keys the complete SPIR-V. Distinct compile modes must
      // remain distinct inputs, while repeat compiles in one immutable mode can share that key.
      if (java.util.Base64.getEncoder()
          .encodeToString(original)
          .equals(java.util.Base64.getEncoder().encodeToString(optimized)))
        throw new AssertionError("Optimization modes collided in the backend's SPIR-V cache key");

      for (String unowned :
          new String[] {
            "minecraft:core/terrain",
            "minecraft:core/text",
            "another_mod:pack",
            "minecraft_shader_loader:pack_extra",
            "minecraft_shader_loader:pack/fragment",
            "minecraft_shader_loader:clear",
            "minecraft_shader_loader:PACK"
          }) {
        byte[] off = compile(compiler, unowned, ShaderType.FRAGMENT, FRAGMENT, false, false);
        byte[] on = compile(compiler, unowned, ShaderType.FRAGMENT, FRAGMENT, true, false);
        if (!Arrays.equals(off, on))
          throw new AssertionError("Changed unowned fragment " + unowned);
      }
      byte[] vertex = compile(compiler, name, ShaderType.VERTEX, VERTEX, false, false);
      if (!Arrays.equals(vertex, compile(compiler, name, ShaderType.VERTEX, VERTEX, true, false)))
        throw new AssertionError("Optimization changed the pack vertex shader");
      byte[] optimizedVertex = compileVertex(compiler, name, VERTEX);
      if (Arrays.equals(vertex, optimizedVertex))
        throw new AssertionError("Explicit vertex optimization stayed opt0");
      try (var beforeVertex = module(vertex, ShaderType.VERTEX);
          var afterVertex = module(optimizedVertex, ShaderType.VERTEX)) {
        Map<String, String> beforeInterface = interfaces(beforeVertex.reflect());
        for (var entry : interfaces(afterVertex.reflect()).entrySet())
          if (!entry.getValue().equals(beforeInterface.get(entry.getKey())))
            throw new AssertionError("Vertex optimization changed live interface: " + entry);
      }
      for (String unowned : new String[] {"minecraft:core/terrain", "other:pack"})
        if (!Arrays.equals(
            compile(compiler, unowned, ShaderType.VERTEX, VERTEX, false, false),
            compileVertex(compiler, unowned, VERTEX)))
          throw new AssertionError("Vertex optimization changed unowned shader " + unowned);

      boolean startupMode = ShadercFragmentOptimization.enabled();
      String oldProperty = System.getProperty(ShadercFragmentOptimization.PROPERTY);
      try {
        System.setProperty(ShadercFragmentOptimization.PROPERTY, Boolean.toString(!startupMode));
        if (ShadercFragmentOptimization.enabled() != startupMode)
          throw new AssertionError("Mutable property invalidated the pipeline cache contract");
        byte[] configured = compile(compiler, name, ShaderType.FRAGMENT, FRAGMENT, false, true);
        if (!Arrays.equals(startupMode ? optimized : original, configured))
          throw new AssertionError("Production option helper did not retain its startup selection");
      } finally {
        if (oldProperty == null) System.clearProperty(ShadercFragmentOptimization.PROPERTY);
        else System.setProperty(ShadercFragmentOptimization.PROPERTY, oldProperty);
      }

      try (SPIRVModule baseline = module(original, ShaderType.FRAGMENT);
          SPIRVModule changed = module(optimized, ShaderType.FRAGMENT);
          SPIRVModule v = module(vertex, ShaderType.VERTEX)) {
        Map<String, String> old = interfaces(baseline.reflect());
        Map<String, String> current = interfaces(changed.reflect());
        for (var entry : current.entrySet())
          if (!entry.getValue().equals(old.get(entry.getKey())))
            throw new AssertionError("Changed live interface/name: " + entry);
        if (!current.containsKey("descriptor:Live")
            || !current.containsKey("descriptor:atlas")
            || current.containsKey("descriptor:Unused")
            || current.containsKey("descriptor:unusedSampler"))
          throw new AssertionError("Live descriptor names or dead-resource elimination changed");
        Map<Integer, String> outputs = new HashMap<>();
        for (var output : v.reflect().outputs())
          outputs.put(output.location(), interfaceType(output));
        for (var input : changed.reflect().inputs())
          if (!interfaceType(input).equals(outputs.get(input.location())))
            throw new AssertionError("Optimized fragment no longer links with unchanged vertex");

        // Exercise the actual frontend module's writable binding decorations across both stages.
        Map<String, Integer> bindings = new LinkedHashMap<>();
        for (var reflected : new SpvModule.Reflection[] {v.reflect(), changed.reflect()})
          for (var descriptor : reflected.descriptors()) {
            int index = bindings.computeIfAbsent(descriptor.name(), ignored -> bindings.size());
            descriptor.binding(index);
            descriptor.descriptorSetIndex(0);
            if (descriptor.binding() != index || descriptor.descriptorSetIndex() != 0)
              throw new AssertionError("Cannot relink descriptor " + descriptor.name());
          }
        // Re-reflection from the modified bytes must observe the compact linked bindings too.
        try (SPIRVModule linked = module(bytes(changed.spv()), ShaderType.FRAGMENT)) {
          for (var descriptor : linked.reflect().descriptors())
            if (descriptor.binding() != bindings.get(descriptor.name())
                || descriptor.descriptorSetIndex() != 0)
              throw new AssertionError("Relinking did not update the SPIR-V bytes");
        }
      }
      Map<String, String> before = blockLayouts(original);
      Map<String, String> after = blockLayouts(optimized);
      if (!before.get("Live").equals(after.get("Live")))
        throw new AssertionError(
            "Optimizer changed std140 offsets, array/matrix strides, or block size");
      if (count(optimized, 207) == 0 || count(optimized, 208) == 0)
        throw new AssertionError("Fixture lost its live derivative operations");
      System.out.println(
          "PASS: independent stage opt-ins, exact loader scope, immutable cache inputs, live names"
              + " and interstage linkage, std140 layouts, dead resources, relinkable descriptor"
              + " bytes (startup optimizeFragmentSpirv="
              + startupMode
              + ")");
    } finally {
      shaderc_compiler_release(compiler);
    }
  }

  private static byte[] compile(
      long compiler,
      String name,
      ShaderType stage,
      String source,
      boolean enabled,
      boolean production) {
    return compile(compiler, name, stage, source, enabled, false, production);
  }

  private static byte[] compileVertex(long compiler, String name, String source) {
    return compile(compiler, name, ShaderType.VERTEX, source, false, true, false);
  }

  private static byte[] compile(
      long compiler,
      String name,
      ShaderType stage,
      String source,
      boolean enabled,
      boolean vertexEnabled,
      boolean production) {
    long options = shaderc_compile_options_initialize();
    try {
      // These are the options set by MC 26.3 GlslCompiler before the production mixin runs.
      shaderc_compile_options_set_target_env(
          options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
      shaderc_compile_options_set_auto_bind_uniforms(options, true);
      shaderc_compile_options_set_preserve_bindings(options, false);
      shaderc_compile_options_set_generate_debug_info(options);
      shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_zero);
      long configured =
          production
              ? ShadercFragmentOptimization.configure(options, name, stage)
              : ShadercFragmentOptimization.configure(options, name, stage, enabled, vertexEnabled);
      if (configured != options)
        throw new AssertionError("Replaced the frontend options allocation");
      long result =
          shaderc_compile_into_spv(
              compiler,
              source,
              stage == ShaderType.VERTEX ? shaderc_vertex_shader : shaderc_fragment_shader,
              name,
              "main",
              options);
      try {
        if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
          throw new AssertionError(shaderc_result_get_error_message(result));
        return bytes(shaderc_result_get_bytes(result));
      } finally {
        shaderc_result_release(result);
      }
    } finally {
      shaderc_compile_options_release(options);
    }
  }

  private static byte[] bytes(ByteBuffer buffer) {
    byte[] data = new byte[buffer.remaining()];
    buffer.duplicate().get(data);
    return data;
  }

  private static SPIRVModule module(byte[] data, ShaderType stage) {
    ByteBuffer owned = MemoryUtil.memAlloc(data.length).order(ByteOrder.nativeOrder());
    owned.put(data).flip();
    return new SPIRVModule(owned, stage);
  }

  private static int count(byte[] data, int opcode) {
    var words = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder()).asIntBuffer();
    int found = 0;
    for (int i = 5; i < words.limit(); ) {
      int instruction = words.get(i), length = instruction >>> 16;
      if (length == 0) throw new AssertionError("Invalid SPIR-V");
      if ((instruction & 65535) == opcode) found++;
      i += length;
    }
    return found;
  }

  private static Map<String, String> interfaces(SpvModule.Reflection reflection) {
    Map<String, String> result = new HashMap<>();
    for (var input : reflection.inputs())
      result.put("input:" + input.name(), input.location() + ":" + interfaceType(input));
    for (var output : reflection.outputs())
      result.put("output:" + output.name(), output.location() + ":" + interfaceType(output));
    for (var descriptor : reflection.descriptors())
      result.put(
          "descriptor:" + descriptor.name(),
          descriptor.resourceType() + ":" + descriptor.type().dimensions());
    return result;
  }

  private static String interfaceType(SpvModule.Reflection.InterfaceVariable variable) {
    return variable.type().baseType()
        + ":"
        + variable.type().vectorSize()
        + ":"
        + variable.decoration(14);
  }

  private static Map<String, String> blockLayouts(byte[] data) {
    long context = 0;
    ByteBuffer memory = MemoryUtil.memAlloc(data.length).order(ByteOrder.nativeOrder());
    memory.put(data).flip();
    try (MemoryStack stack = MemoryStack.stackPush()) {
      PointerBuffer value = stack.callocPointer(1), count = stack.callocPointer(1);
      check(spvc_context_create(value), 0);
      context = value.get(0);
      check(
          spvc_context_parse_spirv(context, memory.asIntBuffer(), data.length / 4, value), context);
      check(
          spvc_context_create_compiler(
              context, SPVC_BACKEND_NONE, value.get(0), SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, value),
          context);
      long compiler = value.get(0);
      check(spvc_compiler_create_shader_resources(compiler, value), context);
      check(
          spvc_resources_get_resource_list_for_type(
              value.get(0), SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, value, count),
          context);
      var resources = SpvcReflectedResource.create(value.get(0), (int) count.get(0));
      Map<String, String> layouts = new HashMap<>();
      for (var resource : resources) {
        long type = spvc_compiler_get_type_handle(compiler, resource.base_type_id());
        check(spvc_compiler_get_declared_struct_size(compiler, type, value), context);
        StringBuilder layout = new StringBuilder("size=" + value.get(0));
        for (int i = 0; i < spvc_type_get_num_member_types(type); i++) {
          int memberType = spvc_type_get_member_type(type, i);
          layout
              .append('|')
              .append(spvc_compiler_get_member_name(compiler, resource.base_type_id(), i))
              .append(':')
              .append(spvc_compiler_get_member_decoration(compiler, resource.base_type_id(), i, 35))
              .append(':')
              .append(spvc_compiler_get_member_decoration(compiler, resource.base_type_id(), i, 7))
              .append(':')
              .append(spvc_compiler_get_decoration(compiler, memberType, 6));
        }
        layouts.put(resource.nameString(), layout.toString());
      }
      return layouts;
    } finally {
      if (context != 0) spvc_context_destroy(context);
      MemoryUtil.memFree(memory);
    }
  }

  private static void check(int result, long context) {
    if (result != SPVC_SUCCESS)
      throw new AssertionError(spvc_context_get_last_error_string(context));
  }
}

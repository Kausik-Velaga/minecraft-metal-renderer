package dev.kausik.metal;

import static dev.kausik.metal.MetalRenderPipeline.Binding;
import static dev.kausik.metal.MetalRenderPipeline.Kind;
import static org.lwjgl.util.spvc.Spvc.*;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;

/** Translates the frontend's linked SPIR-V without changing its shader or resource interfaces. */
public final class MetalShaderCompiler implements AutoCloseable {
  static final int PUSH_CONSTANT_BUFFER = 30;
  private static final int DECORATION_BINDING = 33;
  private static final int DECORATION_DESCRIPTOR_SET = 34;
  private static final int EXECUTION_VERTEX = 0;
  private static final int EXECUTION_FRAGMENT = 4;
  private final Map<TranslationKey, String> mslCache = new HashMap<>();
  private boolean closed;

  // The frontend may submit compilations concurrently. Each SPIRV-Cross context is local,
  // while serialization protects the content-addressed cache and close/reload operations.
  public synchronized MetalRenderPipeline compile(
      long device, BackendRenderPipeline.CreateInfo info) {
    if (closed) throw new IllegalStateException("Shader compiler is closed");
    if (info.pushConstantsSize() < 0 || info.pushConstantsSize() > 128)
      throw new IllegalArgumentException("Invalid push constant size for " + info.name());
    boolean scopedMath = MetalPipelineMath.consumeRelaxedFragment(info.name());
    List<Module> modules = new ArrayList<>();
    try {
      for (var shader : info.shaders()) modules.add(new Module(shader));
      Module vertex = stage(modules, EXECUTION_VERTEX);
      Module fragment = stage(modules, EXECUTION_FRAGMENT);
      List<Binding> bindings = linkResources(info, modules);
      int pushStages = 0;
      for (Module module : modules) {
        if (module.hasPushConstants) pushStages |= module.stageMask();
      }
      if (pushStages != 0 && info.pushConstantsSize() == 0)
        throw new IllegalArgumentException("Shader requires push constants: " + info.name());
      // ICBs accept texture/sampler resources through argument buffers, not direct arguments.
      // Slot 29 is reserved only for this optional path; a full uniform layout keeps the loop.
      boolean arguments =
          MetalNative.indirectCommandsEnabled(device)
              && bindings.stream()
                  .noneMatch(b -> b.kind() == Kind.UNIFORM_BUFFER && b.index() == 29);
      String vertexMsl, fragmentMsl, fragmentWithoutDepthMsl;
      try {
        vertexMsl = translate(vertex, bindings, true, arguments);
        fragmentMsl = translate(fragment, bindings, true, arguments);
        fragmentWithoutDepthMsl =
            fragment.writesDepth ? translate(fragment, bindings, false, arguments) : null;
      } catch (RuntimeException failure) {
        if (!arguments) throw failure;
        arguments = false;
        vertexMsl = translate(vertex, bindings, true, false);
        fragmentMsl = translate(fragment, bindings, true, false);
        fragmentWithoutDepthMsl =
            fragment.writesDepth ? translate(fragment, bindings, false, false) : null;
      }
      int[] argumentResources = arguments ? argumentResources(bindings, modules) : new int[0];
      if (Boolean.getBoolean("minecraftMetal.dumpShaders"))
        dumpShaders(info.name(), vertexMsl, fragmentMsl);
      int[] attributes = new int[info.attribBindings().size() * 4];
      for (int i = 0; i < info.attribBindings().size(); i++) {
        var attribute = info.attribBindings().get(i);
        attributes[i * 4] = attribute.location();
        attributes[i * 4 + 1] = attribute.bufferSlot();
        attributes[i * 4 + 2] = attribute.offset();
        attributes[i * 4 + 3] = MetalMappings.vertexFormat(attribute.format());
      }
      int[] layouts = new int[info.vertexBuffers().size() * 3];
      for (int i = 0; i < info.vertexBuffers().size(); i++) {
        var buffer = info.vertexBuffers().get(i);
        if (buffer.bufferSlot() < 0 || buffer.bufferSlot() >= 16)
          throw new IllegalArgumentException(
              "Vertex buffer slot out of range: " + buffer.bufferSlot());
        layouts[i * 3] = buffer.bufferSlot();
        layouts[i * 3 + 1] = buffer.stride();
        layouts[i * 3 + 2] = buffer.stepRate();
      }
      var depth = info.depthStencilState();
      long handle;
      try {
        if (scopedMath) {
          handle = MetalNative.createPipelineWithMathPolicy(
              device, info.name(), vertexMsl, fragmentMsl, fragmentWithoutDepthMsl,
              attributes, layouts, colorTargets(info.colorTargetStates()),
              depth == null ? -1 : MetalMappings.compare(depth.depthTest()),
              depth != null && depth.writeDepth(), info.cull(),
              info.polygonMode() == PolygonMode.WIREFRAME,
              depth == null ? 0 : depth.depthBiasConstant(),
              depth == null ? 0 : depth.depthBiasScaleFactor(), argumentResources,
              fragment.relaxedMathEligible ? 1 : 0);
        } else {
          handle = MetalNative.createPipelineWithArgumentBuffers(
                device,
                info.name(),
                vertexMsl,
                fragmentMsl,
                fragmentWithoutDepthMsl,
                attributes,
                layouts,
                colorTargets(info.colorTargetStates()),
                depth == null ? -1 : MetalMappings.compare(depth.depthTest()),
                depth != null && depth.writeDepth(),
                info.cull(),
                info.polygonMode() == PolygonMode.WIREFRAME,
                depth == null ? 0 : depth.depthBiasConstant(),
                depth == null ? 0 : depth.depthBiasScaleFactor(),
                argumentResources);
        }
      } catch (RuntimeException exception) {
        dumpShaders(info.name(), vertexMsl, fragmentMsl);
        throw new IllegalStateException(
            "Metal pipeline "
                + info.name()
                + " failed; translated shaders saved under debug/metal-shaders",
            exception);
      }
      if (handle == 0)
        throw new IllegalStateException(
            "Native pipeline compilation returned no pipeline for " + info.name());
      if (scopedMath)
        MetalPipelineMath.record(
            fragment.relaxedMathEligible, MetalNative.pipelineMathModes(handle)[1]);
      return new MetalRenderPipeline(info, handle, bindings, pushStages);
    } finally {
      modules.forEach(Module::close);
    }
  }

  private static Module stage(List<Module> modules, int stage) {
    Module result = null;
    for (Module module : modules) {
      if (module.stage != stage) continue;
      if (result != null) throw new IllegalArgumentException("Duplicate shader stage " + stage);
      result = module;
    }
    if (result == null) throw new IllegalArgumentException("Missing shader stage " + stage);
    return result;
  }

  private String translate(
      Module module, List<Binding> bindings, boolean emitDepth, boolean arguments) {
    return mslCache.computeIfAbsent(
        new TranslationKey(
            module.spirvKey, module.stage, module.entryPoint, bindings, emitDepth, arguments),
        ignored -> {
          // SPIRV-Cross mutates its IR while emitting MSL. A second depth variant must
          // start from fresh SPIR-V, or generated interface members can become corrupted.
          try (Module translation = new Module(module.shader)) {
            translation.bindResources(bindings, arguments);
            return translation.compile(emitDepth, arguments);
          }
        });
  }

  private static List<Binding> linkResources(
      BackendRenderPipeline.CreateInfo info, List<Module> modules) {
    List<Binding> result = new ArrayList<>();
    int bufferIndex = 16;
    int samplerIndex = 0;
    int texelIndex =
        (int)
            info.uniforms().stream()
                .filter(
                    u ->
                        u.type()
                            == com.mojang.renderpearl.api.pipeline.UniformType
                                .COMBINED_IMAGE_SAMPLER)
                .count();
    for (var uniform : info.uniforms()) {
      Kind kind =
          switch (uniform.type()) {
            case UNIFORM_BUFFER -> Kind.UNIFORM_BUFFER;
            case COMBINED_IMAGE_SAMPLER -> Kind.TEXTURE;
            case TEXEL_BUFFER -> Kind.TEXEL_BUFFER;
          };
      int index =
          switch (kind) {
            case UNIFORM_BUFFER -> bufferIndex++;
            case TEXTURE -> samplerIndex++;
            case TEXEL_BUFFER -> texelIndex++;
          };
      if (kind == Kind.UNIFORM_BUFFER && index >= PUSH_CONSTANT_BUFFER)
        throw new IllegalArgumentException(
            "Pipeline exceeds 14 Metal uniform buffer slots: " + info.name());
      if (kind == Kind.TEXTURE && index >= 16)
        throw new IllegalArgumentException(
            "Pipeline exceeds 16 Metal sampler slots: " + info.name());
      if (kind == Kind.TEXEL_BUFFER && index >= 128)
        throw new IllegalArgumentException(
            "Pipeline exceeds 128 Metal texture slots: " + info.name());
      result.add(new Binding(0, index, kind, uniform.gpuFormat(), uniform.name()));
    }
    for (Module module : modules) {
      for (Resource resource : module.resources) {
        if (resource.descriptorSet != 0
            || resource.binding < 0
            || resource.binding >= result.size())
          throw new IllegalArgumentException("Invalid frontend descriptor for " + resource.name);
        Binding binding = result.get(resource.binding);
        Kind kind =
            resource.type == SPVC_RESOURCE_TYPE_UNIFORM_BUFFER
                ? Kind.UNIFORM_BUFFER
                : resource.dimension == 5 ? Kind.TEXEL_BUFFER : Kind.TEXTURE;
        if (binding.kind() != kind)
          throw new IllegalArgumentException("Mismatched resource type for " + resource.name);
        result.set(
            resource.binding,
            new Binding(
                binding.stageMask() | (resource.active() ? module.stageMask() : 0),
                binding.index(),
                binding.kind(),
                binding.gpuFormat(),
                binding.name()));
      }
    }
    return List.copyOf(result);
  }

  private static int[] argumentResources(List<Binding> bindings, List<Module> modules) {
    // Keep every declared entry in the argument encoder's layout. Only residency and CPU
    // binding stages are narrowed; inactive entries cannot shift later texture/sampler IDs.
    int[] declaredStages = new int[bindings.size()];
    for (Module module : modules)
      for (Resource resource : module.resources)
        declaredStages[resource.binding()] |= module.stageMask();
    var result = new ArrayList<Integer>();
    for (int i = 0; i < bindings.size(); i++) {
      Binding binding = bindings.get(i);
      if (binding.kind() == Kind.UNIFORM_BUFFER || declaredStages[i] == 0) continue;
      result.add(declaredStages[i]);
      result.add(binding.stageMask());
      result.add(binding.index());
      result.add(binding.kind() == Kind.TEXTURE ? 1 : 0);
    }
    return result.stream().mapToInt(Integer::intValue).toArray();
  }

  private static int[] colorTargets(List<ColorTargetState> targets) {
    int[] values = new int[targets.size() * 9];
    for (int i = 0; i < targets.size(); ++i) {
      ColorTargetState target = targets.get(i);
      if (target == null) continue;
      int offset = i * 9;
      values[offset] = MetalMappings.pixelFormat(target.format());
      values[offset + 1] = MetalMappings.colorWriteMask(target);
      if (target.blendFunction().isPresent()) {
        var blend = target.blendFunction().get();
        values[offset + 2] = 1;
        values[offset + 3] = MetalMappings.blendFactor(blend.color().sourceFactor());
        values[offset + 4] = MetalMappings.blendFactor(blend.color().destFactor());
        values[offset + 5] = MetalMappings.blendOperation(blend.color().op());
        values[offset + 6] = MetalMappings.blendFactor(blend.alpha().sourceFactor());
        values[offset + 7] = MetalMappings.blendFactor(blend.alpha().destFactor());
        values[offset + 8] = MetalMappings.blendOperation(blend.alpha().op());
      }
    }
    return values;
  }

  private static void dumpShaders(String label, String vertex, String fragment) {
    try {
      Path directory = Path.of("debug", "metal-shaders");
      Files.createDirectories(directory);
      String name = label.replaceAll("[^A-Za-z0-9_.-]", "_");
      Files.writeString(directory.resolve(name + ".vert.metal"), vertex);
      Files.writeString(directory.resolve(name + ".frag.metal"), fragment);
    } catch (IOException exception) {
      System.err.println(
          "[Minecraft Metal] Could not save shader diagnostics: " + exception.getMessage());
    }
  }

  public synchronized void clearCache() {
    mslCache.clear();
  }

  @Override
  public synchronized void close() {
    closed = true;
    clearCache();
  }

  private record TranslationKey(
      String spirv,
      int stage,
      String entryPoint,
      List<Binding> bindings,
      boolean emitDepth,
      boolean arguments) {}

  private record Resource(
      int id,
      int binding,
      int descriptorSet,
      String name,
      int type,
      int dimension,
      boolean active) {}

  private static final class Module implements AutoCloseable {
    private long context;
    private long compiler;
    private long reflected;
    private final int stage;
    private final BackendRenderPipeline.CreateInfo.Shader shader;
    private final String entryPoint;
    private final String spirvKey;
    private final boolean relaxedMathEligible;
    private boolean hasPushConstants;
    private boolean writesDepth;
    private final List<Resource> resources = new ArrayList<>();

    Module(BackendRenderPipeline.CreateInfo.Shader shader) {
      this.shader = shader;
      stage = shader.module().type() == ShaderType.VERTEX ? EXECUTION_VERTEX : EXECUTION_FRAGMENT;
      entryPoint = shader.entryPoint();
      ByteBuffer source = shader.module().spv().duplicate();
      byte[] data = new byte[source.remaining()];
      source.get(data);
      if (data.length == 0 || data.length % 4 != 0)
        throw new IllegalArgumentException("Invalid SPIR-V for " + shader.name());
      spirvKey = Base64.getEncoder().encodeToString(data);
      relaxedMathEligible = SpirvMathPolicy.permitsRelaxedFragment(data);
      ByteBuffer bytes = MemoryUtil.memAlloc(data.length).order(ByteOrder.nativeOrder());
      bytes.put(data).flip();
      try (MemoryStack stack = MemoryStack.stackPush()) {
        PointerBuffer result = stack.callocPointer(1);
        check(spvc_context_create(result));
        context = result.get(0);
        check(spvc_context_parse_spirv(context, bytes.asIntBuffer(), data.length / 4, result));
        check(
            spvc_context_create_compiler(
                context,
                SPVC_BACKEND_MSL,
                result.get(0),
                SPVC_CAPTURE_MODE_TAKE_OWNERSHIP,
                result));
        compiler = result.get(0);
        check(spvc_compiler_set_entry_point(compiler, entryPoint, stage));
        // Native library creation uses main0 for both independently compiled stages.
        if (!entryPoint.equals("main"))
          check(spvc_compiler_rename_entry_point(compiler, entryPoint, "main", stage));
        check(spvc_compiler_create_shader_resources(compiler, result));
        reflected = result.get(0);
        Set<Integer> activeResources = activeResources();
        for (int type :
            new int[] {SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE}) {
          for (var resource : reflect(type)) {
            int dimension =
                type == SPVC_RESOURCE_TYPE_SAMPLED_IMAGE
                    ? spvc_type_get_image_dimension(
                        spvc_compiler_get_type_handle(compiler, resource.type_id()))
                    : -1;
            resources.add(
                new Resource(
                    resource.id(),
                    spvc_compiler_get_decoration(compiler, resource.id(), DECORATION_BINDING),
                    spvc_compiler_get_decoration(
                        compiler, resource.id(), DECORATION_DESCRIPTOR_SET),
                    resource.nameString(),
                    type,
                    dimension,
                    activeResources == null || activeResources.contains(resource.id())));
          }
        }
        hasPushConstants = !reflect(SPVC_RESOURCE_TYPE_PUSH_CONSTANT).isEmpty();
        spvc_compiler_update_active_builtins(compiler);
        // SpvBuiltInFragDepth = 22, SpvStorageClassOutput = 3.
        writesDepth = spvc_compiler_has_active_builtin(compiler, 22, 3);
        if (!reflect(SPVC_RESOURCE_TYPE_STORAGE_BUFFER).isEmpty()
            || !reflect(SPVC_RESOURCE_TYPE_STORAGE_IMAGE).isEmpty())
          throw new UnsupportedOperationException(
              "Storage resources are not exposed by the RenderPearl render-pipeline API");
      } catch (RuntimeException error) {
        close();
        throw error;
      } finally {
        MemoryUtil.memFree(bytes);
      }
    }

    private int stageMask() {
      return stage == EXECUTION_VERTEX ? 1 : 2;
    }

    private List<SpvcReflectedResource> reflect(int type) {
      return reflect(reflected, type);
    }

    private List<SpvcReflectedResource> reflect(long resources, int type) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
        PointerBuffer pointer = stack.callocPointer(1);
        PointerBuffer count = stack.callocPointer(1);
        check(spvc_resources_get_resource_list_for_type(resources, type, pointer, count));
        List<SpvcReflectedResource> result = new ArrayList<>();
        if (count.get(0) != 0) {
          var list = SpvcReflectedResource.create(pointer.get(0), (int) count.get(0));
          for (int i = 0; i < list.capacity(); ++i) result.add(list.get(i));
        }
        return result;
      }
    }

    private Set<Integer> activeResources() {
      try (MemoryStack stack = MemoryStack.stackPush()) {
        PointerBuffer result = stack.callocPointer(1);
        if (spvc_compiler_get_active_interface_variables(compiler, result) != SPVC_SUCCESS
            || result.get(0) == 0) return null;
        long active = result.get(0);
        if (spvc_compiler_create_shader_resources_for_active_variables(compiler, result, active)
                != SPVC_SUCCESS
            || result.get(0) == 0) return null;
        long activeReflection = result.get(0);
        Set<Integer> declared = new HashSet<>(), live = new HashSet<>();
        for (int type :
            new int[] {SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE}) {
          for (var resource : reflect(type)) declared.add(resource.id());
          for (var resource : reflect(activeReflection, type)) live.add(resource.id());
        }
        // An unexpected reflection result never licenses dropping a dependency. Inactive
        // resources remain declared in MSL, including when the active set is empty.
        return declared.containsAll(live) ? live : null;
      } catch (RuntimeException uncertain) {
        return null;
      }
    }

    private void bindResources(List<Binding> bindings, boolean arguments) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
        if (arguments) {
          check(spvc_compiler_msl_add_discrete_descriptor_set(compiler, 0));
          var argumentBinding =
              SpvcMslResourceBinding.calloc(stack)
                  .stage(stage)
                  .desc_set(1)
                  .binding(SPVC_MSL_ARGUMENT_BUFFER_BINDING)
                  .msl_buffer(29);
          check(spvc_compiler_msl_add_resource_binding(compiler, argumentBinding));
        }
        for (Resource resource : resources) {
          Binding target = bindings.get(resource.binding);
          boolean indirect = arguments && target.kind() != Kind.UNIFORM_BUFFER;
          if (indirect)
            spvc_compiler_set_decoration(compiler, resource.id, DECORATION_DESCRIPTOR_SET, 1);
          var binding =
              SpvcMslResourceBinding.calloc(stack)
                  .stage(stage)
                  .desc_set(indirect ? 1 : resource.descriptorSet)
                  .binding(resource.binding)
                  .msl_buffer(target.index())
                  .msl_texture(target.index())
                  .msl_sampler(indirect ? 128 + target.index() : target.index());
          check(spvc_compiler_msl_add_resource_binding(compiler, binding));
        }
        if (hasPushConstants) {
          var binding =
              SpvcMslResourceBinding.calloc(stack)
                  .stage(stage)
                  .desc_set(SPVC_MSL_PUSH_CONSTANT_DESC_SET)
                  .binding(SPVC_MSL_PUSH_CONSTANT_BINDING)
                  .msl_buffer(PUSH_CONSTANT_BUFFER);
          check(spvc_compiler_msl_add_resource_binding(compiler, binding));
        }
      }
    }

    private String compile(boolean emitDepth, boolean arguments) {
      try (MemoryStack stack = MemoryStack.stackPush()) {
        PointerBuffer pointer = stack.callocPointer(1);
        check(spvc_compiler_create_compiler_options(compiler, pointer));
        long options = pointer.get(0);
        // MSL 2.1+ preserves SPIR-V Invariant position decorations as [[position, invariant]].
        // Native library compilation enables preserveInvariance for those marked outputs.
        check(spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, 20300));
        check(
            spvc_compiler_options_set_bool(
                options, SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS, arguments));
        check(
            spvc_compiler_options_set_bool(
                options,
                SPVC_COMPILER_OPTION_MSL_FORCE_ACTIVE_ARGUMENT_BUFFER_RESOURCES,
                arguments));
        check(
            spvc_compiler_options_set_uint(
                options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS));
        check(
            spvc_compiler_options_set_bool(
                options, SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true));
        // RenderPearl permits a depth-writing shader in a pass without a depth attachment.
        // Metal requires a separate fragment function with the depth output suppressed.
        check(
            spvc_compiler_options_set_bool(
                options, SPVC_COMPILER_OPTION_MSL_ENABLE_FRAG_DEPTH_BUILTIN, emitDepth));
        // Match Vulkan's positive-height offscreen viewport. Surface presentation flips Y
        // once more, preserving RenderPearl texture/scissor conventions.
        check(spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true));
        check(spvc_compiler_install_compiler_options(compiler, options));
        check(spvc_compiler_compile(compiler, pointer));
        return MemoryUtil.memUTF8(pointer.get(0));
      }
    }

    private void check(int result) {
      if (result != SPVC_SUCCESS) {
        String detail =
            context == 0 ? "Cannot allocate context" : spvc_context_get_last_error_string(context);
        throw new IllegalStateException("SPIRV-Cross failed (" + result + "): " + detail);
      }
    }

    @Override
    public void close() {
      if (context != 0) {
        spvc_context_destroy(context);
        context = 0;
      }
    }
  }
}

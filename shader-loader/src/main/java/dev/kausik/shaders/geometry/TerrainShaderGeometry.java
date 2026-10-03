package dev.kausik.shaders.geometry;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

/** Pack attributes added during vanilla chunk compilation, without changing its draw batching. */
public final class TerrainShaderGeometry {
  public static final int STRIDE = 64;
  public static final int NORMAL_OFFSET = 28;
  public static final int ENTITY_OFFSET = 32;
  public static final int MID_UV_OFFSET = 40;
  public static final int TANGENT_OFFSET = 48;
  public static final int MID_BLOCK_OFFSET = 52;
  public static final VertexFormat FORMAT =
      VertexFormat.builder(0)
          .addAttribute("Position", GpuFormat.RGB32_FLOAT)
          .addAttribute("Color", GpuFormat.RGBA8_UNORM)
          .addAttribute("UV0", GpuFormat.RG32_FLOAT)
          .addAttribute("UV2", GpuFormat.RG16_SINT)
          .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
          .addAttribute("PackEntity", GpuFormat.RG32_FLOAT)
          .addAttribute("PackMidUV", GpuFormat.RG32_FLOAT)
          .addAttribute("PackTangent", GpuFormat.RGBA8_SNORM)
          .addAttribute("PackMidBlock", GpuFormat.RGB32_FLOAT)
          .build();

  private static final Map<RenderPipeline, RenderPipeline> PIPELINES = new IdentityHashMap<>();
  private static final ThreadLocal<SectionContext> SECTION = new ThreadLocal<>();
  private static volatile Configuration configuration;

  private TerrainShaderGeometry() {}

  /**
   * Call before entering a level, or after disposing its section dispatcher and stopping its
   * workers. The resolver must be immutable and thread safe; an unmapped block has ID -1.
   */
  public static void configure(ToIntFunction<BlockState> resolver) {
    configure(resolver, true);
  }

  public static void configure(ToIntFunction<BlockState> resolver, boolean separateAo) {
    configuration = new Configuration(Objects.requireNonNull(resolver), separateAo);
  }

  /**
   * Requires the same full dispatcher disposal as configure; changing only the compiler is unsafe.
   */
  public static void disable() {
    configuration = null;
  }

  public static boolean isEnabled() {
    return configuration != null;
  }

  /** Each worker keeps the same material mapping for the whole section compilation. */
  public static SectionContext beginSection() {
    SectionContext previous = SECTION.get();
    Configuration settings = configuration;
    if (settings == null) SECTION.remove();
    else SECTION.set(new SectionContext(settings));
    return previous;
  }

  public static void endSection(SectionContext previous) {
    if (previous == null) SECTION.remove();
    else SECTION.set(previous);
  }

  public static boolean hasSection() {
    return SECTION.get() != null;
  }

  public static boolean separateAo() {
    SectionContext context = SECTION.get();
    return context != null && context.settings.separateAo;
  }

  public static void beginBlock(BlockState state, BlockPos pos, boolean fluid) {
    SectionContext context = SECTION.get();
    if (context == null) return;
    context.materialId = context.settings.resolver.applyAsInt(state);
    context.renderType = fluid ? 1 : -1;
    context.midX = (pos.getX() & 15) + 0.5f;
    context.midY = (pos.getY() & 15) + 0.5f;
    context.midZ = (pos.getZ() & 15) + 0.5f;
    context.hasBlock = true;
  }

  public static void endBlock() {
    SectionContext context = SECTION.get();
    if (context != null) context.hasBlock = false;
  }

  public static SectionContext currentBlock() {
    SectionContext context = SECTION.get();
    if (context == null || !context.hasBlock) {
      throw new IllegalStateException("Shader terrain vertex has no block/material context");
    }
    return context;
  }

  /**
   * The pipeline layout also controls arena allocation alignment and indirect baseVertex
   * arithmetic. Keep the extended stride even before the runtime substitutes a compiled pack
   * pipeline.
   */
  public static synchronized RenderPipeline pipeline(RenderPipeline original) {
    if (!isEnabled()) return original;
    return PIPELINES.computeIfAbsent(original, TerrainShaderGeometry::extendPipeline);
  }

  private static RenderPipeline extendPipeline(RenderPipeline original) {
    VertexFormat[] formats = original.getVertexFormatBindings().toArray(VertexFormat[]::new);
    formats[0] = FORMAT;
    ColorTargetState[] colors = original.getColorTargetStates().toArray(ColorTargetState[]::new);
    RenderPipeline.Snippet snippet =
        new RenderPipeline.Snippet(
            original.getShaders(),
            Optional.of(original.getShaderDefines()),
            Optional.of(original.getBindGroupLayouts()),
            colors,
            colors.length,
            Optional.ofNullable(original.getDepthStencilState()),
            Optional.of(original.getPolygonMode()),
            Optional.of(original.isCull()),
            formats,
            Optional.of(original.getPrimitiveTopology()),
            original.pushConstantSize());
    return RenderPipeline.builder(snippet)
        .withLocation(
            Identifier.fromNamespaceAndPath(
                "minecraft_shader_loader", "geometry/" + original.getLocation().getPath()))
        .build();
  }

  public static final class SectionContext {
    private final Configuration settings;
    private boolean hasBlock;
    public int materialId;
    public int renderType;
    public float midX;
    public float midY;
    public float midZ;

    private SectionContext(Configuration settings) {
      this.settings = settings;
    }
  }

  private record Configuration(ToIntFunction<BlockState> resolver, boolean separateAo) {}
}

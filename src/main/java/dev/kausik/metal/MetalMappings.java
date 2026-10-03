package dev.kausik.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;

/** Values are Apple's public Metal ABI enums, never Minecraft enum ordinals. */
public final class MetalMappings {
  private MetalMappings() {}

  public static int primitiveTopology(PrimitiveTopology topology) {
    return switch (topology) {
      case POINTS -> 0;
      case DEBUG_LINES -> 1;
      case DEBUG_LINE_STRIP -> 2;
      case TRIANGLES, QUADS, LINES ->
          3; // Blaze3D expands wide lines and quads to indexed triangles.
      case TRIANGLE_STRIP -> 4;
      case TRIANGLE_FAN -> 5; // Native layer expands fans to indexed triangles.
    };
  }

  public static int compare(CompareOp compare) {
    return switch (compare) {
      case NEVER_PASS -> 0;
      case LESS_THAN -> 1;
      case EQUAL -> 2;
      case LESS_THAN_OR_EQUAL -> 3;
      case GREATER_THAN -> 4;
      case NOT_EQUAL -> 5;
      case GREATER_THAN_OR_EQUAL -> 6;
      case ALWAYS_PASS -> 7;
    };
  }

  public static int blendFactor(BlendFactor factor) {
    return switch (factor) {
      case ZERO -> 0;
      case ONE -> 1;
      case SRC_COLOR -> 2;
      case ONE_MINUS_SRC_COLOR -> 3;
      case SRC_ALPHA -> 4;
      case ONE_MINUS_SRC_ALPHA -> 5;
      case DST_COLOR -> 6;
      case ONE_MINUS_DST_COLOR -> 7;
      case DST_ALPHA -> 8;
      case ONE_MINUS_DST_ALPHA -> 9;
      case SRC_ALPHA_SATURATE -> 10;
      case CONSTANT_COLOR -> 11;
      case ONE_MINUS_CONSTANT_COLOR -> 12;
      case CONSTANT_ALPHA -> 13;
      case ONE_MINUS_CONSTANT_ALPHA -> 14;
    };
  }

  public static int blendOperation(BlendOp operation) {
    return switch (operation) {
      case ADD -> 0;
      case SUBTRACT -> 1;
      case REVERSE_SUBTRACT -> 2;
      case MIN -> 3;
      case MAX -> 4;
    };
  }

  public static int colorWriteMask(ColorTargetState state) {
    return (state.writeRed() ? 8 : 0)
        | (state.writeGreen() ? 4 : 0)
        | (state.writeBlue() ? 2 : 0)
        | (state.writeAlpha() ? 1 : 0);
  }

  public static int vertexFormat(GpuFormat format) {
    int count = format.componentCount();
    return switch (format.componentType()) {
      case UINT_8 -> count == 1 ? 45 : count - 1;
      case SINT_8 -> count == 1 ? 46 : count + 2;
      case UNORM_8 -> count == 1 ? 47 : count + 5;
      case SNORM_8 -> count == 1 ? 48 : count + 8;
      case UINT_16 -> count == 1 ? 49 : count + 11;
      case SINT_16 -> count == 1 ? 50 : count + 14;
      case UNORM_16 -> count == 1 ? 51 : count + 17;
      case SNORM_16 -> count == 1 ? 52 : count + 20;
      case FLOAT_16 -> count == 1 ? 53 : count + 23;
      case FLOAT_32 -> count + 27;
      case SINT_32 -> count + 31;
      case UINT_32 -> count + 35;
      default ->
          switch (format) {
            case RGB10A2_UNORM -> 41;
            case RG11B10_FLOAT -> 54;
            default ->
                throw new UnsupportedOperationException("No Metal vertex format for " + format);
          };
    };
  }

  public static int pixelFormat(GpuFormat format) {
    return switch (format) {
      case R8_UNORM -> 10;
      case R8_SNORM -> 12;
      case R8_UINT -> 13;
      case R8_SINT -> 14;
      case R16_UNORM -> 20;
      case R16_SNORM -> 22;
      case R16_UINT -> 23;
      case R16_SINT -> 24;
      case R16_FLOAT -> 25;
      case RG8_UNORM -> 30;
      case RG8_SNORM -> 32;
      case RG8_UINT -> 33;
      case RG8_SINT -> 34;
      case R32_UINT -> 53;
      case R32_SINT -> 54;
      case R32_FLOAT -> 55;
      case RG16_UNORM -> 60;
      case RG16_SNORM -> 62;
      case RG16_UINT -> 63;
      case RG16_SINT -> 64;
      case RG16_FLOAT -> 65;
      case RGBA8_UNORM -> 70;
      case RGBA8_SNORM -> 72;
      case RGBA8_UINT -> 73;
      case RGBA8_SINT -> 74;
      case RGB10A2_UNORM -> 90;
      case RGB10A2_UINT -> 91;
      case RG11B10_FLOAT -> 92;
      case RG32_UINT -> 103;
      case RG32_SINT -> 104;
      case RG32_FLOAT -> 105;
      case RGBA16_UNORM -> 110;
      case RGBA16_SNORM -> 112;
      case RGBA16_UINT -> 113;
      case RGBA16_SINT -> 114;
      case RGBA16_FLOAT -> 115;
      case RGBA32_UINT -> 123;
      case RGBA32_SINT -> 124;
      case RGBA32_FLOAT -> 125;
      case D16_UNORM -> 250;
      case D32_FLOAT -> 252;
      case S8_UINT -> 253;
      case D32_FLOAT_S8_UINT -> 260;
      default ->
          throw new UnsupportedOperationException(
              "No native Apple Silicon Metal pixel format for " + format);
    };
  }
}

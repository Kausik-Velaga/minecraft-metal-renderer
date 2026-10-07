package dev.kausik.metal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicReference;

/** CPU-only scope lifetime and conservative SPIR-V precision-policy checks. */
public final class MetalPipelineMathTest {
  public static void main(String[] args) throws Exception {
    check(!MetalPipelineMath.consumeRelaxedFragment("a"), "Unscoped request");
    try (var outer = MetalPipelineMath.openRelaxedFragment("a")) {
      check(!MetalPipelineMath.consumeRelaxedFragment("other"), "Name mismatch consumed hint");
      try (var inner = MetalPipelineMath.openRelaxedFragment("b")) {
        check(!MetalPipelineMath.consumeRelaxedFragment("a"), "Outer scope leaked through inner");
        check(MetalPipelineMath.consumeRelaxedFragment("b"), "Matching inner scope ignored");
        check(!MetalPipelineMath.consumeRelaxedFragment("b"), "Hint reused");
        try {
          outer.close();
          throw new AssertionError("Out-of-order close accepted");
        } catch (IllegalStateException expected) {
          // Failed closes must leave both scopes intact.
        }
      }
      var failure = new AtomicReference<Throwable>();
      Thread worker =
          new Thread(
              () -> {
                try {
                  check(
                      !MetalPipelineMath.consumeRelaxedFragment("a"), "Hint propagated to worker");
                  try {
                    outer.close();
                    throw new AssertionError("Foreign-thread close accepted");
                  } catch (IllegalStateException expected) {
                    // Owner can still consume and close the same scope.
                  }
                } catch (Throwable error) {
                  failure.set(error);
                }
              });
      worker.start();
      worker.join();
      if (failure.get() != null) throw new AssertionError(failure.get());
      check(MetalPipelineMath.consumeRelaxedFragment("a"), "Outer scope was lost");
    }
    check(!MetalPipelineMath.consumeRelaxedFragment("a"), "Closed scope leaked");
    try {
      try (var ignored = MetalPipelineMath.openRelaxedFragment("exception")) {
        throw new IllegalArgumentException("fixture");
      }
    } catch (IllegalArgumentException expected) {
      check(!MetalPipelineMath.consumeRelaxedFragment("exception"), "Exceptional close leaked");
    }

    accepts(module(instruction(16, 1, 7), instruction(17, 1)));
    accepts(module(instruction(17, 5379), instruction(16, 1, 9)));
    rejects(module(instruction(71, 8, 42))); // NoContraction on an arithmetic result.
    rejects(module(instruction(72, 8, 0, 18))); // Invariant interface member.
    rejects(module(instruction(71, 8, 39, 0))); // Explicit rounding.
    rejects(module(instruction(71, 8, 40, 0))); // Explicit fast-math controls, even None.
    rejects(module(instruction(16, 1, 31))); // ContractionOff.
    rejects(module(instruction(16, 1, 4461, 32))); // SignedZeroInfNanPreserve.
    rejects(module(instruction(17, 6029))); // FloatControls2.
    rejects(module(instruction(17, 65534))); // Unknown extension capability.
    rejects(module(instruction(331, 1, 6028, 32, 0))); // ExecutionModeId.
    rejects(module(instruction(332, 8, 40, 9))); // DecorateId.
    rejects(new byte[0]);
    byte[] truncated = module(instruction(71, 8, 42));
    rejects(java.util.Arrays.copyOf(truncated, truncated.length - 4));
    byte[] future = module();
    ByteBuffer.wrap(future).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 0x00010700);
    rejects(future);
    System.out.println("PASS: scoped exact-name/thread/lifetime and SPIR-V precision fallbacks");
  }

  private static int[] instruction(int opcode, int... operands) {
    int[] result = new int[operands.length + 1];
    result[0] = result.length << 16 | opcode;
    System.arraycopy(operands, 0, result, 1, operands.length);
    return result;
  }

  private static byte[] module(int[]... instructions) {
    int count = 5;
    for (int[] instruction : instructions) count += instruction.length;
    var bytes = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
    bytes.putInt(0x07230203).putInt(0x00010600).putInt(0).putInt(16).putInt(0);
    for (int[] instruction : instructions) for (int word : instruction) bytes.putInt(word);
    return bytes.array();
  }

  private static void accepts(byte[] source) {
    check(SpirvMathPolicy.permitsRelaxedFragment(source), "Ordinary fragment rejected");
  }

  private static void rejects(byte[] source) {
    check(!SpirvMathPolicy.permitsRelaxedFragment(source), "Precision/malformed IR accepted");
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}

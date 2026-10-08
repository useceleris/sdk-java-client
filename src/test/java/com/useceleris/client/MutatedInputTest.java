package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Feeds the decoder deterministic mutations of every reference vector. Whatever the bytes, it
 * either decodes them or throws a located protocol error with a fixed reason; any other exception
 * is a defect. Run more with {@code -Dceleris.mutations=1000000}, or other inputs with {@code
 * -Dceleris.seed=...}; a failure names the seed and case that reproduce it.
 */
class MutatedInputTest {
  private static final int DEFAULT_MUTATIONS = 20_000;

  private static final long DEFAULT_SEED = 0xce1eL;

  // Bytes the grammar gives meaning to, so mutations reach past the first marker check.
  private static final byte[] SIGNIFICANT_BYTES = CodecVectors.utf8("\r\n+$:;*-@0123456789E");

  private static List<byte[]> seeds() {
    List<byte[]> seeds = new ArrayList<>();

    for (CodecVectors.DecodingVector vector : CodecVectors.decodingVectors()) {
      seeds.add(vector.bytes());
    }

    seeds.addAll(CodecVectors.malformedVectors());

    return seeds;
  } // end method seeds

  /** The reference's own mutation sequence: its linear congruential generator, its 512 inputs. */
  @Test
  void referenceMutationSequenceFailsOnlyWithProtocolErrors() {
    List<CodecVectors.DecodingVector> vectors = CodecVectors.decodingVectors();
    int seed = 0xce1e;

    for (int attempt = 0; attempt < 512; attempt++) {
      byte[] bytes = vectors.get(attempt % vectors.size()).bytes().clone();
      seed = seed * 1664525 + 1013904223;
      bytes[Integer.remainderUnsigned(seed, bytes.length)] = (byte) seed;
      checkOutcome(bytes, "reference attempt " + attempt);
    }
  } // end method referenceMutationSequenceFailsOnlyWithProtocolErrors

  @Test
  void seededMutationsFailOnlyWithLocatedProtocolErrors() {
    int mutations = Integer.getInteger("celeris.mutations", DEFAULT_MUTATIONS);
    long seed = Long.getLong("celeris.seed", DEFAULT_SEED);
    Random random = new Random(seed);
    List<byte[]> seeds = seeds();

    for (int attempt = 0; attempt < mutations; attempt++) {
      byte[] bytes = mutate(seeds.get(random.nextInt(seeds.size())), random);
      checkOutcome(bytes, "seed " + seed + ", attempt " + attempt);
    }
  } // end method seededMutationsFailOnlyWithLocatedProtocolErrors

  private static byte[] mutate(byte[] original, Random random) {
    byte[] bytes = original;
    int operations = 1 + random.nextInt(3);

    for (int operation = 0; operation < operations; operation++) {
      bytes = mutateOnce(bytes, random);
    }

    return bytes;
  } // end method mutate

  private static byte[] mutateOnce(byte[] bytes, Random random) {
    if (bytes.length == 0) {
      return new byte[] {nextByte(random)};
    }

    int position = random.nextInt(bytes.length);

    switch (random.nextInt(6)) {
      case 0 -> {
        byte[] changed = bytes.clone();
        changed[position] = (byte) random.nextInt(256);

        return changed;
      }
      case 1 -> {
        byte[] changed = bytes.clone();
        changed[position] = SIGNIFICANT_BYTES[random.nextInt(SIGNIFICANT_BYTES.length)];

        return changed;
      }
      case 2 -> {
        byte[] inserted = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, inserted, 0, position);
        inserted[position] = nextByte(random);
        System.arraycopy(bytes, position, inserted, position + 1, bytes.length - position);

        return inserted;
      }
      case 3 -> {
        byte[] removed = new byte[bytes.length - 1];
        System.arraycopy(bytes, 0, removed, 0, position);
        System.arraycopy(bytes, position + 1, removed, position, bytes.length - position - 1);

        return removed;
      }
      case 4 -> {
        return Arrays.copyOf(bytes, position);
      }
      default -> {
        // Repeats a slice in place, which multiplies fields, markers and nesting.
        int length = 1 + random.nextInt(Math.min(16, bytes.length - position));
        byte[] repeated = new byte[bytes.length + length];
        System.arraycopy(bytes, 0, repeated, 0, position + length);
        System.arraycopy(bytes, position, repeated, position + length, bytes.length - position);

        return repeated;
      }
    }
  } // end method mutateOnce

  private static byte nextByte(Random random) {
    return random.nextBoolean()
        ? SIGNIFICANT_BYTES[random.nextInt(SIGNIFICANT_BYTES.length)]
        : (byte) random.nextInt(256);
  } // end method nextByte

  private static void checkOutcome(byte[] bytes, String reproduction) {
    try {
      if (MessageDecoder.decode(bytes) == null) {
        fail("Decoded to null: " + describe(bytes, reproduction));
      }
    } catch (CelerisException failure) {
      checkFailure(failure, bytes, reproduction);
    } catch (RuntimeException | StackOverflowError failure) {
      throw new AssertionError(
          "Unexpected " + failure + ": " + describe(bytes, reproduction), failure);
    }
  } // end method checkOutcome

  private static void checkFailure(CelerisException failure, byte[] bytes, String reproduction) {
    String field = failure.field().orElse(null);
    int offset = failure.offset().orElse(-1);

    if (failure.code() != ErrorCode.PROTOCOL
        || field == null
        || !CodecVectors.DECODER_FIELDS.contains(field)
        || offset < 0
        || offset > bytes.length
        || failure.getCause() != null) {
      fail("Unlocated failure " + failure.getMessage() + ": " + describe(bytes, reproduction));
    }

    String suffix = " Field: " + field + ", byte offset " + offset + ".";
    String message = failure.getMessage();

    if (!message.endsWith(suffix)
        || !CodecVectors.DECODER_REASONS.contains(
            message.substring(0, message.length() - suffix.length()))) {
      fail("Unexpected reason " + message + ": " + describe(bytes, reproduction));
    }
  } // end method checkFailure

  private static String describe(byte[] bytes, String reproduction) {
    return reproduction + ", input " + HexFormat.of().formatHex(bytes);
  } // end method describe
} // end class MutatedInputTest

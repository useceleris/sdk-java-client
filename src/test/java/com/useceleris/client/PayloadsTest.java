package com.useceleris.client;

import static com.useceleris.client.CodecVectors.bytes;
import static com.useceleris.client.CodecVectors.join;
import static com.useceleris.client.CodecVectors.utf8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PayloadsTest {
  private static final String REPLACEMENT = codePoint(0xFFFD);

  private static final byte[] REPLACEMENT_BYTES = bytes(0xEF, 0xBF, 0xBD);

  /** Spells out characters a reader could not tell apart, or see, in a literal. */
  private static String codePoint(int value) {
    return new String(Character.toChars(value));
  } // end method codePoint

  static Stream<String> texts() {
    return Stream.of("", "hello", "héllo 안녕 🛰️", "a\u0000b", codePoint(0xFEFF) + "bom");
  } // end method texts

  @ParameterizedTest
  @MethodSource("texts")
  void roundTripsTextByteIdentically(String text) {
    byte[] payload = Payloads.text(text);

    assertArrayEquals(utf8(text), payload);
    assertEquals(text, Payloads.readText(payload));
  } // end method roundTripsTextByteIdentically

  @Test
  void encodesTextAsUtf8() {
    assertArrayEquals(bytes(0x63, 0x3A, 0xC3, 0xA9), Payloads.text("c:é"));
    assertArrayEquals(bytes(0xE8, 0xAD, 0x98), Payloads.text("識"));
    assertArrayEquals(bytes(0xF0, 0x9F, 0x98, 0x80), Payloads.text("😀"));
    assertArrayEquals(bytes(0xF4, 0x8F, 0xBF, 0xBF), Payloads.text("􏿿"));
  } // end method encodesTextAsUtf8

  // TextEncoder replaces each unpaired surrogate with U+FFFD.
  static Stream<Arguments> unpairedSurrogates() {
    return Stream.of(
        Arguments.of("\ud800", REPLACEMENT_BYTES),
        Arguments.of("\udfff", REPLACEMENT_BYTES),
        Arguments.of("room-\ud800", join(utf8("room-"), REPLACEMENT_BYTES)),
        Arguments.of("\udc00-room", join(REPLACEMENT_BYTES, utf8("-room"))),
        Arguments.of("a\ud800b", join(utf8("a"), REPLACEMENT_BYTES, utf8("b"))),
        Arguments.of("\udc00\ud800", join(REPLACEMENT_BYTES, REPLACEMENT_BYTES)),
        Arguments.of("\ud800\ud800", join(REPLACEMENT_BYTES, REPLACEMENT_BYTES)),
        Arguments.of("😀\udfff", join(bytes(0xF0, 0x9F, 0x98, 0x80), REPLACEMENT_BYTES)),
        Arguments.of(
            "\ud800" + codePoint(0x10000), join(REPLACEMENT_BYTES, bytes(0xF0, 0x90, 0x80, 0x80))));
  } // end method unpairedSurrogates

  @ParameterizedTest
  @MethodSource("unpairedSurrogates")
  void replacesEachUnpairedSurrogateWithTheReplacementCharacter(String text, byte[] expected) {
    assertArrayEquals(expected, Payloads.text(text));
  } // end method replacesEachUnpairedSurrogateWithTheReplacementCharacter

  static Stream<byte[]> invalidUtf8Vectors() {
    return CodecVectors.invalidUtf8Vectors().stream();
  } // end method invalidUtf8Vectors

  @ParameterizedTest
  @MethodSource("invalidUtf8Vectors")
  void readTextRejectsInvalidUtf8(byte[] payload) {
    CelerisException failure =
        assertThrows(CelerisException.class, () -> Payloads.readText(payload));

    assertEquals(ErrorCode.CONFIGURATION, failure.code());
    assertEquals("Payload is not valid UTF-8, so it cannot be read as text.", failure.getMessage());
    assertNull(failure.getCause());
  } // end method readTextRejectsInvalidUtf8

  @Test
  void readTextRejectsTruncatedAndOverlongSequencesAnywhere() {
    for (byte[] payload :
        List.of(
            bytes('a', 0xC3),
            bytes(0xE0, 0x80, 0xAF),
            bytes(0xF8, 0x88, 0x80, 0x80, 0x80),
            bytes(0xFF),
            bytes('o', 'k', 0xED, 0xBF, 0xBF))) {
      assertThrows(CelerisException.class, () -> Payloads.readText(payload));
    }
  } // end method readTextRejectsTruncatedAndOverlongSequencesAnywhere

  @Test
  void readTextKeepsALeadingByteOrderMark() {
    assertEquals(codePoint(0xFEFF) + "a", Payloads.readText(bytes(0xEF, 0xBB, 0xBF, 'a')));
  } // end method readTextKeepsALeadingByteOrderMark

  // WHATWG TextDecoder replacement: one U+FFFD per maximal invalid subpart.
  static Stream<Arguments> lenientDecodings() {
    return Stream.of(
        Arguments.of(bytes(0x80), REPLACEMENT),
        Arguments.of(bytes(0xc0, 0xaf), REPLACEMENT.repeat(2)),
        Arguments.of(bytes(0xed, 0xa0, 0x80), REPLACEMENT.repeat(3)),
        Arguments.of(bytes(0xf0, 0x9f, 0x98), REPLACEMENT),
        Arguments.of(bytes(0xf4, 0x90, 0x80, 0x80), REPLACEMENT.repeat(4)),
        Arguments.of(bytes(0xed, 0xbf, 0xbf), REPLACEMENT.repeat(3)),
        Arguments.of(bytes(0xe0, 0x80, 0x80), REPLACEMENT.repeat(3)),
        Arguments.of(bytes(0xf0, 0x80, 0x80, 0x80), REPLACEMENT.repeat(4)),
        Arguments.of(bytes(0xf8, 0x88, 0x80, 0x80, 0x80), REPLACEMENT.repeat(5)),
        Arguments.of(bytes(0xf0, 0x9f, 0x98, 'A'), REPLACEMENT + "A"),
        Arguments.of(bytes(0xe1, 0x80), REPLACEMENT),
        Arguments.of(bytes(0xfe, 0xff), REPLACEMENT.repeat(2)),
        // The Unicode Standard's own example (Table 3-8).
        Arguments.of(
            bytes(0x61, 0xF1, 0x80, 0x80, 0xE1, 0x80, 0xC2, 0x62, 0x80, 0x63, 0x80, 0xBF, 0x64),
            "a" + REPLACEMENT.repeat(3) + "b" + REPLACEMENT + "c" + REPLACEMENT.repeat(2) + "d"),
        // Well-formed sequences at the edges of each range decode unchanged.
        Arguments.of(bytes(0xed, 0x9f, 0xbf), codePoint(0xD7FF)),
        Arguments.of(bytes(0xee, 0x80, 0x80), codePoint(0xE000)),
        Arguments.of(bytes(0xf0, 0x90, 0x80, 0x80), codePoint(0x10000)),
        Arguments.of(bytes(0xf4, 0x8f, 0xbf, 0xbf), codePoint(0x10FFFF)),
        Arguments.of(bytes(0xc2, 0x80), codePoint(0x80)),
        Arguments.of(bytes(0x7f), codePoint(0x7F)),
        Arguments.of(bytes(0xdf, 0xbf), codePoint(0x7FF)),
        Arguments.of(bytes(0xe0, 0xa0, 0x80), codePoint(0x800)),
        Arguments.of(bytes(0xef, 0xbf, 0xbf), codePoint(0xFFFF)),
        Arguments.of(utf8("Rate limit exceeded: 識"), "Rate limit exceeded: 識"),
        // A leading byte order mark is dropped; a second one, or one later on, is text.
        Arguments.of(bytes(0xEF, 0xBB, 0xBF, 'a'), "a"),
        Arguments.of(bytes(0xEF, 0xBB, 0xBF), ""),
        Arguments.of(bytes(0xEF, 0xBB, 0xBF, 0xEF, 0xBB, 0xBF), codePoint(0xFEFF)),
        Arguments.of(bytes('a', 0xEF, 0xBB, 0xBF), "a" + codePoint(0xFEFF)),
        Arguments.of(bytes(0xEF, 0xBB), REPLACEMENT),
        Arguments.of(new byte[0], ""));
  } // end method lenientDecodings

  @ParameterizedTest
  @MethodSource("lenientDecodings")
  void lenientDecodingReplacesLikeTextDecoder(byte[] bytes, String expected) {
    assertEquals(expected, Payloads.lenient(bytes));
  } // end method lenientDecodingReplacesLikeTextDecoder

  @Test
  void codecsRoundTripThroughTheSuppliedFunctions() {
    PayloadCodec<String> codec =
        PayloadCodec.of(value -> utf8("<" + value + ">"), payload -> Payloads.readText(payload));

    byte[] payload = codec.encodePayload("hello");

    assertArrayEquals(utf8("<hello>"), payload);
    assertEquals("<hello>", codec.readPayload(payload));
  } // end method codecsRoundTripThroughTheSuppliedFunctions

  @Test
  void codecsPropagateTheCallersFailuresUnchanged() {
    IllegalStateException encodeFailure = new IllegalStateException("synthetic-encoder-failure");
    IllegalArgumentException decodeFailure =
        new IllegalArgumentException("synthetic-decoder-failure");
    PayloadCodec<String> codec =
        PayloadCodec.of(
            value -> {
              throw encodeFailure;
            },
            payload -> {
              throw decodeFailure;
            });

    assertSame(
        encodeFailure, assertThrows(IllegalStateException.class, () -> codec.encodePayload("x")));
    assertSame(
        decodeFailure,
        assertThrows(IllegalArgumentException.class, () -> codec.readPayload(new byte[0])));
  } // end method codecsPropagateTheCallersFailuresUnchanged

  @Test
  void codecsPropagateErrorsUnchanged() {
    StackOverflowError failure = new StackOverflowError("synthetic");
    PayloadCodec<String> codec =
        PayloadCodec.of(
            value -> new byte[0],
            payload -> {
              throw failure;
            });

    assertSame(
        failure, assertThrows(StackOverflowError.class, () -> codec.readPayload(new byte[0])));
  } // end method codecsPropagateErrorsUnchanged

  @Test
  void codecsRequireBothFunctions() {
    Function<String, byte[]> encode = value -> new byte[0];
    Function<byte[], String> decode = payload -> "";

    for (Executable creation :
        List.<Executable>of(
            () -> PayloadCodec.of(null, decode),
            () -> PayloadCodec.of(encode, null),
            () -> PayloadCodec.<String>of(null, null))) {
      CelerisException failure = assertThrows(CelerisException.class, creation);

      assertEquals(ErrorCode.CONFIGURATION, failure.code());
      assertEquals("Codec must provide encode and decode functions.", failure.getMessage());
      assertNull(failure.getCause());
    }
  } // end method codecsRequireBothFunctions
} // end class PayloadsTest

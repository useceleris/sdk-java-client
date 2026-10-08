package com.useceleris.client;

import static com.useceleris.client.CodecVectors.bytes;
import static com.useceleris.client.CodecVectors.join;
import static com.useceleris.client.CodecVectors.utf8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class EncodeTest {
  private static final int LIMIT = 2 * 1024 * 1024;

  private static final String TOO_LARGE =
      "Encoded command exceeds 2 MiB. That is the most the server accepts on any plan; send a"
          + " smaller payload.";

  private static CelerisException assertConfiguration(Executable encode) {
    CelerisException failure = assertThrows(CelerisException.class, encode);

    assertEquals(ErrorCode.CONFIGURATION, failure.code());
    assertNull(failure.getCause());

    return failure;
  } // end method assertConfiguration

  private static void assertConfiguration(String message, Executable encode) {
    assertEquals(message, assertConfiguration(encode).getMessage());
  } // end method assertConfiguration

  static Stream<CodecVectors.EncodingVector> encodingVectors() {
    return CodecVectors.encodingVectors().stream();
  } // end method encodingVectors

  @ParameterizedTest(name = "{0}")
  @MethodSource("encodingVectors")
  void encodesEveryReferenceVector(CodecVectors.EncodingVector vector) {
    assertArrayEquals(vector.expected(), vector.encode().get());
  } // end method encodesEveryReferenceVector

  @ParameterizedTest
  @ValueSource(strings = {"NODE_PUB", "NODE_FUTURE", "PUB", "PRES_LIST", "sub", "", "SUB\n"})
  void rejectsCommandsOutsideTheSegmentCommands(String name) {
    assertConfiguration(
        "Invalid command. Unsupported command type.",
        () -> CommandEncoder.segmentCommand(name, "s"));
  } // end method rejectsCommandsOutsideTheSegmentCommands

  @Test
  void namesEveryInvalidFieldWithItsRule() {
    String empty = "Must not be empty";
    String control = "Must not contain CR, LF or unpaired UTF-16 surrogates";
    assertConfiguration(
        "Invalid command. segmentId: " + empty + ".",
        () -> CommandEncoder.segmentCommand("SUB", ""));
    assertConfiguration(
        "Invalid command. segmentId: " + control + ".",
        () -> CommandEncoder.segmentCommand("SUB", "a\n"));
    assertConfiguration(
        "Invalid command. segmentId: " + control + ".",
        () -> CommandEncoder.segmentCommand("UNSUB", "a\r"));
    assertConfiguration(
        "Invalid command. segmentId: " + empty + ".",
        () -> CommandEncoder.publish("", null, utf8("x")));
    assertConfiguration(
        "Invalid command. messageId: " + empty + ".",
        () -> CommandEncoder.publish("s", "", utf8("x")));
    assertConfiguration(
        "Invalid command. segmentId: " + control + ". messageId: " + empty + ".",
        () -> CommandEncoder.publish("\r", "", utf8("x")));
    assertConfiguration(
        "Invalid command. requestId: " + empty + ".",
        () -> CommandEncoder.presenceList("s", 1, 1, ""));
    assertConfiguration(
        "Invalid command. segmentId: "
            + empty
            + ". page: Must be at least 1. perPage: Must be at most 100. requestId: "
            + control
            + ".",
        () -> CommandEncoder.presenceList("", 0, 101, "\n"));
  } // end method namesEveryInvalidFieldWithItsRule

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void rejectsPagesBelowOne(int page) {
    assertConfiguration(
        "Invalid command. page: Must be at least 1.",
        () -> CommandEncoder.presenceList("s", page, 1, "1"));
  } // end method rejectsPagesBelowOne

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void rejectsPageSizesBelowOne(int perPage) {
    assertConfiguration(
        "Invalid command. perPage: Must be at least 1.",
        () -> CommandEncoder.presenceList("s", 1, perPage, "1"));
  } // end method rejectsPageSizesBelowOne

  @ParameterizedTest
  @ValueSource(ints = {101, 1000, Integer.MAX_VALUE})
  void rejectsPageSizesAboveOneHundred(int perPage) {
    assertConfiguration(
        "Invalid command. perPage: Must be at most 100.",
        () -> CommandEncoder.presenceList("s", 1, perPage, "1"));
  } // end method rejectsPageSizesAboveOneHundred

  static Stream<String> invalidIdentifierVectors() {
    return CodecVectors.invalidIdentifierVectors().stream();
  } // end method invalidIdentifierVectors

  @ParameterizedTest
  @MethodSource("invalidIdentifierVectors")
  void rejectsIllFormedIdentifiersInEveryPosition(String identifier) {
    List<Executable> encoders =
        List.of(
            () -> CommandEncoder.segmentCommand("SUB", identifier),
            () -> CommandEncoder.segmentCommand("UNSUB", identifier),
            () -> CommandEncoder.segmentCommand("PRES_SUB", identifier),
            () -> CommandEncoder.segmentCommand("PRES_UNSUB", identifier),
            () -> CommandEncoder.presenceList(identifier, 1, 1, "1"),
            () -> CommandEncoder.presenceList("s", 1, 1, identifier),
            () -> CommandEncoder.publish(identifier, null, new byte[0]),
            () -> CommandEncoder.publish("s", identifier, new byte[0]));

    for (Executable encoder : encoders) {
      CelerisException failure = assertConfiguration(encoder);

      assertFalse(failure.getMessage().contains(identifier));
      assertFalse(failure.getMessage().contains("�"));
    }
  } // end method rejectsIllFormedIdentifiersInEveryPosition

  @Test
  void countsTheWholeEncodedCommandAgainstTheLimit() {
    // 2 MiB is the whole encoded command, not the payload: "@PUB\n", "$1\ns\n", "$-1\n",
    // "$2097128\n" and the closing LF are 24 bytes.
    byte[] encoded = CommandEncoder.publish("s", null, new byte[LIMIT - 24]);

    assertEquals(LIMIT, encoded.length);
    assertConfiguration(TOO_LARGE, () -> CommandEncoder.publish("s", null, new byte[LIMIT - 23]));
    // A message id counts too.
    assertConfiguration(TOO_LARGE, () -> CommandEncoder.publish("s", "m", new byte[LIMIT - 24]));
  } // end method countsTheWholeEncodedCommandAgainstTheLimit

  @Test
  void countsSegmentAndRequestIdentifiersInUtf8Bytes() {
    // "@SUB\n", "$2097137\n" and the closing LF are 15 bytes.
    assertEquals(LIMIT, CommandEncoder.segmentCommand("SUB", "s".repeat(LIMIT - 15)).length);
    assertConfiguration(
        TOO_LARGE, () -> CommandEncoder.segmentCommand("SUB", "s".repeat(LIMIT - 14)));
    // Characters within the limit, but each one is two UTF-8 bytes.
    assertConfiguration(
        TOO_LARGE, () -> CommandEncoder.segmentCommand("SUB", "é".repeat(LIMIT / 2)));
    // "@PRES_LIST\n", "$1\ns\n", ";1\n;1\n", "$2097120\n" and the closing LF are 32 bytes.
    assertEquals(LIMIT, CommandEncoder.presenceList("s", 1, 1, "r".repeat(LIMIT - 32)).length);
    assertConfiguration(
        TOO_LARGE, () -> CommandEncoder.presenceList("s", 1, 1, "r".repeat(LIMIT - 31)));
  } // end method countsSegmentAndRequestIdentifiersInUtf8Bytes

  @Test
  void aRefusedCommandLeavesTheNextUnaffected() {
    assertConfiguration(TOO_LARGE, () -> CommandEncoder.publish("s", null, new byte[LIMIT]));

    assertArrayEquals(utf8("@SUB\n$4\nchat\n"), CommandEncoder.segmentCommand("SUB", "chat"));
  } // end method aRefusedCommandLeavesTheNextUnaffected

  @Test
  void copiesThePayloadAndLeavesItUntouched() {
    byte[] payload = bytes(0, 255);
    byte[] encoded = CommandEncoder.publish("s", null, payload);

    assertArrayEquals(bytes(0, 255), payload);
    Arrays.fill(payload, (byte) 42);
    assertArrayEquals(join(utf8("@PUB\n$1\ns\n$-1\n$2\n"), bytes(0, 255, 10)), encoded);
  } // end method copiesThePayloadAndLeavesItUntouched

  @Test
  void namesTheFailedFieldWithoutRepeatingTheInput() {
    CelerisException failure =
        assertConfiguration(() -> CommandEncoder.publish("synthetic-secret\n", null, utf8("x")));

    assertEquals(
        "Invalid command. segmentId: Must not contain CR, LF or unpaired UTF-16 surrogates.",
        failure.getMessage());
    assertFalse(failure.toString().contains("synthetic-secret"));

    CelerisException unsupported =
        assertConfiguration(() -> CommandEncoder.segmentCommand("SECRET", "synthetic-secret"));

    assertFalse(unsupported.getMessage().contains("SECRET"));
    assertFalse(unsupported.getMessage().contains("synthetic-secret"));
  } // end method namesTheFailedFieldWithoutRepeatingTheInput
} // end class EncodeTest

package com.useceleris.client;

import static com.useceleris.client.CodecVectors.bytes;
import static com.useceleris.client.CodecVectors.join;
import static com.useceleris.client.CodecVectors.utf8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ProtocolErrorsTest {
  record Located(String label, byte[] wire, String reason, String field, int offset) {
    @Override
    public String toString() {
      return field + " at byte " + offset + ": " + reason + " (" + label + ")";
    } // end method toString
  } // end record Located

  private static final String NOTICE = "@SERVER_MSG\n:1\n$0\n\n";

  private static final String PRESENCE_FIGURES =
      "@PRES_LIST_RESPONSE\n+s\n$1\n1\n;0\n;1\n;1\n;0\n;0\n";

  private static final String ERROR_FIELDS = "-Err\n+E\n$-1\n$1\nm\n";

  private static Located located(String wire, String reason, String field, int offset) {
    String shown = wire.length() > 48 ? wire.substring(0, 48) + "..." : wire;
    String label = shown.replace("\r", "\\r").replace("\n", "\\n");

    return new Located(label, utf8(wire), reason, field, offset);
  } // end method located

  private static Located located(
      String label, byte[] wire, String reason, String field, int offset) {
    return new Located(label, wire, reason, field, offset);
  } // end method located

  static List<Located> locatedFailures() {
    return List.of(
        // Ported from the reference's protocol-errors suite.
        located("", "Missing field marker.", "message", 0),
        located("?", "Unexpected server message marker.", "message", 0),
        located("*0\n!", "Trailing data after server message.", "message", 3),
        located(
            "*2\n@UNKNOWN\n@SERVER_MSG\n:1\n$0\n\n",
            "Unknown command inside array has ambiguous boundaries.",
            "command",
            3),
        // Java adaptation: the reference says "Invalid integer." here, after its BigInt parser.
        located("@SERVER_MSG\n:abc\n", "Expected decimal digits.", "timestamp", 12),
        located("@SERVER_MSG\n:+1\n", "Expected decimal digits.", "timestamp", 12),
        located(
            "@SERVER_MSG\n:9223372036854775808\n",
            "Integer is outside -9223372036854775808 to 9223372036854775807.",
            "timestamp",
            12),
        located(
            "@SERVER_MSG\n:000000000000000000000\n",
            "Line exceeds its 20-byte limit.",
            "timestamp",
            12),
        located("@SERVER_MSG\n:1", "Unterminated line.", "timestamp", 12),
        located("@SERVER_MSG\n+1\n", "Expected Integer64 marker.", "timestamp", 12),
        located(
            "@PRES_LIST_RESPONSE\n+s\n$1\n1\n;2147483648\n",
            "Integer is outside -2147483648 to 2147483647.",
            "total",
            28),
        located(
            "@PRES_LIST_RESPONSE\n+s\n$1\n1\n;000000000001\n",
            "Line exceeds its 11-byte limit.",
            "total",
            28),
        located("@PRES_LIST_RESPONSE\n+s\n$1\n1\n:1\n", "Expected Integer32 marker.", "total", 28),
        located(
            "@SERVER_MSG\n:1\n$9\nx\n",
            "Bulk payload exceeds remaining message bytes.",
            "payload",
            15),
        located("@SERVER_MSG\n:1\n$1\nx!", "Missing bulk byte terminator.", "payload", 15),
        located("@SERVER_MSG\n:1\n$-2\n", "Invalid bulk byte length.", "payload", 15),
        located("@SERVER_MSG\n:1\n$-1\n", "Payload cannot be null.", "payload", 15),
        located("@SERVER_MSG\n:1\n*0\n", "Expected simple or bulk byte marker.", "payload", 15),
        located("@MSG\n+u\n+\n", "Identifier must be nonempty and CR/LF-free.", "segmentId", 8),
        located("@MSG\n$-1\n", "Identifier cannot be null.", "tokenReference", 5),
        located("*-1\n", "Array length cannot be negative.", "messages", 0),
        located("*4096\n", "Array length exceeds the 4096-fragment budget.", "messages", 0),
        located("-Bad\n", "Invalid error header.", "error", 0),
        located("-Err\n+Bad Name\n$-1\n$6\nsecret\n$-1\n", "Invalid error name.", "errorType", 5),
        located("-Err\nParserError\nsecret", "Expected simple string marker.", "errorType", 5),
        located(
            "-Err\n+ParserError\n$3\nSUB\n$6\nsecret\n$-1\n",
            "Sub type must be a simple string or null.",
            "errorSubType",
            18),
        located(
            "-Err\n+ParserError\n$-1\n$-1\n$-1\n", "Payload cannot be null.", "errorMessage", 22),
        located(
            "-Err\n+ParserError\n$-1\n$6\nsecret\n@X\n",
            "Unexpected resource marker.",
            "resource",
            32),
        located(
            "*1\n".repeat(32) + "*0\n", "Arrays are nested deeper than 32 levels.", "messages", 96),
        located(
            "@PRES_NOTIFY\n+s\n+u\n+c\n;2\n:1\n", "Presence event must be 0 or 1.", "event", 22),
        located(
            "invalid UTF-8 token reference",
            join(utf8("@MSG\n+"), bytes(255), utf8("synthetic-secret\n")),
            "Invalid UTF-8 text.",
            "tokenReference",
            5),
        // Every other reason, and every field label, located at the field's first byte.
        located(
            "*1366\n" + NOTICE.repeat(1366),
            "Server message has more than 4096 fragments.",
            "message",
            6 + 1365 * NOTICE.length()),
        located("*1\n", "Missing field marker.", "message", 3),
        located("@SERVER_MSG\n", "Missing field marker.", "timestamp", 12),
        located("@SERVER_MSG\n:1\n", "Missing field marker.", "payload", 15),
        located("@SERVER_MSG\n:1\n$\n", "Expected decimal digits.", "payload", 15),
        located("@ABCDEFGHIJKLMNOPQRS\n", "Line exceeds its 18-byte limit.", "command", 0),
        located(
            "invalid UTF-8 command",
            join(utf8("@"), bytes(0xFF), utf8("\n")),
            "Invalid UTF-8 text.",
            "command",
            0),
        located("-Errr\n", "Line exceeds its 3-byte limit.", "error", 0),
        located(
            "invalid UTF-8 error header",
            join(utf8("-"), bytes(0xFF, 0xFF, 0xFF), utf8("\n")),
            "Invalid UTF-8 text.",
            "error",
            0),
        located(
            "-Err\n+" + "A".repeat(65) + "\n", "Line exceeds its 64-byte limit.", "errorType", 5),
        located(
            "invalid UTF-8 error type",
            join(utf8("-Err\n+"), bytes(0xFF), utf8("\n$-1\n$1\nm\n$-1\n")),
            "Invalid UTF-8 text.",
            "errorType",
            5),
        located(
            "invalid UTF-8 error sub type",
            join(utf8("-Err\n+E\n+"), bytes(0xFF), utf8("\n$1\nm\n$-1\n")),
            "Invalid UTF-8 text.",
            "errorSubType",
            8),
        located(
            "@MSG\n+u\n+s\n+\n", "Identifier must be nonempty and CR/LF-free.", "messageId", 11),
        located(
            "invalid UTF-8 message id",
            join(utf8("@MSG\n+u\n+s\n$1\n"), bytes(0xFF), utf8("\n")),
            "Invalid UTF-8 text.",
            "messageId",
            11),
        located("@PRES_LIST_RESPONSE\n+s\n$-1\n", "Identifier cannot be null.", "requestId", 23),
        located(
            "@PRES_LIST_RESPONSE\n+s\n$1\n1\n;0\n:1\n",
            "Expected Integer32 marker.",
            "perPage",
            31),
        located(
            "@PRES_LIST_RESPONSE\n+s\n$1\n1\n;0\n;1\n:1\n",
            "Expected Integer32 marker.",
            "currentPage",
            34),
        located(
            "@PRES_LIST_RESPONSE\n+s\n$1\n1\n;0\n;1\n;1\n:1\n",
            "Expected Integer32 marker.",
            "from",
            37),
        located(
            "@PRES_LIST_RESPONSE\n+s\n$1\n1\n;0\n;1\n;1\n;0\n:1\n",
            "Expected Integer32 marker.",
            "to",
            40),
        located(PRESENCE_FIGURES + "+x\n", "Expected array marker.", "connections", 43),
        located(PRESENCE_FIGURES + "*1\n+u\n", "Expected array marker.", "connection", 46),
        located(
            PRESENCE_FIGURES + "*1\n*2\n+u\n+c\n",
            "Presence connection must contain three fields.",
            "connection",
            46),
        located(
            PRESENCE_FIGURES + "*1\n*3\n$-1\n", "Identifier cannot be null.", "tokenReference", 49),
        located("@PRES_NOTIFY\n+s\n+u\n$-1\n", "Identifier cannot be null.", "connectionId", 19),
        located(
            ERROR_FIELDS + ";2147483648\n",
            "Integer is outside -2147483648 to 2147483647.",
            "resource",
            17),
        located(ERROR_FIELDS + "+abc", "Unterminated line.", "resource", 17),
        located(
            "invalid UTF-8 resource",
            join(utf8(ERROR_FIELDS + "$1\n"), bytes(0xFF), utf8("\n")),
            "Invalid UTF-8 text.",
            "resource",
            17),
        located(ERROR_FIELDS + "*-1\n", "Array length cannot be negative.", "resource", 17),
        located(
            ERROR_FIELDS + "*4096\n",
            "Array length exceeds the 4096-fragment budget.",
            "resource",
            17));
  } // end method locatedFailures

  @ParameterizedTest(name = "{0}")
  @MethodSource("locatedFailures")
  void locatesEachFailureAtItsFieldStart(Located expected) {
    CelerisException failure =
        assertThrows(CelerisException.class, () -> MessageDecoder.decode(expected.wire()));

    assertEquals(ErrorCode.PROTOCOL, failure.code());
    assertEquals(expected.field(), failure.field().orElseThrow());
    assertEquals(expected.offset(), failure.offset().orElseThrow());
    assertEquals(
        expected.reason()
            + " Field: "
            + expected.field()
            + ", byte offset "
            + expected.offset()
            + ".",
        failure.getMessage());
    assertNull(failure.getCause());
  } // end method locatesEachFailureAtItsFieldStart

  @Test
  void everyReasonAndFieldLabelHasALocatedCase() {
    Set<String> reasons =
        locatedFailures().stream()
            .map(Located::reason)
            .collect(Collectors.toCollection(TreeSet::new));
    Set<String> fields =
        locatedFailures().stream()
            .map(Located::field)
            .collect(Collectors.toCollection(TreeSet::new));

    assertEquals(new TreeSet<>(CodecVectors.DECODER_REASONS), reasons);
    assertEquals(new TreeSet<>(CodecVectors.DECODER_FIELDS), fields);
  } // end method everyReasonAndFieldLabelHasALocatedCase

  static Stream<byte[]> inputsCarryingSecrets() {
    return Stream.of(
        utf8("synthetic-secret"),
        join(utf8("@MSG\n+"), bytes(255), utf8("synthetic-secret\n")),
        utf8("-Err\n+synthetic-secret!\n$-1\n$1\nm\n$-1\n"),
        utf8("-Err\n+E\n$-1\n$16\nsynthetic-secret\n@synthetic-secret\n"),
        utf8("@SERVER_MSG\n:synthetic-secret\n$0\n\n"),
        utf8("*2\n@synthetic-secret\n+a\n" + NOTICE),
        utf8("@PRES_LIST_RESPONSE\n+s\n$1\n1\n;synthetic-secret\n"));
  } // end method inputsCarryingSecrets

  @ParameterizedTest
  @MethodSource("inputsCarryingSecrets")
  void failuresNeverRepeatReceivedBytes(byte[] wire) {
    CelerisException failure =
        assertThrows(CelerisException.class, () -> MessageDecoder.decode(wire));

    assertFalse(failure.getMessage().contains("secret"), failure.getMessage());
    assertFalse(failure.toString().contains("secret"), failure.toString());
    assertFalse(failure.field().orElseThrow().contains("secret"));
    assertNull(failure.getCause());
  } // end method failuresNeverRepeatReceivedBytes

  @Test
  void reportsTheMarkerFailureOfANonMessageExactly() {
    CelerisException failure =
        assertThrows(CelerisException.class, () -> MessageDecoder.decode(utf8("synthetic-secret")));

    assertEquals(
        "Unexpected server message marker. Field: message, byte offset 0.", failure.getMessage());
    assertEquals("message", failure.field().orElseThrow());
    assertEquals(0, failure.offset().orElseThrow());
  } // end method reportsTheMarkerFailureOfANonMessageExactly
} // end class ProtocolErrorsTest

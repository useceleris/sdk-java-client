package com.useceleris.client;

import static com.useceleris.client.CodecVectors.bytes;
import static com.useceleris.client.CodecVectors.join;
import static com.useceleris.client.CodecVectors.utf8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DecodeBoundariesTest {
  private static final String NOTICE = "@SERVER_MSG\n:1\n$0\n\n";

  private static final String PRESENCE_FIGURES =
      "@PRES_LIST_RESPONSE\n+s\n$1\n1\n;1\n;1\n;1\n;1\n;1\n";

  private static void assertFailure(String wire, String reason, String field, int offset) {
    assertFailure(utf8(wire), reason, field, offset);
  } // end method assertFailure

  private static void assertFailure(byte[] wire, String reason, String field, int offset) {
    CelerisException failure =
        assertThrows(CelerisException.class, () -> MessageDecoder.decode(wire));

    assertEquals(ErrorCode.PROTOCOL, failure.code());
    assertEquals(
        reason + " Field: " + field + ", byte offset " + offset + ".", failure.getMessage());
    assertEquals(field, failure.field().orElseThrow());
    assertEquals(offset, failure.offset().orElseThrow());
  } // end method assertFailure

  private static long timestampOf(String wire) {
    return assertInstanceOf(ServerMessage.Notice.class, MessageDecoder.decode(utf8(wire)))
        .timestamp();
  } // end method timestampOf

  @ParameterizedTest
  @ValueSource(strings = {"\n", "\r\n"})
  void acceptsMaximumLengthNumericAndErrorNameHeaders(String newline) {
    assertEquals(
        1L,
        timestampOf(
            "@SERVER_MSG"
                + newline
                + ":00000000000000000001"
                + newline
                + "$0"
                + newline
                + newline));

    ServerMessage message =
        MessageDecoder.decode(
            utf8(
                "-Err"
                    + newline
                    + "+"
                    + "A".repeat(64)
                    + newline
                    + "+"
                    + "B".repeat(64)
                    + newline
                    + "$1"
                    + newline
                    + "m"
                    + newline
                    + "$-1"
                    + newline));

    ServerMessage.ErrorFrame error = assertInstanceOf(ServerMessage.ErrorFrame.class, message);
    assertEquals("A".repeat(64), error.type());
    assertEquals("B".repeat(64), error.subType());
  } // end method acceptsMaximumLengthNumericAndErrorNameHeaders

  @Test
  void reportsBoundedHeaderFailuresAtTheFieldStart() {
    String prefix = "@SERVER_MSG\n:";
    assertFailure(prefix + "0".repeat(20), "Unterminated line.", "timestamp", 12);
    assertFailure(prefix + "0".repeat(21), "Unterminated line.", "timestamp", 12);
    assertFailure(prefix + "0".repeat(22), "Line exceeds its 20-byte limit.", "timestamp", 12);
    assertFailure(
        prefix + "0".repeat(21) + "\n", "Line exceeds its 20-byte limit.", "timestamp", 12);
    assertFailure(
        prefix + "0".repeat(21) + "\r\n", "Line exceeds its 20-byte limit.", "timestamp", 12);
  } // end method reportsBoundedHeaderFailuresAtTheFieldStart

  @Test
  void rejectsALongMalformedHeaderWithoutSearchingTheRestOfTheMessage() {
    assertFailure(
        "@SERVER_MSG\n:" + "0".repeat(65536) + "\n",
        "Line exceeds its 20-byte limit.",
        "timestamp",
        12);
  } // end method rejectsALongMalformedHeaderWithoutSearchingTheRestOfTheMessage

  @Test
  void copiesBinaryPayloadsMadeOfNewlineAndMarkerBytes() {
    byte[] payload = new byte[65536];
    byte[] pattern = utf8("\n\r@$*:+-");

    for (int index = 0; index < payload.length; index++) {
      payload[index] = pattern[index % pattern.length];
    }

    byte[] bytes = join(utf8("@SERVER_MSG\n:1\n$65536\n"), payload, bytes('\n'));
    ServerMessage.Notice notice =
        assertInstanceOf(ServerMessage.Notice.class, MessageDecoder.decode(bytes));
    Arrays.fill(bytes, (byte) 0);

    assertArrayEquals(payload, notice.payload());
  } // end method copiesBinaryPayloadsMadeOfNewlineAndMarkerBytes

  @Test
  void acceptsExactlyTheFragmentBudget() {
    ServerMessage.Batch batch =
        assertInstanceOf(
            ServerMessage.Batch.class,
            MessageDecoder.decode(utf8("*4095\n" + "*0\n".repeat(4095))));

    assertEquals(4095, batch.messages().size());
  } // end method acceptsExactlyTheFragmentBudget

  @Test
  void rejectsTheFragmentAfterTheBudget() {
    // 1 array marker, then three markers per notice: the 1366th notice's marker is fragment 4097.
    assertFailure(
        "*1366\n" + NOTICE.repeat(1366),
        "Server message has more than 4096 fragments.",
        "message",
        6 + 1365 * NOTICE.length());
  } // end method rejectsTheFragmentAfterTheBudget

  @Test
  void rejectsAnArrayLengthBeyondTheRemainingBudget() {
    assertFailure("*4096\n", "Array length exceeds the 4096-fragment budget.", "messages", 0);
    assertFailure(
        "*4095\n" + "*0\n".repeat(4094) + "*1\n*0\n",
        "Array length exceeds the 4096-fragment budget.",
        "messages",
        6 + 4094 * 3);
  } // end method rejectsAnArrayLengthBeyondTheRemainingBudget

  @Test
  void acceptsThirtyTwoNestedArraysAndRejectsThirtyThree() {
    assertInstanceOf(
        ServerMessage.Batch.class, MessageDecoder.decode(utf8("*1\n".repeat(31) + "*0\n")));
    assertFailure(
        "*1\n".repeat(32) + "*0\n", "Arrays are nested deeper than 32 levels.", "messages", 96);
  } // end method acceptsThirtyTwoNestedArraysAndRejectsThirtyThree

  @Test
  void boundsResourceNestingAtTheSameDepth() {
    String error = "-Err\n+E\n$-1\n$1\nm\n";
    assertInstanceOf(
        ServerMessage.ErrorFrame.class,
        MessageDecoder.decode(utf8(error + "*1\n".repeat(31) + "$-1\n")));
    assertFailure(
        error + "*1\n".repeat(32) + "$-1\n",
        "Arrays are nested deeper than 32 levels.",
        "resource",
        error.length() + 31 * 3);
  } // end method boundsResourceNestingAtTheSameDepth

  @Test
  void boundsPresenceConnectionsByTheDepthOfTheirResponse() {
    String outer = "*1\n".repeat(31);
    assertInstanceOf(
        ServerMessage.Batch.class, MessageDecoder.decode(utf8(outer + PRESENCE_FIGURES + "*0\n")));
    assertFailure(
        outer + PRESENCE_FIGURES + "*1\n*3\n+u\n+c\n:1\n",
        "Arrays are nested deeper than 32 levels.",
        "connection",
        outer.length() + PRESENCE_FIGURES.length() + 3);
  } // end method boundsPresenceConnectionsByTheDepthOfTheirResponse

  @Test
  void acceptsTheEdgesOfTheInteger64Range() {
    assertEquals(Long.MAX_VALUE, timestampOf("@SERVER_MSG\n:9223372036854775807\n$0\n\n"));
    assertEquals(Long.MIN_VALUE, timestampOf("@SERVER_MSG\n:-9223372036854775808\n$0\n\n"));
    assertEquals(-1L, timestampOf("@SERVER_MSG\n:-0000000000000000001\n$0\n\n"));
  } // end method acceptsTheEdgesOfTheInteger64Range

  @Test
  void rejectsIntegers64OutsideTheRangeOrTheLineLimit() {
    String range = "Integer is outside -9223372036854775808 to 9223372036854775807.";
    assertFailure("@SERVER_MSG\n:9223372036854775808\n$0\n\n", range, "timestamp", 12);
    assertFailure("@SERVER_MSG\n:-9223372036854775809\n$0\n\n", range, "timestamp", 12);
    assertFailure("@SERVER_MSG\n:99999999999999999999\n$0\n\n", range, "timestamp", 12);
    assertFailure(
        "@SERVER_MSG\n:-00000000000000000001\n$0\n\n",
        "Line exceeds its 20-byte limit.",
        "timestamp",
        12);
    // Bulk lengths share the Integer64 grammar.
    assertFailure("@SERVER_MSG\n:1\n$9223372036854775808\n", range, "payload", 15);
  } // end method rejectsIntegers64OutsideTheRangeOrTheLineLimit

  @Test
  void acceptsTheEdgesOfTheInteger32Range() {
    ServerMessage message =
        MessageDecoder.decode(
            utf8(
                "@PRES_LIST_RESPONSE\n+s\n$1\n1\n;2147483647\n;-2147483648\n;00000000001\n;-0\n;0\n*0\n"));

    ServerMessage.PresenceListResponse response =
        assertInstanceOf(ServerMessage.PresenceListResponse.class, message);
    assertEquals(Integer.MAX_VALUE, response.page().total());
    assertEquals(Integer.MIN_VALUE, response.page().perPage());
    assertEquals(1, response.page().currentPage());
    assertEquals(0, response.page().from());
    assertEquals(0, response.page().to());
  } // end method acceptsTheEdgesOfTheInteger32Range

  @Test
  void rejectsIntegers32OutsideTheRangeOrTheLineLimit() {
    String prefix = "@PRES_LIST_RESPONSE\n+s\n$1\n1\n";
    String range = "Integer is outside -2147483648 to 2147483647.";
    assertFailure(prefix + ";2147483648\n", range, "total", 28);
    assertFailure(prefix + ";-2147483649\n", range, "total", 28);
    assertFailure(prefix + ";000000000001\n", "Line exceeds its 11-byte limit.", "total", 28);
    assertFailure(prefix + ";-000000000001\n", "Line exceeds its 11-byte limit.", "total", 28);
  } // end method rejectsIntegers32OutsideTheRangeOrTheLineLimit

  @Test
  void acceptsAnEighteenByteCommandNameAndRejectsNineteen() {
    assertInstanceOf(
        ServerMessage.PresenceListResponse.class,
        MessageDecoder.decode(utf8("@PRES_LIST_RESPONSE\r\n+s\n$1\n1\n;1\n;1\n;1\n;1\n;1\n*0\n")));
    assertInstanceOf(
        ServerMessage.Ignored.class, MessageDecoder.decode(utf8("@ABCDEFGHIJKLMNOPQR\r\n")));
    assertFailure("@ABCDEFGHIJKLMNOPQRS\n", "Line exceeds its 18-byte limit.", "command", 0);
  } // end method acceptsAnEighteenByteCommandNameAndRejectsNineteen

  @Test
  void rejectsErrorNamesLongerThanSixtyFourBytes() {
    assertFailure(
        "-Err\n+" + "A".repeat(65) + "\n$-1\n$1\nm\n$-1\n",
        "Line exceeds its 64-byte limit.",
        "errorType",
        5);
    assertFailure(
        "-Err\n+E\n+" + "B".repeat(65) + "\n$1\nm\n$-1\n",
        "Line exceeds its 64-byte limit.",
        "errorSubType",
        8);
  } // end method rejectsErrorNamesLongerThanSixtyFourBytes

  @Test
  void acceptsTheErrorHeaderOnlyAsErr() {
    assertInstanceOf(
        ServerMessage.ErrorFrame.class,
        MessageDecoder.decode(utf8("-Err\r\n+E\r\n$-1\r\n$1\r\nm\r\n$-1\r\n")));
    assertFailure("-Errr\n", "Line exceeds its 3-byte limit.", "error", 0);
    assertFailure("-Er\n", "Invalid error header.", "error", 0);
    assertFailure("-err\n", "Invalid error header.", "error", 0);
  } // end method acceptsTheErrorHeaderOnlyAsErr

  @Test
  void skipsAnUnknownCommandOnlyInTheTailPosition() {
    assertInstanceOf(
        ServerMessage.Batch.class, MessageDecoder.decode(utf8("*1\n*1\n*1\n@FUTURE\n+a\n:1\n")));
    assertFailure(
        "*2\n*1\n@FUTURE\n+a\n" + NOTICE,
        "Unknown command inside array has ambiguous boundaries.",
        "command",
        6);
    assertFailure(
        "*2\n@FUTURE\n+a\n" + NOTICE,
        "Unknown command inside array has ambiguous boundaries.",
        "command",
        3);
  } // end method skipsAnUnknownCommandOnlyInTheTailPosition

  @Test
  void rejectsDataAfterACompleteMessage() {
    assertFailure("*0\n!", "Trailing data after server message.", "message", 3);
    assertFailure(NOTICE + "\n", "Trailing data after server message.", "message", NOTICE.length());
    assertFailure(
        "-Err\n+E\n$-1\n$1\nm\n$-1\n-", "Trailing data after server message.", "message", 21);
  } // end method rejectsDataAfterACompleteMessage

  @Test
  void requiresALineFeedAfterEachBulkString() {
    ServerMessage.Notice notice =
        assertInstanceOf(
            ServerMessage.Notice.class, MessageDecoder.decode(utf8("@SERVER_MSG\n:1\n$2\nok\r\n")));
    assertArrayEquals(utf8("ok"), notice.payload());
    assertFailure("@SERVER_MSG\n:1\n$0\n\r!", "Missing bulk byte terminator.", "payload", 15);
    assertFailure("@SERVER_MSG\n:1\n$0\n\r", "Missing bulk byte terminator.", "payload", 15);
    assertFailure("@SERVER_MSG\n:1\n$1\nx", "Missing bulk byte terminator.", "payload", 15);
    assertFailure("@SERVER_MSG\n:1\n$2\nx\n", "Missing bulk byte terminator.", "payload", 15);
    assertFailure(
        "@SERVER_MSG\n:1\n$3\nx\n", "Bulk payload exceeds remaining message bytes.", "payload", 15);
  } // end method requiresALineFeedAfterEachBulkString

  // An error name is letters, digits and underscores, starting with a letter; each range includes
  // its first and last character.
  @Test
  void acceptsErrorNamesMadeOfTheEdgesOfEachCharacterRange() {
    ServerMessage.ErrorFrame error =
        assertInstanceOf(
            ServerMessage.ErrorFrame.class,
            MessageDecoder.decode(utf8("-Err\n+AZaz09_\n+zA9_0\n$1\nm\n$-1\n")));

    assertEquals("AZaz09_", error.type());
    assertEquals("zA9_0", error.subType());
  } // end method acceptsErrorNamesMadeOfTheEdgesOfEachCharacterRange

  @Test
  void rejectsEmptyErrorNamesAndCharactersJustOutsideEachRange() {
    assertFailure("-Err\n+\n$-1\n$1\nm\n$-1\n", "Invalid error name.", "errorType", 5);
    assertFailure("-Err\n+E\n+\n$1\nm\n$-1\n", "Invalid error name.", "errorSubType", 8);

    for (String name : List.of("0E", "_E", "E/", "E:", "E@", "E[", "E`", "E{")) {
      assertFailure(
          "-Err\n+" + name + "\n$-1\n$1\nm\n$-1\n", "Invalid error name.", "errorType", 5);
    }
  } // end method rejectsEmptyErrorNamesAndCharactersJustOutsideEachRange

  // A bulk string may carry CR and LF anywhere, but an identifier may not, its first byte included.
  @Test
  void rejectsBulkIdentifiersThatStartWithALineBreak() {
    for (String start : List.of("\r", "\n")) {
      assertFailure(
          "@MSG\n+u\n$2\n" + start + "s\n",
          "Identifier must be nonempty and CR/LF-free.",
          "segmentId",
          8);
    }
  } // end method rejectsBulkIdentifiersThatStartWithALineBreak
} // end class DecodeBoundariesTest

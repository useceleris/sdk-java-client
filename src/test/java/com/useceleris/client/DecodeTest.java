package com.useceleris.client;

import static com.useceleris.client.CodecVectors.assertMessageEquals;
import static com.useceleris.client.CodecVectors.bytes;
import static com.useceleris.client.CodecVectors.join;
import static com.useceleris.client.CodecVectors.utf8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class DecodeTest {
  static Stream<CodecVectors.DecodingVector> decodingVectors() {
    return CodecVectors.decodingVectors().stream();
  } // end method decodingVectors

  static Stream<byte[]> malformedVectors() {
    return CodecVectors.malformedVectors().stream();
  } // end method malformedVectors

  @ParameterizedTest(name = "{0}")
  @MethodSource("decodingVectors")
  void decodesEveryReferenceVector(CodecVectors.DecodingVector vector) {
    assertMessageEquals(vector.expected(), MessageDecoder.decode(vector.bytes()));
  } // end method decodesEveryReferenceVector

  @ParameterizedTest
  @MethodSource("malformedVectors")
  void rejectsEveryMalformedReferenceVector(byte[] bytes) {
    CelerisException failure =
        assertThrows(CelerisException.class, () -> MessageDecoder.decode(bytes));

    assertEquals(ErrorCode.PROTOCOL, failure.code());
  } // end method rejectsEveryMalformedReferenceVector

  @Test
  void rejectsEveryTruncationOfAMessageThatSkipsNothing() {
    for (CodecVectors.DecodingVector vector : CodecVectors.decodingVectors()) {
      // A skipped command runs to the end of the message, so its truncations are still messages.
      if (CodecVectors.containsIgnored(vector.expected())) {
        continue;
      }

      for (int length = 0; length < vector.bytes().length; length++) {
        byte[] truncated = Arrays.copyOf(vector.bytes(), length);
        CelerisException failure =
            assertThrows(
                CelerisException.class,
                () -> MessageDecoder.decode(truncated),
                vector.name() + " truncated to " + length);

        assertEquals(ErrorCode.PROTOCOL, failure.code());
      }
    }
  } // end method rejectsEveryTruncationOfAMessageThatSkipsNothing

  @Test
  void skipsAnUnknownCommandThroughTheEndOfTheMessage() {
    byte[] bytes = join(utf8("@FUTURE_COMMAND\n"), bytes(0xFF, 0x00, '*', '9', '\r'), utf8("+"));

    assertInstanceOf(ServerMessage.Ignored.class, MessageDecoder.decode(bytes));
  } // end method skipsAnUnknownCommandThroughTheEndOfTheMessage

  @Test
  void skipsAnUnknownCommandInTheTailOfNestedArrays() {
    ServerMessage message =
        MessageDecoder.decode(utf8("*2\n@SERVER_MSG\n:1\n$0\n\n*1\n*1\n@FUTURE\n+a\n:1\n"));

    assertMessageEquals(
        new ServerMessage.Batch(
            List.of(
                new ServerMessage.Notice(1L, new byte[0]),
                new ServerMessage.Batch(
                    List.of(new ServerMessage.Batch(List.of(new ServerMessage.Ignored())))))),
        message);
  } // end method skipsAnUnknownCommandInTheTailOfNestedArrays

  // LIMIT-01: received messages are never size-checked.
  @Test
  void decodesADeliveryLargerThanOneMebibyte() {
    int payloadLength = 1024 * 1024;
    byte[] payload = new byte[payloadLength];
    Arrays.fill(payload, (byte) 'x');
    payload[0] = '\n';
    payload[payloadLength - 1] = '\r';
    byte[] bytes =
        join(
            utf8("@MSG\n+user\n+chat\n+msg_1\n:1\n$" + payloadLength + "\n"), payload, bytes('\n'));

    ServerMessage.Delivery delivery =
        assertInstanceOf(ServerMessage.Delivery.class, MessageDecoder.decode(bytes));

    assertTrue(bytes.length > 1024 * 1024);
    assertEquals("chat", delivery.segmentId());
    assertEquals("msg_1", delivery.messageId());
    assertArrayEquals(payload, delivery.payload());
  } // end method decodesADeliveryLargerThanOneMebibyte

  @Test
  void ownsPayloadCopiesIndependentlyOfTheInputAndSiblings() {
    byte[] bytes = utf8("*2\n@SERVER_MSG\n:1\n$1\nx\n@SERVER_MSG\n:1\n$1\nx\n");
    byte[] original = bytes.clone();

    ServerMessage.Batch batch =
        assertInstanceOf(ServerMessage.Batch.class, MessageDecoder.decode(bytes));

    assertArrayEquals(original, bytes);
    Arrays.fill(bytes, (byte) 0);
    ServerMessage.Notice first = (ServerMessage.Notice) batch.messages().get(0);
    ServerMessage.Notice second = (ServerMessage.Notice) batch.messages().get(1);
    assertArrayEquals(utf8("x"), first.payload());
    first.payload()[0] = 0;
    assertArrayEquals(utf8("x"), second.payload());
  } // end method ownsPayloadCopiesIndependentlyOfTheInputAndSiblings

  @Test
  void ownsErrorMessageCopies() {
    byte[] bytes = utf8("-Err\n+ParserError\n$-1\n$3\nbad\n$-1\n");

    ServerMessage.ErrorFrame error =
        assertInstanceOf(ServerMessage.ErrorFrame.class, MessageDecoder.decode(bytes));
    Arrays.fill(bytes, (byte) 0);

    assertArrayEquals(utf8("bad"), error.message());
  } // end method ownsErrorMessageCopies

  @ParameterizedTest
  @ValueSource(
      strings = {
        "", " ", "0x10", "0o10", "0b10", "+1", "1 ", "\t1", "1\r\r", "--1", "1.5", "1e2", "١", "-",
        "1-", "１"
      })
  void rejectsNumericTextOutsideTheDecimalGrammar(String text) {
    CelerisException failure =
        assertThrows(
            CelerisException.class,
            () -> MessageDecoder.decode(utf8("@SERVER_MSG\n:" + text + "\n$0\n\n")));

    assertEquals(ErrorCode.PROTOCOL, failure.code());
    assertEquals("timestamp", failure.field().orElseThrow());
  } // end method rejectsNumericTextOutsideTheDecimalGrammar

  @Test
  void preservesAcceptedDecimalSpellings() {
    assertEquals(1L, noticeTimestamp("0001"));
    assertEquals(0L, noticeTimestamp("-0"));
    assertEquals(-1L, noticeTimestamp("-0001"));
  } // end method preservesAcceptedDecimalSpellings

  private static long noticeTimestamp(String text) {
    ServerMessage message = MessageDecoder.decode(utf8("@SERVER_MSG\n:" + text + "\n$0\n\n"));

    return assertInstanceOf(ServerMessage.Notice.class, message).timestamp();
  } // end method noticeTimestamp

  @Test
  void stripsOneCarriageReturnBeforeEachLineFeed() {
    ServerMessage message = MessageDecoder.decode(utf8("@SERVER_MSG\r\n:1\r\n+da\rta\r\n"));

    assertArrayEquals(
        utf8("da\rta"), assertInstanceOf(ServerMessage.Notice.class, message).payload());
  } // end method stripsOneCarriageReturnBeforeEachLineFeed

  @Test
  void decodesResourcesAsStringsLongsIntegersAndUnmodifiableLists() {
    ServerMessage message =
        MessageDecoder.decode(
            utf8(
                "-Err\n+FutureError\n$-1\n$1\nx\n*4\n:9223372036854775807\n;-2147483648\n+s\n*1\n*0\n"));

    ServerMessage.ErrorFrame error = assertInstanceOf(ServerMessage.ErrorFrame.class, message);
    assertNull(error.subType());
    List<?> resource = assertInstanceOf(List.class, error.resource());
    assertEquals(Long.valueOf(Long.MAX_VALUE), assertInstanceOf(Long.class, resource.get(0)));
    assertEquals(
        Integer.valueOf(Integer.MIN_VALUE), assertInstanceOf(Integer.class, resource.get(1)));
    assertEquals("s", resource.get(2));
    List<?> nested = assertInstanceOf(List.class, resource.get(3));
    List<?> innermost = assertInstanceOf(List.class, nested.get(0));
    assertTrue(innermost.isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> resource.remove(0));
    assertThrows(UnsupportedOperationException.class, () -> nested.remove(0));
    assertThrows(UnsupportedOperationException.class, innermost::clear);
  } // end method decodesResourcesAsStringsLongsIntegersAndUnmodifiableLists

  @Test
  void decodesScalarResources() {
    assertEquals(7, errorResource(";7\n"));
    assertEquals(-5L, errorResource(":-5\n"));
    assertEquals("room", errorResource("+room\n"));
    assertEquals("", errorResource("$0\n\n"));
    assertNull(errorResource("$-1\n"));
  } // end method decodesScalarResources

  private static @Nullable Object errorResource(String resource) {
    ServerMessage message =
        MessageDecoder.decode(utf8("-Err\n+ParserError\n$-1\n$1\nm\n" + resource));

    return assertInstanceOf(ServerMessage.ErrorFrame.class, message).resource();
  } // end method errorResource

  @Test
  void returnsUnmodifiableBatchesAndConnectionLists() {
    ServerMessage.Batch batch =
        assertInstanceOf(
            ServerMessage.Batch.class,
            MessageDecoder.decode(
                utf8(
                    "*1\n@PRES_LIST_RESPONSE\n+s\n$1\n1\n;1\n;1\n;1\n;1\n;1\n*1\n*3\n+u\n+c\n:1\n")));
    ServerMessage.PresenceListResponse response =
        assertInstanceOf(ServerMessage.PresenceListResponse.class, batch.messages().get(0));

    assertThrows(UnsupportedOperationException.class, () -> batch.messages().remove(0));
    assertThrows(
        UnsupportedOperationException.class, () -> response.page().connections().remove(0));
  } // end method returnsUnmodifiableBatchesAndConnectionLists
} // end class DecodeTest

package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

// Vector revision 2: realtime@cfa901fa73b2bd26abcc46c2f5ce47879dd4dc75 plus the
// uncommitted C14 error frame and presence request ids.
// Hand-authored from Rust layouts and copied by hand from the JavaScript reference's
// tests/fixtures/codec-vectors.ts; no SDK encoder creates expectations.
final class CodecVectors {
  private CodecVectors() {}

  record EncodingVector(String name, Supplier<byte[]> encode, byte[] expected) {
    @Override
    public String toString() {
      return name;
    } // end method toString
  } // end record EncodingVector

  record DecodingVector(String name, byte[] bytes, ServerMessage expected) {
    @Override
    public String toString() {
      return name;
    } // end method toString
  } // end record DecodingVector

  /** The UTF-8 bytes of well-formed text, as the reference's TextEncoder-based utf8 helper. */
  static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  } // end method utf8

  static byte[] bytes(int... values) {
    byte[] result = new byte[values.length];

    for (int index = 0; index < values.length; index++) {
      result[index] = (byte) values[index];
    }

    return result;
  } // end method bytes

  static byte[] join(byte[]... parts) {
    int length = 0;

    for (byte[] part : parts) {
      length += part.length;
    }

    byte[] joined = new byte[length];
    int position = 0;

    for (byte[] part : parts) {
      System.arraycopy(part, 0, joined, position, part.length);
      position += part.length;
    }

    return joined;
  } // end method join

  static List<EncodingVector> encodingVectors() {
    List<EncodingVector> vectors = new ArrayList<>();
    vectors.add(
        new EncodingVector(
            "publish null ID",
            () -> CommandEncoder.publish("default", null, utf8("hello")),
            utf8("@PUB\n$7\ndefault\n$-1\n$5\nhello\n")));
    vectors.add(
        new EncodingVector(
            "publish binary and Unicode",
            () -> CommandEncoder.publish("c:é", "識", bytes(0, 255, 13, 10, 64)),
            join(utf8("@PUB\n$4\nc:é\n$3\n識\n$5\n"), bytes(0, 255, 13, 10, 64, 10))));
    vectors.add(
        new EncodingVector(
            "publish empty",
            () -> CommandEncoder.publish("a", null, new byte[0]),
            utf8("@PUB\n$1\na\n$-1\n$0\n\n")));
    vectors.add(
        new EncodingVector(
            "subscribe",
            () -> CommandEncoder.segmentCommand("SUB", "chat"),
            utf8("@SUB\n$4\nchat\n")));
    vectors.add(
        new EncodingVector(
            "unsubscribe",
            () -> CommandEncoder.segmentCommand("UNSUB", "chat"),
            utf8("@UNSUB\n$4\nchat\n")));
    vectors.add(
        new EncodingVector(
            "presence subscribe",
            () -> CommandEncoder.segmentCommand("PRES_SUB", "chat"),
            utf8("@PRES_SUB\n$4\nchat\n")));
    vectors.add(
        new EncodingVector(
            "presence unsubscribe",
            () -> CommandEncoder.segmentCommand("PRES_UNSUB", "chat"),
            utf8("@PRES_UNSUB\n$4\nchat\n")));
    vectors.add(
        new EncodingVector(
            "presence first page",
            () -> CommandEncoder.presenceList("chat", 1, 1, "1"),
            utf8("@PRES_LIST\n$4\nchat\n;1\n;1\n$1\n1\n")));
    vectors.add(
        new EncodingVector(
            "presence last allowed page",
            () -> CommandEncoder.presenceList("chat", 2147483647, 100, "識-9"),
            utf8("@PRES_LIST\n$4\nchat\n;2147483647\n;100\n$5\n識-9\n")));

    // Valid Unicode is preserved without normalization.
    vectors.add(preservedIdentifier("preserves Unicode identifier \"😀\"", "😀", 4));
    vectors.add(
        preservedIdentifier("preserves Unicode identifier \"\\ud800\\udc00\"", "\ud800\udc00", 4));
    vectors.add(
        preservedIdentifier("preserves Unicode identifier \"\\udbff\\udfff\"", "\udbff\udfff", 4));
    vectors.add(preservedIdentifier("preserves Unicode identifier \"\\ufffd\"", "\ufffd", 3));
    vectors.add(preservedIdentifier("preserves Unicode identifier \"e\\u0301\"", "e\u0301", 3));
    vectors.add(preservedIdentifier("preserves Unicode identifier \"é\"", "é", 2));
    vectors.add(preservedIdentifier("preserves Unicode identifier \"a\\u0000b\"", "a\u0000b", 3));

    return vectors;
  } // end method encodingVectors

  private static EncodingVector preservedIdentifier(String name, String identifier, int length) {
    return new EncodingVector(
        name,
        () -> CommandEncoder.segmentCommand("SUB", identifier),
        utf8("@SUB\n$" + length + "\n" + identifier + "\n"));
  } // end method preservedIdentifier

  static List<DecodingVector> decodingVectors() {
    List<DecodingVector> vectors = new ArrayList<>();
    vectors.add(
        new DecodingVector(
            "peer null ID and zero timestamp",
            utf8("@MSG\n+user\n+chat\n$-1\n:0\n$0\n\n"),
            new ServerMessage.Delivery("user", "chat", null, 0L, new byte[0])));
    vectors.add(
        new DecodingVector(
            "peer Unicode and max timestamp",
            join(
                utf8("@MSG\n+用戶\n+c:é\n+識\n:9223372036854775807\n$5\n"),
                bytes(0, 255, 13, 10, 64, 10)),
            new ServerMessage.Delivery(
                "用戶", "c:é", "識", 9223372036854775807L, bytes(0, 255, 13, 10, 64))));
    vectors.add(
        new DecodingVector(
            "CRLF and min timestamp",
            utf8("@SERVER_MSG\r\n:-9223372036854775808\r\n$2\r\nok\r\n"),
            new ServerMessage.Notice(-9223372036854775808L, utf8("ok"))));
    vectors.add(
        new DecodingVector(
            "raw notice",
            utf8("@SERVER_MSG\n:1\n$6\njoined\n"),
            new ServerMessage.Notice(1L, utf8("joined"))));
    vectors.add(
        new DecodingVector(
            "simple payload and bulk identifiers",
            utf8("@MSG\n$1\nu\n$1\ns\n$1\nm\n:-1\n+data\n"),
            new ServerMessage.Delivery("u", "s", "m", -1L, utf8("data"))));
    vectors.add(
        new DecodingVector(
            "preserve BOM in text",
            utf8("@MSG\n+\uFEFFu\n+s\n$-1\n:1\n$0\n\n"),
            new ServerMessage.Delivery("\uFEFFu", "s", null, 1L, new byte[0])));
    vectors.add(
        new DecodingVector("empty array", utf8("*0\n"), new ServerMessage.Batch(List.of())));
    vectors.add(
        new DecodingVector(
            "nested arrays",
            utf8("*2\n*0\n*1\n@SERVER_MSG\n:1\n$0\n\n"),
            new ServerMessage.Batch(
                List.of(
                    new ServerMessage.Batch(List.of()),
                    new ServerMessage.Batch(List.of(new ServerMessage.Notice(1L, new byte[0])))))));
    vectors.add(
        new DecodingVector(
            "presence connections",
            utf8(
                "@PRES_LIST_RESPONSE\n+chat\n$1\n7\n;1\n;25\n;1\n;1\n;1\n*1\n*3\n+user\n+connection\n:123\n"),
            new ServerMessage.PresenceListResponse(
                "7",
                new PresencePage(
                    "chat",
                    1,
                    25,
                    1,
                    1,
                    1,
                    List.of(new PresenceConnection("user", "connection", 123L))))));
    vectors.add(
        new DecodingVector(
            "presence empty",
            utf8("@PRES_LIST_RESPONSE\n+chat\n$1\n8\n;0\n;25\n;1\n;0\n;0\n*0\n"),
            new ServerMessage.PresenceListResponse(
                "8", new PresencePage("chat", 0, 25, 1, 0, 0, List.of()))));
    vectors.add(
        new DecodingVector(
            "presence notification join",
            utf8("@PRES_NOTIFY\n+chat\n+user\n+connection\n;1\n:123\n"),
            new ServerMessage.PresenceNotify(
                new PresenceEvent("chat", "user", "connection", true, 123L))));
    vectors.add(
        new DecodingVector(
            "presence notification leave",
            utf8("@PRES_NOTIFY\n+chat\n+user\n+connection\n;0\n:124\n"),
            new ServerMessage.PresenceNotify(
                new PresenceEvent("chat", "user", "connection", false, 124L))));
    // DECODE-01: a command this version does not know is skipped, not rejected, so a newer server
    // cannot break a deployed client.
    vectors.add(
        new DecodingVector(
            "unknown command ignored",
            utf8("@FUTURE_COMMAND\n+a\n:1\n"),
            new ServerMessage.Ignored()));
    vectors.add(
        new DecodingVector(
            "unknown command ignored in tail position",
            utf8("*2\n@SERVER_MSG\n:1\n$0\n\n@FUTURE_COMMAND\n+a\n"),
            new ServerMessage.Batch(
                List.of(new ServerMessage.Notice(1L, new byte[0]), new ServerMessage.Ignored()))));
    // NODE_* commands are internal between server nodes. The SDK recognises none of them, so one
    // that arrives is skipped like any other unknown command.
    vectors.add(
        new DecodingVector(
            "internal node command ignored",
            utf8("@NODE_PUB\n+node-1\n$4\nbody\n"),
            new ServerMessage.Ignored()));
    vectors.add(
        new DecodingVector(
            "internal node command ignored in tail position",
            utf8("*2\n@SERVER_MSG\n:1\n$0\n\n@NODE_PUB\n+node-1\n"),
            new ServerMessage.Batch(
                List.of(new ServerMessage.Notice(1L, new byte[0]), new ServerMessage.Ignored()))));
    vectors.add(
        new DecodingVector(
            "any NODE_ command ignored", utf8("@NODE_FUTURE\n+a\n"), new ServerMessage.Ignored()));
    vectors.add(
        new DecodingVector(
            "presence past last page",
            utf8("@PRES_LIST_RESPONSE\n+chat\n$1\n9\n;1\n;25\n;2\n;26\n;1\n*0\n"),
            new ServerMessage.PresenceListResponse(
                "9", new PresencePage("chat", 1, 25, 2, 26, 1, List.of()))));
    vectors.add(
        new DecodingVector(
            "error without sub type or resource",
            utf8("-Err\n+RateLimitError\n$-1\n$4\nslow\n$-1\n"),
            new ServerMessage.ErrorFrame("RateLimitError", null, utf8("slow"), null)));
    // The message is length-prefixed, so it may contain anything, including text that looks like
    // another error.
    vectors.add(
        new DecodingVector(
            "error message containing frame text",
            utf8("-Err\n+PermissionDeniedError\n+SUB\n$14\ntext\n-Err\nmore\n$4\nroom\n"),
            new ServerMessage.ErrorFrame(
                "PermissionDeniedError", "SUB", utf8("text\n-Err\nmore"), "room")));
    vectors.add(
        new DecodingVector(
            "presence query error carrying its request id",
            utf8("-Err\n+InternalError\n+PRES_LIST\n$27\nError getting presence data\n$1\n3\n"),
            new ServerMessage.ErrorFrame(
                "InternalError", "PRES_LIST", utf8("Error getting presence data"), "3")));
    vectors.add(
        new DecodingVector(
            "error resource of every shape",
            utf8("-Err\n+FutureError\n+PUB\n$1\nx\n*5\n+a\n:-5\n;7\n$-1\n*1\n$1\nb\n"),
            new ServerMessage.ErrorFrame(
                "FutureError",
                "PUB",
                utf8("x"),
                Arrays.<Object>asList("a", -5L, 7, null, List.of("b")))));
    // Errors are self-delimiting, so they may sit anywhere in a batch, and two batched errors
    // decode as two.
    vectors.add(
        new DecodingVector(
            "batched errors before other messages",
            utf8(
                "*3\n-Err\n+PermissionDeniedError\n+PRES_SUB\n$2\nno\n$4\nroom\n-Err\n+PermissionDeniedError\n+PRES_LIST\n$2\nno\n$1\n4\n@SERVER_MSG\n:1\n$2\nok\n"),
            new ServerMessage.Batch(
                List.of(
                    new ServerMessage.ErrorFrame(
                        "PermissionDeniedError", "PRES_SUB", utf8("no"), "room"),
                    new ServerMessage.ErrorFrame(
                        "PermissionDeniedError", "PRES_LIST", utf8("no"), "4"),
                    new ServerMessage.Notice(1L, utf8("ok"))))));

    // Invalid text encodings remain valid opaque binary payloads.
    for (byte[] invalid : invalidUtf8Vectors()) {
      vectors.add(
          new DecodingVector(
              "opaque non-UTF8 payload " + describe(invalid),
              join(utf8("@SERVER_MSG\n:1\n$" + invalid.length + "\n"), invalid, bytes(10)),
              new ServerMessage.Notice(1L, invalid.clone())));
    }

    return vectors;
  } // end method decodingVectors

  static List<byte[]> malformedVectors() {
    List<String> texts = new ArrayList<>();
    texts.add("");
    texts.add("*0\ntrailing");
    texts.add("*1\n");
    texts.add("*-1\n");
    texts.add("*4096\n");
    texts.add("*9223372036854775808\n");
    // An unknown command is skippable only when it runs to the end of the transport message;
    // anywhere else its boundary is unknowable (DECODE-01).
    texts.add("*2\n@FUTURE_COMMAND\n+a\n@SERVER_MSG\n:1\n$0\n\n");
    texts.add("*2\n*1\n@FUTURE_COMMAND\n+a\n@SERVER_MSG\n:1\n$0\n\n");
    texts.add("*2\n@NODE_PUB\n+node-1\n@SERVER_MSG\n:1\n$0\n\n");
    texts.add("+hello\n");
    texts.add(":1\n");
    texts.add("$-1\n");
    texts.add("@SERVER_MSG\n:+1\n$0\n\n");
    texts.add("@SERVER_MSG\n: 1\n$0\n\n");
    texts.add("@SERVER_MSG\n:1.0\n$0\n\n");
    texts.add("@SERVER_MSG\n:9223372036854775808\n$0\n\n");
    texts.add("@SERVER_MSG\n:-9223372036854775809\n$0\n\n");
    texts.add("@SERVER_MSG\n:1\n$-2\n");
    texts.add("@SERVER_MSG\n:1\n$-1\n");
    texts.add("@SERVER_MSG\n:1\n$9999999999999999999\n");
    texts.add("@SERVER_MSG\n:1\n$2\nx\n");
    texts.add("@SERVER_MSG\n:1\n$1\nx!");
    texts.add("@SERVER_MSG\n:1\n$0\n\r!");
    texts.add("@MSG\n+\n+s\n$-1\n:1\n$0\n\n");
    texts.add("@MSG\n+u\n+s\n+\n:1\n$0\n\n");
    texts.add("@MSG\n+u\rX\n+s\n$-1\n:1\n$0\n\n");
    texts.add("@MSG\n$3\nu\ns\n+s\n$-1\n:1\n$0\n\n");
    // A line-based layout without field markers.
    texts.add("-Err\nParserError\nmessage");
    texts.add("-Other\n+ParserError\n$-1\n$1\nm\n$-1\n");
    // Missing fields.
    texts.add("-Err\n+ParserError\n$-1\n$1\nm\n");
    texts.add("-Err\n+ParserError\n$-1\n");
    // The type must be a simple-string name.
    texts.add("-Err\n$11\nParserError\n$-1\n$1\nm\n$-1\n");
    texts.add("-Err\n+Bad\rName\n$-1\n$1\nm\n$-1\n");
    texts.add("-Err\n+Bad-Name\n$-1\n$1\nm\n$-1\n");
    texts.add(
        "-Err\n+ParserErrorParserErrorParserErrorParserErrorParserErrorParserError\n$-1\n$1\nm\n$-1\n");
    // The sub type must be a name or null.
    texts.add("-Err\n+ParserError\n+PRES LIST\n$1\nm\n$-1\n");
    texts.add("-Err\n+ParserError\n$3\nSUB\n$1\nm\n$-1\n");
    texts.add("-Err\n+ParserError\n:1\n$1\nm\n$-1\n");
    // The message cannot be null.
    texts.add("-Err\n+ParserError\n$-1\n$-1\n$-1\n");
    // The resource must be a known fragment, within the depth limit.
    texts.add("-Err\n+ParserError\n$-1\n$1\nm\n@SERVER_MSG\n");
    texts.add("-Err\n+ParserError\n$-1\n$1\nm\n" + "*1\n".repeat(40) + "$-1\n");
    texts.add("@PRES_LIST_RESPONSE\n+s\n$1\n1\n;0\n;1\n;1\n;0\n;0\n*1\n*2\n+u\n+c\n");
    // Presence figures and the join/leave flag are Integer32: signed 32-bit, decimal digits only,
    // with the `;` marker.
    for (String total :
        List.of(
            ";2147483648\n",
            ";-2147483649\n",
            ";000000000001\n",
            ";+1\n",
            "; 1\n",
            ";1.0\n",
            ":1\n")) {
      texts.add("@PRES_LIST_RESPONSE\n+s\n$1\n1\n" + total + ";1\n;1\n;0\n;0\n*0\n");
    }

    texts.add("@PRES_NOTIFY\n+s\n+u\n+c\n:1\n:123\n");

    List<byte[]> vectors = new ArrayList<>();

    for (String text : texts) {
      vectors.add(utf8(text));
    }

    vectors.add(join(utf8("@MSG\n+"), bytes(255), utf8("\n+s\n$-1\n:1\n$0\n\n")));

    for (byte[] invalid : invalidUtf8Vectors()) {
      vectors.add(
          join(utf8("@MSG\n$" + invalid.length + "\n"), invalid, utf8("\n+s\n$-1\n:1\n$0\n\n")));
    }

    return vectors;
  } // end method malformedVectors

  /** Invalid UTF-16 must not collapse onto the valid replacement character. */
  static List<String> invalidIdentifierVectors() {
    return List.of(
        "\ud800",
        "\udfff",
        "room-\ud800",
        "\udc00-room",
        "a\ud800b",
        "\udc00\ud800",
        "\ud800\ud800",
        "😀\udfff");
  } // end method invalidIdentifierVectors

  /** Invalid text encodings, which remain valid opaque binary payloads. */
  static List<byte[]> invalidUtf8Vectors() {
    return List.of(
        bytes(0x80),
        bytes(0xc0, 0xaf),
        bytes(0xed, 0xa0, 0x80),
        bytes(0xf0, 0x9f, 0x98),
        bytes(0xf4, 0x90, 0x80, 0x80));
  } // end method invalidUtf8Vectors

  /** Every reason the decoder gives: fixed text, so a failure can never repeat received bytes. */
  static final Set<String> DECODER_REASONS =
      Set.of(
          "Missing field marker.",
          "Unexpected server message marker.",
          "Trailing data after server message.",
          "Server message has more than 4096 fragments.",
          "Line exceeds its 3-byte limit.",
          "Line exceeds its 11-byte limit.",
          "Line exceeds its 18-byte limit.",
          "Line exceeds its 20-byte limit.",
          "Line exceeds its 64-byte limit.",
          "Unterminated line.",
          "Invalid UTF-8 text.",
          "Expected decimal digits.",
          "Integer is outside -9223372036854775808 to 9223372036854775807.",
          "Integer is outside -2147483648 to 2147483647.",
          "Expected Integer64 marker.",
          "Expected Integer32 marker.",
          "Expected simple or bulk byte marker.",
          "Invalid bulk byte length.",
          "Bulk payload exceeds remaining message bytes.",
          "Missing bulk byte terminator.",
          "Identifier cannot be null.",
          "Identifier must be nonempty and CR/LF-free.",
          "Payload cannot be null.",
          "Arrays are nested deeper than 32 levels.",
          "Expected array marker.",
          "Array length cannot be negative.",
          "Array length exceeds the 4096-fragment budget.",
          "Presence connection must contain three fields.",
          "Invalid error header.",
          "Expected simple string marker.",
          "Sub type must be a simple string or null.",
          "Invalid error name.",
          "Unexpected resource marker.",
          "Unknown command inside array has ambiguous boundaries.",
          "Presence event must be 0 or 1.");

  /** Every field label the decoder reports: the reference's camelCase protocol names. */
  static final Set<String> DECODER_FIELDS =
      Set.of(
          "message",
          "messages",
          "command",
          "error",
          "errorType",
          "errorSubType",
          "errorMessage",
          "resource",
          "tokenReference",
          "segmentId",
          "messageId",
          "timestamp",
          "payload",
          "connectionId",
          "requestId",
          "event",
          "total",
          "perPage",
          "currentPage",
          "from",
          "to",
          "connections",
          "connection");

  /** Reports whether a decoded message contains a skipped command anywhere. */
  static boolean containsIgnored(ServerMessage message) {
    if (message instanceof ServerMessage.Ignored) {
      return true;
    }

    if (message instanceof ServerMessage.Batch batch) {
      return batch.messages().stream().anyMatch(CodecVectors::containsIgnored);
    }

    return false;
  } // end method containsIgnored

  /**
   * Compares decoded messages field by field: payloads by content, and resources by value and type,
   * so a Long never matches an Integer.
   */
  static void assertMessageEquals(ServerMessage expected, ServerMessage actual) {
    assertEquals(expected.getClass(), actual.getClass(), "message kind");

    if (expected instanceof ServerMessage.Batch expectedBatch) {
      List<ServerMessage> actualMessages = ((ServerMessage.Batch) actual).messages();
      assertEquals(expectedBatch.messages().size(), actualMessages.size(), "batch size");

      for (int index = 0; index < actualMessages.size(); index++) {
        assertMessageEquals(expectedBatch.messages().get(index), actualMessages.get(index));
      }
    } else if (expected instanceof ServerMessage.Delivery expectedDelivery) {
      ServerMessage.Delivery actualDelivery = (ServerMessage.Delivery) actual;
      assertEquals(expectedDelivery.tokenReference(), actualDelivery.tokenReference());
      assertEquals(expectedDelivery.segmentId(), actualDelivery.segmentId());
      assertEquals(expectedDelivery.messageId(), actualDelivery.messageId());
      assertEquals(expectedDelivery.timestamp(), actualDelivery.timestamp());
      assertArrayEquals(expectedDelivery.payload(), actualDelivery.payload());
    } else if (expected instanceof ServerMessage.Notice expectedNotice) {
      ServerMessage.Notice actualNotice = (ServerMessage.Notice) actual;
      assertEquals(expectedNotice.timestamp(), actualNotice.timestamp());
      assertArrayEquals(expectedNotice.payload(), actualNotice.payload());
    } else if (expected instanceof ServerMessage.ErrorFrame expectedError) {
      ServerMessage.ErrorFrame actualError = (ServerMessage.ErrorFrame) actual;
      assertEquals(expectedError.type(), actualError.type());
      assertEquals(expectedError.subType(), actualError.subType());
      assertArrayEquals(expectedError.message(), actualError.message());
      assertResourceEquals(expectedError.resource(), actualError.resource());
    } else {
      assertEquals(expected, actual);
    }
  } // end method assertMessageEquals

  private static void assertResourceEquals(@Nullable Object expected, @Nullable Object actual) {
    if (expected instanceof List<?> expectedItems) {
      List<?> actualItems = assertInstanceOf(List.class, actual);
      assertEquals(expectedItems.size(), actualItems.size(), "resource list size");

      for (int index = 0; index < expectedItems.size(); index++) {
        assertResourceEquals(expectedItems.get(index), actualItems.get(index));
      }

      return;
    }

    if (expected != null) {
      assertEquals(expected.getClass(), actual == null ? null : actual.getClass());
    }

    assertEquals(expected, actual);
  } // end method assertResourceEquals

  static String describe(byte[] bytes) {
    StringBuilder description = new StringBuilder();

    for (int index = 0; index < bytes.length; index++) {
      if (index > 0) {
        description.append(',');
      }

      description.append(bytes[index] & 0xFF);
    }

    return description.toString();
  } // end method describe
} // end class CodecVectors

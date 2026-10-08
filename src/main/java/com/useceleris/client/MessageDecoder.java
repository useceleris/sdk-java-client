package com.useceleris.client;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Decodes one transport message. A decoder is built per message over that message's own bytes, so
 * nothing spans messages and a malformed one cannot desynchronise the next (DECODE-01). Every
 * failure is a {@link ErrorCode#PROTOCOL} error carrying a fixed reason, a field name this package
 * chose and a zero-based byte offset; never received bytes.
 */
final class MessageDecoder {
  private final byte[] data;
  private int offset;
  private int fragments;

  private MessageDecoder(byte[] data) {
    this.data = data;
  } // end constructor MessageDecoder

  static ServerMessage decode(byte[] data) {
    MessageDecoder decoder = new MessageDecoder(data);
    ServerMessage message = decoder.readMessage(0, true);

    if (decoder.offset != data.length) {
      throw CelerisException.protocol(
          "Trailing data after server message.", "message", decoder.offset);
    }

    return message;
  } // end method decode

  private byte readMarker(String field) {
    int fieldStartOffset = offset;
    fragments++;

    if (fragments > Constants.MAXIMUM_FRAGMENTS) {
      throw CelerisException.protocol(
          "Server message has more than 4096 fragments.", field, fieldStartOffset);
    }

    if (offset >= data.length) {
      throw CelerisException.protocol("Missing field marker.", field, fieldStartOffset);
    }

    return data[offset++];
  } // end method readMarker

  /**
   * Reads up to LF, allowing one CR before it, and returns the bounds of the content between. The
   * scan never runs past the content limit, one CR and the LF.
   */
  private int[] readLine(String field, int fieldStartOffset, int maximumLength) {
    int lineStart = offset;
    int searchEnd = (int) Math.min(data.length, (long) lineStart + maximumLength + 2);
    int newline = -1;

    for (int index = lineStart; index < searchEnd; index++) {
      if (data[index] == '\n') {
        newline = index;

        break;
      }
    }

    if (newline < 0) {
      if ((long) searchEnd - lineStart == (long) maximumLength + 2) {
        throw CelerisException.protocol(
            "Line exceeds its " + maximumLength + "-byte limit.", field, fieldStartOffset);
      }

      throw CelerisException.protocol("Unterminated line.", field, fieldStartOffset);
    }

    int contentEnd = newline;
    offset = newline + 1;

    if (contentEnd > lineStart && data[contentEnd - 1] == '\r') {
      contentEnd--;
    }

    if (contentEnd - lineStart > maximumLength) {
      throw CelerisException.protocol(
          "Line exceeds its " + maximumLength + "-byte limit.", field, fieldStartOffset);
    }

    return new int[] {lineStart, contentEnd};
  } // end method readLine

  private String readText(int start, int end, String field, int fieldStartOffset) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .decode(ByteBuffer.wrap(data, start, end - start))
          .toString();
    } catch (CharacterCodingException failure) {
      throw CelerisException.protocol("Invalid UTF-8 text.", field, fieldStartOffset);
    }
  } // end method readText

  /**
   * Reads the decimal grammar every numeric field shares: an optional minus sign, then digits only.
   * The range defaults to signed 64-bit, which also bounds bulk and array lengths.
   */
  private long readDecimal(
      String field, int fieldStartOffset, int maximumLineBytes, long minimum, long maximum) {
    int[] line = readLine(field, fieldStartOffset, maximumLineBytes);
    String text = readText(line[0], line[1], field, fieldStartOffset);
    String digits = text.startsWith("-") ? text.substring(1) : text;

    if (digits.isEmpty()) {
      throw CelerisException.protocol("Expected decimal digits.", field, fieldStartOffset);
    }

    for (int index = 0; index < digits.length(); index++) {
      char character = digits.charAt(index);

      if (character < '0' || character > '9') {
        throw CelerisException.protocol("Expected decimal digits.", field, fieldStartOffset);
      }
    }

    long value;

    try {
      value = Long.parseLong(text);
    } catch (NumberFormatException failure) {
      throw outOfRange(field, fieldStartOffset, minimum, maximum);
    }

    if (value < minimum || value > maximum) {
      throw outOfRange(field, fieldStartOffset, minimum, maximum);
    }

    return value;
  } // end method readDecimal

  private static CelerisException outOfRange(
      String field, int fieldStartOffset, long minimum, long maximum) {
    return CelerisException.protocol(
        "Integer is outside " + minimum + " to " + maximum + ".", field, fieldStartOffset);
  } // end method outOfRange

  private long readInteger64Digits(String field, int fieldStartOffset) {
    return readDecimal(
        field,
        fieldStartOffset,
        Constants.MAXIMUM_INTEGER64_LINE_BYTES,
        Long.MIN_VALUE,
        Long.MAX_VALUE);
  } // end method readInteger64Digits

  private int readInteger32Digits(String field, int fieldStartOffset) {
    return (int)
        readDecimal(
            field,
            fieldStartOffset,
            Constants.MAXIMUM_INTEGER32_LINE_BYTES,
            Integer.MIN_VALUE,
            Integer.MAX_VALUE);
  } // end method readInteger32Digits

  /** Reads an Integer64, which carries timestamps only. */
  private long readTimestamp() {
    int fieldStartOffset = offset;

    if (readMarker("timestamp") != ':') {
      throw CelerisException.protocol("Expected Integer64 marker.", "timestamp", fieldStartOffset);
    }

    return readInteger64Digits("timestamp", fieldStartOffset);
  } // end method readTimestamp

  private int readInteger32(String field) {
    int fieldStartOffset = offset;

    if (readMarker(field) != ';') {
      throw CelerisException.protocol("Expected Integer32 marker.", field, fieldStartOffset);
    }

    return readInteger32Digits(field, fieldStartOffset);
  } // end method readInteger32

  /** Reads a simple or bulk string; null is reported as null. */
  private int @Nullable [] readBytes(String field) {
    int fieldStartOffset = offset;
    byte marker = readMarker(field);

    return switch (marker) {
      case '+' -> readLine(field, fieldStartOffset, data.length);
      case '$' -> readBulkBytes(field, fieldStartOffset);
      default ->
          throw CelerisException.protocol(
              "Expected simple or bulk byte marker.", field, fieldStartOffset);
    };
  } // end method readBytes

  private int @Nullable [] readBulkBytes(String field, int fieldStartOffset) {
    long length = readInteger64Digits(field, fieldStartOffset);

    if (length == -1) {
      return null;
    }

    if (length < 0) {
      throw CelerisException.protocol("Invalid bulk byte length.", field, fieldStartOffset);
    }

    if (length > data.length - offset) {
      throw CelerisException.protocol(
          "Bulk payload exceeds remaining message bytes.", field, fieldStartOffset);
    }

    int start = offset;
    int end = offset + (int) length;
    offset = end;

    if (offset < data.length && data[offset] == '\r') {
      offset++;
    }

    if (offset >= data.length || data[offset] != '\n') {
      throw CelerisException.protocol("Missing bulk byte terminator.", field, fieldStartOffset);
    }

    offset++;

    return new int[] {start, end};
  } // end method readBulkBytes

  private String readIdentifier(String field) {
    int fieldStartOffset = offset;
    String identifier = readNullableIdentifier(field);

    if (identifier == null) {
      throw CelerisException.protocol("Identifier cannot be null.", field, fieldStartOffset);
    }

    return identifier;
  } // end method readIdentifier

  private @Nullable String readNullableIdentifier(String field) {
    int fieldStartOffset = offset;
    int[] bounds = readBytes(field);

    if (bounds == null) {
      return null;
    }

    String identifier = readText(bounds[0], bounds[1], field, fieldStartOffset);

    if (identifier.isEmpty() || identifier.indexOf('\r') >= 0 || identifier.indexOf('\n') >= 0) {
      throw CelerisException.protocol(
          "Identifier must be nonempty and CR/LF-free.", field, fieldStartOffset);
    }

    return identifier;
  } // end method readNullableIdentifier

  /** Copies the payload once, so what listeners receive shares no memory with the transport. */
  private byte[] readPayload(String field) {
    int fieldStartOffset = offset;
    int[] bounds = readBytes(field);

    if (bounds == null) {
      throw CelerisException.protocol("Payload cannot be null.", field, fieldStartOffset);
    }

    return Arrays.copyOfRange(data, bounds[0], bounds[1]);
  } // end method readPayload

  private int readArrayLength(int depth, String field, boolean markerAlreadyRead) {
    int fieldStartOffset = markerAlreadyRead ? offset - 1 : offset;

    if (depth >= Constants.MAXIMUM_DEPTH) {
      throw CelerisException.protocol(
          "Arrays are nested deeper than 32 levels.", field, fieldStartOffset);
    }

    if (!markerAlreadyRead && readMarker(field) != '*') {
      throw CelerisException.protocol("Expected array marker.", field, fieldStartOffset);
    }

    long length = readInteger64Digits(field, fieldStartOffset);

    if (length < 0) {
      throw CelerisException.protocol("Array length cannot be negative.", field, fieldStartOffset);
    }

    if (length > Constants.MAXIMUM_FRAGMENTS - fragments) {
      throw CelerisException.protocol(
          "Array length exceeds the 4096-fragment budget.", field, fieldStartOffset);
    }

    return (int) length;
  } // end method readArrayLength

  private List<PresenceConnection> readConnections(int depth) {
    int length = readArrayLength(depth, "connections", false);
    List<PresenceConnection> connections = new ArrayList<>(length);

    for (int index = 0; index < length; index++) {
      int fieldStartOffset = offset;
      int fields = readArrayLength(depth + 1, "connection", false);

      if (fields != 3) {
        throw CelerisException.protocol(
            "Presence connection must contain three fields.", "connection", fieldStartOffset);
      }

      String tokenReference = readIdentifier("tokenReference");
      String connectionId = readIdentifier("connectionId");
      long timestamp = readTimestamp();
      connections.add(new PresenceConnection(tokenReference, connectionId, timestamp));
    }

    return connections;
  } // end method readConnections

  private ServerMessage readMessage(int depth, boolean tail) {
    int fieldStartOffset = offset;
    byte marker = readMarker("message");

    return switch (marker) {
      case '*' -> readMessageArray(depth, tail);
      case '-' -> readErrorMessage(depth);
      case '@' -> readCommandMessage(depth, tail);
      default ->
          throw CelerisException.protocol(
              "Unexpected server message marker.", "message", fieldStartOffset);
    };
  } // end method readMessage

  private ServerMessage readMessageArray(int depth, boolean tail) {
    int length = readArrayLength(depth, "messages", true);
    List<ServerMessage> messages = new ArrayList<>(length);

    for (int index = 0; index < length; index++) {
      messages.add(readMessage(depth + 1, tail && index == length - 1));
    }

    return new ServerMessage.Batch(Collections.unmodifiableList(messages));
  } // end method readMessageArray

  /**
   * Reads an error frame. Every field is self-delimiting, so an error may sit anywhere in a batch.
   */
  private ServerMessage readErrorMessage(int depth) {
    int fieldStartOffset = offset - 1;
    int[] header = readLine("error", fieldStartOffset, 3);

    if (!readText(header[0], header[1], "error", fieldStartOffset).equals("Err")) {
      throw CelerisException.protocol("Invalid error header.", "error", fieldStartOffset);
    }

    String type = readErrorType();
    String subType = readErrorSubType();
    byte[] message = readPayload("errorMessage");
    Object resource = readResource(depth);

    return new ServerMessage.ErrorFrame(type, subType, message, resource);
  } // end method readErrorMessage

  private String readErrorType() {
    int fieldStartOffset = offset;

    if (readMarker("errorType") != '+') {
      throw CelerisException.protocol(
          "Expected simple string marker.", "errorType", fieldStartOffset);
    }

    return readErrorName("errorType", fieldStartOffset);
  } // end method readErrorType

  /** Returns null for a null sub type. */
  private @Nullable String readErrorSubType() {
    int fieldStartOffset = offset;
    byte marker = readMarker("errorSubType");

    if (marker == '+') {
      return readErrorName("errorSubType", fieldStartOffset);
    }

    if (marker == '$' && readInteger64Digits("errorSubType", fieldStartOffset) == -1) {
      return null;
    }

    throw CelerisException.protocol(
        "Sub type must be a simple string or null.", "errorSubType", fieldStartOffset);
  } // end method readErrorSubType

  /**
   * Reads an error type or sub type: a bounded name of letters, digits and underscores that starts
   * with a letter, such as PermissionDeniedError or PRES_LIST.
   */
  private String readErrorName(String field, int fieldStartOffset) {
    int[] line = readLine(field, fieldStartOffset, Constants.MAXIMUM_ERROR_NAME_BYTES);
    String name = readText(line[0], line[1], field, fieldStartOffset);

    if (!validErrorName(name)) {
      throw CelerisException.protocol("Invalid error name.", field, fieldStartOffset);
    }

    return name;
  } // end method readErrorName

  private static boolean validErrorName(String name) {
    if (name.isEmpty()) {
      return false;
    }

    for (int index = 0; index < name.length(); index++) {
      char character = name.charAt(index);
      boolean letter =
          (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
      boolean digit = character >= '0' && character <= '9';

      if (!letter && (index == 0 || (!digit && character != '_'))) {
        return false;
      }
    }

    return true;
  } // end method validErrorName

  /**
   * Reads any single fragment an error's type and sub type define: null, a string, an Integer64, an
   * Integer32, or an array of these.
   */
  private @Nullable Object readResource(int depth) {
    int fieldStartOffset = offset;
    byte marker = readMarker("resource");

    return switch (marker) {
      case '+' -> {
        int[] line = readLine("resource", fieldStartOffset, data.length);

        yield readText(line[0], line[1], "resource", fieldStartOffset);
      }
      case '$' -> {
        int[] bounds = readBulkBytes("resource", fieldStartOffset);

        if (bounds == null) {
          yield null;
        }

        yield readText(bounds[0], bounds[1], "resource", fieldStartOffset);
      }
      case ':' -> readInteger64Digits("resource", fieldStartOffset);
      case ';' -> readInteger32Digits("resource", fieldStartOffset);
      case '*' -> {
        int length = readArrayLength(depth + 1, "resource", true);
        List<@Nullable Object> items = new ArrayList<>(length);

        for (int index = 0; index < length; index++) {
          items.add(readResource(depth + 1));
        }

        yield Collections.unmodifiableList(items);
      }
      default ->
          throw CelerisException.protocol(
              "Unexpected resource marker.", "resource", fieldStartOffset);
    };
  } // end method readResource

  private ServerMessage readCommandMessage(int depth, boolean tail) {
    int fieldStartOffset = offset - 1;
    int[] line = readLine("command", fieldStartOffset, Constants.MAXIMUM_COMMAND_NAME_BYTES);
    String command = readText(line[0], line[1], "command", fieldStartOffset);

    return switch (command) {
      case "MSG" -> readDelivery();
      case "SERVER_MSG" -> readNotice();
      case "PRES_NOTIFY" -> readPresenceNotification();
      case "PRES_LIST_RESPONSE" -> readPresenceResponse(depth);
      default -> skipUnknownCommand(depth, tail, fieldStartOffset);
    };
  } // end method readCommandMessage

  /**
   * Skips a command this version does not know. It carries an unknown number of fields, so its end
   * is knowable only when it runs to the end of the transport message. Newer servers may add
   * commands; skipping them keeps this client working instead of dropping its connection.
   */
  private ServerMessage skipUnknownCommand(int depth, boolean tail, int fieldStartOffset) {
    if (depth != 0 && !tail) {
      throw CelerisException.protocol(
          "Unknown command inside array has ambiguous boundaries.", "command", fieldStartOffset);
    }

    offset = data.length;

    return new ServerMessage.Ignored();
  } // end method skipUnknownCommand

  private ServerMessage readDelivery() {
    String tokenReference = readIdentifier("tokenReference");
    String segmentId = readIdentifier("segmentId");
    String messageId = readNullableIdentifier("messageId");
    long timestamp = readTimestamp();
    byte[] payload = readPayload("payload");

    return new ServerMessage.Delivery(tokenReference, segmentId, messageId, timestamp, payload);
  } // end method readDelivery

  private ServerMessage readNotice() {
    long timestamp = readTimestamp();
    byte[] payload = readPayload("payload");

    return new ServerMessage.Notice(timestamp, payload);
  } // end method readNotice

  private ServerMessage readPresenceNotification() {
    String segmentId = readIdentifier("segmentId");
    String tokenReference = readIdentifier("tokenReference");
    String connectionId = readIdentifier("connectionId");
    int eventOffset = offset;
    int event = readInteger32("event");

    // A join/leave flag, not metadata: narrowed here rather than passed through raw, and any other
    // value is not a flag this client knows.
    if (event != 0 && event != 1) {
      throw CelerisException.protocol("Presence event must be 0 or 1.", "event", eventOffset);
    }

    long timestamp = readTimestamp();

    return new ServerMessage.PresenceNotify(
        new PresenceEvent(segmentId, tokenReference, connectionId, event == 1, timestamp));
  } // end method readPresenceNotification

  private ServerMessage readPresenceResponse(int depth) {
    String segmentId = readIdentifier("segmentId");
    String requestId = readIdentifier("requestId");
    int total = readInteger32("total");
    int perPage = readInteger32("perPage");
    int currentPage = readInteger32("currentPage");
    int from = readInteger32("from");
    int to = readInteger32("to");
    List<PresenceConnection> connections = readConnections(depth);

    return new ServerMessage.PresenceListResponse(
        requestId, new PresencePage(segmentId, total, perPage, currentPage, from, to, connections));
  } // end method readPresenceResponse
} // end class MessageDecoder

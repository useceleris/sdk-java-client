package com.useceleris.client;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Encodes the commands the client sends. Every identifier is validated before encoding. */
final class CommandEncoder {
  private CommandEncoder() {}

  static final String COMMAND_TOO_LARGE =
      "Encoded command exceeds 2 MiB. That is the most the server accepts on any plan; send a"
          + " smaller payload.";

  /**
   * Encodes PUB. A null message id encodes as null; the channel always supplies one (RESEND-01), so
   * null is reachable only from tests.
   */
  static byte[] publish(String segmentId, @Nullable String messageId, byte[] payload) {
    List<String> failures = new ArrayList<>();
    addFailure(failures, "segmentId", segmentId);

    if (messageId != null) {
      addFailure(failures, "messageId", messageId);
    }

    if (!failures.isEmpty()) {
      throw Identifiers.configurationError("command", failures);
    }

    byte[] segment = segmentId.getBytes(StandardCharsets.UTF_8);
    byte[] identifier = messageId == null ? null : messageId.getBytes(StandardCharsets.UTF_8);
    long size =
        "@PUB\n".length()
            + bulkSize(segment.length)
            + (identifier == null ? "$-1\n".length() : bulkSize(identifier.length))
            + bulkSize(payload.length);

    if (size > Constants.MAXIMUM_COMMAND_BYTES) {
      throw new CelerisException(ErrorCode.CONFIGURATION, COMMAND_TOO_LARGE);
    }

    ByteArrayOutputStream command = new ByteArrayOutputStream((int) size);
    appendText(command, "@PUB\n");
    appendBulk(command, segment);

    if (identifier == null) {
      appendText(command, "$-1\n");
    } else {
      appendBulk(command, identifier);
    }

    appendBulk(command, payload);

    return command.toByteArray();
  } // end method publish

  /** Encodes SUB, UNSUB, PRES_SUB or PRES_UNSUB. */
  static byte[] segmentCommand(String name, String segmentId) {
    switch (name) {
      case Constants.SUBSCRIBE_COMMAND,
          Constants.UNSUBSCRIBE_COMMAND,
          Constants.PRESENCE_SUBSCRIBE_COMMAND,
          Constants.PRESENCE_UNSUBSCRIBE_COMMAND -> {}
      default ->
          throw new CelerisException(
              ErrorCode.CONFIGURATION, "Invalid command. Unsupported command type.");
    }

    List<String> failures = new ArrayList<>();
    addFailure(failures, "segmentId", segmentId);

    if (!failures.isEmpty()) {
      throw Identifiers.configurationError("command", failures);
    }

    byte[] segment = segmentId.getBytes(StandardCharsets.UTF_8);
    long size = name.length() + 2 + bulkSize(segment.length);

    if (size > Constants.MAXIMUM_COMMAND_BYTES) {
      throw new CelerisException(ErrorCode.CONFIGURATION, COMMAND_TOO_LARGE);
    }

    ByteArrayOutputStream command = new ByteArrayOutputStream((int) size);
    appendText(command, "@" + name + "\n");
    appendBulk(command, segment);

    return command.toByteArray();
  } // end method segmentCommand

  /** Encodes PRES_LIST with the query's request id (QUERY-01). */
  static byte[] presenceList(String segmentId, int page, int perPage, String requestId) {
    List<String> failures = new ArrayList<>();
    addFailure(failures, "segmentId", segmentId);

    if (page < 1) {
      failures.add(Identifiers.failure("page", "Must be at least 1"));
    }

    if (perPage < 1) {
      failures.add(Identifiers.failure("perPage", "Must be at least 1"));
    }

    if (perPage > Constants.MAXIMUM_PRESENCE_PAGE_SIZE) {
      failures.add(Identifiers.failure("perPage", "Must be at most 100"));
    }

    addFailure(failures, "requestId", requestId);

    if (!failures.isEmpty()) {
      throw Identifiers.configurationError("command", failures);
    }

    byte[] segment = segmentId.getBytes(StandardCharsets.UTF_8);
    byte[] request = requestId.getBytes(StandardCharsets.UTF_8);
    String figures = ";" + page + "\n;" + perPage + "\n";
    long size =
        "@PRES_LIST\n".length()
            + bulkSize(segment.length)
            + figures.length()
            + bulkSize(request.length);

    if (size > Constants.MAXIMUM_COMMAND_BYTES) {
      throw new CelerisException(ErrorCode.CONFIGURATION, COMMAND_TOO_LARGE);
    }

    ByteArrayOutputStream command = new ByteArrayOutputStream((int) size);
    appendText(command, "@PRES_LIST\n");
    appendBulk(command, segment);
    appendText(command, figures);
    appendBulk(command, request);

    return command.toByteArray();
  } // end method presenceList

  private static void addFailure(List<String> failures, String field, String identifier) {
    String rule = Identifiers.identifierRule(identifier);

    if (rule != null) {
      failures.add(Identifiers.failure(field, rule));
    }
  } // end method addFailure

  /** The encoded size of a bulk string of the given length. */
  private static long bulkSize(int length) {
    return "$\n\n".length() + Integer.toString(length).length() + (long) length;
  } // end method bulkSize

  private static void appendText(ByteArrayOutputStream command, String text) {
    command.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
  } // end method appendText

  private static void appendBulk(ByteArrayOutputStream command, byte[] value) {
    appendText(command, "$" + value.length + "\n");
    command.writeBytes(value);
    command.write('\n');
  } // end method appendBulk
} // end class CommandEncoder

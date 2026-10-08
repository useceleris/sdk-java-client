package com.useceleris.client;

import java.util.List;

/** One decoded server message. Decoding never spans transport messages (DECODE-01). */
@SuppressWarnings("ArrayRecordComponent") // decoded once, routed once, never compared
sealed interface ServerMessage {
  /**
   * MSG: a delivery. A null message id leaves it undeliverable, since it cannot be deduplicated.
   */
  record Delivery(
      String tokenReference,
      String segmentId,
      @Nullable String messageId,
      long timestamp,
      byte[] payload)
      implements ServerMessage {} // end record Delivery

  /** SERVER_MSG: untagged prose, delivered at channel level only. */
  record Notice(long timestamp, byte[] payload) implements ServerMessage {}

  /** PRES_LIST_RESPONSE: one page answering the query with the same request id. */
  record PresenceListResponse(String requestId, PresencePage page) implements ServerMessage {}

  /** PRES_NOTIFY: one join or leave. */
  record PresenceNotify(PresenceEvent event) implements ServerMessage {}

  /** -Err: an error frame; a null sub type means the server named no command. */
  record ErrorFrame(
      String type, @Nullable String subType, byte[] message, @Nullable Object resource)
      implements ServerMessage {} // end record ErrorFrame

  /** An array of messages, routed entry by entry. */
  record Batch(List<ServerMessage> messages) implements ServerMessage {}

  /** A command this version does not know. Skipped, never surfaced (DECODE-01). */
  record Ignored() implements ServerMessage {}

  /** A transport message that could not be decoded, reported in order with everything else. */
  record DecodeFailure(CelerisException error) implements ServerMessage {}
} // end interface ServerMessage

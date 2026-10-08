package com.useceleris.client;

import java.time.Duration;

/**
 * Every fixed value the package uses, in one place. None of these is part of the public surface.
 */
final class Constants {
  private Constants() {}

  // Outgoing size (LIMIT-01). Size is only ever checked on commands the client sends. Received
  // messages are already fully buffered when they arrive, so checking them bounds no memory and
  // only discards data.
  //
  // The command bound is the server's transport ceiling: above it no plan can accept a command.
  // Each plan's own, smaller payload cap is enforced by the server and surfaces as a
  // MessageSizeLimitError server error.
  static final int MAXIMUM_COMMAND_BYTES = 2 * 1024 * 1024;

  // Equal to the command bound, so a maximum-size command always fits an empty writer and anything
  // behind it waits for room.
  static final int MAXIMUM_BUFFERED_BYTES = MAXIMUM_COMMAND_BYTES;

  // Bounds the writer, the publishes kept for a rate-limit resend, and, as publishQueueSize's
  // default, the publishes waiting for the writer.
  static final int MAXIMUM_PENDING_COMMANDS = 64;

  // Outbound recovery (RESEND-01). A rate limit is reported without saying which frame it dropped,
  // so whatever went out recently is resent.
  //
  // The server reports drops at most once a second, and a second more covers the round trip, so a
  // report concerns only commands sent within this window.
  static final Duration RATE_LIMIT_SUSPECT_WINDOW = Duration.ofSeconds(2);

  // Resending waits at least this long, past the per-second window the dropped frames were counted
  // in.
  static final Duration RATE_LIMIT_COOLDOWN = Duration.ofSeconds(1);

  // Bounds the extra load, and the extra usage, a rate limit can cause.
  static final int MAXIMUM_PUBLISH_RESENDS = 1;

  // After this many rate limits in a row the limit is treated as a used-up quota (per hour or per
  // month): recent publishes are no longer resent, and recent subscriptions wait for a quota probe.
  static final int MAXIMUM_CONSECUTIVE_RATE_LIMITS = 8;

  // A used-up quota refuses every frame, so the subscriptions it dropped are re-sent rarely rather
  // than abandoned: first after a minute, then doubling.
  static final Duration QUOTA_PROBE_FIRST_DELAY = Duration.ofMinutes(1);

  static final Duration QUOTA_PROBE_MAXIMUM_DELAY = Duration.ofHours(1);

  // Generated message ids share every receiver's deduplication window with ids from other
  // publishers, so they are random and long enough never to collide.
  static final int MESSAGE_ID_RANDOM_BYTES = 16;

  // Decoder bounds. These limit parsing work and recursion, not message size; no legitimate server
  // message approaches them.
  static final int MAXIMUM_FRAGMENTS = 4096;

  static final int MAXIMUM_DEPTH = 32;

  // "PRES_LIST_RESPONSE", the longest command the server sends.
  static final int MAXIMUM_COMMAND_NAME_BYTES = 18;

  static final int MAXIMUM_ERROR_NAME_BYTES = 64;

  // Integer64 (':') carries timestamps; it is also the range of bulk and array lengths.
  // "-9223372036854775808" is its widest value.
  static final int MAXIMUM_INTEGER64_LINE_BYTES = 20;

  // Integer32 (';') carries every other integer. "-2147483648" is its widest value.
  static final int MAXIMUM_INTEGER32_LINE_BYTES = 11;

  // The largest page a presence query may request.
  static final int MAXIMUM_PRESENCE_PAGE_SIZE = 100;

  // Connection defaults. Consumers do not configure where Celeris lives; overriding the base URL is
  // for local stacks and other deployments (ENDPOINT-01).
  static final String DEFAULT_BASE_URL = "wss://realtime.useceleris.com";

  static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(15);

  static final Duration DEFAULT_PRESENCE_QUERY_TIMEOUT = Duration.ofSeconds(10);

  // The bounds of a configured connect, reconnect or presence query timeout (CONFIG-01).
  static final Duration MINIMUM_TIMEOUT = Duration.ofMillis(1);

  static final Duration MAXIMUM_TIMEOUT = Duration.ofMinutes(15);

  static final String DEFAULT_SEGMENT_ID = "default";

  // The largest TCP port; a base URL naming a larger one is not a URL.
  static final int MAXIMUM_PORT = 65_535;

  // The longest channel reference the server accepts.
  static final int MAXIMUM_CHANNEL_REFERENCE_LENGTH = 255;

  // Client commands. A presence query error names PRES_LIST as its sub type (QUERY-01).
  static final String SUBSCRIBE_COMMAND = "SUB";

  static final String UNSUBSCRIBE_COMMAND = "UNSUB";

  static final String PRESENCE_SUBSCRIBE_COMMAND = "PRES_SUB";

  static final String PRESENCE_UNSUBSCRIBE_COMMAND = "PRES_UNSUB";

  static final String PRESENCE_LIST_COMMAND = "PRES_LIST";

  // Recovery (CONFIG-01). Failed reconnect attempts allowed per budget before the channel fails:
  // the default, and the most maximumReconnectAttempts accepts.
  static final int DEFAULT_MAXIMUM_RECONNECT_ATTEMPTS = 10;

  static final int MAXIMUM_RECONNECT_ATTEMPTS_CEILING = 100;

  static final Duration RETRY_BUDGET_RESET = Duration.ofMinutes(1);

  static final Duration RETRY_BASE_DELAY = Duration.ofMillis(500);

  static final Duration RETRY_DELAY_CAP = Duration.ofSeconds(30);

  // A rate limit already in hand disproves recovery only if commands flowed unrefused for longer
  // than the longest pause plus the report window; any sooner, it may be a late report of the
  // frames that just went out.
  static final Duration QUOTA_RETURN_CONFIRMATION = RETRY_DELAY_CAP.plus(RATE_LIMIT_SUSPECT_WINDOW);

  static final Duration REPLAY_OVERLAP = Duration.ofSeconds(5);

  // The server's largest replay lookback: an unsigned 32-bit millisecond count.
  static final Duration REPLAY_LOOKBACK_CAP = Duration.ofMillis(4_294_967_295L);

  static final Duration CLOSE_BUDGET = Duration.ofSeconds(5);

  // The default deduplicationWindowSize.
  static final int DEDUPLICATION_WINDOW_SIZE = 1024;

  // Heartbeat (HEARTBEAT-01). The server pings every 30 seconds and closes a connection it has
  // heard no Ping or Pong from for 60. The client pings after 15 seconds without hearing from the
  // server, which also keeps the server's clock fresh while a listener holds the read side, and
  // treats a ping unanswered for 15 seconds of reading time as a dead connection.
  static final Duration HEARTBEAT_IDLE = Duration.ofSeconds(15);

  static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(15);

  static final Duration HEARTBEAT_CHECK_INTERVAL = Duration.ofSeconds(5);

  // Post-delivery probe. The JDK's WebSocket client reads ahead while no message is requested, and
  // an end of stream (a TCP close without a close frame) that it reads in the moment between
  // delivering a message and the next request is lost: no onClose or onError follows, and only the
  // heartbeat would notice, about 30 seconds later. So when the next message is requested after a
  // delivery and the server then stays quiet for the probe delay, the client sends one ping; when
  // nothing arrives within the pong timeout, the connection is dead.
  static final Duration POST_DELIVERY_PROBE_DELAY = Duration.ofSeconds(1);

  static final Duration POST_DELIVERY_PONG_TIMEOUT = Duration.ofSeconds(5);
} // end class Constants

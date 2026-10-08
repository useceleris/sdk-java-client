package com.useceleris.client;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A handle for one segment of a channel. It holds only its id: subscriptions, listeners and the
 * connection belong to the channel, so any number of handles for one segment share them. Obtain one
 * with {@link Channel#segment(String)} or {@link Channel#defaultSegment()}.
 */
public final class Segment {
  private final Channel channel;
  private final String segmentId;

  Segment(Channel channel, String segmentId) {
    this.channel = channel;
    this.segmentId = segmentId;
  } // end constructor Segment

  /**
   * Returns the segment's id.
   *
   * @return the id
   */
  public String segmentId() {
    return segmentId;
  } // end method segmentId

  /**
   * Registers interest in the segment's messages. The first interest subscribes it, and it stays
   * subscribed until every interest is cancelled; subscriptions are restored after every reconnect.
   * The default segment is joined automatically and never subscribed or unsubscribed. No
   * acknowledgement exists: a denial arrives later through {@link ChannelEventHandler#onError}.
   *
   * @return the subscription
   * @throws CelerisException with {@link ErrorCode#NOT_CONNECTED} once the channel is closing or
   *     closed
   */
  public Subscription subscribe() {
    return channel.addInterest(InterestKind.MESSAGE, segmentId);
  } // end method subscribe

  /**
   * Registers interest in the segment's presence joins and leaves. Watching presence is not
   * membership: it neither joins nor holds the segment for messages (SEG-01).
   *
   * @return the subscription
   * @throws CelerisException with {@link ErrorCode#NOT_CONNECTED} once the channel is closing or
   *     closed
   */
  public Subscription subscribePresence() {
    return channel.addInterest(InterestKind.PRESENCE, segmentId);
  } // end method subscribePresence

  /**
   * Registers a listener for the segment's deliveries. Each listener receives its own copy of the
   * payload. Each message id is delivered once within a bounded window (REV-01).
   *
   * @param listener receives each delivery
   * @return the registration
   */
  public Registration onMessage(MessageListener listener) {
    return channel.addMessageListener(segmentId, listener);
  } // end method onMessage

  /**
   * Registers a listener for joins and leaves. Events arrive only while a presence subscription is
   * held: the server sends them to presence subscribers alone (PRES-01).
   *
   * @param listener receives each presence event
   * @return the registration
   */
  public Registration onPresence(Consumer<PresenceEvent> listener) {
    return channel.addPresenceListener(segmentId, listener);
  } // end method onPresence

  /**
   * Publishes the payload with a generated message id. The future completes once the socket
   * accepted the bytes. That is not receipt, delivery or durability: the protocol has no
   * acknowledgements. The payload is copied before this method returns.
   *
   * <p>While the channel is reconnecting, the publish waits in the queue and is written after the
   * restored subscriptions once a new connection is up. A publish the socket had not started
   * writing when the connection dropped waits the same way. Waiting publishes fail with the error
   * {@link ChannelEventHandler#onError} reports when recovery fails, and with {@link
   * ErrorCode#CANCELLED} on close.
   *
   * <p>It fails with {@link ErrorCode#NOT_CONNECTED} before the first connect completes and once
   * the channel is failed, closing or closed, with {@link ErrorCode#BACKPRESSURE} when the publish
   * queue ({@code publishQueueSize} publishes, 64 by default) is already full, and with {@link
   * ErrorCode#DELIVERY_UNKNOWN} when the socket failed mid-write; such a publish is never resent.
   * Cancelling the future withdraws a publish the socket has not started writing. A publish over
   * the plan's payload cap still succeeds here and is refused afterwards with a
   * MessageSizeLimitError through {@link ChannelEventHandler#onError}.
   *
   * @param payload the bytes to publish, possibly empty
   * @return a future completing once the socket accepted the bytes
   */
  public CompletableFuture<Void> publish(byte[] payload) {
    Objects.requireNonNull(payload, "payload");

    return channel.publish(segmentId, payload, MessageIds.generate());
  } // end method publish

  /**
   * Publishes the payload with your own message id. Receivers drop a repeated id within their
   * deduplication window, so reuse an id only for the same message.
   *
   * @param payload the bytes to publish, possibly empty
   * @param messageId the message id: non-empty, without CR, LF or unpaired surrogates
   * @return a future completing once the socket accepted the bytes
   */
  public CompletableFuture<Void> publish(byte[] payload, String messageId) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(messageId, "messageId");

    return channel.publish(segmentId, payload, messageId);
  } // end method publish

  /**
   * Returns one page of the segment's presence, up to 100 connections per page. One query may be in
   * flight per channel: another fails with {@link ErrorCode#OPERATION_IN_PROGRESS}, and a query
   * fails with {@link ErrorCode#TIMEOUT} after the presence query timeout without affecting the
   * connection. Cancelling the future frees the query slot.
   *
   * <p>The reply is routed only once listeners return, so a listener that blocks waiting for the
   * result always times out: compose on the future instead.
   *
   * @param page the page number, at least 1
   * @param perPage the page size, 1 to 100
   * @return a future completing with the page
   */
  public CompletableFuture<PresencePage> presenceList(int page, int perPage) {
    return channel.presenceList(segmentId, page, perPage);
  } // end method presenceList
} // end class Segment

package com.useceleris.client;

/**
 * One registered interest in a segment's messages or presence. Cancel it, or close it in a
 * try-with-resources block, when done.
 */
public final class Subscription implements AutoCloseable {
  private final Channel channel;
  private final InterestKind kind;
  private final String segmentId;

  @GuardedBy("channel.lock")
  private boolean cancelled;

  Subscription(Channel channel, InterestKind kind, String segmentId) {
    this.channel = channel;
    this.kind = kind;
    this.segmentId = segmentId;
  } // end constructor Subscription

  /**
   * Releases the interest at once. The segment is unsubscribed when its last message and presence
   * interests are both released; cancelling presence never leaves the segment. Calling it again
   * does nothing.
   */
  public void cancel() {
    channel.releaseInterest(this);
  } // end method cancel

  /** Same as {@link #cancel()}. */
  @Override
  public void close() {
    cancel();
  } // end method close

  InterestKind kind() {
    return kind;
  } // end method kind

  String segmentId() {
    return segmentId;
  } // end method segmentId

  /**
   * Marks the subscription cancelled and reports whether it was live. The caller holds the lock.
   */
  boolean markCancelled() {
    if (cancelled) {
      return false;
    }

    cancelled = true;

    return true;
  } // end method markCancelled
} // end class Subscription

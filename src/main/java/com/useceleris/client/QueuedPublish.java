package com.useceleris.client;

/**
 * One publish: queued, handed to a connection's writer, or settled. Guarded by the channel's lock.
 */
final class QueuedPublish {
  final String segmentId;
  final byte[] data;

  /** Its place in the order commands were queued in. */
  final long sequence;

  final OperationFuture<Void> future;

  int resends;

  /**
   * The connection it was last handed to, or null while it has never been handed over. While the
   * publish is unsettled, that connection's writer still holds a frame of it and settles it: every
   * path that detaches a connection settles what its writer holds.
   */
  @Nullable Connection connection;

  QueuedPublish(String segmentId, byte[] data, long sequence, OperationFuture<Void> future) {
    this.segmentId = segmentId;
    this.data = data;
    this.sequence = sequence;
    this.future = future;
  } // end constructor QueuedPublish

  boolean settled() {
    return future.isRecorded();
  } // end method settled

  void settle(@Nullable Throwable failure) {
    future.settle(null, failure);
  } // end method settle
} // end class QueuedPublish

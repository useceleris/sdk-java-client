package com.useceleris.client;

/** Receives a segment's deliveries: the payload first, then its metadata (MSG-01). */
@FunctionalInterface
public interface MessageListener {
  /**
   * Handles one delivery.
   *
   * @param payload the message bytes; this listener's own copy
   * @param metadata the sender, segment, message id and timestamp
   */
  void onMessage(byte[] payload, MessageMetadata metadata);
} // end interface MessageListener

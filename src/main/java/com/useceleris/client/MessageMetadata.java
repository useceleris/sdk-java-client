package com.useceleris.client;

/**
 * Describes a delivery, beside its payload (MSG-01).
 *
 * @param tokenReference the sender's token reference
 * @param segmentId the segment the message was published to
 * @param messageId the publisher's message id, or one the server assigned; always present
 * @param timestamp Unix milliseconds; convert with {@link java.time.Instant#ofEpochMilli(long)}
 */
public record MessageMetadata(
    String tokenReference,
    String segmentId,
    String messageId,
    long timestamp) {} // end record MessageMetadata

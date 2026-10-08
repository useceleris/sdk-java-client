package com.useceleris.client;

/**
 * One connection joining or leaving one segment (PRES-01).
 *
 * @param segmentId the segment joined or left
 * @param tokenReference the connection's token reference
 * @param connectionId the server's id for the connection
 * @param joined true for a join, false for a leave
 * @param timestamp Unix milliseconds
 */
public record PresenceEvent(
    String segmentId,
    String tokenReference,
    String connectionId,
    boolean joined,
    long timestamp) {} // end record PresenceEvent

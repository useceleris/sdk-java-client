package com.useceleris.client;

/**
 * Reports a reconnect. Replay is a bounded window, not a durable log, so gaps and duplicates are
 * always possible after one.
 *
 * @param retryIndex the zero-based retry that succeeded, counted within the current retry budget,
 *     which carries over when a connection drops within 60 seconds of connecting
 * @param possibleGaps always true
 * @param possibleDuplicates always true
 */
public record RecoveryEvent(int retryIndex, boolean possibleGaps, boolean possibleDuplicates) {}

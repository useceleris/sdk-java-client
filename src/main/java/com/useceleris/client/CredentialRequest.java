package com.useceleris.client;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Describes the attempt credentials are wanted for. Credentials are requested fresh for every
 * attempt, reconnects included (D-001).
 *
 * @param channelReference the channel being connected
 * @param reconnect false for the attempt {@link Channel#connect()} starts, true for every automatic
 *     reconnect
 * @param disconnectedAt when the connection dropped; present only on a reconnect
 * @param replayLookback how much history to replay: the outage so far plus five seconds of overlap;
 *     present only on a reconnect. The signing server decides whether to grant it.
 */
public record CredentialRequest(
    String channelReference,
    boolean reconnect,
    Optional<Instant> disconnectedAt,
    Optional<Duration> replayLookback) {} // end record CredentialRequest

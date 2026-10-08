package com.useceleris.client;

/**
 * One connection present in a segment. A token reference can hold several connections.
 *
 * @param tokenReference the connection's token reference
 * @param connectionId the server's id for the connection
 * @param timestamp Unix milliseconds
 */
public record PresenceConnection(String tokenReference, String connectionId, long timestamp) {}

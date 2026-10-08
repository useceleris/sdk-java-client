package com.useceleris.client;

import java.util.List;

/**
 * One page of a segment's presence, every figure exactly as the server sent it. Past the last page
 * {@code from} exceeds {@code to} and {@code connections} is empty; that is not an error.
 *
 * @param segmentId the segment queried
 * @param total how many connections are present
 * @param perPage the page size the server applied
 * @param currentPage the page number
 * @param from the 1-based position of the first connection on this page
 * @param to the position of the last connection on this page
 * @param connections the connections on this page
 */
public record PresencePage(
    String segmentId,
    int total,
    int perPage,
    int currentPage,
    int from,
    int to,
    List<PresenceConnection> connections) {
  /**
   * Keeps an unmodifiable copy of the connections.
   *
   * @param segmentId the segment queried
   * @param total how many connections are present
   * @param perPage the page size the server applied
   * @param currentPage the page number
   * @param from the 1-based position of the first connection on this page
   * @param to the position of the last connection on this page
   * @param connections the connections on this page
   */
  public PresencePage {
    connections = List.copyOf(connections);
  } // end constructor PresencePage
} // end record PresencePage

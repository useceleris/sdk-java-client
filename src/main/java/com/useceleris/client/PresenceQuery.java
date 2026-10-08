package com.useceleris.client;

/** The presence query in flight, at most one per channel (QUERY-01). */
final class PresenceQuery {
  final String requestId;
  final OperationFuture<PresencePage> future;
  Timers.@Nullable Cancellable timer;

  PresenceQuery(String requestId, OperationFuture<PresencePage> future) {
    this.requestId = requestId;
    this.future = future;
  } // end constructor PresenceQuery
} // end class PresenceQuery

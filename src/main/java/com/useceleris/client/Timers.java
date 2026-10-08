package com.useceleris.client;

import java.time.Duration;
import java.time.Instant;

/**
 * The one time seam: scheduling and clocks. Scheduled tasks run on the client's worker threads,
 * never on the timer's own.
 */
interface Timers {
  /** Runs task once after delay, unless cancelled first. */
  Cancellable schedule(Duration delay, Runnable task);

  /** A monotonic reading, for measuring durations. */
  long monotonicNanos();

  /** The wall clock, for reporting when something happened. */
  Instant now();

  /** A scheduled task that can be withdrawn. */
  interface Cancellable {
    /** Withdraws the task if it has not started; does nothing otherwise. */
    void cancel();
  } // end interface Cancellable

  /** Cancels the timer, if there is one. */
  static void cancel(@Nullable Cancellable timer) {
    if (timer != null) {
      timer.cancel();
    }
  } // end method cancel
} // end interface Timers

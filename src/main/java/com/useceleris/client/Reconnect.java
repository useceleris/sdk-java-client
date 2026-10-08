package com.useceleris.client;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/** Recovery arithmetic (REC-01). */
final class Reconnect {
  private Reconnect() {}

  /** Full jitter: random × min(30 s, 500 ms × 2^retryIndex). */
  static Duration retryDelay(int retryIndex, DoubleSupplier random) {
    long ceiling = Constants.RETRY_DELAY_CAP.toMillis();

    if (retryIndex < 16) {
      ceiling = Math.min(ceiling, Constants.RETRY_BASE_DELAY.toMillis() << retryIndex);
    }

    return Duration.ofNanos((long) (random.getAsDouble() * ceiling * 1_000_000));
  } // end method retryDelay

  /** The outage so far, rounded up to whole milliseconds, plus five seconds, capped. */
  static Duration replayLookback(Duration outage) {
    long milliseconds = outage.toMillis();

    if (Duration.ofMillis(milliseconds).compareTo(outage) < 0) {
      milliseconds++;
    }

    Duration lookback = Duration.ofMillis(milliseconds).plus(Constants.REPLAY_OVERLAP);

    return lookback.compareTo(Constants.REPLAY_LOOKBACK_CAP) > 0
        ? Constants.REPLAY_LOOKBACK_CAP
        : lookback;
  } // end method replayLookback
} // end class Reconnect

package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconnectTest {
  private static final Duration CAP = Duration.ofMillis(4_294_967_295L);

  @Test
  void retryCeilingDoublesFromHalfASecondToThirtySeconds() {
    List<Long> ceilings = List.of(500L, 1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L);

    for (int retryIndex = 0; retryIndex < ceilings.size(); retryIndex++) {
      assertEquals(
          Duration.ofMillis(ceilings.get(retryIndex)),
          Reconnect.retryDelay(retryIndex, () -> 1.0),
          "retry " + retryIndex);
    }
  } // end method retryCeilingDoublesFromHalfASecondToThirtySeconds

  @Test
  void retryCeilingStaysCappedForLargeRetryIndexes() {
    for (int retryIndex : new int[] {9, 15, 16, 31, 32, 62, 63, 64, 1000, Integer.MAX_VALUE}) {
      assertEquals(
          Duration.ofSeconds(30),
          Reconnect.retryDelay(retryIndex, () -> 1.0),
          "retry " + retryIndex);
    }
  } // end method retryCeilingStaysCappedForLargeRetryIndexes

  @Test
  void retryDelayIsTheRandomFractionOfTheCeiling() {
    // The reference's sequence with random 0.5: half of each ceiling.
    List<Long> delays =
        List.of(250L, 500L, 1000L, 2000L, 4000L, 8000L, 15000L, 15000L, 15000L, 15000L);

    for (int retryIndex = 0; retryIndex < delays.size(); retryIndex++) {
      assertEquals(
          Duration.ofMillis(delays.get(retryIndex)), Reconnect.retryDelay(retryIndex, () -> 0.5));
    }

    assertEquals(Duration.ofNanos(125_000_000L), Reconnect.retryDelay(0, () -> 0.25));
    assertEquals(Duration.ofNanos(750_000_000L), Reconnect.retryDelay(1, () -> 0.75));
  } // end method retryDelayIsTheRandomFractionOfTheCeiling

  @Test
  void fullJitterStaysWithinZeroAndTheCeiling() {
    double justBelowOne = Math.nextDown(1.0);

    for (int retryIndex = 0; retryIndex < 40; retryIndex++) {
      Duration ceiling = Reconnect.retryDelay(retryIndex, () -> 1.0);

      assertEquals(Duration.ZERO, Reconnect.retryDelay(retryIndex, () -> 0.0));
      assertTrue(Reconnect.retryDelay(retryIndex, () -> justBelowOne).compareTo(ceiling) < 0);
      assertTrue(
          Reconnect.retryDelay(retryIndex, () -> Double.MIN_VALUE).compareTo(Duration.ZERO) >= 0);
    }
  } // end method fullJitterStaysWithinZeroAndTheCeiling

  @Test
  void replayLookbackIsTheOutagePlusFiveSeconds() {
    assertEquals(Duration.ofMillis(5000), Reconnect.replayLookback(Duration.ZERO));
    assertEquals(Duration.ofMillis(5500), Reconnect.replayLookback(Duration.ofMillis(500)));
    assertEquals(Duration.ofMillis(6500), Reconnect.replayLookback(Duration.ofMillis(1500)));
    assertEquals(Duration.ofMillis(8500), Reconnect.replayLookback(Duration.ofMillis(3500)));
    assertEquals(Duration.ofMillis(65_000), Reconnect.replayLookback(Duration.ofMinutes(1)));
  } // end method replayLookbackIsTheOutagePlusFiveSeconds

  @Test
  void replayLookbackRoundsTheOutageUpToWholeMilliseconds() {
    assertEquals(Duration.ofMillis(5001), Reconnect.replayLookback(Duration.ofNanos(1)));
    assertEquals(Duration.ofMillis(5001), Reconnect.replayLookback(Duration.ofNanos(999_999)));
    assertEquals(Duration.ofMillis(5001), Reconnect.replayLookback(Duration.ofNanos(1_000_000)));
    assertEquals(Duration.ofMillis(5002), Reconnect.replayLookback(Duration.ofNanos(1_000_001)));
    assertEquals(
        Duration.ofMillis(6235), Reconnect.replayLookback(Duration.ofNanos(1_234_000_001L)));
  } // end method replayLookbackRoundsTheOutageUpToWholeMilliseconds

  @Test
  void replayLookbackIsCappedAtTheServersUnsigned32BitMaximum() {
    Duration lastUncapped = CAP.minusSeconds(5);

    assertEquals(CAP, Reconnect.replayLookback(lastUncapped));
    assertEquals(CAP.minusMillis(1), Reconnect.replayLookback(lastUncapped.minusMillis(1)));
    // One nanosecond more rounds up to a whole millisecond over the cap.
    assertEquals(CAP, Reconnect.replayLookback(lastUncapped.plusNanos(1)));
    assertEquals(CAP, Reconnect.replayLookback(CAP));
    assertEquals(CAP, Reconnect.replayLookback(Duration.ofDays(365L * 200)));
    assertEquals(CAP, Reconnect.replayLookback(Duration.ofNanos(Long.MAX_VALUE)));
  } // end method replayLookbackIsCappedAtTheServersUnsigned32BitMaximum
} // end class ReconnectTest

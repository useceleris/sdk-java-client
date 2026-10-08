package com.useceleris.client;

import static com.useceleris.client.TestRuntime.assertSucceeded;
import static com.useceleris.client.TestRuntime.messageFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * The ping a second after a delivery that exposes a close the JDK lost (see {@code
 * Constants.POST_DELIVERY_PROBE_DELAY}). Every test delivers its message ten seconds after
 * connecting, well clear of the heartbeat's first ping at fifteen.
 */
class PostDeliveryProbeTest {
  /** A connected channel that has just delivered one message, with the jitter at its floor. */
  private record Delivered(TestRuntime runtime, Channel channel, FakeWebSocket socket) {}

  private static Delivered delivered() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    runtime.advance(Duration.ofSeconds(10));
    runtime.receive(socket, messageFrame("default", "m-1", "x"));

    return new Delivered(runtime, channel, socket);
  } // end method delivered

  @Test
  void aDeliveryFollowedByOneSecondOfSilenceDrawsOnePing() {
    Delivered delivered = delivered();

    delivered.runtime().advance(Duration.ofMillis(999));
    assertEquals(0, delivered.socket().pingCount());

    delivered.runtime().advance(Duration.ofMillis(1));
    assertEquals(1, delivered.socket().pingCount());
  } // end method aDeliveryFollowedByOneSecondOfSilenceDrawsOnePing

  @Test
  void anInboundFrameWithinTheSecondCancelsTheProbe() {
    Delivered delivered = delivered();
    TestRuntime runtime = delivered.runtime();

    runtime.advance(Duration.ofMillis(500));
    delivered.socket().receivePing();
    runtime.run();
    runtime.advance(Duration.ofSeconds(4));

    assertEquals(0, delivered.socket().pingCount());
    assertEquals(ChannelState.CONNECTED, delivered.channel().state());
    assertFalse(delivered.socket().isAborted());
  } // end method anInboundFrameWithinTheSecondCancelsTheProbe

  @Test
  void noSecondProbeWithoutAnotherDelivery() {
    Delivered delivered = delivered();
    TestRuntime runtime = delivered.runtime();

    runtime.advance(Duration.ofSeconds(1));
    delivered.socket().receivePong();
    runtime.run();
    runtime.advance(Duration.ofMillis(3900));

    // Only the probe: the heartbeat's next ping is due fifteen seconds after the pong.
    assertEquals(1, delivered.socket().pingCount());
    assertEquals(ChannelState.CONNECTED, delivered.channel().state());

    // Another delivery arms another probe.
    runtime.receive(delivered.socket(), messageFrame("default", "m-2", "y"));
    runtime.advance(Duration.ofSeconds(1));
    assertEquals(2, delivered.socket().pingCount());
  } // end method noSecondProbeWithoutAnotherDelivery

  @Test
  void noAnswerWithinFiveSecondsIsADeadConnection() {
    Delivered delivered = delivered();
    TestRuntime runtime = delivered.runtime();
    Instant lastHeard = runtime.timers.now();
    runtime.blockDials = true;
    runtime.advance(Duration.ofSeconds(1));
    assertEquals(1, delivered.socket().pingCount());

    runtime.advance(Duration.ofSeconds(5).minusMillis(1));
    assertEquals(ChannelState.CONNECTED, delivered.channel().state());
    assertFalse(delivered.socket().isAborted());

    runtime.advance(Duration.ofMillis(1));
    assertEquals(ChannelState.RECONNECTING, delivered.channel().state());
    assertTrue(delivered.socket().isAborted());

    // The outage started when the server was last heard from: the delivery.
    CredentialRequest reconnect =
        runtime.credentialRequests.get(runtime.credentialRequests.size() - 1);
    assertTrue(reconnect.reconnect());
    assertEquals(lastHeard, reconnect.disconnectedAt().orElseThrow());
  } // end method noAnswerWithinFiveSecondsIsADeadConnection

  @Test
  void aPongWithinFiveSecondsKeepsTheConnection() {
    Delivered delivered = delivered();
    TestRuntime runtime = delivered.runtime();
    runtime.advance(Duration.ofSeconds(1));

    runtime.advance(Duration.ofMillis(4999));
    delivered.socket().receivePong();
    runtime.run();
    runtime.advance(Duration.ofSeconds(10));

    assertEquals(ChannelState.CONNECTED, delivered.channel().state());
    assertFalse(delivered.socket().isAborted());
    assertEquals(1, runtime.sockets.size());
  } // end method aPongWithinFiveSecondsKeepsTheConnection

  @Test
  void aProbePingThatCannotBeWrittenReconnectsAtOnce() {
    Delivered delivered = delivered();
    TestRuntime runtime = delivered.runtime();
    runtime.blockDials = true;
    delivered.socket().failPings();

    runtime.advance(Duration.ofSeconds(1));

    assertEquals(1, delivered.socket().pingCount());
    assertEquals(ChannelState.RECONNECTING, delivered.channel().state());
    assertTrue(delivered.socket().isAborted());
  } // end method aProbePingThatCannotBeWrittenReconnectsAtOnce

  @Test
  void closeCancelsTheProbeTimers() {
    for (Duration beforeClose : new Duration[] {Duration.ofMillis(500), Duration.ofSeconds(2)}) {
      Delivered delivered = delivered();
      TestRuntime runtime = delivered.runtime();
      runtime.advance(beforeClose);

      CompletableFuture<Void> closed = delivered.channel().closeAsync();
      runtime.run();

      assertSucceeded(closed);
      assertEquals(ChannelState.CLOSED, delivered.channel().state());
      assertEquals(0, runtime.timers.pendingCount(), "timers left after " + beforeClose);
    }
  } // end method closeCancelsTheProbeTimers
} // end class PostDeliveryProbeTest

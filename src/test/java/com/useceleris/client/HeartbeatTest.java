package com.useceleris.client;

import static com.useceleris.client.TestRuntime.assertCode;
import static com.useceleris.client.TestRuntime.assertSucceeded;
import static com.useceleris.client.TestRuntime.bytes;
import static com.useceleris.client.TestRuntime.messageFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** HEARTBEAT-01: client pings keep the server's clock fresh and expose a dead path. */
class HeartbeatTest {
  @Test
  void anIdleConnectionPingsAfterFifteenSecondsOfSilence() {
    TestRuntime runtime = new TestRuntime();
    runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();

    runtime.advance(Duration.ofSeconds(14));
    assertEquals(0, socket.pingCount());

    runtime.advance(Duration.ofSeconds(1));
    assertEquals(1, socket.pingCount());
  } // end method anIdleConnectionPingsAfterFifteenSecondsOfSilence

  @Test
  void anythingHeardFromTheServerDefersThePing() {
    TestRuntime runtime = new TestRuntime();
    runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();

    runtime.advance(Duration.ofSeconds(10));
    socket.receivePing();
    runtime.run();
    runtime.advance(Duration.ofSeconds(14));
    assertEquals(0, socket.pingCount());

    runtime.advance(Duration.ofSeconds(1));
    assertEquals(1, socket.pingCount());
  } // end method anythingHeardFromTheServerDefersThePing

  @Test
  void aPingUnansweredForFifteenSecondsOfReadingIsADeadConnection() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();

    runtime.advance(Duration.ofSeconds(15));
    assertEquals(1, socket.pingCount());
    runtime.advance(Duration.ofSeconds(10));
    assertEquals(ChannelState.CONNECTED, channel.state());

    runtime.advance(Duration.ofSeconds(5));
    assertTrue(socket.isAborted());
    assertTrue(
        channel.state() == ChannelState.RECONNECTING || channel.state() == ChannelState.CONNECTED);
    assertEquals(2, runtime.sockets.size());
  } // end method aPingUnansweredForFifteenSecondsOfReadingIsADeadConnection

  @Test
  void anAnswerClearsTheUnansweredPing() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();

    runtime.advance(Duration.ofSeconds(15));
    runtime.advance(Duration.ofSeconds(10));
    socket.receive(TestRuntime.messageFrame("default", "m-1", "x"));
    runtime.run();
    answerThePostDeliveryProbe(runtime, socket);
    runtime.advance(Duration.ofSeconds(9));

    assertFalse(socket.isAborted());
    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method anAnswerClearsTheUnansweredPing

  @Test
  void aListenerHoldingTheReadSideNeverMakesTheConnectionLookDead() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    boolean[] held = new boolean[1];
    channel
        .defaultSegment()
        .onMessage(
            (payload, metadata) -> {
              if (!held[0]) {
                held[0] = true;

                // Ninety seconds pass while this listener runs: nothing more is read.
                runtime.advance(Duration.ofSeconds(90));
              }
            });

    socket.receive(TestRuntime.messageFrame("default", "m-1", "x"));
    runtime.run();

    assertTrue(held[0]);
    assertFalse(socket.isAborted());
    assertEquals(ChannelState.CONNECTED, channel.state());
    // It kept pinging, which keeps the server's 60-second heartbeat satisfied.
    assertTrue(socket.pingCount() >= 5, "pings " + socket.pingCount());
  } // end method aListenerHoldingTheReadSideNeverMakesTheConnectionLookDead

  @Test
  void readingTimeAfterTheListenerReturnsStillDetectsADeadPath() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    boolean[] held = new boolean[1];
    channel
        .defaultSegment()
        .onMessage(
            (payload, metadata) -> {
              if (!held[0]) {
                held[0] = true;
                runtime.advance(Duration.ofSeconds(60));
              }
            });

    socket.receive(TestRuntime.messageFrame("default", "m-1", "x"));
    runtime.run();
    assertFalse(socket.isAborted());

    // Reading again, the outstanding ping goes unanswered for fifteen more seconds.
    runtime.advance(Duration.ofSeconds(20));
    assertTrue(socket.isAborted());
  } // end method readingTimeAfterTheListenerReturnsStillDetectsADeadPath

  @Test
  void closingStopsTheHeartbeat() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();

    channel.closeAsync();
    runtime.run();
    runtime.advance(Duration.ofMinutes(2));

    assertEquals(0, socket.pingCount());
    assertEquals(ChannelState.CLOSED, channel.state());
    assertEquals(0, runtime.timers.pendingCount());
  } // end method closingStopsTheHeartbeat

  @Test
  void aHeartbeatOutageStartsWhenTheServerWasLastHeard() {
    TestRuntime runtime = new TestRuntime();
    runtime.answerPings = false;
    Channel channel = runtime.connectedChannel();
    java.time.Instant connectedAt = runtime.timers.now();

    // Silence from the connection on: a ping at 15 s goes unanswered until 30 s.
    runtime.advance(Duration.ofSeconds(30));

    CredentialRequest reconnect =
        runtime.credentialRequests.get(runtime.credentialRequests.size() - 1);
    assertTrue(reconnect.reconnect());
    assertEquals(connectedAt, reconnect.disconnectedAt().orElseThrow());
    // The silent thirty seconds plus five of overlap, so replay covers what was missed.
    assertEquals(Duration.ofSeconds(35), reconnect.replayLookback().orElseThrow());
    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method aHeartbeatOutageStartsWhenTheServerWasLastHeard

  @Test
  void aTextFrameStillCountsAsHearingFromTheServer() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();

    runtime.advance(Duration.ofSeconds(25));
    socket.receiveText("text");
    runtime.run();
    answerThePostDeliveryProbe(runtime, socket);
    runtime.advance(Duration.ofSeconds(9));

    assertFalse(socket.isAborted());
    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method aTextFrameStillCountsAsHearingFromTheServer

  // The JDK counts a server ping against the demand, so reading carries on after answering it.
  @Test
  void messagesKeepArrivingAfterAServerPing() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<String> delivered = new TestRuntime.Recorder<>();
    channel
        .defaultSegment()
        .onMessage((payload, metadata) -> delivered.accept(metadata.messageId()));

    socket.receivePing();
    socket.receive(messageFrame("default", "m-1", "x"));
    runtime.run();

    assertEquals(List.of("m-1"), delivered.all());
  } // end method messagesKeepArrivingAfterAServerPing

  @Test
  void silenceDrawsOnePingEveryFifteenSeconds() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    channel
        .defaultSegment()
        .onMessage((payload, metadata) -> runtime.advance(Duration.ofSeconds(60)));

    socket.receive(messageFrame("default", "m-1", "x"));
    runtime.run();

    // Sixty silent seconds while the listener holds the read side: pings at 15, 30, 45 and 60.
    assertEquals(4, socket.pingCount());
  } // end method silenceDrawsOnePingEveryFifteenSeconds

  @Test
  void anErrorListenerHoldingTheReadSideNeverMakesTheConnectionLookDead() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    boolean[] held = new boolean[1];
    channel
        .events()
        .onError(
            error -> {
              if (!held[0]) {
                held[0] = true;
                runtime.advance(Duration.ofSeconds(90));
              }
            });

    socket.receiveText("text");
    runtime.run();

    assertTrue(held[0]);
    assertFalse(socket.isAborted());
    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method anErrorListenerHoldingTheReadSideNeverMakesTheConnectionLookDead

  // After a listener held the read side, reading resumes, and the post-delivery probe finds a
  // silent path well before the heartbeat would.
  @Test
  void aSilentPathAfterAListenerReturnsIsFoundByThePostDeliveryProbe() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    boolean[] held = new boolean[1];
    channel
        .defaultSegment()
        .onMessage(
            (payload, metadata) -> {
              if (!held[0]) {
                held[0] = true;
                runtime.advance(Duration.ofSeconds(80));
              }
            });

    socket.receive(messageFrame("default", "m-1", "x"));
    runtime.run();

    // Five heartbeat pings went out while the listener held; reading resumed at 80 s, and the
    // probe pinged at 81 s.
    runtime.advance(Duration.ofSeconds(1));
    assertEquals(6, socket.pingCount());
    runtime.advance(Duration.ofSeconds(5).minusMillis(1));
    assertFalse(socket.isAborted());

    runtime.advance(Duration.ofMillis(1));
    assertTrue(socket.isAborted());
  } // end method aSilentPathAfterAListenerReturnsIsFoundByThePostDeliveryProbe

  @Test
  void heartbeatsNeverLeaveTheLockHeld() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();

    runtime.advance(Duration.ofSeconds(15));

    assertEquals(1, runtime.socket().pingCount());
    assertFalse(channel.lock.isLocked());
  } // end method heartbeatsNeverLeaveTheLockHeld

  @Test
  void aDeadPathIsReportedAtOnce() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    runtime.random = 1;
    TestRuntime.Recorder<ChannelState> states = new TestRuntime.Recorder<>();
    channel.events().onStateChange(states);

    runtime.advance(Duration.ofSeconds(30));

    // The first retry is still 500 ms away.
    assertEquals(1, runtime.sockets.size());
    assertEquals(List.of(ChannelState.RECONNECTING), states.all());
  } // end method aDeadPathIsReportedAtOnce

  // A closing socket keeps its heartbeat: found dead, it is abandoned at once rather than at the
  // end of the close budget, and what it never wrote fails as cancelled by the close.
  @Test
  void aClosingSocketFoundDeadEndsTheCloseEarly() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    runtime.advance(Duration.ofSeconds(27));
    assertEquals(1, socket.pingCount());
    socket.holdWrites();
    socket.ignoreClose();
    CompletableFuture<Void> writing = channel.defaultSegment().publish(bytes("x"), "m-1");
    CompletableFuture<Void> waiting = channel.defaultSegment().publish(bytes("y"), "m-2");
    runtime.run();

    CompletableFuture<Void> closed = channel.closeAsync();
    runtime.advance(Duration.ofSeconds(3));

    assertSucceeded(closed);
    assertTrue(socket.isAborted());
    assertCode(writing, ErrorCode.DELIVERY_UNKNOWN);
    assertCode(waiting, ErrorCode.CANCELLED);
    assertEquals(ChannelState.CLOSED, channel.state());
  } // end method aClosingSocketFoundDeadEndsTheCloseEarly

  /** Answers the ping the post-delivery probe sends a second after a delivery. */
  private static void answerThePostDeliveryProbe(TestRuntime runtime, FakeWebSocket socket) {
    runtime.advance(Duration.ofSeconds(1));
    socket.receivePong();
    runtime.run();
  } // end method answerThePostDeliveryProbe
} // end class HeartbeatTest

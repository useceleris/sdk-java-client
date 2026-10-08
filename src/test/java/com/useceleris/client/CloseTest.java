package com.useceleris.client;

import static com.useceleris.client.TestRuntime.assertCode;
import static com.useceleris.client.TestRuntime.assertSucceeded;
import static com.useceleris.client.TestRuntime.bytes;
import static com.useceleris.client.TestRuntime.publishFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CloseTest {
  // ---- Idempotence ----

  @Test
  void closeIsIdempotentAndSafeFromManyThreads() throws InterruptedException {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.channel();
    TestRuntime.Recorder<ChannelState> states = new TestRuntime.Recorder<>();
    channel.events().onStateChange(states);
    List<CompletableFuture<Void>> closes = new ArrayList<>();
    CountDownLatch start = new CountDownLatch(1);
    List<Thread> closers = new ArrayList<>();

    for (int index = 0; index < 5; index++) {
      Thread closer =
          new Thread(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();

                  return;
                }

                CompletableFuture<Void> closed = channel.closeAsync();

                synchronized (closes) {
                  closes.add(closed);
                }
              });

      closer.start();
      closers.add(closer);
    }

    start.countDown();

    for (Thread closer : closers) {
      closer.join(TimeUnit.SECONDS.toMillis(10));
    }

    CompletableFuture<Void> again = channel.closeAsync();
    runtime.run();

    assertEquals(5, closes.size());
    closes.forEach(TestRuntime::assertSucceeded);
    assertSucceeded(again);
    assertEquals(ChannelState.CLOSED, channel.state());
    assertEquals(List.of(ChannelState.CLOSING, ChannelState.CLOSED), states.all());
  } // end method closeIsIdempotentAndSafeFromManyThreads

  @Test
  void everyCloseReturnsItsOwnCopy() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();

    CompletableFuture<Void> first = channel.closeAsync();
    CompletableFuture<Void> second = channel.closeAsync();
    second.cancel(true);
    runtime.run();

    assertSucceeded(first);
    assertSucceeded(channel.closeAsync());
    assertEquals(ChannelState.CLOSED, channel.state());
  } // end method everyCloseReturnsItsOwnCopy

  // ---- Graceful shutdown ----

  @Test
  void closeShutsAConnectedChannelGracefully() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<ChannelState> states = new TestRuntime.Recorder<>();
    channel.events().onStateChange(states);

    CompletableFuture<Void> closed = channel.closeAsync();
    runtime.run();

    assertSucceeded(closed);
    assertEquals(List.of(ChannelState.CLOSING, ChannelState.CLOSED), states.all());
    assertTrue(socket.isCloseSent(), "no closing handshake");
  } // end method closeShutsAConnectedChannelGracefully

  @Test
  void closeFlushesHandedOffPublishes() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    socket.holdWrites();
    CompletableFuture<Void> written = channel.defaultSegment().publish(bytes("x"), "m-1");
    runtime.run();

    CompletableFuture<Void> closed = channel.closeAsync();
    runtime.run();
    assertFalse(closed.isDone(), "closed before the writer flushed");
    assertFalse(socket.isCloseSent(), "closing handshake ahead of a handed-off publish");

    socket.releaseWrites();
    runtime.run();

    assertSucceeded(written);
    assertSucceeded(closed);
    assertEquals(List.of(publishFrame("default", "m-1", "x")), socket.commands());
    assertTrue(socket.isCloseSent());
  } // end method closeFlushesHandedOffPublishes

  @Test
  void publishWhileClosingIsRefusedAndNeverWritten() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    socket.holdWrites();
    CompletableFuture<Void> written = channel.defaultSegment().publish(bytes("x"), "m-1");
    runtime.run();
    CompletableFuture<Void> closed = channel.closeAsync();
    runtime.run();
    assertEquals(ChannelState.CLOSING, channel.state());

    CompletableFuture<Void> late = channel.defaultSegment().publish(bytes("y"), "m-2");
    runtime.run();

    assertCode(late, ErrorCode.NOT_CONNECTED);
    assertEquals(
        "Channel is not connected; it is closing.", TestRuntime.failureOf(late).getMessage());

    socket.releaseWrites();
    runtime.run();

    assertSucceeded(written);
    assertSucceeded(closed);
    assertEquals(List.of(publishFrame("default", "m-1", "x")), socket.commands());
  } // end method publishWhileClosingIsRefusedAndNeverWritten

  @Test
  void closeIsBoundedByItsBudget() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    socket.holdWrites();
    socket.ignoreClose();
    CompletableFuture<Void> writing = channel.defaultSegment().publish(bytes("x"), "m-1");
    CompletableFuture<Void> waiting = channel.defaultSegment().publish(bytes("y"), "m-2");
    runtime.run();
    long start = runtime.timers.monotonicNanos();

    CompletableFuture<Void> closed = channel.closeAsync();
    runtime.advance(Duration.ofSeconds(5).minusMillis(1));

    assertEquals(ChannelState.CLOSING, channel.state());
    assertFalse(closed.isDone());

    runtime.advance(Duration.ofMillis(1));

    assertSucceeded(closed);
    assertEquals(Duration.ofSeconds(5).toNanos(), runtime.timers.monotonicNanos() - start);
    assertEquals(ChannelState.CLOSED, channel.state());
    assertTrue(socket.isAborted());
    assertCode(writing, ErrorCode.DELIVERY_UNKNOWN);
    assertCode(waiting, ErrorCode.CANCELLED);
    assertEquals(
        "Channel closed before the publish was sent.", TestRuntime.failureOf(waiting).getMessage());
  } // end method closeIsBoundedByItsBudget

  @Test
  void closeFailsPublishesStillWaitingForTheWriter() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    runtime.receive(runtime.socket(), TestRuntime.rateLimitFrame());
    CompletableFuture<Void> queued = channel.defaultSegment().publish(bytes("x"), "m-1");
    runtime.run();

    channel.closeAsync();
    runtime.run();

    assertCode(queued, ErrorCode.CANCELLED);
    assertEquals(
        "Channel closed before the publish was sent.", TestRuntime.failureOf(queued).getMessage());
    assertTrue(runtime.socket().commands().isEmpty());
  } // end method closeFailsPublishesStillWaitingForTheWriter

  // A write failing while close flushes the writer fails the publishes behind it as cancelled by
  // close, not as lost to a connection that will be restored.
  @Test
  void writeFailingDuringCloseCancelsTheRest() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    socket.holdWrites();
    CompletableFuture<Void> writing = channel.defaultSegment().publish(bytes("x"), "m-1");
    CompletableFuture<Void> waiting = channel.defaultSegment().publish(bytes("y"), "m-2");
    runtime.run();

    CompletableFuture<Void> closed = channel.closeAsync();
    runtime.run();
    socket.drop();
    runtime.run();

    assertCode(writing, ErrorCode.DELIVERY_UNKNOWN);
    assertCode(waiting, ErrorCode.CANCELLED);
    assertSucceeded(closed);
    assertEquals(ChannelState.CLOSED, channel.state());
    assertEquals(1, runtime.sockets.size());
  } // end method writeFailingDuringCloseCancelsTheRest

  // ---- Attempts and late events ----

  @Test
  void closeAbortsAPendingAttemptAndDiscardsLateCredentials() {
    TestRuntime runtime = new TestRuntime();
    runtime.blockProvider = true;
    Channel channel = runtime.channel();
    CompletableFuture<Void> connected = channel.connect();
    runtime.run();

    channel.closeAsync();
    runtime.run();
    assertCode(connected, ErrorCode.CANCELLED);
    assertTrue(runtime.blockedProviders.get(0).isCancelled());

    // A provider that ignores cancellation completes anyway; its result is discarded.
    runtime.blockedProviders.get(0).obtrudeValue(TestRuntime.CREDENTIALS);
    runtime.run();

    assertEquals(ChannelState.CLOSED, channel.state());
    assertTrue(runtime.sockets.isEmpty());
  } // end method closeAbortsAPendingAttemptAndDiscardsLateCredentials

  @Test
  void closeAbortsAHandshakeInProgress() {
    TestRuntime runtime = new TestRuntime();
    runtime.blockDials = true;
    Channel channel = runtime.channel();
    CompletableFuture<Void> connected = channel.connect();
    runtime.run();

    channel.closeAsync();
    runtime.run();

    assertCode(connected, ErrorCode.CANCELLED);
    assertTrue(runtime.blockedDials.get(0).isCancelled());
    assertEquals(ChannelState.CLOSED, channel.state());
  } // end method closeAbortsAHandshakeInProgress

  @Test
  void staleSocketEventsAfterCloseAreIgnored() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    channel.closeAsync();
    runtime.run();
    TestRuntime.Recorder<ChannelState> states = new TestRuntime.Recorder<>();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onStateChange(states);
    channel.events().onError(errors);

    socket.receiveText("text");
    socket.receive(TestRuntime.messageFrame("default", "id-1", "x"));
    socket.drop();
    runtime.run();

    assertTrue(states.all().isEmpty(), () -> "states " + states.all());
    assertTrue(errors.all().isEmpty(), () -> "errors " + errors.all());
    assertEquals(ChannelState.CLOSED, channel.state());
  } // end method staleSocketEventsAfterCloseAreIgnored

  @Test
  void closeWorksFromEveryNonTerminalState() {
    TestRuntime idleRuntime = new TestRuntime();
    Channel idle = idleRuntime.channel();

    TestRuntime failedRuntime = new TestRuntime();
    failedRuntime.failProvider = true;
    Channel failed = failedRuntime.channel();
    CompletableFuture<Void> failedConnect = failed.connect();
    failedRuntime.run();
    assertCode(failedConnect, ErrorCode.TRANSPORT);

    TestRuntime reconnectingRuntime = new TestRuntime();
    Channel reconnecting = reconnectingRuntime.connectedChannel();
    reconnectingRuntime.blockDials = true;
    reconnectingRuntime.socket().drop();
    reconnectingRuntime.run();
    assertEquals(ChannelState.RECONNECTING, reconnecting.state());

    TestRuntime connectedRuntime = new TestRuntime();
    Channel connected = connectedRuntime.connectedChannel();

    List<TestRuntime> runtimes =
        List.of(idleRuntime, failedRuntime, reconnectingRuntime, connectedRuntime);
    List<Channel> channels = List.of(idle, failed, reconnecting, connected);

    for (int index = 0; index < channels.size(); index++) {
      CompletableFuture<Void> closed = channels.get(index).closeAsync();
      runtimes.get(index).run();

      assertSucceeded(closed);
      assertEquals(ChannelState.CLOSED, channels.get(index).state());
    }

    assertTrue(reconnectingRuntime.blockedDials.get(0).isCancelled());
  } // end method closeWorksFromEveryNonTerminalState

  @Test
  void closeLeavesNoTimerBehind() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    channel.segment("chat").presenceList(1, 25);
    runtime.run();
    runtime.receive(runtime.socket(), TestRuntime.rateLimitFrame());

    channel.closeAsync();
    runtime.run();

    assertEquals(0, runtime.timers.pendingCount());
  } // end method closeLeavesNoTimerBehind

  @Test
  void closeDuringABackoffCancelsTheRetry() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    runtime.random = 1;
    runtime.socket().drop();
    runtime.run();
    assertEquals(1, runtime.timers.pendingCount());

    channel.closeAsync();
    runtime.run();
    assertEquals(0, runtime.timers.pendingCount());

    runtime.advance(Duration.ofMinutes(1));

    assertEquals(1, runtime.credentialRequests.size());
    assertEquals(1, runtime.sockets.size());
    assertEquals(ChannelState.CLOSED, channel.state());
  } // end method closeDuringABackoffCancelsTheRetry

  // A close landing before the connection attempt starts cancels it before the provider is asked.
  @Test
  void closeBeforeTheAttemptStartsNeverCallsTheProvider() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.channel();

    CompletableFuture<Void> connected = channel.connect();
    CompletableFuture<Void> closed = channel.closeAsync();
    runtime.run();

    assertCode(connected, ErrorCode.CANCELLED);
    assertSucceeded(closed);
    assertTrue(runtime.credentialRequests.isEmpty());
    assertTrue(runtime.sockets.isEmpty());
    assertEquals(ChannelState.CLOSED, channel.state());
  } // end method closeBeforeTheAttemptStartsNeverCallsTheProvider

  @Test
  void aFailedClosingHandshakeLeavesNoTimerBehind() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    socket.failClose();

    // Closing from a listener holds the read side, so reading time never advances.
    channel.defaultSegment().onMessage((payload, metadata) -> channel.closeAsync());
    socket.receive(TestRuntime.messageFrame("default", "m-1", "x"));
    runtime.run();
    runtime.advance(Duration.ofMinutes(10));

    assertEquals(ChannelState.CLOSED, channel.state());
    assertTrue(socket.isAborted());
    assertEquals(0, runtime.timers.pendingCount());
  } // end method aFailedClosingHandshakeLeavesNoTimerBehind

  // ---- The closing handshake ----

  @Test
  void closingIsReportedWhileTheWriterFlushes() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<ChannelState> states = new TestRuntime.Recorder<>();
    channel.events().onStateChange(states);
    socket.holdWrites();
    channel.defaultSegment().publish(bytes("x"), "m-1");
    runtime.run();

    CompletableFuture<Void> closed = channel.closeAsync();
    runtime.run();

    assertFalse(closed.isDone());
    assertEquals(List.of(ChannelState.CLOSING), states.all());
  } // end method closingIsReportedWhileTheWriterFlushes

  // Once the closing handshake is sent, close waits for the server's reply until the budget runs
  // out, and only then aborts the socket.
  @Test
  void closeWaitsForTheServersReplyWithinItsBudget() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    socket.ignoreClose();

    CompletableFuture<Void> closed = channel.closeAsync();
    runtime.advance(Duration.ofSeconds(5).minusMillis(1));

    assertTrue(socket.isCloseSent());
    assertFalse(closed.isDone());
    assertFalse(socket.isAborted());

    runtime.advance(Duration.ofMillis(1));

    assertSucceeded(closed);
    assertTrue(socket.isAborted());
  } // end method closeWaitsForTheServersReplyWithinItsBudget

  @Test
  void closingHandshakeThatCannotBeSentAbortsAtOnce() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    socket.failClose();

    CompletableFuture<Void> closed = channel.closeAsync();
    runtime.run();

    assertSucceeded(closed);
    assertTrue(socket.isAborted());
    assertEquals(ChannelState.CLOSED, channel.state());
  } // end method closingHandshakeThatCannotBeSentAbortsAtOnce

  // The server closing while close flushes the writer fails what was never written as cancelled by
  // the close, as a failed write does.
  @Test
  void serverClosingDuringCloseCancelsTheRest() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    socket.holdWrites();
    CompletableFuture<Void> writing = channel.defaultSegment().publish(bytes("x"), "m-1");
    CompletableFuture<Void> waiting = channel.defaultSegment().publish(bytes("y"), "m-2");
    runtime.run();

    CompletableFuture<Void> closed = channel.closeAsync();
    runtime.run();
    socket.receiveClose();
    runtime.run();

    assertCode(writing, ErrorCode.DELIVERY_UNKNOWN);
    assertCode(waiting, ErrorCode.CANCELLED);
    assertSucceeded(closed);
    assertEquals(ChannelState.CLOSED, channel.state());
    assertEquals(1, runtime.sockets.size());
  } // end method serverClosingDuringCloseCancelsTheRest

  // ---- Timers ----

  @Test
  void closeWhileProbingTheQuotaLeavesNoTimerBehind() {
    TestRuntime runtime = new TestRuntime();
    runtime.answerPings = true;
    Channel channel = RateLimitTest.exhaustedChannel(runtime);
    runtime.receive(runtime.socket(), TestRuntime.rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));

    channel.closeAsync();
    runtime.run();

    assertEquals(0, runtime.timers.pendingCount());
  } // end method closeWhileProbingTheQuotaLeavesNoTimerBehind

  @Test
  void closeAfterTheQuotaReturnedLeavesNoTimerBehind() {
    TestRuntime runtime = new TestRuntime();
    runtime.answerPings = true;
    Channel channel = RateLimitTest.exhaustedChannel(runtime);
    FakeWebSocket socket = runtime.socket();
    runtime.receive(socket, TestRuntime.rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));

    // A publish goes out and two quiet seconds pass: the quota is back, and the probe is dropped.
    channel.defaultSegment().publish(bytes("x"), "m-1");
    runtime.advance(Duration.ofMillis(2001));
    socket.clearCommands();
    channel.segment("lobby").subscribe();
    runtime.run();
    assertEquals(List.of("@SUB\n$5\nlobby\n", "@SUB\n$4\nchat\n"), socket.commands());

    channel.closeAsync();
    runtime.run();

    assertEquals(0, runtime.timers.pendingCount());
  } // end method closeAfterTheQuotaReturnedLeavesNoTimerBehind
} // end class CloseTest

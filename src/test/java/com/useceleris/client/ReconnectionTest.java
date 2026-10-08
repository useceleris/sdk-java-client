package com.useceleris.client;

import static com.useceleris.client.TestRuntime.assertCode;
import static com.useceleris.client.TestRuntime.assertSucceeded;
import static com.useceleris.client.TestRuntime.bytes;
import static com.useceleris.client.TestRuntime.messageFrame;
import static com.useceleris.client.TestRuntime.presenceResponseFrame;
import static com.useceleris.client.TestRuntime.publishFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ReconnectionTest {
  /** Checks that the next attempt dials exactly delay after the previous one ended. */
  static FakeWebSocket expectAttemptAfter(TestRuntime runtime, Duration delay) {
    int count = runtime.sockets.size();

    if (!delay.isZero()) {
      runtime.advance(delay.minusMillis(1));
      assertEquals(count, runtime.sockets.size(), "attempt before " + delay);
      runtime.advance(Duration.ofMillis(1));
    } else {
      runtime.run();
    }

    assertEquals(count + 1, runtime.sockets.size(), "no attempt after " + delay);

    return runtime.socket();
  } // end method expectAttemptAfter

  private static List<CredentialRequest> reconnectRequests(TestRuntime runtime) {
    return runtime.credentialRequests.stream().filter(CredentialRequest::reconnect).toList();
  } // end method reconnectRequests

  // ---- Retry schedule ----

  @Test
  void reconnectDelaysAreJitteredAndBoundedThenFail() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    runtime.random = 0.5;
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    TestRuntime.Recorder<ChannelState> states = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    channel.events().onStateChange(states);

    // Every attempt's handshake is refused, so each fails as a transport error.
    runtime.failDials = 10;
    runtime.socket().drop();
    runtime.run();
    assertEquals(ChannelState.RECONNECTING, channel.state());

    for (long delay : new long[] {250, 500, 1000, 2000, 4000, 8000, 15000, 15000, 15000, 15000}) {
      int before = runtime.credentialRequests.size();
      runtime.advance(Duration.ofMillis(delay - 1));
      assertEquals(before, runtime.credentialRequests.size(), "attempt before " + delay + " ms");

      runtime.advance(Duration.ofMillis(1));
      assertEquals(before + 1, runtime.credentialRequests.size(), "no attempt after " + delay);
    }

    assertEquals(ChannelState.FAILED, channel.state());
    assertEquals(1, errors.all().size());
    assertCode(errors.all().get(0), ErrorCode.TRANSPORT);
    assertEquals(List.of(ChannelState.RECONNECTING, ChannelState.FAILED), states.all());
    assertEquals(10, reconnectRequests(runtime).size());

    // A failed channel retries no more.
    runtime.advance(Duration.ofMinutes(1));
    assertEquals(11, runtime.credentialRequests.size());
  } // end method reconnectDelaysAreJitteredAndBoundedThenFail

  @Test
  void retryBudgetResetsOnlyAfterSixtySecondsConnected() {
    TestRuntime runtime = new TestRuntime();
    runtime.answerPings = true;
    Channel channel = runtime.connectedChannel();
    runtime.random = 0.5;
    runtime.failDials = 3;
    runtime.socket().drop();
    runtime.run();

    for (long delay : new long[] {250, 500, 1000}) {
      runtime.advance(Duration.ofMillis(delay));
    }

    FakeWebSocket socket = expectAttemptAfter(runtime, Duration.ofMillis(2000));

    runtime.advance(Duration.ofSeconds(1));
    socket.drop();
    socket = expectAttemptAfter(runtime, Duration.ofMillis(2000));

    runtime.advance(Duration.ofMinutes(1));
    socket.drop();
    expectAttemptAfter(runtime, Duration.ofMillis(250));

    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method retryBudgetResetsOnlyAfterSixtySecondsConnected

  @Test
  void recoveryFollowsTheConnectedStateWithItsRetryIndex() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<Object> log = new TestRuntime.Recorder<>();
    channel.events().onStateChange(log::accept);
    channel.events().onRecovery(log::accept);
    runtime.failDials = 2;

    runtime.socket().drop();
    runtime.run();

    assertEquals(
        List.of(
            ChannelState.RECONNECTING, ChannelState.CONNECTED, new RecoveryEvent(2, true, true)),
        log.all());
  } // end method recoveryFollowsTheConnectedStateWithItsRetryIndex

  @Test
  void reconnectRequestsFreshCredentialsWithAGrowingLookback() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    runtime.random = 1;
    runtime.failDials = 3;
    runtime.advance(Duration.ofSeconds(5));
    Instant disconnectedAt = runtime.timers.now();

    runtime.socket().drop();
    runtime.run();
    runtime.advance(Duration.ofMillis(500));
    runtime.advance(Duration.ofSeconds(1));
    runtime.advance(Duration.ofSeconds(2));

    List<CredentialRequest> reconnects = reconnectRequests(runtime);
    assertEquals(3, reconnects.size());

    // The outage so far, rounded up to whole milliseconds, plus five seconds of overlap.
    List<Long> lookbacks = List.of(5500L, 6500L, 8500L);

    for (int index = 0; index < reconnects.size(); index++) {
      CredentialRequest request = reconnects.get(index);
      assertEquals("room-1", request.channelReference());
      assertEquals(Optional.of(disconnectedAt), request.disconnectedAt());
      assertEquals(Optional.of(Duration.ofMillis(lookbacks.get(index))), request.replayLookback());
    }

    assertEquals(ChannelState.RECONNECTING, channel.state());
  } // end method reconnectRequestsFreshCredentialsWithAGrowingLookback

  // ---- Terminal failures ----

  @Test
  void deterministicReconnectFailureIsTerminalAtOnce() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    runtime.credentials = new Credentials("", "");

    runtime.socket().drop();
    runtime.run();

    assertEquals(ChannelState.FAILED, channel.state());
    assertEquals(1, errors.all().size());
    assertCode(errors.all().get(0), ErrorCode.CONFIGURATION);
    assertEquals(1, reconnectRequests(runtime).size());
  } // end method deterministicReconnectFailureIsTerminalAtOnce

  @Test
  void reconnectTimeoutsAreRetried() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    runtime.blockDials = true;

    runtime.socket().drop();
    runtime.advance(Duration.ofSeconds(15));
    assertEquals(2, runtime.blockedDials.size());
    assertTrue(runtime.blockedDials.get(0).isCancelled());

    runtime.blockDials = false;
    runtime.advance(Duration.ofSeconds(15));

    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method reconnectTimeoutsAreRetried

  @Test
  void reconnectAttemptsRunOnTheReconnectTimeout() {
    TestRuntime runtime =
        new TestRuntime(options -> options.reconnectTimeout(Duration.ofSeconds(3)));
    runtime.blockDials = true;
    Channel channel = runtime.channel();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);

    // The initial connect keeps the connect timeout.
    CompletableFuture<Void> connecting = channel.connect();
    runtime.advance(Duration.ofSeconds(15).minusMillis(1));
    assertFalse(connecting.isDone());

    runtime.advance(Duration.ofMillis(1));
    assertCode(connecting, ErrorCode.TIMEOUT);
    assertEquals(
        "Connection attempt timed out after 15000 ms.",
        TestRuntime.failureOf(connecting).getMessage());

    runtime.blockDials = false;
    CompletableFuture<Void> connected = channel.connect();
    runtime.run();
    assertSucceeded(connected);

    // After a drop, each reconnect attempt runs on the shorter reconnect timeout.
    runtime.blockDials = true;
    runtime.socket().drop();
    runtime.advance(Duration.ofSeconds(3).minusMillis(1));
    assertEquals(2, runtime.blockedDials.size());
    assertFalse(runtime.blockedDials.get(1).isCancelled());

    runtime.advance(Duration.ofMillis(1));
    assertTrue(runtime.blockedDials.get(1).isCancelled());
    assertEquals(3, runtime.blockedDials.size());

    // Every retry times out the same way; the terminal report names the reconnect timeout.
    for (int attempt = 2; attempt <= Constants.DEFAULT_MAXIMUM_RECONNECT_ATTEMPTS; attempt++) {
      runtime.advance(Duration.ofSeconds(3));
    }

    assertEquals(ChannelState.FAILED, channel.state());
    assertEquals(1, errors.all().size());
    assertEquals("Connection attempt timed out after 3000 ms.", errors.all().get(0).getMessage());
  } // end method reconnectAttemptsRunOnTheReconnectTimeout

  @Test
  void undecodableMessageNeverStartsAReconnect() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);

    socket.receiveText("text");
    runtime.run();

    assertEquals(ChannelState.CONNECTED, channel.state());
    assertEquals(1, errors.all().size());
    assertFalse(socket.isAborted());
    assertEquals(1, runtime.sockets.size());
  } // end method undecodableMessageNeverStartsAReconnect

  // QUEUE-01: what the old socket was writing may have been sent, so it is never resent; what its
  // writer never started goes out on the next socket, after the restored subscription, in order.
  @Test
  void dropRequeuesTheWriterPublishesTheSocketNeverStarted() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    Segment chat = channel.segment("chat");
    chat.subscribe();
    runtime.run();
    runtime.socket().holdWrites();
    CompletableFuture<Void> writing = chat.publish(bytes("x"), "m-1");
    CompletableFuture<Void> second = chat.publish(bytes("y"), "m-2");
    CompletableFuture<Void> third = chat.publish(bytes("z"), "m-3");
    runtime.run();

    runtime.socket().drop();
    runtime.run();

    assertCode(writing, ErrorCode.DELIVERY_UNKNOWN);
    assertSucceeded(second);
    assertSucceeded(third);
    assertEquals(ChannelState.CONNECTED, channel.state());
    assertEquals(
        List.of(
            "@SUB\n$4\nchat\n", publishFrame("chat", "m-2", "y"), publishFrame("chat", "m-3", "z")),
        runtime.socket().commands());
  } // end method dropRequeuesTheWriterPublishesTheSocketNeverStarted

  // ---- Close during recovery ----

  @Test
  void closeDuringAReconnectAttemptStopsRecovery() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    runtime.blockProvider = true;
    runtime.socket().drop();
    runtime.run();
    TestRuntime.Recorder<ChannelState> states = new TestRuntime.Recorder<>();
    channel.events().onStateChange(states);

    channel.closeAsync();
    runtime.run();
    runtime.blockedProviders.get(0).obtrudeValue(TestRuntime.CREDENTIALS);
    runtime.advance(Duration.ofMinutes(1));

    assertEquals(List.of(ChannelState.CLOSING, ChannelState.CLOSED), states.all());
    assertEquals(ChannelState.CLOSED, channel.state());
    assertEquals(1, runtime.sockets.size());
  } // end method closeDuringAReconnectAttemptStopsRecovery

  // A close at the instant a retry timer fires must not let the closed channel reconnect.
  @Test
  void closeRacingARetryTimerNeverReconnects() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    runtime.random = 1;
    runtime.socket().drop();
    runtime.run();
    int count = runtime.sockets.size();

    // The retry is due in 500 ms; the close lands as its timer hands it to the workers.
    long due = runtime.timers.monotonicNanos() + Duration.ofMillis(500).toNanos();
    assertTrue(runtime.timers.fireNextDueBy(due));
    channel.closeAsync();
    runtime.run();

    assertEquals(ChannelState.CLOSED, channel.state());
    assertEquals(count, runtime.sockets.size());
  } // end method closeRacingARetryTimerNeverReconnects

  // A close landing while the attempt is dialing aborts the socket it produces.
  @Test
  void closeRacingAHandshakeAbortsTheLateSocket() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    runtime.blockDials = true;
    runtime.socket().drop();
    runtime.run();
    CompletableFuture<java.net.http.WebSocket> dialing = runtime.blockedDials.get(0);

    channel.closeAsync();
    runtime.run();

    assertTrue(dialing.isCancelled());
    assertEquals(ChannelState.CLOSED, channel.state());
  } // end method closeRacingAHandshakeAbortsTheLateSocket

  // ---- Restoration ----

  @Test
  void restorationSendsCurrentIntentMessagesThenPresence() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    channel.segment("beta").subscribe();
    channel.segment("alpha").subscribe();
    Subscription cancelledMessages = channel.segment("gone").subscribe();
    channel.defaultSegment().subscribePresence();
    channel.segment("alpha").subscribePresence();
    Subscription cancelledPresence = channel.segment("brief").subscribePresence();
    CompletableFuture<Void> published = channel.segment("beta").publish(bytes("x"), "m-1");
    runtime.run();
    assertSucceeded(published);

    runtime.blockDials = true;
    runtime.socket().drop();
    runtime.run();
    cancelledMessages.cancel();
    cancelledPresence.cancel();
    runtime.blockDials = false;
    runtime.advance(Duration.ofSeconds(15));

    assertEquals(ChannelState.CONNECTED, channel.state());

    // Interests only, messages before presence, registration order, cancelled intent excluded, no
    // publish resent.
    assertEquals(
        List.of(
            "@SUB\n$4\nbeta\n",
            "@SUB\n$5\nalpha\n",
            "@PRES_SUB\n$7\ndefault\n",
            "@PRES_SUB\n$5\nalpha\n"),
        runtime.socket().commands());
  } // end method restorationSendsCurrentIntentMessagesThenPresence

  @Test
  void restorationPrecedesAnyPublishMadeOnConnected() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    channel.segment("chat").subscribe();
    TestRuntime.Recorder<String> log = new TestRuntime.Recorder<>();
    channel
        .events()
        .onStateChange(
            state -> {
              if (state == ChannelState.CONNECTED) {
                channel.segment("chat").publish(bytes("x"), "m-1");
              }

              log.accept(state.toString());
            });

    channel.events().onRecovery(recovery -> log.accept("recovery"));

    runtime.socket().drop();
    runtime.run();

    assertEquals(
        List.of("@SUB\n$4\nchat\n", publishFrame("chat", "m-1", "x")), runtime.socket().commands());
    assertEquals(List.of("reconnecting", "connected", "recovery"), log.all());
  } // end method restorationPrecedesAnyPublishMadeOnConnected

  // Frames that arrived with the handshake are routed only after the connected state and the
  // recovery event.
  @Test
  void handshakeFramesFollowConnectedAndRecovery() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<String> log = new TestRuntime.Recorder<>();
    channel.events().onStateChange(state -> log.accept(state.toString()));
    channel.events().onRecovery(recovery -> log.accept("recovery"));
    channel.events().onNotice(notice -> log.accept("notice"));

    // The next socket carries a greeting the moment it opens.
    runtime.onOpen = socket -> socket.receive("@SERVER_MSG\n:1\n$5\nhello\n");
    runtime.socket().drop();
    runtime.run();

    assertEquals(List.of("reconnecting", "connected", "recovery", "notice"), log.all());
  } // end method handshakeFramesFollowConnectedAndRecovery

  @Test
  void defaultSegmentKeepsDeliveringWithoutRestoration() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    channel.defaultSegment().subscribe();
    channel.segment("chat").subscribe();
    TestRuntime.Recorder<String> delivered = new TestRuntime.Recorder<>();
    channel
        .defaultSegment()
        .onMessage((payload, metadata) -> delivered.accept(metadata.messageId()));

    runtime.socket().drop();
    runtime.run();
    FakeWebSocket restored = runtime.socket();

    // The server rejoins "default" by itself; a named segment must be rejoined explicitly.
    assertEquals(List.of("@SUB\n$4\nchat\n"), restored.commands());
    runtime.receive(restored, messageFrame("default", "id-1", "a"));
    assertEquals(List.of("id-1"), delivered.all());
  } // end method defaultSegmentKeepsDeliveringWithoutRestoration

  @Test
  void replayedDuplicatesAreAbsorbedAcrossReconnect() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    channel.segment("chat").subscribe();
    TestRuntime.Recorder<String> delivered = new TestRuntime.Recorder<>();
    channel
        .segment("chat")
        .onMessage((payload, metadata) -> delivered.accept(metadata.messageId()));
    runtime.receive(
        runtime.socket(), messageFrame("chat", "id-1", "a"), messageFrame("chat", "id-2", "b"));

    runtime.socket().drop();
    runtime.run();
    runtime.receive(
        runtime.socket(),
        messageFrame("chat", "id-1", "a"),
        messageFrame("chat", "id-2", "b"),
        messageFrame("chat", "id-3", "c"));

    assertEquals(List.of("id-1", "id-2", "id-3"), delivered.all());
  } // end method replayedDuplicatesAreAbsorbedAcrossReconnect

  @Test
  void requestIdsAreNeverReusedAcrossReconnects() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    Segment chat = channel.segment("chat");
    CompletableFuture<PresencePage> first = chat.presenceList(1, 25);
    runtime.run();
    runtime.receive(runtime.socket(), presenceResponseFrame("1"));
    assertSucceeded(first);

    runtime.socket().drop();
    runtime.run();
    FakeWebSocket restored = runtime.socket();
    CompletableFuture<PresencePage> second = chat.presenceList(1, 25);
    runtime.run();

    assertEquals(List.of("@PRES_LIST\n$4\nchat\n;1\n;25\n$1\n2\n"), restored.commands());
    runtime.receive(restored, presenceResponseFrame("2"));
    assertSucceeded(second);
  } // end method requestIdsAreNeverReusedAcrossReconnects

  @Test
  void secondRecoveryRestoresIntentWithoutDuplicates() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    channel.segment("chat").subscribe();
    channel.segment("chat").subscribePresence();

    runtime.socket().drop();
    runtime.run();
    FakeWebSocket first = runtime.socket();
    first.drop();
    runtime.run();
    FakeWebSocket second = runtime.socket();

    for (FakeWebSocket restored : List.of(first, second)) {
      assertEquals(List.of("@SUB\n$4\nchat\n", "@PRES_SUB\n$4\nchat\n"), restored.commands());
    }

    assertEquals(3, runtime.sockets.size());
    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method secondRecoveryRestoresIntentWithoutDuplicates

  @Test
  void interestsChangedWhileReconnectingAreRestoredAsTheyStand() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    channel.segment("chat").subscribe();
    runtime.blockDials = true;
    runtime.socket().drop();
    runtime.run();

    channel.segment("lobby").subscribe();
    runtime.run();
    runtime.blockDials = false;
    runtime.advance(Duration.ofSeconds(15));

    assertEquals(List.of("@SUB\n$4\nchat\n", "@SUB\n$5\nlobby\n"), runtime.socket().commands());
  } // end method interestsChangedWhileReconnectingAreRestoredAsTheyStand

  @Test
  void aRetryDueAtOnceThatRunsBeforeItsTimerIsRecordedStillReconnects() throws Exception {
    ManualExecutor executor = new ManualExecutor();
    FakeTimers fake = new FakeTimers(executor);
    AtomicBoolean raceNextRetry = new AtomicBoolean();
    AtomicBoolean racerReachedTheLock = new AtomicBoolean();
    List<Thread> racers = new CopyOnWriteArrayList<>();

    // A zero-delay timer fires on its own thread, which reaches the channel's lock before
    // schedule returns: the interleaving a preempted scheduling thread allows.
    Timers timers =
        new Timers() {
          @Override
          public Cancellable schedule(Duration delay, Runnable task) {
            if (delay.isZero() && raceNextRetry.compareAndSet(true, false)) {
              Thread racer = new Thread(task, "retry-racer");
              racers.add(racer);
              racer.start();
              long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

              while (racer.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
              }

              racerReachedTheLock.set(racer.getState() == Thread.State.WAITING);

              return () -> {};
            }

            return fake.schedule(delay, task);
          } // end method schedule

          @Override
          public long monotonicNanos() {
            return fake.monotonicNanos();
          } // end method monotonicNanos

          @Override
          public Instant now() {
            return fake.now();
          } // end method now
        };

    List<FakeWebSocket> sockets = new CopyOnWriteArrayList<>();
    Dialer dialer =
        (url, timeout, listener) -> {
          FakeWebSocket socket = new FakeWebSocket(url, listener, executor);
          socket.answerPings();
          sockets.add(socket);
          listener.onOpen(socket);

          return CompletableFuture.completedFuture(socket);
        };

    CelerisClient client =
        new CelerisClient(
            ClientOptions.builder(
                    request -> CompletableFuture.completedFuture(TestRuntime.CREDENTIALS))
                .baseUrl("wss://example.test/")
                .build(),
            dialer,
            timers,
            executor,
            () -> 0);
    Channel channel = client.channel("room-1");
    CompletableFuture<Void> connected = channel.connect();
    drain(executor, fake);
    assertSucceeded(connected);

    raceNextRetry.set(true);
    sockets.get(0).drop();
    drain(executor, fake);

    for (Thread racer : racers) {
      racer.join(TimeUnit.SECONDS.toMillis(5));
    }

    drain(executor, fake);

    assertEquals(1, racers.size());
    assertTrue(racerReachedTheLock.get(), "the retry never raced its own scheduling");
    assertEquals(ChannelState.CONNECTED, channel.state());
    assertEquals(2, sockets.size());
  } // end method aRetryDueAtOnceThatRunsBeforeItsTimerIsRecordedStillReconnects

  private static void drain(ManualExecutor executor, FakeTimers timers) {
    while (executor.runOne() || timers.fireNextDueBy(timers.monotonicNanos())) {
      // Until nothing is left to do now.
    }
  } // end method drain

  // ---- Refused writes ----

  @Test
  void refusedWriteIsReportedAtOnce() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    runtime.random = 1;
    TestRuntime.Recorder<ChannelState> states = new TestRuntime.Recorder<>();
    channel.events().onStateChange(states);
    runtime.socket().failWrites();

    channel.segment("chat").subscribe();
    runtime.run();

    // The first retry is still 500 ms away.
    assertEquals(1, runtime.sockets.size());
    assertEquals(List.of(ChannelState.RECONNECTING), states.all());
  } // end method refusedWriteIsReportedAtOnce
} // end class ReconnectionTest

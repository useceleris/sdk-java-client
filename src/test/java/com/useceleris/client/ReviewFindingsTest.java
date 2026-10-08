package com.useceleris.client;

import static com.useceleris.client.TestRuntime.assertCode;
import static com.useceleris.client.TestRuntime.assertSucceeded;
import static com.useceleris.client.TestRuntime.bytes;
import static com.useceleris.client.TestRuntime.failureOf;
import static com.useceleris.client.TestRuntime.messageFrame;
import static com.useceleris.client.TestRuntime.presenceResponseFrame;
import static com.useceleris.client.TestRuntime.publishFrame;
import static com.useceleris.client.TestRuntime.rateLimitFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;

/** Regression tests for findings of the independent client review, adapted to Java. */
class ReviewFindingsTest {
  /** A client on the runtime's executor and clock, with its own provider and jitter. */
  private static CelerisClient client(
      TestRuntime runtime,
      CredentialProvider provider,
      DoubleSupplier random,
      List<FakeWebSocket> sockets) {
    return new CelerisClient(
        ClientOptions.builder(provider).baseUrl("wss://example.test/").build(),
        (url, timeout, listener) -> {
          FakeWebSocket socket = new FakeWebSocket(url, listener, runtime.executor);
          sockets.add(socket);
          listener.onOpen(socket);

          return CompletableFuture.<WebSocket>completedFuture(socket);
        },
        runtime.timers,
        runtime.executor,
        random);
  } // end method client

  // ---- Closing ----

  // A listener that closes while close is delivering events gets the same close back instead of
  // waiting on the delivery it runs in.
  @Test
  void closeFromAListenerDuringCloseCompletes() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    List<CompletableFuture<Void>> inner = new ArrayList<>();
    channel
        .events()
        .onStateChange(
            state -> {
              if (state == ChannelState.CLOSING) {
                inner.add(channel.closeAsync());
              }
            });

    CompletableFuture<Void> outer = channel.closeAsync();
    runtime.run();

    assertSucceeded(outer);
    assertEquals(1, inner.size());
    assertSucceeded(inner.get(0));
    assertEquals(ChannelState.CLOSED, channel.state());
  } // end method closeFromAListenerDuringCloseCompletes

  // ---- Failure reporting ----

  // A refused handshake delivers the failed state it queued, even though nothing else follows it.
  @Test
  void refusedHandshakeDeliversTheFailedState() {
    TestRuntime runtime = new TestRuntime();
    runtime.failDials = 1;
    Channel channel = runtime.channel();
    TestRuntime.Recorder<ChannelState> states = new TestRuntime.Recorder<>();
    channel.events().onStateChange(states);

    CompletableFuture<Void> connected = channel.connect();
    runtime.run();

    assertCode(connected, ErrorCode.TRANSPORT);
    assertEquals(List.of(ChannelState.CONNECTING, ChannelState.FAILED), states.all());
  } // end method refusedHandshakeDeliversTheFailedState

  @Test
  void timeoutMessagesNameTheirBound() {
    TestRuntime connecting = new TestRuntime();
    connecting.blockProvider = true;
    CompletableFuture<Void> connected = connecting.channel().connect();
    connecting.advance(Duration.ofSeconds(15));
    assertEquals("Connection attempt timed out after 15000 ms.", failureOf(connected).getMessage());

    TestRuntime querying = new TestRuntime();
    Channel channel = querying.connectedChannel();
    CompletableFuture<PresencePage> query = channel.segment("chat").presenceList(1, 25);
    querying.advance(Duration.ofSeconds(10));
    assertEquals("Presence query timed out after 10000 ms.", failureOf(query).getMessage());
  } // end method timeoutMessagesNameTheirBound

  @Test
  void publishChecksTheConnectionBeforeTheMessageId() {
    TestRuntime runtime = new TestRuntime();
    Segment lobby = runtime.channel().defaultSegment();

    List<CompletableFuture<Void>> publishes =
        List.of(
            lobby.publish(bytes("x"), "bad\nid"),
            lobby.publish(bytes("x"), ""),
            lobby.publish(new byte[3 << 20], "m-1"));
    runtime.run();

    for (CompletableFuture<Void> published : publishes) {
      assertCode(published, ErrorCode.NOT_CONNECTED);
    }
  } // end method publishChecksTheConnectionBeforeTheMessageId

  // ---- Listener ordering ----

  // A recovery listener registered inside the connected listener receives the recovery event, as
  // listeners registered before dispatch do in the reference.
  @Test
  void listenersRegisteredBeforeTheirTurnReceiveTheEvent() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<RecoveryEvent> recoveries = new TestRuntime.Recorder<>();
    channel
        .events()
        .onStateChange(
            state -> {
              if (state == ChannelState.CONNECTED) {
                channel.events().onRecovery(recoveries);
              }
            });

    runtime.socket().drop();
    runtime.run();

    assertEquals(List.of(new RecoveryEvent(0, true, true)), recoveries.all());
  } // end method listenersRegisteredBeforeTheirTurnReceiveTheEvent

  // A listener's failure is reported before the next queued event.
  @Test
  void listenerFailureIsReportedBeforeTheNextEvent() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<String> log = new TestRuntime.Recorder<>();
    channel
        .events()
        .onStateChange(
            state -> {
              if (state == ChannelState.CONNECTED) {
                throw new IllegalStateException("listener failure");
              }
            });

    channel.events().onRecovery(recovery -> log.accept("recovery"));
    channel.events().onError(error -> log.accept("error"));

    runtime.socket().drop();
    runtime.run();

    assertEquals(List.of("error", "recovery"), log.all());
  } // end method listenerFailureIsReportedBeforeTheNextEvent

  // Go's listeners may end their goroutine, as t.FailNow does. Java's analog is a listener throwing
  // an Error, such as a failed assertion: it is contained, and the listeners after it still receive
  // the event.
  @Test
  void listenersAfterOneThrowingAnErrorStillReceive() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.channel();
    TestRuntime.Recorder<ChannelState> after = new TestRuntime.Recorder<>();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel
        .events()
        .onStateChange(
            state -> {
              if (state == ChannelState.CONNECTING) {
                throw new AssertionError("listener assertion");
              }
            });

    channel.events().onStateChange(after);
    channel.events().onError(errors);

    channel.connect();
    runtime.run();

    assertEquals(List.of(ChannelState.CONNECTING, ChannelState.CONNECTED), after.all());
    assertEquals(1, errors.all().size());
  } // end method listenersAfterOneThrowingAnErrorStillReceive

  // A fatal error leaves the listener's thread, as a Go listener ending its goroutine does, and the
  // channel keeps reading the socket: the rest of the event queue is delivered on another turn.
  @Test
  void listenerThrowingAFatalErrorKeepsTheSocketRead() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<String> seen = new TestRuntime.Recorder<>();
    channel
        .defaultSegment()
        .onMessage(
            (payload, metadata) -> {
              seen.accept(metadata.messageId());

              if (metadata.messageId().equals("id-1")) {
                throw new StackOverflowError("injected");
              }
            });

    socket.receive(messageFrame("default", "id-1", "x"));
    assertThrows(StackOverflowError.class, runtime::run);
    runtime.run();
    runtime.receive(socket, messageFrame("default", "id-2", "x"));

    assertEquals(List.of("id-1", "id-2"), seen.all());
    assertEquals(1, runtime.sockets.size());
    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method listenerThrowingAFatalErrorKeepsTheSocketRead

  // A failure inside routing, where the lock is held, leaves the worker with its own message
  // instead of being swallowed, and never leaves the lock held: the channel still closes.
  @Test
  void routingFailureSurfacesAndReleasesTheLock() {
    TestRuntime runtime = new TestRuntime();
    boolean[] failing = new boolean[1];
    List<FakeWebSocket> sockets = new ArrayList<>();
    Channel channel =
        client(
                runtime,
                request -> CompletableFuture.completedFuture(TestRuntime.CREDENTIALS),
                () -> {
                  if (failing[0]) {
                    throw new IllegalStateException("injected routing failure");
                  }

                  return 0;
                },
                sockets)
            .channel("room-1");
    channel.connect();
    runtime.run();

    // The rate limit's pause draws its jitter while routing.
    failing[0] = true;
    sockets.get(0).receive(rateLimitFrame());
    IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::run);

    assertEquals("injected routing failure", failure.getMessage());
    assertFalse(channel.lock.isLocked());
    assertEquals(ChannelState.CONNECTED, channel.state());

    CompletableFuture<Void> closed = channel.closeAsync();
    runtime.run();
    assertSucceeded(closed);
  } // end method routingFailureSurfacesAndReleasesTheLock

  // Go reads a caller's context outside its mutex. Java's callbacks are the provider, listeners and
  // stages attached to returned futures: none runs under the channel's lock, so each may call back.
  @Test
  void callbacksRunOutsideTheLock() {
    TestRuntime runtime = new TestRuntime();
    Channel[] holder = new Channel[1];
    TestRuntime.Recorder<String> locked = new TestRuntime.Recorder<>();
    List<FakeWebSocket> sockets = new ArrayList<>();
    CelerisClient client =
        client(
            runtime,
            request -> {
              if (holder[0].lock.isHeldByCurrentThread()) {
                locked.accept("provider");
              }

              holder[0].state();

              return CompletableFuture.completedFuture(TestRuntime.CREDENTIALS);
            },
            () -> 0,
            sockets);
    Channel channel = client.channel("room-1");
    holder[0] = channel;
    Runnable check =
        () -> {
          if (channel.lock.isHeldByCurrentThread()) {
            locked.accept("callback");
          }

          channel.state();
        };

    channel.events().onStateChange(state -> check.run());
    channel.defaultSegment().onMessage((payload, metadata) -> check.run());

    var connected = channel.connect().thenRun(check);
    runtime.run();
    var published = channel.defaultSegment().publish(bytes("x"), "m-1").thenRun(check);
    var queried = channel.segment("chat").presenceList(1, 25).thenRun(check);
    runtime.run();
    runtime.receive(
        sockets.get(0), presenceResponseFrame("1"), messageFrame("default", "id-1", "x"));

    assertSucceeded(connected);
    assertSucceeded(published);
    assertSucceeded(queried);
    assertTrue(locked.all().isEmpty(), () -> "ran under the lock: " + locked.all());
  } // end method callbacksRunOutsideTheLock

  // ---- Withdrawn and resent publishes ----

  // Frames handed to the writer before a limit, and written during the pause, are not commands sent
  // since it: a second frame for the same burst is the same episode.
  @Test
  void backlogWrittenDuringThePauseIsNotANewEpisode() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    Segment chat = channel.segment("chat");

    for (int burst = 0; burst < 8; burst++) {
      socket.holdWrites();
      CompletableFuture<Void> done = chat.publish(bytes("x"), "m-" + burst);
      runtime.run();
      runtime.receive(socket, rateLimitFrame());
      socket.releaseWrites();
      runtime.run();
      assertSucceeded(done);

      runtime.receive(socket, rateLimitFrame());
      socket.clearCommands();
      runtime.advance(Duration.ofSeconds(1));

      assertFalse(socket.commands().isEmpty(), "burst " + (burst + 1) + " treated as a quota");
    }
  } // end method backlogWrittenDuringThePauseIsNotANewEpisode

  // A publish taken back from the writer is never resent by a later rate limit.
  @Test
  void publishTakenBackFromTheWriterIsNeverResent() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    Segment chat = channel.segment("chat");
    socket.holdWrites();
    CompletableFuture<Void> writing = chat.publish(bytes("x"), "m-1");
    CompletableFuture<Void> withdrawn = chat.publish(bytes("y"), "m-2");
    runtime.run();

    assertTrue(withdrawn.cancel(true));
    socket.releaseWrites();
    runtime.run();
    assertSucceeded(writing);

    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));

    assertEquals(
        List.of(publishFrame("chat", "m-1", "x"), publishFrame("chat", "m-1", "x")),
        socket.commands());
  } // end method publishTakenBackFromTheWriterIsNeverResent

  // A rate limit requeues a publish still waiting in the writer, so it has two copies. Cancelling
  // it
  // takes back both: a publish reported cancelled never goes out.
  @Test
  void cancelTakesBackEveryCopyARateLimitLeft() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    Segment chat = channel.segment("chat");
    socket.holdWrites();
    CompletableFuture<Void> writing = chat.publish(bytes("x"), "m-0");
    CompletableFuture<Void> waiting = chat.publish(bytes("y"), "m-1");
    runtime.run();

    runtime.receive(socket, rateLimitFrame());
    assertTrue(waiting.cancel(true));
    socket.releaseWrites();
    runtime.run();
    assertSucceeded(writing);

    runtime.advance(Duration.ofSeconds(1));

    assertEquals(
        List.of(publishFrame("chat", "m-0", "x"), publishFrame("chat", "m-0", "x")),
        socket.commands());
  } // end method cancelTakesBackEveryCopyARateLimitLeft

  // A publish cancelled mid-write reports DeliveryUnknown, and such a publish is never resent, even
  // by a rate limit that follows.
  @Test
  void deliveryUnknownPublishIsNeverResent() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    socket.holdWrites();
    CompletableFuture<Void> writing = channel.segment("chat").publish(bytes("x"), "m-1");
    runtime.run();

    assertFalse(writing.cancel(true));
    assertCode(writing, ErrorCode.DELIVERY_UNKNOWN);
    socket.releaseWrites();
    runtime.run();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));

    assertEquals(List.of(publishFrame("chat", "m-1", "x")), socket.commands());
  } // end method deliveryUnknownPublishIsNeverResent

  // Taking a publish back from the writer frees room, so a publish waiting for it is handed over at
  // once rather than when unrelated traffic drains the queue: a subscription made afterwards
  // follows
  // it.
  @Test
  void publishTakenBackFreesRoomForTheNext() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    Segment lobby = channel.defaultSegment();
    byte[] large = new byte[1100 * 1024];
    socket.holdWrites();
    CompletableFuture<Void> writing = lobby.publish(bytes("a"), "a");

    // The writer holds the first large publish but has no room for the second.
    CompletableFuture<Void> taken = lobby.publish(large, "b");
    CompletableFuture<Void> waiting = lobby.publish(large, "c");
    runtime.run();
    assertTrue(taken.cancel(true));
    channel.segment("other").subscribe();
    socket.releaseWrites();
    runtime.run();

    assertSucceeded(writing);
    assertSucceeded(waiting);
    List<String> commands = socket.commands();
    assertEquals(3, commands.size());
    assertTrue(commands.get(1).startsWith("@PUB\n$7\ndefault\n$1\nc\n"), "second large publish");
    assertEquals("@SUB\n$5\nother\n", commands.get(2));
  } // end method publishTakenBackFreesRoomForTheNext
} // end class ReviewFindingsTest

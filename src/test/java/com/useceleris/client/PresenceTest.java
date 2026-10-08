package com.useceleris.client;

import static com.useceleris.client.TestRuntime.PRESENCE_CONNECTION;
import static com.useceleris.client.TestRuntime.assertCode;
import static com.useceleris.client.TestRuntime.assertSucceeded;
import static com.useceleris.client.TestRuntime.bytes;
import static com.useceleris.client.TestRuntime.failureOf;
import static com.useceleris.client.TestRuntime.presenceErrorFrame;
import static com.useceleris.client.TestRuntime.presenceNotifyFrame;
import static com.useceleris.client.TestRuntime.presenceResponseFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PresenceTest {
  private static final String CHAT_QUERY = "@PRES_LIST\n$4\nchat\n;1\n;25\n$1\n1\n";

  // ---- Presence interests ----

  @Test
  void presenceInterestsShareOneCount() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    Subscription first = channel.segment("chat").subscribePresence();
    Subscription second = channel.segment("chat").subscribePresence();
    first.cancel();
    first.cancel();
    runtime.run();
    assertEquals(List.of("@PRES_SUB\n$4\nchat\n"), socket.commands());

    second.cancel();
    runtime.run();
    assertEquals(List.of("@PRES_SUB\n$4\nchat\n", "@PRES_UNSUB\n$4\nchat\n"), socket.commands());
  } // end method presenceInterestsShareOneCount

  @Test
  void defaultSegmentTakesPresenceCommands() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();

    channel.defaultSegment().subscribePresence().cancel();
    runtime.run();

    assertEquals(
        List.of("@PRES_SUB\n$7\ndefault\n", "@PRES_UNSUB\n$7\ndefault\n"),
        runtime.socket().commands());
  } // end method defaultSegmentTakesPresenceCommands

  // Watching presence is not membership (SEG-01).
  @Test
  void messageCancelSendsUnsubscribeWhilePresenceIsHeld() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    Subscription messages = channel.segment("chat").subscribe();
    Subscription presence = channel.segment("chat").subscribePresence();
    runtime.run();

    messages.cancel();
    runtime.run();
    assertEquals(
        List.of("@SUB\n$4\nchat\n", "@PRES_SUB\n$4\nchat\n", "@UNSUB\n$4\nchat\n"),
        socket.commands());

    presence.cancel();
    runtime.run();
    assertEquals(
        List.of(
            "@SUB\n$4\nchat\n",
            "@PRES_SUB\n$4\nchat\n",
            "@UNSUB\n$4\nchat\n",
            "@PRES_UNSUB\n$4\nchat\n"),
        socket.commands());
  } // end method messageCancelSendsUnsubscribeWhilePresenceIsHeld

  @Test
  void presenceInterestOnAClosedChannelFails() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.channel();
    channel.closeAsync();
    runtime.run();

    CelerisException failure =
        assertThrows(CelerisException.class, () -> channel.segment("chat").subscribePresence());

    assertEquals(ErrorCode.NOT_CONNECTED, failure.code());
  } // end method presenceInterestOnAClosedChannelFails

  // ---- Queries ----

  @Test
  void presenceQueryResolvesWithRawMetadata() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    CompletableFuture<PresencePage> pending = channel.segment("chat").presenceList(1, 25);
    runtime.run();
    assertEquals(List.of(CHAT_QUERY), socket.commands());

    List<PresenceConnection> connections =
        List.of(PRESENCE_CONNECTION, new PresenceConnection("user", "connection-2", 456));
    runtime.receive(socket, presenceResponseFrame("chat", "1", 2, 25, 1, 1, 2, connections));

    assertSucceeded(pending);
    assertEquals(new PresencePage("chat", 2, 25, 1, 1, 2, connections), pending.join());
  } // end method presenceQueryResolvesWithRawMetadata

  @Test
  void presencePagePastTheEndKeepsFromAboveTo() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    CompletableFuture<PresencePage> pending = channel.segment("chat").presenceList(2, 25);
    runtime.run();

    runtime.receive(
        runtime.socket(), presenceResponseFrame("chat", "1", 1, 25, 2, 26, 1, List.of()));

    assertSucceeded(pending);
    PresencePage page = pending.join();
    assertEquals(26, page.from());
    assertEquals(1, page.to());
    assertTrue(page.connections().isEmpty());
  } // end method presencePagePastTheEndKeepsFromAboveTo

  @Test
  void onePresenceQueryAtATime() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    CompletableFuture<PresencePage> first = channel.segment("chat").presenceList(1, 25);
    CompletableFuture<PresencePage> second = channel.segment("other").presenceList(1, 25);
    runtime.run();

    assertCode(second, ErrorCode.OPERATION_IN_PROGRESS);
    assertEquals(
        "A presence query is already in flight; wait for it to settle before starting another.",
        failureOf(second).getMessage());

    runtime.receive(runtime.socket(), presenceResponseFrame("1"));

    assertSucceeded(first);
    assertEquals("chat", first.join().segmentId());
  } // end method onePresenceQueryAtATime

  @Test
  void presenceBoundsAreValidatedAndSpendTheirId() {
    int[][] cases = {{0, 25}, {-1, 25}, {1, 0}, {1, 101}};

    for (int[] bounds : cases) {
      TestRuntime runtime = new TestRuntime();
      Channel channel = runtime.connectedChannel();
      FakeWebSocket socket = runtime.socket();
      CompletableFuture<PresencePage> refused =
          channel.segment("chat").presenceList(bounds[0], bounds[1]);
      runtime.run();
      assertCode(refused, ErrorCode.CONFIGURATION);
      assertTrue(socket.commands().isEmpty());

      CompletableFuture<PresencePage> recovered = channel.segment("chat").presenceList(1, 25);
      runtime.run();
      assertEquals(List.of("@PRES_LIST\n$4\nchat\n;1\n;25\n$1\n2\n"), socket.commands());
      runtime.receive(socket, presenceResponseFrame("2"));

      assertSucceeded(recovered);
    }
  } // end method presenceBoundsAreValidatedAndSpendTheirId

  @Test
  void presenceBoundsMessagesNameEachRule() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();

    CompletableFuture<PresencePage> refused = channel.segment("chat").presenceList(0, 101);
    runtime.run();

    assertEquals(
        "Invalid command. page: Must be at least 1. perPage: Must be at most 100.",
        failureOf(refused).getMessage());
  } // end method presenceBoundsMessagesNameEachRule

  @Test
  void presenceQueryFailsWhileNotConnected() {
    TestRuntime idleRuntime = new TestRuntime();
    CompletableFuture<PresencePage> idle =
        idleRuntime.channel().segment("chat").presenceList(1, 25);
    idleRuntime.run();
    assertCode(idle, ErrorCode.NOT_CONNECTED);

    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    runtime.blockDials = true;
    runtime.socket().drop();
    runtime.run();

    CompletableFuture<PresencePage> reconnecting = channel.segment("chat").presenceList(1, 25);
    runtime.run();

    assertCode(reconnecting, ErrorCode.NOT_CONNECTED);
    assertEquals(
        "Channel is not connected; it is reconnecting.", failureOf(reconnecting).getMessage());
  } // end method presenceQueryFailsWhileNotConnected

  // A query the writer has no room for fails without taking the query slot; its request id stays
  // spent, as in the reference.
  @Test
  void presenceQueryAgainstAFullWriterIsBackpressure() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    socket.holdWrites();

    for (int index = 0; index < 64; index++) {
      channel.defaultSegment().publish(bytes("x"), "m-" + index);
    }

    CompletableFuture<PresencePage> refused = channel.segment("chat").presenceList(1, 25);
    runtime.run();

    assertCode(refused, ErrorCode.BACKPRESSURE);
    assertEquals(
        "Command writer is full: 64 commands are waiting to be sent. Retry once the socket has"
            + " flushed them.",
        failureOf(refused).getMessage());

    socket.releaseWrites();
    runtime.run();
    socket.clearCommands();
    CompletableFuture<PresencePage> next = channel.segment("chat").presenceList(1, 25);
    runtime.run();

    assertEquals(List.of("@PRES_LIST\n$4\nchat\n;1\n;25\n$1\n2\n"), socket.commands());
    runtime.receive(socket, presenceResponseFrame("2"));
    assertSucceeded(next);
  } // end method presenceQueryAgainstAFullWriterIsBackpressure

  @Test
  void presenceTimeoutLeavesTheConnectionAndDropsTheLateReply() {
    TestRuntime runtime = new TestRuntime();
    runtime.answerPings = true;
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    TestRuntime.Recorder<ChannelState> states = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    channel.events().onStateChange(states);
    CompletableFuture<PresencePage> pending = channel.segment("chat").presenceList(1, 25);
    runtime.run();

    runtime.advance(Duration.ofSeconds(10).minusMillis(1));
    assertFalse(pending.isDone(), "settled before its deadline");

    runtime.advance(Duration.ofMillis(1));
    assertCode(pending, ErrorCode.TIMEOUT);
    assertEquals("Presence query timed out after 10000 ms.", failureOf(pending).getMessage());

    // A late reply carries its own query's request id, so it cannot be mistaken for the next
    // query's (QUERY-01).
    assertTrue(states.all().isEmpty(), () -> "states " + states.all());
    assertFalse(socket.isAborted());

    CompletableFuture<PresencePage> next = channel.segment("chat").presenceList(1, 25);
    runtime.run();
    runtime.receive(
        socket,
        presenceResponseFrame("1"),
        presenceErrorFrame("InternalError", "1"),
        presenceResponseFrame("chat", "2", 7, 25, 1, 1, 1, List.of(PRESENCE_CONNECTION)));

    assertSucceeded(next);
    assertEquals(7, next.join().total());
    assertTrue(errors.all().isEmpty(), () -> "errors " + errors.all());
  } // end method presenceTimeoutLeavesTheConnectionAndDropsTheLateReply

  @Test
  void cancellingASentQueryFreesTheSlot() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    CompletableFuture<PresencePage> pending = channel.segment("chat").presenceList(1, 25);
    runtime.run();

    assertTrue(pending.cancel(true));
    runtime.run();
    assertTrue(pending.isCancelled());
    assertEquals(ChannelState.CONNECTED, channel.state());

    CompletableFuture<PresencePage> next = channel.segment("chat").presenceList(1, 25);
    runtime.run();
    runtime.receive(
        socket,
        presenceResponseFrame("1"),
        presenceResponseFrame("chat", "2", 3, 25, 1, 1, 1, List.of(PRESENCE_CONNECTION)));

    assertSucceeded(next);
    assertEquals(3, next.join().total());
  } // end method cancellingASentQueryFreesTheSlot

  @Test
  void presenceResponsesMatchByRequestIdAlone() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    runtime.receive(socket, presenceResponseFrame("9"));
    CompletableFuture<PresencePage> pending = channel.segment("chat").presenceList(2, 50);
    runtime.run();

    runtime.receive(
        socket,
        presenceResponseFrame("0"),
        presenceResponseFrame("10"),
        presenceResponseFrame("chat", "1", 1, 25, 7, 1, 1, List.of(PRESENCE_CONNECTION)));

    assertSucceeded(pending);
    assertEquals(7, pending.join().currentPage());
  } // end method presenceResponsesMatchByRequestIdAlone

  @Test
  void presenceErrorNamingTheQueryRejectsItAtOnce() {
    for (String errorType : List.of("InternalError", "PermissionDeniedError")) {
      TestRuntime runtime = new TestRuntime();
      Channel channel = runtime.connectedChannel();
      FakeWebSocket socket = runtime.socket();
      TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
      channel.events().onError(errors);
      CompletableFuture<PresencePage> pending = channel.segment("chat").presenceList(1, 25);
      runtime.run();

      runtime.receive(socket, presenceErrorFrame(errorType, "1"));

      TestRuntime.assertServerError(failureOf(pending), errorType, "PRES_LIST", "failed", "1");

      // Reported once, to the caller; the connection is untouched.
      assertTrue(errors.all().isEmpty(), () -> errorType + ": errors " + errors.all());
      assertEquals(ChannelState.CONNECTED, channel.state(), errorType);

      CompletableFuture<PresencePage> next = channel.segment("chat").presenceList(1, 25);
      runtime.run();
      runtime.receive(socket, presenceResponseFrame("2"));

      assertSucceeded(next);
    }
  } // end method presenceErrorNamingTheQueryRejectsItAtOnce

  @Test
  void presenceErrorForAnotherQueryIsDropped() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    runtime.receive(socket, presenceErrorFrame("InternalError", "1"));
    CompletableFuture<PresencePage> pending = channel.segment("chat").presenceList(1, 25);
    runtime.run();

    runtime.receive(socket, presenceErrorFrame("InternalError", "0"), presenceResponseFrame("1"));

    assertSucceeded(pending);
    assertTrue(errors.all().isEmpty(), () -> "errors " + errors.all());
  } // end method presenceErrorForAnotherQueryIsDropped

  @Test
  void pendingQueryFailsOnConnectionLossAndOnClose() {
    TestRuntime lostRuntime = new TestRuntime();
    Channel lost = lostRuntime.connectedChannel();
    lostRuntime.blockDials = true;
    CompletableFuture<PresencePage> lostQuery = lost.segment("chat").presenceList(1, 25);
    lostRuntime.run();

    lostRuntime.socket().drop();
    lostRuntime.run();

    assertCode(lostQuery, ErrorCode.TRANSPORT);
    assertEquals(
        "Connection lost during the presence query; query again once the channel reconnects.",
        failureOf(lostQuery).getMessage());

    TestRuntime closedRuntime = new TestRuntime();
    Channel closed = closedRuntime.connectedChannel();
    CompletableFuture<PresencePage> closedQuery = closed.segment("chat").presenceList(1, 25);
    closedRuntime.run();

    closed.closeAsync();
    closedRuntime.run();

    assertCode(closedQuery, ErrorCode.CANCELLED);
    assertEquals(
        "Channel closed while the presence query was pending.",
        failureOf(closedQuery).getMessage());
  } // end method pendingQueryFailsOnConnectionLossAndOnClose

  // A write the socket refuses breaks the connection: the query fails with the connection, and its
  // request id stays spent since it may have gone out.
  @Test
  void refusedQueryWriteSpendsItsId() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    runtime.socket().failWrites();
    CompletableFuture<PresencePage> refused = channel.segment("chat").presenceList(1, 25);
    runtime.run();
    assertCode(refused, ErrorCode.TRANSPORT);
    assertEquals(ChannelState.CONNECTED, channel.state());
    assertEquals(2, runtime.sockets.size());

    CompletableFuture<PresencePage> next = channel.segment("chat").presenceList(1, 25);
    runtime.run();
    FakeWebSocket restored = runtime.socket();
    assertEquals(List.of("@PRES_LIST\n$4\nchat\n;1\n;25\n$1\n2\n"), restored.commands());
    runtime.receive(restored, presenceResponseFrame("2"));

    assertSucceeded(next);
  } // end method refusedQueryWriteSpendsItsId

  // The reply is routed only once listeners return, so a query made inside one completes after it:
  // compose on the future rather than waiting for it.
  @Test
  void queryFromAListenerSettlesOnceTheListenerReturns() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<String> log = new TestRuntime.Recorder<>();
    channel
        .events()
        .onNotice(
            notice -> {
              CompletableFuture<PresencePage> page = channel.segment("chat").presenceList(1, 25);
              var unused = page.thenAccept(answered -> log.accept("page " + answered.total()));

              // The reply is already waiting at the socket; it cannot be routed until this returns.
              runtime.run();
              log.accept("listener returned, settled " + page.isDone());
            });

    socket.receive("@SERVER_MSG\n:1\n$0\n\n");
    socket.receive(presenceResponseFrame("1"));
    runtime.run();

    assertEquals(List.of("listener returned, settled false", "page 1"), log.all());
  } // end method queryFromAListenerSettlesOnceTheListenerReturns

  // ---- Notices and presence events ----

  @Test
  void noticesAreDeliveredInOrderWithWorkingRemoval() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<String> seen = new TestRuntime.Recorder<>();
    Registration first =
        channel.events().onNotice(notice -> seen.accept("first " + describe(notice)));
    channel.events().onNotice(notice -> seen.accept("second " + describe(notice)));

    runtime.receive(socket, "@SERVER_MSG\n:7\n$6\njoined\n");
    first.close();
    runtime.receive(socket, "@SERVER_MSG\n:8\n$4\nleft\n");

    assertEquals(List.of("first 7 joined", "second 7 joined", "second 8 left"), seen.all());
  } // end method noticesAreDeliveredInOrderWithWorkingRemoval

  @Test
  void presenceEventsReachOnlyTheirSegment() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<PresenceEvent> chat = new TestRuntime.Recorder<>();
    TestRuntime.Recorder<PresenceEvent> lobby = new TestRuntime.Recorder<>();
    channel.segment("chat").onPresence(chat);
    channel.segment("lobby").onPresence(lobby);

    runtime.receive(
        runtime.socket(),
        presenceNotifyFrame("chat", true, 7),
        presenceNotifyFrame("chat", false, 9),
        presenceNotifyFrame("unwatched", true, 1));

    assertEquals(
        List.of(
            new PresenceEvent("chat", "user", "connection-1", true, 7),
            new PresenceEvent("chat", "user", "connection-1", false, 9)),
        chat.all());
    assertTrue(lobby.all().isEmpty());
  } // end method presenceEventsReachOnlyTheirSegment

  @Test
  void throwingNoticeAndPresenceListenersAreContained() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    TestRuntime.Recorder<String> order = new TestRuntime.Recorder<>();
    channel
        .events()
        .onNotice(
            notice -> {
              throw new IllegalStateException("notice-secret");
            });

    channel.events().onNotice(notice -> order.accept("notice after"));
    channel
        .segment("chat")
        .onPresence(
            event -> {
              throw new IllegalStateException("presence-secret");
            });

    channel.segment("chat").onPresence(event -> order.accept("presence after"));

    runtime.receive(
        runtime.socket(), "@SERVER_MSG\n:1\n$0\n\n", presenceNotifyFrame("chat", true, 1));

    assertEquals(List.of("notice after", "presence after"), order.all());
    assertEquals(2, errors.all().size());

    for (RuntimeException failure : errors.all()) {
      assertFalse(failure.getMessage().contains("secret"), failure.getMessage());
    }
  } // end method throwingNoticeAndPresenceListenersAreContained

  private static String describe(ServerNotice notice) {
    return notice.timestamp() + " " + new String(notice.payload(), StandardCharsets.UTF_8);
  } // end method describe

  @Test
  void aQueryRefusedForTheByteBoundNamesIt() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    runtime.socket().holdWrites();
    var unused = channel.defaultSegment().publish(new byte[2 * 1024 * 1024 - 40], "big");
    runtime.run();

    CompletableFuture<PresencePage> query = channel.segment("chat").presenceList(1, 25);
    runtime.run();

    assertCode(query, ErrorCode.BACKPRESSURE);
    assertEquals(
        "WebSocket buffer is full: this command would take unsent data past 2 MiB. Retry once the"
            + " buffer drains.",
        failureOf(query).getMessage());
  } // end method aQueryRefusedForTheByteBoundNamesIt

  @Test
  void presenceListenersRemoveCleanly() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<PresenceEvent> kept = new TestRuntime.Recorder<>();
    TestRuntime.Recorder<PresenceEvent> removed = new TestRuntime.Recorder<>();
    channel.segment("chat").onPresence(kept);
    channel.segment("chat").onPresence(removed).close();

    runtime.receive(runtime.socket(), presenceNotifyFrame("chat", true, 7));

    assertEquals(List.of(new PresenceEvent("chat", "user", "connection-1", true, 7)), kept.all());
    assertTrue(removed.all().isEmpty());
  } // end method presenceListenersRemoveCleanly
} // end class PresenceTest

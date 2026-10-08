package com.useceleris.client;

import static com.useceleris.client.TestRuntime.assertCode;
import static com.useceleris.client.TestRuntime.assertSucceeded;
import static com.useceleris.client.TestRuntime.bytes;
import static com.useceleris.client.TestRuntime.failureOf;
import static com.useceleris.client.TestRuntime.publishFrame;
import static com.useceleris.client.TestRuntime.rateLimitFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * RESEND-01. Time is the fake clock; jitter is zero unless a test sets it, so a pause is one
 * second. The peer answers pings, so minutes of silence never look like a dead connection.
 */
class RateLimitTest {
  private static final String CHAT_SUBSCRIBE = "@SUB\n$4\nchat\n";
  private static final String LOBBY_SUBSCRIBE = "@SUB\n$5\nlobby\n";

  private static TestRuntime runtime() {
    TestRuntime runtime = new TestRuntime();
    runtime.answerPings = true;

    return runtime;
  } // end method runtime

  /**
   * Reports eight limits in a row, each after the previous resend round, so the next limit is
   * treated as a used-up quota.
   */
  private static void exhaustRateLimit(TestRuntime runtime, FakeWebSocket socket) {
    for (int round = 0; round < 8; round++) {
      runtime.receive(socket, rateLimitFrame());
      runtime.advance(Duration.ofSeconds(1));
    }
  } // end method exhaustRateLimit

  /** A connected channel subscribed to "chat", with the quota used up. */
  static Channel exhaustedChannel(TestRuntime runtime) {
    Channel channel = runtime.connectedChannel();
    channel.segment("chat").subscribe();
    runtime.run();
    exhaustRateLimit(runtime, runtime.socket());

    return channel;
  } // end method exhaustedChannel

  // ---- Pausing and resending ----

  @Test
  void rateLimitPausesThenResendsSubscriptionsBeforePublishes() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    Segment chat = channel.segment("chat");
    Segment lobby = channel.segment("lobby");
    chat.subscribe();
    chat.subscribePresence();
    CompletableFuture<Void> first = lobby.publish(bytes("a"), "m-1");
    runtime.run();
    assertSucceeded(first);
    socket.clearCommands();

    runtime.receive(socket, rateLimitFrame());
    CompletableFuture<Void> queued = lobby.publish(bytes("b"), "m-2");
    runtime.advance(Duration.ofMillis(999));

    assertEquals(1, errors.all().size());
    ServerErrorException limit = assertInstanceOf(ServerErrorException.class, errors.all().get(0));
    assertEquals(ServerErrorException.RATE_LIMIT_ERROR, limit.type());
    assertTrue(socket.commands().isEmpty());
    assertTrue(!queued.isDone());

    runtime.advance(Duration.ofMillis(1));

    assertSucceeded(queued);
    assertEquals(
        List.of(
            CHAT_SUBSCRIBE,
            "@PRES_SUB\n$4\nchat\n",
            publishFrame("lobby", "m-1", "a"),
            publishFrame("lobby", "m-2", "b")),
        socket.commands());
  } // end method rateLimitPausesThenResendsSubscriptionsBeforePublishes

  @Test
  void resendsACommandSentExactly2000MsBeforeTheLimitNotOneSent2001MsBefore() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    Segment chat = channel.segment("chat");
    chat.publish(bytes("old"), "old");
    runtime.run();
    runtime.advance(Duration.ofMillis(1));
    chat.publish(bytes("edge"), "edge");
    runtime.run();
    runtime.advance(Duration.ofMillis(2000));
    socket.clearCommands();

    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));

    assertEquals(List.of(publishFrame("chat", "edge", "edge")), socket.commands());
  } // end method resendsACommandSentExactly2000MsBeforeTheLimitNotOneSent2001MsBefore

  @Test
  void resendsAPublishByteForByteWithItsOriginalIdAndOnlyOnce() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    channel.segment("chat").publish(bytes("x"), "original-id");
    runtime.run();
    List<String> original = socket.commands();
    assertEquals(List.of(publishFrame("chat", "original-id", "x")), original);
    socket.clearCommands();

    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));
    assertEquals(original, socket.commands());

    socket.clearCommands();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofSeconds(31));
    assertEquals(List.of(), socket.commands());
  } // end method resendsAPublishByteForByteWithItsOriginalIdAndOnlyOnce

  @Test
  void subscriptionToggledWhilePausedSendsOneCommand() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    runtime.receive(socket, rateLimitFrame());

    channel.segment("chat").subscribe().cancel();
    channel.segment("chat").subscribe();
    runtime.advance(Duration.ofSeconds(1));

    assertEquals(List.of(CHAT_SUBSCRIBE), socket.commands());
  } // end method subscriptionToggledWhilePausedSendsOneCommand

  @Test
  void backsOffOnConsecutiveRateLimitsAndStartsOverAfterAQuietWindow() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    runtime.random = 1;
    channel.segment("chat").subscribe();
    runtime.run();

    // 1 000 ms plus the reconnect delay for the streak index, which is capped at 30 000 ms. The
    // seventh limit is the first to reach the cap.
    for (long pause : new long[] {1500, 2000, 3000, 5000, 9000, 17000, 31000}) {
      socket.clearCommands();
      runtime.receive(socket, rateLimitFrame());
      runtime.advance(Duration.ofMillis(pause - 1));
      assertEquals(List.of(), socket.commands(), "resent before " + pause + " ms");

      runtime.advance(Duration.ofMillis(1));
      assertEquals(List.of(CHAT_SUBSCRIBE), socket.commands());
    }

    // Past the last pause and its suspect window, the streak starts over.
    runtime.advance(Duration.ofSeconds(40));
    channel.segment("lobby").subscribe();
    runtime.run();
    socket.clearCommands();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofMillis(1499));
    assertEquals(List.of(), socket.commands());

    runtime.advance(Duration.ofMillis(1));
    assertEquals(List.of(LOBBY_SUBSCRIBE), socket.commands());
  } // end method backsOffOnConsecutiveRateLimitsAndStartsOverAfterAQuietWindow

  @Test
  void resendsAtMostTheLast64Publishes() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    Segment lobby = channel.segment("lobby");
    List<String> expected = new ArrayList<>();

    for (int index = 0; index < 65; index++) {
      lobby.publish(bytes("x"), "m-" + index);
      runtime.run();

      if (index > 0) {
        expected.add(publishFrame("lobby", "m-" + index, "x"));
      }
    }

    socket.clearCommands();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));

    assertEquals(expected, socket.commands());
  } // end method resendsAtMostTheLast64Publishes

  @Test
  void restoredSubscriptionsAreResentAfterARateLimit() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    channel.segment("chat").subscribe();
    runtime.socket().drop();
    runtime.run();
    FakeWebSocket restored = runtime.socket();
    restored.clearCommands();

    runtime.receive(restored, rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));

    assertEquals(List.of(CHAT_SUBSCRIBE), restored.commands());
    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method restoredSubscriptionsAreResentAfterARateLimit

  // ---- What waits during a pause ----

  @Test
  void cancellingAQueuedPublishWithdrawsIt() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    runtime.receive(socket, rateLimitFrame());
    CompletableFuture<Void> queued = channel.segment("chat").publish(bytes("x"), "m-1");
    runtime.run();

    assertTrue(queued.cancel(true));
    runtime.run();
    assertTrue(queued.isCancelled());

    runtime.advance(Duration.ofSeconds(1));
    assertTrue(socket.commands().isEmpty());
  } // end method cancellingAQueuedPublishWithdrawsIt

  // QUEUE-01: a drop ends the pause, and the publishes it held wait for the next socket.
  @Test
  void publishesHeldByThePauseAreSentOnTheNextSocket() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    channel.segment("chat").subscribe();
    runtime.run();
    runtime.receive(runtime.socket(), rateLimitFrame());
    CompletableFuture<Void> queued = channel.segment("chat").publish(bytes("x"), "m-1");
    runtime.run();
    assertFalse(queued.isDone());

    runtime.socket().drop();
    runtime.run();

    assertEquals(2, runtime.sockets.size());
    assertEquals(
        List.of(CHAT_SUBSCRIBE, publishFrame("chat", "m-1", "x")), runtime.socket().commands());
    assertSucceeded(queued);
  } // end method publishesHeldByThePauseAreSentOnTheNextSocket

  @Test
  void presenceQueryWhilePausedIsBackpressure() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    runtime.receive(runtime.socket(), rateLimitFrame());

    CompletableFuture<PresencePage> query = channel.segment("chat").presenceList(1, 25);
    runtime.run();

    assertCode(query, ErrorCode.BACKPRESSURE);
    assertEquals(
        "Sending is paused after a rate limit; try again in a moment.",
        failureOf(query).getMessage());
  } // end method presenceQueryWhilePausedIsBackpressure

  @Test
  void subscriptionChangeNeverOvertakesAnEarlierPublishToItsSegment() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    runtime.receive(socket, rateLimitFrame());
    Segment chat = channel.segment("chat");
    Subscription subscription = chat.subscribe();
    CompletableFuture<Void> published = chat.publish(bytes("x"), "m-1");
    subscription.cancel();

    runtime.advance(Duration.ofSeconds(1));

    // Publishing joins the segment, so the UNSUB has to follow it.
    assertSucceeded(published);
    assertEquals(
        List.of(publishFrame("chat", "m-1", "x"), "@UNSUB\n$4\nchat\n"), socket.commands());
  } // end method subscriptionChangeNeverOvertakesAnEarlierPublishToItsSegment

  @Test
  void subscriptionChangeWaitsOnlyForPublishesQueuedBeforeIt() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    runtime.receive(socket, rateLimitFrame());
    Segment chat = channel.segment("chat");
    CompletableFuture<Void> first = chat.publish(bytes("1"), "m-1");
    chat.subscribe();
    CompletableFuture<Void> second = chat.publish(bytes("2"), "m-2");

    runtime.advance(Duration.ofSeconds(1));

    assertSucceeded(first);
    assertSucceeded(second);
    assertEquals(
        List.of(publishFrame("chat", "m-1", "1"), CHAT_SUBSCRIBE, publishFrame("chat", "m-2", "2")),
        socket.commands());
  } // end method subscriptionChangeWaitsOnlyForPublishesQueuedBeforeIt

  // ---- Episodes ----

  @Test
  void severalFramesForOneBurstCountOnce() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    channel.segment("chat").subscribe();
    runtime.run();

    // Eight episodes, each reported through two limit frames: still eight resend rounds, not a
    // give-up at four.
    for (int episode = 0; episode < 8; episode++) {
      socket.clearCommands();
      runtime.receive(socket, rateLimitFrame(), rateLimitFrame());
      runtime.advance(Duration.ofSeconds(1));
      assertEquals(List.of(CHAT_SUBSCRIBE), socket.commands(), "episode " + (episode + 1));
    }

    socket.clearCommands();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofMinutes(1).minusMillis(1));
    assertTrue(socket.commands().isEmpty());

    runtime.advance(Duration.ofMillis(1));
    assertEquals(List.of(CHAT_SUBSCRIBE), socket.commands());
  } // end method severalFramesForOneBurstCountOnce

  // ---- Quota probing ----

  @Test
  void dropsPublishesAndKeepsSubscriptionsForTheProbeAfterEightLimitsInARow() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    Segment lobby = channel.segment("lobby");
    channel.segment("chat").subscribe();
    runtime.run();

    for (int limit = 1; limit < 8; limit++) {
      runtime.receive(socket, rateLimitFrame());
      runtime.advance(Duration.ofSeconds(1));
    }

    // The eighth consecutive limit still resends.
    lobby.publish(bytes("x"), "m-8");
    runtime.run();
    socket.clearCommands();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));
    assertEquals(List.of(CHAT_SUBSCRIBE, publishFrame("lobby", "m-8", "x")), socket.commands());

    // The ninth resends nothing: the publish is dropped, the subscription waits for the probe.
    lobby.publish(bytes("y"), "m-9");
    runtime.run();
    socket.clearCommands();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofMinutes(1).minusMillis(1));
    assertEquals(List.of(), socket.commands());

    runtime.advance(Duration.ofMillis(1));
    assertEquals(List.of(CHAT_SUBSCRIBE), socket.commands());
  } // end method dropsPublishesAndKeepsSubscriptionsForTheProbeAfterEightLimitsInARow

  @Test
  void reSendsDroppedSubscriptionsOnASlowProbeThatDoublesAfterEachLimitUpToOneHour() {
    TestRuntime runtime = runtime();
    exhaustedChannel(runtime);
    FakeWebSocket socket = runtime.socket();

    for (long delay :
        new long[] {60_000, 120_000, 240_000, 480_000, 960_000, 1_920_000, 3_600_000, 3_600_000}) {
      socket.clearCommands();
      runtime.receive(socket, rateLimitFrame());
      runtime.advance(Duration.ofMillis(delay - 1));
      assertEquals(List.of(), socket.commands(), "probed before " + delay + " ms");

      runtime.advance(Duration.ofMillis(1));
      assertEquals(List.of(CHAT_SUBSCRIBE), socket.commands());
    }
  } // end method reSendsDroppedSubscriptionsOnASlowProbeThatDoublesAfterEachLimitUpToOneHour

  @Test
  void resendingReturnsOnceCommandsGoAWindowWithoutALimit() {
    TestRuntime runtime = runtime();
    Channel channel = exhaustedChannel(runtime);
    FakeWebSocket socket = runtime.socket();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofMinutes(1));

    runtime.advance(Duration.ofSeconds(3));
    channel.segment("lobby").subscribe();
    runtime.run();
    socket.clearCommands();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));

    assertEquals(List.of(LOBBY_SUBSCRIBE), socket.commands());
  } // end method resendingReturnsOnceCommandsGoAWindowWithoutALimit

  @Test
  void quotaProbingSurvivesAReconnect() {
    TestRuntime runtime = runtime();
    exhaustedChannel(runtime);
    runtime.receive(runtime.socket(), rateLimitFrame());

    runtime.socket().drop();
    runtime.run();
    FakeWebSocket restored = runtime.socket();
    assertEquals(List.of(CHAT_SUBSCRIBE), restored.commands());

    restored.clearCommands();
    runtime.receive(restored, rateLimitFrame());
    runtime.advance(Duration.ofMinutes(2).minusMillis(1));
    assertTrue(restored.commands().isEmpty());

    runtime.advance(Duration.ofMillis(1));
    assertEquals(List.of(CHAT_SUBSCRIBE), restored.commands());
  } // end method quotaProbingSurvivesAReconnect

  @Test
  void lateReportWhileProbingKeepsTheQuotaExhausted() {
    TestRuntime runtime = runtime();
    exhaustedChannel(runtime);
    FakeWebSocket socket = runtime.socket();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofMinutes(1));

    // The dropped probe's report lands past the suspect window but far inside the confirmation
    // span: probing continues, doubled.
    runtime.advance(Duration.ofMillis(2500));
    socket.clearCommands();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofMinutes(2).minusMillis(1));
    assertTrue(socket.commands().isEmpty());

    runtime.advance(Duration.ofMillis(1));
    assertEquals(List.of(CHAT_SUBSCRIBE), socket.commands());
  } // end method lateReportWhileProbingKeepsTheQuotaExhausted

  @Test
  void limitLongAfterAcceptedTrafficEndsProbing() {
    TestRuntime runtime = runtime();
    Channel channel = exhaustedChannel(runtime);
    FakeWebSocket socket = runtime.socket();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofMinutes(1));

    // The probe's frames were accepted; a limit far later is a new burst, handled with normal
    // resend rounds again.
    runtime.advance(Duration.ofSeconds(40));
    runtime.receive(socket, rateLimitFrame());
    channel.segment("lobby").subscribe();
    socket.clearCommands();
    runtime.advance(Duration.ofSeconds(1));
    assertEquals(List.of(LOBBY_SUBSCRIBE), socket.commands());

    runtime.receive(socket, rateLimitFrame());
    socket.clearCommands();
    runtime.advance(Duration.ofSeconds(1));
    assertEquals(List.of(LOBBY_SUBSCRIBE), socket.commands());
  } // end method limitLongAfterAcceptedTrafficEndsProbing

  @Test
  void sentPresenceQueryProvesTheQuotaReturned() {
    TestRuntime runtime = runtime();
    Channel channel = exhaustedChannel(runtime);
    FakeWebSocket socket = runtime.socket();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));

    channel.segment("chat").presenceList(1, 25);
    runtime.run();
    runtime.advance(Duration.ofMillis(2500));
    socket.clearCommands();
    channel.segment("lobby").subscribe();
    runtime.run();

    assertEquals(List.of(LOBBY_SUBSCRIBE, CHAT_SUBSCRIBE), socket.commands());
  } // end method sentPresenceQueryProvesTheQuotaReturned

  // ---- Refused writes ----

  @Test
  void refusedSubscriptionWriteReplacesTheSocket() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    TestRuntime.Recorder<ChannelState> states = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    channel.events().onStateChange(states);
    socket.failWrites();

    channel.segment("chat").subscribe();
    runtime.run();

    assertTrue(socket.isAborted());
    assertEquals(ChannelState.CONNECTED, channel.state());
    assertTrue(errors.all().isEmpty(), () -> "errors " + errors.all());
    assertEquals(List.of(ChannelState.RECONNECTING, ChannelState.CONNECTED), states.all());
    assertEquals(List.of(CHAT_SUBSCRIBE), runtime.socket().commands());
  } // end method refusedSubscriptionWriteReplacesTheSocket

  @Test
  void closeFlushesAPublishTheRateLimitRequeuedWhileTheWriterHeldIt() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    Segment chat = channel.segment("chat");
    socket.holdWrites();
    CompletableFuture<Void> writing = chat.publish(bytes("x"), "m-0");
    CompletableFuture<Void> waiting = chat.publish(bytes("y"), "m-1");
    runtime.run();

    // Both are in the writer, and the limit requeues both.
    runtime.receive(socket, rateLimitFrame());
    channel.closeAsync();
    runtime.run();
    socket.releaseWrites();
    runtime.run();

    // The closing connection flushed both, so neither was cancelled before it was sent.
    assertEquals(
        List.of(publishFrame("chat", "m-0", "x"), publishFrame("chat", "m-1", "y")),
        socket.commands());
    assertSucceeded(writing);
    assertSucceeded(waiting);
  } // end method closeFlushesAPublishTheRateLimitRequeuedWhileTheWriterHeldIt

  @Test
  void aDropMidWriteReportsDeliveryUnknownForAPublishTheRateLimitRequeued() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    socket.holdWrites();
    CompletableFuture<Void> writing = channel.segment("chat").publish(bytes("x"), "m-0");
    runtime.run();
    runtime.receive(socket, rateLimitFrame());

    // The JDK reports the server's close while the send is outstanding.
    var unused = channel.connection.onClose(socket, 1001, "");
    runtime.run();

    assertCode(writing, ErrorCode.DELIVERY_UNKNOWN);
  } // end method aDropMidWriteReportsDeliveryUnknownForAPublishTheRateLimitRequeued

  // ---- What a limit takes into account ----

  // Only subscriptions sent since the previous limit wait for the quota probe: one the server
  // accepted before the first limit of the streak is never probed.
  @Test
  void quotaProbeSkipsSubscriptionsAcceptedBeforeThePreviousLimit() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    channel.segment("chat").subscribe();
    runtime.run();
    runtime.advance(Duration.ofSeconds(3));

    // Eight limits, each with a publish sent since, so none is a repeat.
    for (int index = 0; index < 8; index++) {
      runtime.receive(socket, rateLimitFrame());
      runtime.advance(Duration.ofSeconds(1));
      channel.defaultSegment().publish(bytes("x"), "m-" + index);
      runtime.run();
    }

    socket.clearCommands();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofMinutes(2));

    assertTrue(socket.commands().isEmpty(), () -> "probed " + socket.commands());
  } // end method quotaProbeSkipsSubscriptionsAcceptedBeforeThePreviousLimit

  // A default-segment subscription needs no command, and what was queued behind it during a pause
  // still goes out when the pause ends.
  @Test
  void defaultSegmentSubscriptionQueuedDuringAPauseHoldsNothingBack() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    runtime.receive(socket, rateLimitFrame());
    channel.defaultSegment().subscribe();
    channel.segment("chat").subscribe();
    CompletableFuture<Void> published = channel.segment("lobby").publish(bytes("x"), "m-1");

    runtime.advance(Duration.ofSeconds(1));

    assertSucceeded(published);
    assertEquals(List.of(CHAT_SUBSCRIBE, publishFrame("lobby", "m-1", "x")), socket.commands());
  } // end method defaultSegmentSubscriptionQueuedDuringAPauseHoldsNothingBack

  // ---- What a reconnect forgets ----

  // A failed connection clears the queue: a subscription change still waiting when the socket
  // dropped is not sent to the next one, which restores interests as they stand.
  @Test
  void changeStillQueuedAtADropIsNotSentToTheNextSocket() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    channel.segment("beta").subscribe();
    runtime.receive(runtime.socket(), rateLimitFrame());
    channel.segment("chat").subscribe().cancel();

    runtime.socket().drop();
    runtime.run();

    assertEquals(2, runtime.sockets.size());
    assertEquals(List.of("@SUB\n$4\nbeta\n"), runtime.socket().commands());
  } // end method changeStillQueuedAtADropIsNotSentToTheNextSocket

  // A limit on a new socket resends nothing sent on the old one: those commands died with it, and
  // the reconnect restored every subscription itself.
  @Test
  void limitRightAfterAReconnectResendsNothingFromTheOldSocket() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    channel.segment("chat").subscribe().cancel();
    channel.defaultSegment().publish(bytes("x"), "m-1");
    runtime.run();
    assertEquals(3, runtime.socket().commands().size());

    runtime.socket().drop();
    runtime.run();
    FakeWebSocket restored = runtime.socket();
    runtime.receive(restored, rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));

    assertEquals(2, runtime.sockets.size());
    assertTrue(restored.commands().isEmpty(), () -> "resent " + restored.commands());
  } // end method limitRightAfterAReconnectResendsNothingFromTheOldSocket

  // A reconnect forgets what the quota probe was holding: the new socket restores every interest
  // itself, so a subscription cancelled before the drop is never probed afterwards. The probe
  // schedule itself survives, as the quota does.
  @Test
  void reconnectForgetsSubscriptionsWaitingForTheProbe() {
    TestRuntime runtime = runtime();
    Channel channel = runtime.connectedChannel();
    Subscription chat = channel.segment("chat").subscribe();
    runtime.run();
    exhaustRateLimit(runtime, runtime.socket());
    runtime.receive(runtime.socket(), rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));
    chat.cancel();
    runtime.run();
    assertEquals("@UNSUB\n$4\nchat\n", lastCommand(runtime.socket()));

    runtime.socket().drop();
    runtime.run();
    FakeWebSocket restored = runtime.socket();
    runtime.receive(restored, rateLimitFrame());
    runtime.advance(Duration.ofMinutes(3));

    assertEquals(2, runtime.sockets.size());
    assertTrue(restored.commands().isEmpty(), () -> "probed " + restored.commands());
  } // end method reconnectForgetsSubscriptionsWaitingForTheProbe

  private static String lastCommand(FakeWebSocket socket) {
    List<String> commands = socket.commands();

    return commands.get(commands.size() - 1);
  } // end method lastCommand
} // end class RateLimitTest

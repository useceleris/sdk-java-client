package com.useceleris.client;

import static com.useceleris.client.ReconnectionTest.expectAttemptAfter;
import static com.useceleris.client.TestRuntime.assertCode;
import static com.useceleris.client.TestRuntime.assertSucceeded;
import static com.useceleris.client.TestRuntime.bytes;
import static com.useceleris.client.TestRuntime.messageFrame;
import static com.useceleris.client.TestRuntime.presenceResponseFrame;
import static com.useceleris.client.TestRuntime.publishFrame;
import static com.useceleris.client.TestRuntime.rateLimitFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Boundary cases: each pins one comparison of the runtime to the side the reference takes. */
class RuntimeBoundariesTest {
  private static final String CHAT_SUBSCRIBE = "@SUB\n$4\nchat\n";

  private static TestRuntime pingingRuntime() {
    TestRuntime runtime = new TestRuntime();
    runtime.answerPings = true;

    return runtime;
  } // end method pingingRuntime

  // ---- Deduplication ----

  @Test
  void deduplicationWindowHoldsExactly1024Ids() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<String> delivered = new TestRuntime.Recorder<>();
    channel
        .segment("chat")
        .onMessage((payload, metadata) -> delivered.accept(metadata.messageId()));

    for (int index = 0; index < 1024; index++) {
      runtime.receive(socket, messageFrame("chat", "id-" + index, "x"));
    }

    // All 1024 are still remembered.
    runtime.receive(socket, messageFrame("chat", "id-0", "x"));
    assertEquals(1024, delivered.all().size(), "the repeat was delivered");

    // One more evicts the oldest.
    runtime.receive(
        socket, messageFrame("chat", "id-1024", "x"), messageFrame("chat", "id-0", "x"));
    assertEquals(1026, delivered.all().size(), "the evicted id was not delivered again");
  } // end method deduplicationWindowHoldsExactly1024Ids

  @Test
  void deduplicationWindowSizeBoundsTheWindow() {
    TestRuntime runtime = new TestRuntime(options -> options.deduplicationWindowSize(2));
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<String> delivered = new TestRuntime.Recorder<>();
    channel
        .segment("chat")
        .onMessage((payload, metadata) -> delivered.accept(metadata.messageId()));

    runtime.receive(
        socket,
        messageFrame("chat", "id-a", "x"),
        messageFrame("chat", "id-b", "x"),
        messageFrame("chat", "id-c", "x"));

    // The window holds the last two ids, so the newest replay is absorbed.
    runtime.receive(socket, messageFrame("chat", "id-c", "x"));
    assertEquals(3, delivered.all().size(), "the repeat inside the window was delivered");

    // The third id evicted the first, so its replay is delivered again.
    runtime.receive(socket, messageFrame("chat", "id-a", "x"));
    assertEquals(List.of("id-a", "id-b", "id-c", "id-a"), delivered.all());
  } // end method deduplicationWindowSizeBoundsTheWindow

  // ---- Command size ----

  @Test
  void publishOneByteOverTheCommandLimitIsRefused() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();

    // "@PUB\n$1\ns\n$1\nm\n$2097128\n" and the closing LF are 25 bytes.
    CompletableFuture<Void> refused =
        channel.segment("s").publish(new byte[Constants.MAXIMUM_COMMAND_BYTES - 24], "m");
    CompletableFuture<Void> accepted =
        channel.segment("s").publish(new byte[Constants.MAXIMUM_COMMAND_BYTES - 25], "m");
    runtime.run();

    assertCode(refused, ErrorCode.CONFIGURATION);
    assertSucceeded(accepted);
    assertEquals(1, runtime.socket().commands().size());
  } // end method publishOneByteOverTheCommandLimitIsRefused

  // ---- Presence ----

  @Test
  void presencePageBoundsAreInclusive() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();

    CompletableFuture<PresencePage> query = channel.segment("chat").presenceList(1, 100);
    runtime.run();

    assertEquals(List.of("@PRES_LIST\n$4\nchat\n;1\n;100\n$1\n1\n"), socket.commands());
    runtime.receive(socket, presenceResponseFrame("1"));
    assertSucceeded(query);
  } // end method presencePageBoundsAreInclusive

  // ---- Recovery ----

  @Test
  void retryBudgetResetsAtExactlySixtySecondsConnected() {
    TestRuntime runtime = pingingRuntime();
    runtime.connectedChannel();
    runtime.random = 0.5;
    runtime.failDials = 1;
    runtime.socket().drop();
    runtime.advance(Duration.ofMillis(250));
    FakeWebSocket socket = expectAttemptAfter(runtime, Duration.ofMillis(500));

    // One retry is spent; a millisecond short of a minute connected keeps it spent.
    runtime.advance(Duration.ofMinutes(1).minusMillis(1));
    socket.drop();
    socket = expectAttemptAfter(runtime, Duration.ofMillis(500));

    runtime.advance(Duration.ofMinutes(1));
    socket.drop();
    expectAttemptAfter(runtime, Duration.ofMillis(250));
  } // end method retryBudgetResetsAtExactlySixtySecondsConnected

  // ---- Rate limits ----

  // A command sent exactly two seconds before a limit is still a suspect, and recording a newer
  // command does not expire it early.
  @Test
  void rateLimitSuspectWindowIncludesItsEdge() {
    TestRuntime runtime = pingingRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    channel.segment("alpha").subscribe();
    channel.segment("lobby").publish(bytes("x"), "early");
    runtime.run();
    runtime.advance(Constants.RATE_LIMIT_SUSPECT_WINDOW);
    channel.segment("beta").subscribe();
    channel.segment("lobby").publish(bytes("y"), "late");
    runtime.run();
    socket.clearCommands();

    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));

    assertEquals(
        List.of(
            "@SUB\n$5\nalpha\n",
            "@SUB\n$4\nbeta\n",
            publishFrame("lobby", "early", "x"),
            publishFrame("lobby", "late", "y")),
        socket.commands());
  } // end method rateLimitSuspectWindowIncludesItsEdge

  @Test
  void rateLimitPauseEndsAtExactlyItsDelay() {
    TestRuntime runtime = pingingRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    runtime.receive(socket, rateLimitFrame());
    CompletableFuture<Void> queued = channel.defaultSegment().publish(bytes("x"), "m-1");

    runtime.advance(Constants.RATE_LIMIT_COOLDOWN.minusNanos(1));
    assertTrue(socket.commands().isEmpty());

    runtime.advance(Duration.ofNanos(1));
    assertSucceeded(queued);
  } // end method rateLimitPauseEndsAtExactlyItsDelay

  // With nothing to abandon, a quota limit schedules no probe, so the next probe still starts at a
  // minute.
  @Test
  void quotaLimitWithNothingToAbandonSchedulesNoProbe() {
    TestRuntime runtime = pingingRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();

    // Eight limits, each with a publish sent since, so none is a repeat.
    for (int index = 0; index < 8; index++) {
      runtime.receive(socket, rateLimitFrame());
      runtime.advance(Duration.ofSeconds(1));
      channel.defaultSegment().publish(bytes("x"), "m-" + index);
      runtime.run();
    }

    // The ninth is a quota limit, with no subscription sent to abandon.
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));

    channel.segment("chat").subscribe();
    runtime.run();
    socket.clearCommands();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofMinutes(1).minusMillis(1));
    assertTrue(socket.commands().isEmpty());

    runtime.advance(Duration.ofMillis(1));
    assertEquals(List.of(CHAT_SUBSCRIBE), socket.commands());
  } // end method quotaLimitWithNothingToAbandonSchedulesNoProbe

  // Probing ends only once commands went strictly longer than the quiet span without a limit. At
  // exactly two seconds after the probe the quota is not yet proven back, so the next limit still
  // abandons rather than resends.
  @Test
  void probingContinuesAtExactlyTheQuietSpan() {
    TestRuntime runtime = pingingRuntime();
    Channel channel = RateLimitTest.exhaustedChannel(runtime);
    FakeWebSocket socket = runtime.socket();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofMinutes(1));

    runtime.advance(Constants.RATE_LIMIT_SUSPECT_WINDOW);
    channel.segment("lobby").subscribe();
    runtime.run();
    socket.clearCommands();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));

    assertTrue(socket.commands().isEmpty(), () -> "resent " + socket.commands());
  } // end method probingContinuesAtExactlyTheQuietSpan

  @Test
  void probingEndsJustPastTheQuietSpan() {
    TestRuntime runtime = pingingRuntime();
    Channel channel = RateLimitTest.exhaustedChannel(runtime);
    FakeWebSocket socket = runtime.socket();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofMinutes(1));

    runtime.advance(Constants.RATE_LIMIT_SUSPECT_WINDOW.plusMillis(1));
    channel.segment("lobby").subscribe();
    runtime.run();
    socket.clearCommands();
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));

    assertEquals(List.of("@SUB\n$5\nlobby\n"), socket.commands());
  } // end method probingEndsJustPastTheQuietSpan

  // A limit landing exactly as the streak's window ends still continues the streak, as the
  // reference's strict comparison does, so its pause backs off further.
  @Test
  void rateLimitStreakContinuesAtExactlyTheEndOfItsWindow() {
    TestRuntime runtime = pingingRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    runtime.random = 1;
    channel.segment("chat").subscribe();
    runtime.run();

    // The first pause is 1.5 s, and the streak's window ends two seconds after it.
    runtime.receive(socket, rateLimitFrame());
    runtime.advance(Duration.ofMillis(3500));
    socket.clearCommands();
    runtime.receive(socket, rateLimitFrame());

    // The second limit in a row pauses for 2 s, rather than starting over at 1.5 s.
    runtime.advance(Duration.ofMillis(1999));
    assertTrue(socket.commands().isEmpty(), () -> "resent " + socket.commands());

    runtime.advance(Duration.ofMillis(1));
    assertEquals(List.of(CHAT_SUBSCRIBE), socket.commands());
  } // end method rateLimitStreakContinuesAtExactlyTheEndOfItsWindow

  // ---- Connecting ----

  // The handshake gets what remains of the connect deadline, but never less than a millisecond.
  @Test
  void handshakeTimeoutIsWhatRemainsButAtLeastOneMillisecond() {
    assertEquals(Duration.ofMillis(1), handshakeTimeoutWith(Duration.ofNanos(500_000)));
    assertEquals(Duration.ofNanos(1_500_000), handshakeTimeoutWith(Duration.ofNanos(1_500_000)));
  } // end method handshakeTimeoutIsWhatRemainsButAtLeastOneMillisecond

  /** The timeout the handshake gets when credentials arrive with remaining of the deadline left. */
  private static Duration handshakeTimeoutWith(Duration remaining) {
    TestRuntime runtime = new TestRuntime();
    runtime.blockProvider = true;
    CompletableFuture<Void> connected = runtime.channel().connect();
    runtime.advance(Constants.DEFAULT_CONNECT_TIMEOUT.minus(remaining));

    runtime.blockedProviders.get(0).complete(TestRuntime.CREDENTIALS);
    runtime.run();

    assertSucceeded(connected);
    assertEquals(1, runtime.dialTimeouts.size());

    return runtime.dialTimeouts.get(0);
  } // end method handshakeTimeoutWith
} // end class RuntimeBoundariesTest

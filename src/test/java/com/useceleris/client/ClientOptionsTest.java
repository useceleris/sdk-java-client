package com.useceleris.client;

import static com.useceleris.client.TestRuntime.assertCode;
import static com.useceleris.client.TestRuntime.assertSucceeded;
import static com.useceleris.client.TestRuntime.bytes;
import static com.useceleris.client.TestRuntime.failureOf;
import static com.useceleris.client.TestRuntime.messageFrame;
import static com.useceleris.client.TestRuntime.publishFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every option set through the public builder: how it is validated, and how a client built from it
 * behaves on the fake clock.
 */
class ClientOptionsTest {
  private static final CredentialProvider PROVIDER = request -> new CompletableFuture<>();

  private static final Duration LONGEST = Duration.ofMinutes(15);

  private static final String SCHEME =
      "Invalid connection URL. baseUrl must use wss://, or ws:// for a loopback host when"
          + " allowInsecureLoopback is true.";

  // ---- Validation ----

  @ParameterizedTest
  @ValueSource(strings = {"connectTimeout", "reconnectTimeout", "presenceQueryTimeout"})
  void eachTimeoutAcceptsOneMillisecondToFifteenMinutes(String field) {
    for (Duration accepted :
        List.of(Duration.ofMillis(1), Duration.ofNanos(1_500_000), Duration.ofMillis(900_000))) {
      ClientOptions.Builder builder = ClientOptions.builder(PROVIDER);
      setTimeout(builder, field, accepted);

      assertEquals(accepted, timeoutOf(builder.build(), field));
    }

    for (Duration tooShort :
        List.of(
            Duration.ZERO,
            Duration.ofMillis(-1),
            Duration.ofMillis(1).minusNanos(1),
            Duration.ofNanos(1))) {
      assertEquals(
          "Invalid client options. " + field + ": Must be at least 1 ms.",
          refusal(builder -> setTimeout(builder, field, tooShort)));
    }

    for (Duration tooLong :
        List.of(
            LONGEST.plusMillis(1),
            LONGEST.plusNanos(1),
            Duration.ofNanos(Long.MAX_VALUE),
            Duration.ofSeconds(Long.MAX_VALUE))) {
      assertEquals(
          "Invalid client options. " + field + ": Must be at most 15 minutes.",
          refusal(builder -> setTimeout(builder, field, tooLong)));
    }

    // A null reconnect timeout is the unset one, which follows the connect timeout.
    if (field.equals("reconnectTimeout")) {
      ClientOptions unset =
          ClientOptions.builder(PROVIDER)
              .connectTimeout(Duration.ofMillis(4000))
              .reconnectTimeout(null)
              .build();

      assertEquals(Duration.ofMillis(4000), unset.reconnectTimeout);
    } else {
      assertEquals(
          "Invalid client options. " + field + ": Required.",
          refusal(builder -> setTimeout(builder, field, null)));
    }
  } // end method eachTimeoutAcceptsOneMillisecondToFifteenMinutes

  @Test
  void sizesAcceptOneToTheLargestInt() {
    for (int accepted : new int[] {1, Integer.MAX_VALUE}) {
      ClientOptions options =
          ClientOptions.builder(PROVIDER)
              .publishQueueSize(accepted)
              .deduplicationWindowSize(accepted)
              .build();

      assertEquals(accepted, options.publishQueueSize);
      assertEquals(accepted, options.deduplicationWindowSize);
    }

    for (int refused : new int[] {0, -1, Integer.MIN_VALUE}) {
      assertEquals(
          "Invalid client options. publishQueueSize: Must be at least 1.",
          refusal(builder -> builder.publishQueueSize(refused)));
      assertEquals(
          "Invalid client options. deduplicationWindowSize: Must be at least 1.",
          refusal(builder -> builder.deduplicationWindowSize(refused)));
    }
  } // end method sizesAcceptOneToTheLargestInt

  @Test
  void maximumReconnectAttemptsAcceptsOneToOneHundred() {
    for (int accepted : new int[] {1, 100}) {
      ClientOptions options =
          ClientOptions.builder(PROVIDER).maximumReconnectAttempts(accepted).build();

      assertEquals(accepted, options.maximumReconnectAttempts);
    }

    for (int refused : new int[] {0, -1, Integer.MIN_VALUE}) {
      assertEquals(
          "Invalid client options. maximumReconnectAttempts: Must be at least 1.",
          refusal(builder -> builder.maximumReconnectAttempts(refused)));
    }

    for (int refused : new int[] {101, Integer.MAX_VALUE}) {
      assertEquals(
          "Invalid client options. maximumReconnectAttempts: Must be at most 100.",
          refusal(builder -> builder.maximumReconnectAttempts(refused)));
    }
  } // end method maximumReconnectAttemptsAcceptsOneToOneHundred

  @Test
  void requiredValuesAreNamed() {
    CelerisException missingProvider =
        assertThrows(CelerisException.class, () -> ClientOptions.builder(null).build());
    assertEquals(ErrorCode.CONFIGURATION, missingProvider.code());
    assertEquals(
        "Invalid client options. credentialProvider: Required.", missingProvider.getMessage());

    assertEquals(
        "Invalid client options. baseUrl: Required.", refusal(builder -> builder.baseUrl(null)));
    assertEquals(
        "Invalid connection URL. baseUrl is not an absolute URL.",
        refusal(builder -> builder.baseUrl("")));
  } // end method requiredValuesAreNamed

  @Test
  void everyFailureIsReportedInFieldOrder() {
    assertEquals(
        "Invalid client options. credentialProvider: Required. baseUrl: Required. connectTimeout:"
            + " Must be at least 1 ms. reconnectTimeout: Must be at most 15 minutes."
            + " presenceQueryTimeout: Required. publishQueueSize: Must be at least 1."
            + " deduplicationWindowSize: Must be at least 1. maximumReconnectAttempts: Must be at"
            + " most 100.",
        assertThrows(
                CelerisException.class,
                () ->
                    ClientOptions.builder(null)
                        .baseUrl(null)
                        .connectTimeout(Duration.ZERO)
                        .reconnectTimeout(LONGEST.plusMillis(1))
                        .presenceQueryTimeout(null)
                        .publishQueueSize(0)
                        .deduplicationWindowSize(-1)
                        .maximumReconnectAttempts(101)
                        .build())
            .getMessage());
  } // end method everyFailureIsReportedInFieldOrder

  // ---- Defaults ----

  // ENDPOINT-01: consumers do not configure where Celeris lives.
  @Test
  void theDefaultBaseUrlIsProduction() {
    CelerisClient client = CelerisClient.create(ClientOptions.builder(PROVIDER).build());

    assertEquals("wss://realtime.useceleris.com", client.baseUrl.toString());
  } // end method theDefaultBaseUrlIsProduction

  @Test
  void defaultTimeouts() {
    assertConnectTimesOutAt(new TestRuntime(), Duration.ofSeconds(15));
    assertPresenceQueryTimesOutAt(new TestRuntime(), Duration.ofSeconds(10));

    // Unset, the reconnect timeout is the connect timeout.
    TestRuntime reconnecting = new TestRuntime();
    reconnecting.connectedChannel();
    assertReconnectAttemptTimesOutAt(reconnecting, Duration.ofSeconds(15));
  } // end method defaultTimeouts

  @Test
  void defaultSizes() {
    assertPublishQueueHolds(new TestRuntime(), 64);
    assertDeduplicationWindowHolds(new TestRuntime(), 1024);
  } // end method defaultSizes

  // ---- Behaviour at other values ----

  @Test
  void connectTimeout() {
    assertConnectTimesOutAt(
        new TestRuntime(options -> options.connectTimeout(Duration.ofMillis(5000))),
        Duration.ofMillis(5000));
  } // end method connectTimeout

  @Test
  void reconnectTimeoutEqualToLongerThanOrShorterThanConnect() {
    List<List<Duration>> cases =
        List.of(
            List.of(Duration.ofMillis(2000), Duration.ofMillis(2000)),
            List.of(Duration.ofMillis(2000), Duration.ofMillis(7000)),
            List.of(Duration.ofMillis(7000), Duration.ofMillis(2000)));

    for (List<Duration> timeouts : cases) {
      TestRuntime runtime =
          new TestRuntime(
              options -> options.connectTimeout(timeouts.get(0)).reconnectTimeout(timeouts.get(1)));

      // The initial connect keeps the connect timeout.
      assertConnectTimesOutAt(runtime, timeouts.get(0));
      runtime.blockProvider = false;
      CompletableFuture<Void> connected = runtime.channel().connect();
      runtime.run();
      assertSucceeded(connected);

      assertReconnectAttemptTimesOutAt(runtime, timeouts.get(1));
    }
  } // end method reconnectTimeoutEqualToLongerThanOrShorterThanConnect

  @Test
  void unsetReconnectTimeoutFollowsAConfiguredConnectTimeout() {
    TestRuntime runtime =
        new TestRuntime(options -> options.connectTimeout(Duration.ofMillis(4000)));
    runtime.connectedChannel();

    assertReconnectAttemptTimesOutAt(runtime, Duration.ofMillis(4000));
  } // end method unsetReconnectTimeoutFollowsAConfiguredConnectTimeout

  @Test
  void presenceQueryTimeout() {
    assertPresenceQueryTimesOutAt(
        new TestRuntime(options -> options.presenceQueryTimeout(Duration.ofMillis(2000))),
        Duration.ofMillis(2000));
  } // end method presenceQueryTimeout

  @Test
  void theLongestTimeoutsStillTimeOutExactly() {
    assertConnectTimesOutAt(new TestRuntime(options -> options.connectTimeout(LONGEST)), LONGEST);
    assertPresenceQueryTimesOutAt(
        new TestRuntime(options -> options.presenceQueryTimeout(LONGEST)), LONGEST);

    TestRuntime reconnecting = new TestRuntime(options -> options.reconnectTimeout(LONGEST));
    reconnecting.connectedChannel();
    assertReconnectAttemptTimesOutAt(reconnecting, LONGEST);
  } // end method theLongestTimeoutsStillTimeOutExactly

  @Test
  void publishQueueSizeOfOne() {
    assertPublishQueueHolds(new TestRuntime(options -> options.publishQueueSize(1)), 1);
  } // end method publishQueueSizeOfOne

  @Test
  void deduplicationWindowSizeOfOne() {
    assertDeduplicationWindowHolds(
        new TestRuntime(options -> options.deduplicationWindowSize(1)), 1);
  } // end method deduplicationWindowSizeOfOne

  @Test
  void aMaximumOfOneReconnectAttemptFailsOnTheFirstFailure() {
    Outage outage = connectAndDrop(1, 1, false);

    outage.expectFailedOnce();
    assertEquals(1, outage.reconnectRequests());

    outage.runtime().advance(Duration.ofMinutes(1));
    assertEquals(1, outage.reconnectRequests());
  } // end method aMaximumOfOneReconnectAttemptFailsOnTheFirstFailure

  @Test
  void aMaximumOfThreeReconnectAttemptsFailsAfterExactlyThree() {
    Outage outage = connectAndDrop(3, 2, true);

    assertEquals(ChannelState.RECONNECTING, outage.channel().state());
    assertEquals(List.of(), outage.errors().all());
    assertEquals(3, outage.reconnectRequests());

    outage.refuseBlockedDial();
    outage.expectFailedOnce();
    assertEquals(3, outage.reconnectRequests());
  } // end method aMaximumOfThreeReconnectAttemptsFailsAfterExactlyThree

  @Test
  void theDefaultMaximumIsTenReconnectAttempts() {
    Outage outage = connectAndDrop(null, 9, true);

    assertEquals(ChannelState.RECONNECTING, outage.channel().state());
    assertEquals(10, outage.reconnectRequests());

    outage.refuseBlockedDial();
    outage.expectFailedOnce();
    assertEquals(10, outage.reconnectRequests());
  } // end method theDefaultMaximumIsTenReconnectAttempts

  @Test
  void aMaximumOfOneHundredKeepsReconnectingPastTen() {
    Outage outage = connectAndDrop(100, 10, true);

    assertEquals(ChannelState.RECONNECTING, outage.channel().state());
    assertEquals(List.of(), outage.errors().all());
    assertEquals(11, outage.reconnectRequests());
  } // end method aMaximumOfOneHundredKeepsReconnectingPastTen

  @Test
  void anOutageWithinSixtySecondsOfRecoveryKeepsTheSpentAttempts() {
    Outage outage = connectAndDrop(2, 1, false);
    TestRuntime runtime = outage.runtime();
    assertEquals(ChannelState.CONNECTED, outage.channel().state());

    runtime.advance(Duration.ofSeconds(59));
    runtime.failDials = 1;
    runtime.socket().drop();
    runtime.run();

    outage.expectFailedOnce();
    assertEquals(3, outage.reconnectRequests());
  } // end method anOutageWithinSixtySecondsOfRecoveryKeepsTheSpentAttempts

  @Test
  void sixtySecondsConnectedAllowsTheFullMaximumAgain() {
    Outage outage = connectAndDrop(2, 1, false);
    TestRuntime runtime = outage.runtime();
    assertEquals(ChannelState.CONNECTED, outage.channel().state());

    runtime.advance(Duration.ofSeconds(60));
    runtime.failDials = 1;
    runtime.blockDials = true;
    runtime.socket().drop();
    runtime.run();
    assertEquals(ChannelState.RECONNECTING, outage.channel().state());

    outage.refuseBlockedDial();
    outage.expectFailedOnce();
    assertEquals(4, outage.reconnectRequests());
  } // end method sixtySecondsConnectedAllowsTheFullMaximumAgain

  @Test
  void loopbackNeedsTheOptIn() {
    for (Consumer<ClientOptions.Builder> refused :
        List.<Consumer<ClientOptions.Builder>>of(
            options -> options.baseUrl("ws://localhost:8080"),
            options -> options.baseUrl("ws://localhost:8080").allowInsecureLoopback(false),
            options -> options.baseUrl("ws://example.test"),
            options -> options.baseUrl("ws://example.test").allowInsecureLoopback(true))) {
      CelerisException failure =
          assertThrows(CelerisException.class, () -> new TestRuntime(refused));
      assertEquals(ErrorCode.CONFIGURATION, failure.code());
      assertEquals(SCHEME, failure.getMessage());
    }

    TestRuntime runtime =
        new TestRuntime(
            options -> options.baseUrl("ws://localhost:8080").allowInsecureLoopback(true));
    runtime.connectedChannel();

    assertEquals(
        "ws://localhost:8080/channel/room-1?payload=payload-1&signature=signature-1",
        runtime.socket().url.toString());
  } // end method loopbackNeedsTheOptIn

  // ---- Helpers ----

  /** A connected channel whose connection dropped, with what it reported since. */
  private record Outage(
      TestRuntime runtime,
      Channel channel,
      TestRuntime.Recorder<RuntimeException> errors,
      TestRuntime.Recorder<ChannelState> states) {
    long reconnectRequests() {
      return runtime.credentialRequests.stream().filter(CredentialRequest::reconnect).count();
    } // end method reconnectRequests

    /** Fails the reconnect attempt waiting on its handshake as a refused one. */
    void refuseBlockedDial() {
      List<CompletableFuture<WebSocket>> blocked = runtime.blockedDials;
      blocked.get(blocked.size() - 1).completeExceptionally(new IOException("refused"));
      runtime.run();
    } // end method refuseBlockedDial

    void expectFailedOnce() {
      assertEquals(ChannelState.FAILED, channel.state());
      assertEquals(1, errors.all().size());
      assertCode(errors.all().get(0), ErrorCode.TRANSPORT);
      List<ChannelState> all = states.all();
      assertEquals(
          List.of(ChannelState.RECONNECTING, ChannelState.FAILED),
          all.subList(all.size() - 2, all.size()));
    } // end method expectFailedOnce
  } // end record Outage

  /**
   * Connects with the maximum (null for the default), then drops the connection so that the next
   * refusedAttempts reconnect attempts are refused; with blockAfter, the attempt after them waits
   * on its handshake.
   */
  private static Outage connectAndDrop(
      @Nullable Integer maximum, int refusedAttempts, boolean blockAfter) {
    TestRuntime runtime =
        new TestRuntime(
            options -> {
              if (maximum != null) {
                options.maximumReconnectAttempts(maximum);
              }
            });

    runtime.answerPings = true;
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    TestRuntime.Recorder<ChannelState> states = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    channel.events().onStateChange(states);

    runtime.failDials = refusedAttempts;
    runtime.blockDials = blockAfter;
    runtime.socket().drop();
    runtime.run();

    return new Outage(runtime, channel, errors, states);
  } // end method connectAndDrop

  private static String refusal(Consumer<ClientOptions.Builder> configure) {
    ClientOptions.Builder builder = ClientOptions.builder(PROVIDER);
    configure.accept(builder);
    CelerisException failure = assertThrows(CelerisException.class, builder::build);
    assertEquals(ErrorCode.CONFIGURATION, failure.code());

    return failure.getMessage();
  } // end method refusal

  private static void setTimeout(
      ClientOptions.Builder builder, String field, @Nullable Duration timeout) {
    switch (field) {
      case "connectTimeout" -> builder.connectTimeout(timeout);
      case "reconnectTimeout" -> builder.reconnectTimeout(timeout);
      default -> builder.presenceQueryTimeout(timeout);
    }
  } // end method setTimeout

  private static Duration timeoutOf(ClientOptions options, String field) {
    return switch (field) {
      case "connectTimeout" -> options.connectTimeout;
      case "reconnectTimeout" -> options.reconnectTimeout;
      default -> options.presenceQueryTimeout;
    };
  } // end method timeoutOf

  /** Checks a connect is pending a millisecond before the timeout and fails at it. */
  private static void assertConnectTimesOutAt(TestRuntime runtime, Duration timeout) {
    runtime.blockProvider = true;
    CompletableFuture<Void> connected = runtime.channel().connect();
    runtime.advance(timeout.minusMillis(1));
    assertFalse(connected.isDone(), "settled before its deadline");

    runtime.advance(Duration.ofMillis(1));

    assertCode(connected, ErrorCode.TIMEOUT);
    assertEquals(
        "Connection attempt timed out after " + timeout.toMillis() + " ms.",
        failureOf(connected).getMessage());
  } // end method assertConnectTimesOutAt

  /** Drops the connection and checks the first reconnect attempt runs on the timeout. */
  private static void assertReconnectAttemptTimesOutAt(TestRuntime runtime, Duration timeout) {
    runtime.blockDials = true;
    int earlierAttempts = runtime.blockedDials.size();
    runtime.socket().drop();
    runtime.advance(timeout.minusMillis(1));

    assertEquals(earlierAttempts + 1, runtime.blockedDials.size());
    CompletableFuture<WebSocket> attempt = runtime.blockedDials.get(earlierAttempts);
    assertFalse(attempt.isCancelled(), "timed out before its deadline");
    assertEquals(timeout, runtime.dialTimeouts.get(runtime.dialTimeouts.size() - 1));

    runtime.advance(Duration.ofMillis(1));

    assertTrue(attempt.isCancelled(), "still pending at its deadline");
    assertEquals(earlierAttempts + 2, runtime.blockedDials.size());
  } // end method assertReconnectAttemptTimesOutAt

  /** Checks a query is pending a millisecond before the timeout and fails at it alone. */
  private static void assertPresenceQueryTimesOutAt(TestRuntime runtime, Duration timeout) {
    runtime.answerPings = true;
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    CompletableFuture<PresencePage> query = channel.segment("chat").presenceList(1, 25);
    runtime.advance(timeout.minusMillis(1));
    assertFalse(query.isDone(), "settled before its deadline");

    runtime.advance(Duration.ofMillis(1));

    assertCode(query, ErrorCode.TIMEOUT);
    assertEquals(
        "Presence query timed out after " + timeout.toMillis() + " ms.",
        failureOf(query).getMessage());
    assertEquals(ChannelState.CONNECTED, channel.state());
    assertFalse(socket.isAborted());
    assertEquals(1, runtime.sockets.size());
  } // end method assertPresenceQueryTimesOutAt

  /**
   * Fills the writer, then checks that exactly size publishes wait behind it, the next is refused,
   * and the waiting ones go out in order once the writer drains.
   */
  private static void assertPublishQueueHolds(TestRuntime runtime, int size) {
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    Segment lobby = channel.defaultSegment();
    socket.holdWrites();
    int accepted = Constants.MAXIMUM_PENDING_COMMANDS + size;
    List<CompletableFuture<Void>> publishes = new ArrayList<>();
    List<String> expected = new ArrayList<>();

    for (int index = 0; index < accepted; index++) {
      publishes.add(lobby.publish(bytes("x"), "m-" + index));
      expected.add(publishFrame("default", "m-" + index, "x"));
    }

    CompletableFuture<Void> refused = lobby.publish(bytes("x"), "refused");
    runtime.run();

    assertCode(refused, ErrorCode.BACKPRESSURE);
    assertEquals(
        "The publish queue is full (size " + size + "). Retry once some publishes have gone out.",
        failureOf(refused).getMessage());
    assertTrue(publishes.stream().noneMatch(CompletableFuture::isDone));

    socket.releaseWrites();
    runtime.run();

    publishes.forEach(TestRuntime::assertSucceeded);
    assertEquals(expected, socket.commands());
    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method assertPublishQueueHolds

  /**
   * Delivers size + 1 distinct ids, then checks the newest repeat is absorbed and the first id,
   * evicted, is delivered again.
   */
  private static void assertDeduplicationWindowHolds(TestRuntime runtime, int size) {
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<String> delivered = new TestRuntime.Recorder<>();
    channel
        .segment("chat")
        .onMessage((payload, metadata) -> delivered.accept(metadata.messageId()));

    for (int index = 0; index <= size; index++) {
      runtime.receive(socket, messageFrame("chat", "id-" + index, "x"));
    }

    runtime.receive(socket, messageFrame("chat", "id-" + size, "x"));
    assertEquals(size + 1, delivered.all().size(), "the repeat inside the window was delivered");

    runtime.receive(socket, messageFrame("chat", "id-0", "x"));
    assertEquals(size + 2, delivered.all().size(), "the evicted id was absorbed");
    assertEquals("id-0", delivered.all().get(size + 1));
  } // end method assertDeduplicationWindowHolds
} // end class ClientOptionsTest

package com.useceleris.client;

import static com.useceleris.client.TestRuntime.assertCode;
import static com.useceleris.client.TestRuntime.assertSucceeded;
import static com.useceleris.client.TestRuntime.failureOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class LifecycleTest {
  @Test
  void connectMovesThroughConnectingToConnected() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.channel();
    TestRuntime.Recorder<ChannelState> states = new TestRuntime.Recorder<>();
    channel.events().onStateChange(states);
    assertEquals(ChannelState.IDLE, channel.state());

    CompletableFuture<Void> connected = channel.connect();
    runtime.run();

    assertSucceeded(connected);
    assertEquals(ChannelState.CONNECTED, channel.state());
    assertEquals(List.of(ChannelState.CONNECTING, ChannelState.CONNECTED), states.all());
  } // end method connectMovesThroughConnectingToConnected

  @Test
  void constructionDoesNoNetworkWork() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.channel();
    Channel other = runtime.channel();
    channel.segment("chat");
    channel.defaultSegment();
    runtime.run();

    assertNotSame(channel, other);
    assertTrue(runtime.sockets.isEmpty());
    assertTrue(runtime.credentialRequests.isEmpty());
    assertEquals(0, runtime.timers.pendingCount());
  } // end method constructionDoesNoNetworkWork

  @Test
  void concurrentConnectIsRejected() {
    TestRuntime runtime = new TestRuntime();
    runtime.blockDials = true;
    Channel channel = runtime.channel();
    channel.connect();
    runtime.run();

    CompletableFuture<Void> second = channel.connect();
    runtime.run();
    assertCode(second, ErrorCode.OPERATION_IN_PROGRESS);

    TestRuntime other = new TestRuntime();
    Channel connected = other.connectedChannel();
    CompletableFuture<Void> again = connected.connect();
    other.run();
    assertCode(again, ErrorCode.OPERATION_IN_PROGRESS);
    assertEquals(
        "connect() was already called; the channel is connected.", failureOf(again).getMessage());
  } // end method concurrentConnectIsRejected

  @Test
  void connectAfterCloseIsRejected() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.channel();
    channel.closeAsync();
    runtime.run();

    CompletableFuture<Void> connected = channel.connect();
    runtime.run();

    assertCode(connected, ErrorCode.NOT_CONNECTED);
    assertEquals(ChannelState.CLOSED, channel.state());
  } // end method connectAfterCloseIsRejected

  @Test
  void initialFailureIsReportedOnceToTheCaller() {
    TestRuntime runtime = new TestRuntime();
    runtime.failProvider = true;
    Channel channel = runtime.channel();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);

    CompletableFuture<Void> connected = channel.connect();
    runtime.run();

    assertCode(connected, ErrorCode.TRANSPORT);
    Throwable failure = failureOf(connected);
    assertEquals(
        "Credential acquisition failed: the credential provider threw or rejected.",
        failure.getMessage());
    assertEquals(null, failure.getCause());
    assertEquals(ChannelState.FAILED, channel.state());
    assertTrue(errors.all().isEmpty());

    // An explicit connect starts over from failed.
    runtime.failProvider = false;
    CompletableFuture<Void> restarted = channel.connect();
    runtime.run();
    assertSucceeded(restarted);
    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method initialFailureIsReportedOnceToTheCaller

  @Test
  void handshakeFailureNeverQuotesTheCredentialUrl() {
    TestRuntime runtime = new TestRuntime();
    runtime.failDials = 1;
    Channel channel = runtime.channel();

    CompletableFuture<Void> connected = channel.connect();
    runtime.run();

    assertCode(connected, ErrorCode.TRANSPORT);
    String message = failureOf(connected).getMessage();
    assertFalse(message.contains("payload-1") || message.contains("signature-1"), message);
    assertEquals(null, failureOf(connected).getCause());
  } // end method handshakeFailureNeverQuotesTheCredentialUrl

  @Test
  void cancellingConnectAbandonsTheAttempt() {
    TestRuntime runtime = new TestRuntime();
    runtime.blockProvider = true;
    Channel channel = runtime.channel();
    CompletableFuture<Void> connected = channel.connect();
    runtime.run();

    assertTrue(connected.cancel(true));
    runtime.run();

    assertInstanceOf(CancellationException.class, failureOf(connected));
    assertEquals(ChannelState.FAILED, channel.state());
    assertTrue(runtime.sockets.isEmpty());
    // The provider's stage is cancelled with the attempt.
    assertTrue(runtime.blockedProviders.get(0).isCancelled());
  } // end method cancellingConnectAbandonsTheAttempt

  @Test
  void connectDeadlineCoversCredentialsAndHandshake() {
    TestRuntime runtime = new TestRuntime(options -> options.connectTimeout(Duration.ofSeconds(5)));
    runtime.blockProvider = true;
    Channel channel = runtime.channel();
    long start = runtime.timers.monotonicNanos();

    CompletableFuture<Void> connected = channel.connect();
    runtime.advance(Duration.ofSeconds(4));
    assertFalse(connected.isDone());
    runtime.advance(Duration.ofSeconds(1));

    assertCode(connected, ErrorCode.TIMEOUT);
    assertEquals(Duration.ofSeconds(5).toNanos(), runtime.timers.monotonicNanos() - start);
    assertEquals("Connection attempt timed out after 5000 ms.", failureOf(connected).getMessage());

    runtime.blockProvider = false;
    runtime.blockDials = true;
    CompletableFuture<Void> again = channel.connect();
    runtime.advance(Duration.ofSeconds(5));

    assertCode(again, ErrorCode.TIMEOUT);
    assertEquals(ChannelState.FAILED, channel.state());
    assertTrue(runtime.blockedDials.get(0).isCancelled());
  } // end method connectDeadlineCoversCredentialsAndHandshake

  @Test
  void callerTimeoutAbandonsTheAttempt() {
    TestRuntime runtime = new TestRuntime();
    runtime.blockProvider = true;
    Channel channel = runtime.channel();
    CompletableFuture<Void> connected = channel.connect();
    runtime.run();

    connected.completeExceptionally(new java.util.concurrent.TimeoutException());
    runtime.run();

    assertEquals(ChannelState.FAILED, channel.state());
    assertTrue(runtime.blockedProviders.get(0).isCancelled());
  } // end method callerTimeoutAbandonsTheAttempt

  @Test
  void lateCredentialsAreDiscardedAndOpenNothing() {
    TestRuntime runtime = new TestRuntime();
    runtime.blockProvider = true;
    Channel channel = runtime.channel();
    CompletableFuture<Void> connected = channel.connect();
    runtime.advance(Duration.ofSeconds(15));
    assertCode(connected, ErrorCode.TIMEOUT);

    // A provider that ignores cancellation completes anyway; its result is discarded.
    runtime.blockedProviders.get(0).obtrudeValue(TestRuntime.CREDENTIALS);
    runtime.run();

    assertTrue(runtime.sockets.isEmpty());
    assertEquals(ChannelState.FAILED, channel.state());
  } // end method lateCredentialsAreDiscardedAndOpenNothing

  @Test
  void providerThatThrowsIsATransportFailure() {
    TestRuntime runtime = new TestRuntime();
    CelerisClient client =
        new CelerisClient(
            ClientOptions.builder(
                    request -> {
                      throw new IllegalStateException("synthetic-secret");
                    })
                .baseUrl("wss://example.test/")
                .build(),
            (url, timeout, listener) -> new CompletableFuture<>(),
            runtime.timers,
            runtime.executor,
            () -> 0);
    Channel channel = client.channel("room-1");

    CompletableFuture<Void> connected = channel.connect();
    runtime.run();

    assertCode(connected, ErrorCode.TRANSPORT);
    assertFalse(failureOf(connected).getMessage().contains("secret"));
  } // end method providerThatThrowsIsATransportFailure

  @Test
  void providerReturningNullIsATransportFailure() {
    TestRuntime runtime = new TestRuntime();
    CelerisClient client =
        new CelerisClient(
            ClientOptions.builder(request -> null).baseUrl("wss://example.test/").build(),
            (url, timeout, listener) -> new CompletableFuture<>(),
            runtime.timers,
            runtime.executor,
            () -> 0);

    CompletableFuture<Void> connected = client.channel("room-1").connect();
    runtime.run();

    assertCode(connected, ErrorCode.TRANSPORT);
  } // end method providerReturningNullIsATransportFailure

  @Test
  void invalidCredentialsAreAConfigurationError() {
    TestRuntime runtime = new TestRuntime();
    runtime.credentials = new Credentials("secret-payload", "");
    Channel channel = runtime.channel();

    CompletableFuture<Void> connected = channel.connect();
    runtime.run();

    assertCode(connected, ErrorCode.CONFIGURATION);
    assertEquals(
        "Invalid credentials. signature: Must not be empty.", failureOf(connected).getMessage());

    runtime.credentials = null;
    CompletableFuture<Void> again = channel.connect();
    runtime.run();
    assertEquals("Invalid credentials. Must not be null.", failureOf(again).getMessage());

    runtime.credentials = new Credentials(null, "\ud800");
    CompletableFuture<Void> third = channel.connect();
    runtime.run();
    assertEquals(
        "Invalid credentials. payload: Required. signature: Must not contain unpaired UTF-16"
            + " surrogates.",
        failureOf(third).getMessage());
  } // end method invalidCredentialsAreAConfigurationError

  @Test
  void initialCredentialRequestCarriesNoOutage() {
    TestRuntime runtime = new TestRuntime();
    runtime.connectedChannel();

    assertEquals(
        List.of(new CredentialRequest("room-1", false, Optional.empty(), Optional.empty())),
        runtime.credentialRequests);
    assertEquals(
        "wss://example.test/channel/room-1?payload=payload-1&signature=signature-1",
        runtime.socket().url.toString());
  } // end method initialCredentialRequestCarriesNoOutage

  @Test
  void stateListenersRunInOrderAndRemoveCleanly() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.channel();
    TestRuntime.Recorder<String> order = new TestRuntime.Recorder<>();
    Registration first = channel.events().onStateChange(state -> order.accept("first"));
    channel.events().onStateChange(state -> order.accept("second"));
    channel.connect();
    runtime.run();

    first.close();
    first.close();
    channel.closeAsync();
    runtime.run();

    assertEquals(List.of("first", "second", "first", "second", "second", "second"), order.all());
  } // end method stateListenersRunInOrderAndRemoveCleanly

  @Test
  void listenerRemovedBeforeItsTurnIsSkipped() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.channel();
    TestRuntime.Recorder<String> order = new TestRuntime.Recorder<>();
    Registration[] second = new Registration[1];
    channel
        .events()
        .onStateChange(
            state -> {
              order.accept("first");
              second[0].close();
            });

    second[0] = channel.events().onStateChange(state -> order.accept("second"));
    java.util.function.Consumer<ChannelState> shared = state -> order.accept("shared");
    channel.events().onStateChange(shared);
    Registration duplicate = channel.events().onStateChange(shared);

    channel.connect();
    runtime.run();
    duplicate.close();
    channel.closeAsync();
    runtime.run();

    assertEquals(
        List.of(
            "first", "shared", "shared", "first", "shared", "shared", "first", "shared", "first",
            "shared"),
        order.all());
  } // end method listenerRemovedBeforeItsTurnIsSkipped

  @Test
  void throwingListenersAreContainedAndReportedOnce() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.channel();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    TestRuntime.Recorder<String> order = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    channel
        .events()
        .onError(
            error -> {
              throw new IllegalStateException("error-listener-secret");
            });

    channel
        .events()
        .onStateChange(
            state -> {
              if (state == ChannelState.CONNECTING) {
                throw new IllegalStateException("listener-secret");
              }
            });

    channel.events().onStateChange(state -> order.accept("after " + state));

    channel.connect();
    runtime.run();

    assertEquals(List.of("after connecting", "after connected"), order.all());
    assertEquals(1, errors.all().size());
    assertCode(errors.all().get(0), ErrorCode.TRANSPORT);
    assertEquals(
        "A listener callback threw; the channel caught the error and kept running.",
        errors.all().get(0).getMessage());
  } // end method throwingListenersAreContainedAndReportedOnce

  @Test
  void nullListenersAreRefused() {
    Channel channel = new TestRuntime().channel();

    assertThrows(NullPointerException.class, () -> channel.events().onError(null));
    assertThrows(NullPointerException.class, () -> channel.defaultSegment().onMessage(null));
  } // end method nullListenersAreRefused

  @Test
  void channelReferencesAreValidated() {
    CelerisClient client = new TestRuntime().client;
    List<List<String>> cases =
        List.of(
            List.of("", "Invalid channel reference. Must not be empty."),
            List.of(
                "bad ref!",
                "Invalid channel reference. Must contain only ASCII letters, digits, hyphens (-) or"
                    + " underscores (_)."),
            List.of("a".repeat(256), "Invalid channel reference. Must be at most 255 characters."),
            List.of(
                "room/../../other-secret",
                "Invalid channel reference. Must contain only ASCII letters, digits, hyphens (-) or"
                    + " underscores (_)."));

    for (List<String> test : cases) {
      CelerisException failure =
          assertThrows(CelerisException.class, () -> client.channel(test.get(0)));
      assertEquals(ErrorCode.CONFIGURATION, failure.code());
      assertEquals(test.get(1), failure.getMessage());
    }

    client.channel("a".repeat(255));
  } // end method channelReferencesAreValidated

  @Test
  void segmentIdsAreValidated() {
    Channel channel = new TestRuntime().channel();

    assertEquals(
        "Invalid segment ID. Must not be empty.",
        assertThrows(CelerisException.class, () -> channel.segment("")).getMessage());

    for (String identifier : List.of("a\n", "a\r", "\ud800", "a\udfff", "\ud83d")) {
      assertEquals(
          "Invalid segment ID. Must not contain CR, LF or unpaired UTF-16 surrogates.",
          assertThrows(CelerisException.class, () -> channel.segment(identifier)).getMessage());
    }

    assertEquals("default", channel.defaultSegment().segmentId());
    assertEquals("chat 😀", channel.segment("chat 😀").segmentId());
  } // end method segmentIdsAreValidated

  @Test
  void aSocketThatFailsBeforeItIsInstalledFailsTheAttempt() {
    TestRuntime runtime = new TestRuntime();
    runtime.onOpen = FakeWebSocket::drop;
    Channel channel = runtime.channel();

    CompletableFuture<Void> connected = channel.connect();
    runtime.run();

    assertCode(connected, ErrorCode.TRANSPORT);
    assertEquals(ChannelState.FAILED, channel.state());
  } // end method aSocketThatFailsBeforeItIsInstalledFailsTheAttempt

  // ---- Attempts ----

  /** A client on the runtime's executor and clock, with its own provider and dialer. */
  private static CelerisClient client(
      TestRuntime runtime, CredentialProvider provider, Dialer dialer) {
    return new CelerisClient(
        ClientOptions.builder(provider).baseUrl("wss://example.test/").build(),
        dialer,
        runtime.timers,
        runtime.executor,
        () -> 0);
  } // end method client

  // As in the reference, connecting is reported before credentials are requested.
  @Test
  void connectingIsDeliveredBeforeCredentialsAreRequested() {
    TestRuntime runtime = new TestRuntime();
    TestRuntime.Recorder<String> log = new TestRuntime.Recorder<>();
    Channel channel =
        client(
                runtime,
                request -> {
                  log.accept("credentials");

                  return new CompletableFuture<>();
                },
                (url, timeout, listener) -> new CompletableFuture<>())
            .channel("room-1");

    channel.events().onStateChange(state -> log.accept(state.toString()));

    channel.connect();
    runtime.run();

    assertEquals(List.of("connecting", "credentials"), log.all());
  } // end method connectingIsDeliveredBeforeCredentialsAreRequested

  // An attempt cancelled before it starts never asks the provider, whether connect is called again
  // or the channel is closed.
  @Test
  void attemptCancelledBeforeItStartsNeverAsksTheProvider() {
    TestRuntime restarting = new TestRuntime();
    Channel channel = restarting.channel();
    assertTrue(channel.connect().cancel(true));
    CompletableFuture<Void> again = channel.connect();
    restarting.run();

    assertSucceeded(again);
    assertEquals(1, restarting.credentialRequests.size());

    TestRuntime closing = new TestRuntime();
    Channel closed = closing.channel();
    assertTrue(closed.connect().cancel(true));
    closed.closeAsync();
    closing.run();

    assertTrue(closing.credentialRequests.isEmpty());
    assertEquals(ChannelState.CLOSED, closed.state());
    assertEquals(0, closing.timers.pendingCount());
  } // end method attemptCancelledBeforeItStartsNeverAsksTheProvider

  @Test
  void failedConnectLeavesNoTimerBehind() {
    TestRuntime runtime = new TestRuntime();
    runtime.failProvider = true;

    CompletableFuture<Void> connected = runtime.channel().connect();
    runtime.run();

    assertCode(connected, ErrorCode.TRANSPORT);
    assertEquals(0, runtime.timers.pendingCount());
  } // end method failedConnectLeavesNoTimerBehind

  // Cancelling connect reports the failed state at once and outside the lock, even when the
  // provider's stage ignores the cancellation and never completes.
  @Test
  void cancellingConnectReportsTheFailedStateAtOnce() {
    TestRuntime runtime = new TestRuntime();
    CompletableFuture<Credentials> ignoresCancellation = new CompletableFuture<>();
    Channel channel =
        client(
                runtime,
                request -> ignoresCancellation.minimalCompletionStage(),
                (url, timeout, listener) -> new CompletableFuture<>())
            .channel("room-1");
    TestRuntime.Recorder<String> states = new TestRuntime.Recorder<>();
    channel
        .events()
        .onStateChange(
            state ->
                states.accept(
                    state + (channel.lock.isHeldByCurrentThread() ? " under the lock" : "")));

    CompletableFuture<Void> connected = channel.connect();
    runtime.run();

    assertTrue(connected.cancel(true));
    runtime.run();

    assertEquals(List.of("connecting", "failed"), states.all());
    assertFalse(channel.lock.isLocked());
    assertFalse(ignoresCancellation.isDone());
  } // end method cancellingConnectReportsTheFailedStateAtOnce

  // A provider that closes the channel itself gets back a cancellation for the stage it returns.
  @Test
  void stageReturnedAfterItsAttemptEndedIsCancelled() {
    TestRuntime runtime = new TestRuntime();
    CompletableFuture<Credentials> stage = new CompletableFuture<>();
    Channel[] channel = new Channel[1];
    channel[0] =
        client(
                runtime,
                request -> {
                  channel[0].closeAsync();

                  return stage;
                },
                (url, timeout, listener) -> new CompletableFuture<>())
            .channel("room-1");

    CompletableFuture<Void> connected = channel[0].connect();
    runtime.run();

    assertCode(connected, ErrorCode.CANCELLED);
    assertTrue(stage.isCancelled());
    assertEquals(ChannelState.CLOSED, channel[0].state());
  } // end method stageReturnedAfterItsAttemptEndedIsCancelled

  // A close landing while the handshake call itself runs aborts the socket it produces.
  @Test
  void socketProducedAfterItsAttemptEndedIsAborted() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.channel();
    runtime.onOpen = socket -> channel.closeAsync();

    CompletableFuture<Void> connected = channel.connect();
    runtime.run();

    assertCode(connected, ErrorCode.CANCELLED);
    assertTrue(runtime.socket().isAborted());
    assertEquals(ChannelState.CLOSED, channel.state());
  } // end method socketProducedAfterItsAttemptEndedIsAborted

  // One deadline covers credentials and the handshake: the handshake gets what remains of it.
  @Test
  void handshakeGetsWhatRemainsOfTheConnectDeadline() {
    TestRuntime runtime = new TestRuntime();
    runtime.blockProvider = true;
    CompletableFuture<Void> connected = runtime.channel().connect();
    runtime.advance(Duration.ofSeconds(4));

    runtime.blockedProviders.get(0).complete(TestRuntime.CREDENTIALS);
    runtime.run();

    assertSucceeded(connected);
    assertEquals(List.of(Duration.ofSeconds(11)), runtime.dialTimeouts);
  } // end method handshakeGetsWhatRemainsOfTheConnectDeadline

  // The JDK reports a handshake that outlived its timeout as an HttpTimeoutException, raw or
  // wrapped by the stage that carried it: either is a timeout, not a transport failure.
  @Test
  void handshakeTimeoutIsATimeout() {
    for (Throwable timeout :
        List.of(
            new HttpConnectTimeoutException("synthetic timeout"),
            new CompletionException(new HttpConnectTimeoutException("synthetic timeout")))) {
      TestRuntime runtime = new TestRuntime();
      Channel channel =
          client(
                  runtime,
                  request -> CompletableFuture.completedFuture(TestRuntime.CREDENTIALS),
                  (url, deadline, listener) -> CompletableFuture.failedFuture(timeout))
              .channel("room-1");

      CompletableFuture<Void> connected = channel.connect();
      runtime.run();

      assertCode(connected, ErrorCode.TIMEOUT);
      assertEquals(
          "Connection attempt timed out after 15000 ms.", failureOf(connected).getMessage());
    }
  } // end method handshakeTimeoutIsATimeout

  // ---- Listeners ----

  @Test
  void recoveryAndErrorListenersRemoveCleanly() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<Object> removed = new TestRuntime.Recorder<>();
    TestRuntime.Recorder<Object> kept = new TestRuntime.Recorder<>();
    channel.events().onRecovery(removed::accept).close();
    channel.events().onError(removed::accept).close();
    channel.events().onRecovery(kept::accept);
    channel.events().onError(kept::accept);

    runtime.socket().drop();
    runtime.run();
    runtime.socket().receiveText("text");
    runtime.run();

    assertEquals(2, kept.all().size());
    assertTrue(removed.all().isEmpty(), () -> "delivered " + removed.all());
  } // end method recoveryAndErrorListenersRemoveCleanly
} // end class LifecycleTest

package com.useceleris.client.live;

import static com.useceleris.client.live.LiveSupport.DELIVERY_TIMEOUT;
import static com.useceleris.client.live.LiveSupport.await;
import static com.useceleris.client.live.LiveSupport.claims;
import static com.useceleris.client.live.LiveSupport.client;
import static com.useceleris.client.live.LiveSupport.collect;
import static com.useceleris.client.live.LiveSupport.collectErrors;
import static com.useceleris.client.live.LiveSupport.failureOf;
import static com.useceleris.client.live.LiveSupport.pause;
import static com.useceleris.client.live.LiveSupport.settle;
import static com.useceleris.client.live.LiveSupport.sign;
import static com.useceleris.client.live.LiveSupport.texts;
import static com.useceleris.client.live.LiveSupport.uniqueChannelReference;
import static com.useceleris.client.live.LiveSupport.waitUntil;
import static com.useceleris.client.live.LiveSupport.websocketUrl;
import static com.useceleris.client.live.LiveSupport.withText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.useceleris.client.CelerisClient;
import com.useceleris.client.CelerisException;
import com.useceleris.client.Channel;
import com.useceleris.client.ChannelState;
import com.useceleris.client.ClientOptions;
import com.useceleris.client.CredentialRequest;
import com.useceleris.client.ErrorCode;
import com.useceleris.client.Payloads;
import com.useceleris.client.RecoveryEvent;
import com.useceleris.client.live.LiveSupport.Delivery;
import com.useceleris.client.live.LiveSupport.Recorder;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Connecting, closing and reconnecting at their edges, mostly behind the dropping proxy. */
@Tag("live")
@Timeout(120)
final class LifecycleLiveTest {
  @RegisterExtension final LiveSupport live = new LiveSupport();

  /** A channel behind its own dropping proxy, with everything it reports recorded. */
  private record Setup(
      DroppingProxy proxy,
      Channel channel,
      Recorder<CredentialRequest> requests,
      Recorder<ChannelState> states,
      Recorder<RuntimeException> errors) {} // end record Setup

  private Setup proxiedChannel(String label) throws IOException {
    return proxiedChannel(label, UnaryOperator.identity());
  } // end method proxiedChannel

  private Setup proxiedChannel(String label, UnaryOperator<ClientOptions.Builder> options)
      throws IOException {
    DroppingProxy proxy = live.closeAfterTest(DroppingProxy.start(websocketUrl()));
    Recorder<CredentialRequest> requests = new Recorder<>();
    ClientOptions.Builder builder =
        ClientOptions.builder(
                request -> {
                  requests.accept(request);

                  return CompletableFuture.completedFuture(sign(claims()));
                })
            .baseUrl(proxy.url())
            .allowInsecureLoopback(true);
    Channel channel =
        live.channel(
            CelerisClient.create(options.apply(builder).build()), uniqueChannelReference(label));
    Recorder<ChannelState> states = new Recorder<>();
    channel.events().onStateChange(states);

    return new Setup(proxy, channel, requests, states, collectErrors(channel));
  } // end method proxiedChannel

  private static void untilState(Channel channel, ChannelState state, Duration timeout)
      throws InterruptedException {
    waitUntil(() -> channel.state() == state, "the " + state + " state", timeout);
  } // end method untilState

  /** Cuts the channel's connection and refuses new ones, so it reconnects in vain. */
  private static void startOutage(Setup setup) {
    setup.proxy().refuseNewConnections(true);
    setup.proxy().cutOpenConnections();
  } // end method startOutage

  @Test
  void failsAConnectAtItsConnectTimeoutWhenTheServerNeverAnswers() throws Exception {
    Setup setup =
        proxiedChannel(
            "lifecycle-timeout", builder -> builder.connectTimeout(Duration.ofMillis(2000)));
    setup.proxy().blackholeNewConnections(true);
    long started = System.nanoTime();

    Throwable failure = failureOf(setup.channel().connect(), "connect");

    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
    CelerisException timedOut = assertInstanceOf(CelerisException.class, failure);
    assertEquals(ErrorCode.TIMEOUT, timedOut.code());
    assertEquals("Connection attempt timed out after 2000 ms.", timedOut.getMessage());
    assertTrue(elapsed.compareTo(Duration.ofMillis(1900)) >= 0, "failed after " + elapsed);
    assertTrue(elapsed.compareTo(Duration.ofSeconds(4)) < 0, "failed after " + elapsed);
    assertEquals(ChannelState.FAILED, setup.channel().state());
    setup.states().await(state -> state == ChannelState.FAILED, "failed", DELIVERY_TIMEOUT);
    assertEquals(List.of(ChannelState.CONNECTING, ChannelState.FAILED), setup.states().all());

    // One failure, one report: the caller only (LIFE-02).
    pause(Duration.ofMillis(500));
    assertEquals(List.of(), setup.errors().all());
  } // end method failsAConnectAtItsConnectTimeoutWhenTheServerNeverAnswers

  @Test
  void timesOutAtAOneMillisecondConnectTimeoutAgainstTheRealServer() throws Exception {
    CelerisClient client =
        CelerisClient.create(
            ClientOptions.builder(request -> CompletableFuture.completedFuture(sign(claims())))
                .baseUrl(websocketUrl())
                .allowInsecureLoopback(true)
                .connectTimeout(Duration.ofMillis(1))
                .build());
    Channel channel = live.channel(client, uniqueChannelReference("lifecycle-1ms"));

    Throwable failure = failureOf(channel.connect(), "connect");

    assertEquals(ErrorCode.TIMEOUT, assertInstanceOf(CelerisException.class, failure).code());
    assertEquals(ChannelState.FAILED, channel.state());
  } // end method timesOutAtAOneMillisecondConnectTimeoutAgainstTheRealServer

  /** Cancelling the future connect returned is its cancellation (LANG-02). */
  @Test
  void cancelsAConnectByCancellingItsFuture() throws Exception {
    Setup setup = proxiedChannel("lifecycle-abort");
    setup.proxy().blackholeNewConnections(true);

    CompletableFuture<Void> pending = setup.channel().connect();
    pause(Duration.ofMillis(500));

    assertTrue(pending.cancel(true), "the attempt was not withdrawn");
    assertThrows(CancellationException.class, pending::join);
    assertEquals(ChannelState.FAILED, setup.channel().state());
  } // end method cancelsAConnectByCancellingItsFuture

  @Test
  void closesAChannelThatIsStillConnecting() throws Exception {
    Setup setup = proxiedChannel("lifecycle-close-connecting");
    setup.proxy().blackholeNewConnections(true);

    CompletableFuture<Void> pending = setup.channel().connect();
    pause(Duration.ofMillis(500));
    setup.channel().close();

    Throwable failure = failureOf(pending, "the abandoned connect");
    assertEquals(ErrorCode.CANCELLED, assertInstanceOf(CelerisException.class, failure).code());
    assertEquals(ChannelState.CLOSED, setup.channel().state());
    Throwable again = failureOf(setup.channel().connect(), "connect after close");
    assertEquals(ErrorCode.NOT_CONNECTED, assertInstanceOf(CelerisException.class, again).code());
  } // end method closesAChannelThatIsStillConnecting

  @Test
  void closesAChannelThatIsReconnectingAndStopsItsRetries() throws Exception {
    Setup setup = proxiedChannel("lifecycle-close-reconnecting");
    await(setup.channel().connect(), "connect through the proxy");
    startOutage(setup);
    untilState(setup.channel(), ChannelState.RECONNECTING, Duration.ofSeconds(5));
    pause(Duration.ofSeconds(1));

    setup.channel().close();
    int requestsAtClose = setup.requests().count();
    setup.proxy().refuseNewConnections(false);
    pause(Duration.ofSeconds(5));

    assertEquals(ChannelState.CLOSED, setup.channel().state());
    assertEquals(requestsAtClose, setup.requests().count(), setup.requests().all().toString());
  } // end method closesAChannelThatIsReconnectingAndStopsItsRetries

  /** QUEUE-01: a publish made while reconnecting waits, and recovery failing rejects it. */
  @Test
  void rejectsAPublishQueuedWhileReconnectingWithTheTerminalErrorAndRefusesOneAfterFailed()
      throws Exception {
    Setup setup =
        proxiedChannel(
            "lifecycle-publish-reconnecting", builder -> builder.maximumReconnectAttempts(2));
    await(setup.channel().connect(), "connect through the proxy");
    startOutage(setup);
    untilState(setup.channel(), ChannelState.RECONNECTING, Duration.ofSeconds(5));

    CompletableFuture<Void> queued = setup.channel().segment("chat").publish(Payloads.text("x"));
    untilState(setup.channel(), ChannelState.FAILED, Duration.ofSeconds(60));

    List<RuntimeException> errors = setup.errors().all();
    assertEquals(1, errors.size(), errors.toString());
    assertEquals(
        ErrorCode.TRANSPORT, assertInstanceOf(CelerisException.class, errors.get(0)).code());
    assertSame(errors.get(0), failureOf(queued, "the queued publish"));

    Throwable refused =
        failureOf(setup.channel().segment("chat").publish(Payloads.text("y")), "publish");
    CelerisException notConnected = assertInstanceOf(CelerisException.class, refused);
    assertEquals(ErrorCode.NOT_CONNECTED, notConnected.code());
    assertEquals("Channel is not connected; it is failed.", notConnected.getMessage());
  } // end method rejectsAPublishQueuedWhileReconnectingWithTheTerminalErrorAndRefusesOneAfterFailed

  @Test
  void restartsFromFailedWithAnExplicitConnectAndSendsHeldSubscriptions() throws Exception {
    Setup setup = proxiedChannel("lifecycle-restart");
    Recorder<Delivery> chat = collect(setup.channel().segment("chat"));
    setup.channel().segment("chat").subscribe();
    setup.proxy().refuseNewConnections(true);

    Throwable failure = failureOf(setup.channel().connect(), "connect while refused");
    assertEquals(ErrorCode.TRANSPORT, assertInstanceOf(CelerisException.class, failure).code());
    assertEquals(ChannelState.FAILED, setup.channel().state());

    setup.proxy().refuseNewConnections(false);
    await(setup.channel().connect(), "connect after the failure");
    Channel publisher = live.connectedChannel(setup.requests().all().get(0).channelReference());
    settle();
    await(publisher.segment("chat").publish(Payloads.text("after-restart")), "publish");
    chat.await(withText("after-restart"), "the delivery after the restart", DELIVERY_TIMEOUT);

    List<CredentialRequest> requests = setup.requests().all();
    assertEquals(2, requests.size(), requests.toString());
    assertFalse(requests.get(0).reconnect(), requests.toString());
    assertFalse(requests.get(1).reconnect(), requests.toString());
    assertEquals(List.of("after-restart"), texts(chat));
  } // end method restartsFromFailedWithAnExplicitConnectAndSendsHeldSubscriptions

  @Test
  @Timeout(240)
  void failsAfterTenFailedReconnectAttemptsAndReportsIt() throws Exception {
    Setup setup = proxiedChannel("lifecycle-exhaustion");
    await(setup.channel().connect(), "connect through the proxy");
    startOutage(setup);

    // The error is delivered ahead of the state change that follows it.
    setup
        .states()
        .await(state -> state == ChannelState.FAILED, "the failed state", Duration.ofSeconds(200));

    List<CredentialRequest> reconnects =
        setup.requests().all().stream().filter(CredentialRequest::reconnect).toList();
    assertEquals(10, reconnects.size(), reconnects.toString());
    List<RuntimeException> errors = setup.errors().all();
    assertEquals(1, errors.size(), errors.toString());
    assertEquals(
        ErrorCode.TRANSPORT, assertInstanceOf(CelerisException.class, errors.get(0)).code());
    List<ChannelState> states = setup.states().all();
    assertEquals(
        List.of(ChannelState.RECONNECTING, ChannelState.FAILED),
        states.subList(states.size() - 2, states.size()));
  } // end method failsAfterTenFailedReconnectAttemptsAndReportsIt

  @Test
  void failsAfterTheConfiguredMaximumOfTwoReconnectAttempts() throws Exception {
    Setup setup =
        proxiedChannel("lifecycle-maximum", builder -> builder.maximumReconnectAttempts(2));
    await(setup.channel().connect(), "connect through the proxy");
    startOutage(setup);

    setup
        .states()
        .await(state -> state == ChannelState.FAILED, "the failed state", Duration.ofSeconds(60));

    List<CredentialRequest> reconnects =
        setup.requests().all().stream().filter(CredentialRequest::reconnect).toList();
    assertEquals(2, reconnects.size(), reconnects.toString());
    List<RuntimeException> errors = setup.errors().all();
    assertEquals(1, errors.size(), errors.toString());
    assertEquals(
        ErrorCode.TRANSPORT, assertInstanceOf(CelerisException.class, errors.get(0)).code());
    List<ChannelState> states = setup.states().all();
    assertEquals(
        List.of(ChannelState.RECONNECTING, ChannelState.FAILED),
        states.subList(states.size() - 2, states.size()));
  } // end method failsAfterTheConfiguredMaximumOfTwoReconnectAttempts

  @Test
  @Timeout(150)
  void resetsTheRetryBudgetAfterSixtySecondsConnected() throws Exception {
    Setup setup = proxiedChannel("lifecycle-budget");
    Recorder<RecoveryEvent> recoveries = new Recorder<>();
    setup.channel().events().onRecovery(recoveries);
    await(setup.channel().connect(), "connect through the proxy");

    // A first outage uses at least two failed attempts: it lasts until the third reconnect attempt
    // has asked for credentials, so no earlier attempt can succeed.
    startOutage(setup);
    waitUntil(
        () -> setup.requests().count() >= 4, "the third reconnect attempt", Duration.ofSeconds(30));
    setup.proxy().refuseNewConnections(false);
    RecoveryEvent first =
        recoveries.await(any -> true, "the first recovery", Duration.ofSeconds(30));
    assertTrue(first.retryIndex() >= 2, first.toString());
    untilState(setup.channel(), ChannelState.CONNECTED, DELIVERY_TIMEOUT);

    // After sixty seconds connected, the next outage starts a new budget.
    pause(Duration.ofSeconds(61));
    setup.proxy().cutOpenConnections();
    waitUntil(() -> recoveries.count() >= 2, "the second recovery", Duration.ofSeconds(30));

    List<RecoveryEvent> recovered = recoveries.all();
    assertEquals(0, recovered.get(recovered.size() - 1).retryIndex(), recovered.toString());
  } // end method resetsTheRetryBudgetAfterSixtySecondsConnected

  /** The runtime answers the server's pings; nothing else is sent while idle. */
  @Test
  @Timeout(150)
  void keepsAnIdleConnectionOpenPastTheServersSixtySecondHeartbeat() throws Exception {
    String reference = uniqueChannelReference("lifecycle-idle");
    Channel publisher = live.connectedChannel(reference);
    Channel receiver = live.channel(client(claims()), reference);
    Recorder<ChannelState> states = new Recorder<>();
    receiver.events().onStateChange(states);
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    receiver.segment("chat").subscribe();

    // The connected state event may arrive after the connect future completes.
    await(receiver.connect(), "connect");
    states.await(state -> state == ChannelState.CONNECTED, "the connected state", DELIVERY_TIMEOUT);

    pause(Duration.ofSeconds(95));
    await(publisher.segment("chat").publish(Payloads.text("still-here")), "publish");
    chat.await(withText("still-here"), "the delivery after the idle period", DELIVERY_TIMEOUT);

    assertEquals(List.of(ChannelState.CONNECTING, ChannelState.CONNECTED), states.all());
    assertEquals(ChannelState.CONNECTED, receiver.state());
  } // end method keepsAnIdleConnectionOpenPastTheServersSixtySecondHeartbeat
} // end class LifecycleLiveTest

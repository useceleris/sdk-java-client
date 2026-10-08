package com.useceleris.client.live;

import static com.useceleris.client.live.LiveSupport.DELIVERY_TIMEOUT;
import static com.useceleris.client.live.LiveSupport.await;
import static com.useceleris.client.live.LiveSupport.claims;
import static com.useceleris.client.live.LiveSupport.client;
import static com.useceleris.client.live.LiveSupport.collect;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.useceleris.client.Channel;
import com.useceleris.client.ChannelState;
import com.useceleris.client.CredentialRequest;
import com.useceleris.client.Payloads;
import com.useceleris.client.PresenceConnection;
import com.useceleris.client.PresenceEvent;
import com.useceleris.client.PresencePage;
import com.useceleris.client.RecoveryEvent;
import com.useceleris.client.Segment;
import com.useceleris.client.Subscription;
import com.useceleris.client.live.LiveSupport.Claims;
import com.useceleris.client.live.LiveSupport.Delivery;
import com.useceleris.client.live.LiveSupport.Recorder;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

@Tag("live")
@Timeout(120)
final class ReconnectLiveTest {
  /** The client pings after 15 s without hearing the server, and gives up 15 s later. */
  private static final Duration HEARTBEAT_IDLE = Duration.ofSeconds(15);

  private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(45);

  /** A replayed delivery waits for the reconnect, whose backoff can reach a few seconds. */
  private static final Duration REPLAY_TIMEOUT = Duration.ofSeconds(30);

  /** Past the server's own heartbeat deadline: it closes a client silent for 60 s. */
  private static final Duration LISTENER_BLOCK = Duration.ofSeconds(90);

  @RegisterExtension final LiveSupport live = new LiveSupport();

  /**
   * A receiver behind the dropping proxy and a publisher that connects directly, so only the
   * receiver has the outage. Every credential request is recorded.
   */
  private record Setup(
      DroppingProxy proxy,
      Channel receiver,
      Channel publisher,
      Recorder<CredentialRequest> requests,
      Recorder<RecoveryEvent> recoveries) {
    /**
     * Cuts every connection through the proxy and refuses new ones, so a message published now can
     * reach the receiver only by replay.
     */
    void startOutage() throws InterruptedException {
      proxy.refuseNewConnections(true);
      proxy.cutOpenConnections();
      pause(Duration.ofMillis(500));
      assertEquals(ChannelState.RECONNECTING, receiver.state());
    } // end method startOutage

    void endOutage() throws InterruptedException {
      proxy.refuseNewConnections(false);
      recoveries.await(any -> true, "the recovery event", RECOVERY_TIMEOUT);
    } // end method endOutage
  } // end record Setup

  private Setup setUp(String label) throws Exception {
    return setUp(label, true);
  } // end method setUp

  /**
   * With replayOnReconnect, the credential provider signs the lookback the SDK asks for into the
   * token: the canonical mapping.
   */
  private Setup setUp(String label, boolean replayOnReconnect) throws Exception {
    DroppingProxy proxy = live.closeAfterTest(DroppingProxy.start(websocketUrl()));
    String reference = uniqueChannelReference(label);
    Recorder<CredentialRequest> requests = new Recorder<>();
    Channel receiver =
        live.channel(
            client(
                proxy.url(),
                request -> {
                  requests.accept(request);
                  Claims claims =
                      replayOnReconnect && request.reconnect()
                          ? claims().withReplay(request.replayLookback().orElseThrow().toMillis())
                          : claims();

                  return CompletableFuture.completedFuture(sign(claims));
                }),
            reference);
    Recorder<RecoveryEvent> recoveries = new Recorder<>();
    receiver.events().onRecovery(recoveries);
    Channel publisher = live.connectedChannel(reference);

    return new Setup(proxy, receiver, publisher, requests, recoveries);
  } // end method setUp

  private static void publish(Channel publisher, String segmentId, String body)
      throws InterruptedException {
    await(publisher.segment(segmentId).publish(Payloads.text(body)), "publishing " + body);
  } // end method publish

  private static void arrival(Recorder<Delivery> deliveries, String body, Duration timeout)
      throws InterruptedException {
    deliveries.await(withText(body), "the delivery of \"" + body + "\"", timeout);
  } // end method arrival

  /**
   * SUB-01, REC-02: an outage recovers with fresh credentials, a replay of what it missed, and the
   * subscription restored; replayed duplicates are absorbed by the deduplication window.
   */
  @Test
  void recoversAfterAnOutageWithReplayAndRestoredSubscriptions() throws Exception {
    Setup setup = setUp("reconnect");
    Channel receiver = setup.receiver();
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    receiver.segment("chat").subscribe();
    await(receiver.connect(), "connect through the proxy");
    settle();
    publish(setup.publisher(), "chat", "before");
    arrival(chat, "before", DELIVERY_TIMEOUT);

    setup.startOutage();
    publish(setup.publisher(), "chat", "during");
    pause(Duration.ofSeconds(2));
    setup.endOutage();
    arrival(chat, "during", REPLAY_TIMEOUT);

    RecoveryEvent recovery = setup.recoveries().all().get(0);
    assertTrue(recovery.possibleGaps(), recovery.toString());
    assertTrue(recovery.possibleDuplicates(), recovery.toString());

    List<CredentialRequest> requests = setup.requests().all();
    assertFalse(requests.get(0).reconnect(), requests.toString());

    for (CredentialRequest request : requests.subList(1, requests.size())) {
      assertTrue(request.reconnect(), request.toString());
      assertTrue(request.disconnectedAt().orElseThrow().isAfter(Instant.EPOCH), request.toString());
      assertTrue(
          request.replayLookback().orElseThrow().compareTo(Duration.ofSeconds(5)) >= 0,
          request.toString());
    }

    // The subscription was restored on the new connection.
    publish(setup.publisher(), "chat", "after");
    arrival(chat, "after", DELIVERY_TIMEOUT);
    settle();

    // Replay sent "before" again; the deduplication window dropped it.
    assertEquals(List.of("before", "during", "after"), texts(chat));
    Set<String> messageIds = new HashSet<>();

    for (Delivery delivery : chat.all()) {
      messageIds.add(delivery.metadata().messageId());
    }

    assertEquals(3, messageIds.size(), chat.all().toString());
  } // end method recoversAfterAnOutageWithReplayAndRestoredSubscriptions

  @Test
  void recoversEveryMissedMessageOnSeveralSegmentsInOrderAndOneTime() throws Exception {
    Setup setup = setUp("reconnect-segments");
    Channel receiver = setup.receiver();
    Channel publisher = setup.publisher();
    Recorder<Delivery> alpha = collect(receiver.segment("alpha"));
    Recorder<Delivery> beta = collect(receiver.segment("beta"));
    Recorder<Delivery> lobby = collect(receiver.defaultSegment());
    Recorder<String> channelWide = new Recorder<>();
    receiver
        .events()
        .onMessage(
            (payload, metadata) ->
                channelWide.accept(metadata.segmentId() + ":" + Payloads.readText(payload)));
    receiver.segment("alpha").subscribe();
    receiver.segment("beta").subscribe();
    await(receiver.connect(), "connect through the proxy");
    settle();
    publish(publisher, "alpha", "a0");
    arrival(alpha, "a0", DELIVERY_TIMEOUT);

    setup.startOutage();

    for (String body : List.of("a1", "a2", "a3")) {
      publish(publisher, "alpha", body);
    }

    for (String body : List.of("b1", "b2")) {
      publish(publisher, "beta", body);
    }

    publish(publisher, "default", "d1");
    pause(Duration.ofSeconds(2));
    setup.endOutage();
    arrival(alpha, "a3", REPLAY_TIMEOUT);
    arrival(beta, "b2", REPLAY_TIMEOUT);
    arrival(lobby, "d1", REPLAY_TIMEOUT);
    settle();

    assertEquals(List.of("a0", "a1", "a2", "a3"), texts(alpha));
    assertEquals(List.of("b1", "b2"), texts(beta));
    assertEquals(List.of("d1"), texts(lobby));
    assertEquals(
        List.of("alpha:a0", "alpha:a1", "alpha:a2", "alpha:a3", "beta:b1", "beta:b2", "default:d1")
            .stream()
            .sorted()
            .toList(),
        channelWide.all().stream().sorted().toList());
  } // end method recoversEveryMissedMessageOnSeveralSegmentsInOrderAndOneTime

  @Test
  void losesMissedMessagesWithoutAReplayClaimButRestoresTheSubscription() throws Exception {
    Setup setup = setUp("reconnect-no-replay", false);
    Channel receiver = setup.receiver();
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    receiver.segment("chat").subscribe();
    await(receiver.connect(), "connect through the proxy");
    settle();
    publish(setup.publisher(), "chat", "before");
    arrival(chat, "before", DELIVERY_TIMEOUT);

    setup.startOutage();
    publish(setup.publisher(), "chat", "missed");
    pause(Duration.ofSeconds(2));
    setup.endOutage();
    settle();

    publish(setup.publisher(), "chat", "after");
    arrival(chat, "after", DELIVERY_TIMEOUT);
    settle();

    // The recovery event declares the gap that this test makes.
    RecoveryEvent recovery = setup.recoveries().all().get(0);
    assertTrue(recovery.possibleGaps(), recovery.toString());
    assertEquals(List.of("before", "after"), texts(chat));
  } // end method losesMissedMessagesWithoutAReplayClaimButRestoresTheSubscription

  @Test
  void recoversEveryMissedMessageAfterALongerOutageWithFailedAttempts() throws Exception {
    Setup setup = setUp("reconnect-long");
    Channel receiver = setup.receiver();
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    receiver.segment("chat").subscribe();
    await(receiver.connect(), "connect through the proxy");
    settle();

    setup.startOutage();
    publish(setup.publisher(), "chat", "m1");
    pause(Duration.ofSeconds(3));
    publish(setup.publisher(), "chat", "m2");
    pause(Duration.ofSeconds(3));
    publish(setup.publisher(), "chat", "m3");
    pause(Duration.ofMillis(500));
    setup.endOutage();
    arrival(chat, "m3", REPLAY_TIMEOUT);
    settle();

    // Retries in the first 6.5 s fail (their delays are at most 0.5, 1 and 2 s), each with a fresh
    // credential request and a longer lookback.
    List<CredentialRequest> reconnects =
        setup.requests().all().stream().filter(CredentialRequest::reconnect).toList();
    assertTrue(reconnects.size() >= 4, reconnects.toString());
    List<Duration> lookbacks =
        reconnects.stream().map(request -> request.replayLookback().orElseThrow()).toList();
    assertEquals(lookbacks.stream().sorted().toList(), lookbacks);
    assertTrue(
        lookbacks.get(lookbacks.size() - 1).compareTo(Duration.ofSeconds(11)) >= 0,
        lookbacks.toString());
    Set<Optional<Instant>> disconnectedAt = new HashSet<>();

    for (CredentialRequest request : reconnects) {
      disconnectedAt.add(request.disconnectedAt());
    }

    assertEquals(1, disconnectedAt.size(), reconnects.toString());
    assertEquals(List.of("m1", "m2", "m3"), texts(chat));
  } // end method recoversEveryMissedMessageAfterALongerOutageWithFailedAttempts

  @Test
  void doesNotRejoinASegmentThatTheConnectionJoinedOnlyByPublishing() throws Exception {
    Setup setup = setUp("reconnect-publish-join");
    Channel receiver = setup.receiver();
    Channel publisher = setup.publisher();
    Recorder<Delivery> team = collect(receiver.segment("team"));
    Recorder<Delivery> lobby = collect(receiver.defaultSegment());
    await(receiver.connect(), "connect through the proxy");
    publish(receiver, "team", "joining");
    settle();
    publish(publisher, "team", "before");
    arrival(team, "before", DELIVERY_TIMEOUT);

    setup.startOutage();
    setup.endOutage();
    settle();

    publish(publisher, "team", "after");
    publish(publisher, "default", "control");
    arrival(lobby, "control", DELIVERY_TIMEOUT);
    pause(Duration.ofMillis(2500));

    assertEquals(List.of("before"), texts(team));
  } // end method doesNotRejoinASegmentThatTheConnectionJoinedOnlyByPublishing

  @Test
  void announcesTheNewConnectionAndRestoresItsPresenceSubscriptionAfterAReconnect()
      throws Exception {
    Setup setup = setUp("reconnect-presence");
    Channel receiver = setup.receiver();
    Channel publisher = setup.publisher();
    Recorder<PresenceEvent> atPublisher = new Recorder<>();
    publisher.segment("room").onPresence(atPublisher);
    Recorder<PresenceEvent> atReceiver = new Recorder<>();
    receiver.segment("room").onPresence(atReceiver);
    receiver.segment("room").subscribe();
    receiver.segment("room").subscribePresence();
    publisher.segment("room").subscribePresence();
    await(receiver.connect(), "connect through the proxy");
    PresenceEvent before =
        atPublisher.await(PresenceEvent::joined, "the first join", Duration.ofSeconds(20));

    setup.startOutage();
    atPublisher.await(
        event -> !event.joined() && event.connectionId().equals(before.connectionId()),
        "the leave of the old connection",
        Duration.ofSeconds(20));
    setup.endOutage();
    atPublisher.await(
        event -> event.joined() && !event.connectionId().equals(before.connectionId()),
        "the join of the new connection",
        RECOVERY_TIMEOUT);

    // The receiver's presence subscription came back with the reconnect.
    Channel actor =
        live.connectedChannel(
            setup.requests().all().get(0).channelReference(), claims().withReference("actor"));
    actor.segment("room").subscribe();
    atReceiver.await(
        event -> event.joined() && event.tokenReference().equals("actor"),
        "the actor's join at the restored watcher",
        Duration.ofSeconds(20));
  } // end method announcesTheNewConnectionAndRestoresItsPresenceSubscriptionAfterAReconnect

  /**
   * A connection that stops carrying traffic without closing is noticed only by the heartbeat: a
   * ping after 15 s of silence, given up 15 s later.
   */
  @Test
  void recoversFromABlackholedConnectionThroughTheHeartbeat() throws Exception {
    Setup setup = setUp("blackhole");
    Channel receiver = setup.receiver();
    Recorder<ChannelState> states = new Recorder<>();
    receiver.events().onStateChange(states);
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    receiver.segment("chat").subscribe();
    await(receiver.connect(), "connect through the proxy");
    settle();
    publish(setup.publisher(), "chat", "before");
    arrival(chat, "before", DELIVERY_TIMEOUT);

    // Let the post-delivery probe fire and get its pong first, so that only
    // the heartbeat can find the blackholed connection.
    pause(Duration.ofSeconds(3));

    long blackholedAt = System.nanoTime();
    setup.proxy().blackholeOpenConnections();

    setup
        .recoveries()
        .await(any -> true, "recovery from the blackholed connection", RECOVERY_TIMEOUT);
    Duration recoveredAfter = Duration.ofNanos(System.nanoTime() - blackholedAt);

    // Anything sooner than the idle threshold was not the heartbeat's doing.
    assertTrue(recoveredAfter.compareTo(HEARTBEAT_IDLE) >= 0, "recovered after " + recoveredAfter);
    assertTrue(states.all().contains(ChannelState.RECONNECTING), states.all().toString());
    assertEquals(ChannelState.CONNECTED, receiver.state());

    // The subscription was restored on the new connection.
    publish(setup.publisher(), "chat", "after");
    arrival(chat, "after", DELIVERY_TIMEOUT);
  } // end method recoversFromABlackholedConnectionThroughTheHeartbeat

  /**
   * A listener holding the read side for longer than the server's 60 s heartbeat deadline keeps its
   * connection: the client pings while listeners run, so the server keeps hearing from it.
   */
  @Test
  @Timeout(value = 4, unit = TimeUnit.MINUTES)
  void keepsTheConnectionWhileAListenerBlocksForNinetySeconds() throws Exception {
    String reference = uniqueChannelReference("slow");
    Channel receiver = live.connectedChannel(reference);
    Channel publisher = live.connectedChannel(reference);
    Recorder<ChannelState> states = new Recorder<>();
    receiver.events().onStateChange(states);
    Segment chat = receiver.segment("chat");
    Recorder<Delivery> delivered = collect(chat);
    CountDownLatch blocking = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    chat.onMessage(
        (payload, metadata) -> {
          if (Payloads.readText(payload).equals("block")) {
            blocking.countDown();
            awaitRelease(release);
          }
        });

    chat.subscribe();
    settle();

    await(publisher.segment("chat").publish(Payloads.text("block")), "publish block");
    assertTrue(blocking.await(15, TimeUnit.SECONDS), "the listener never received the message");

    long blockEnds = System.nanoTime() + LISTENER_BLOCK.toNanos();

    try {
      while (System.nanoTime() - blockEnds < 0) {
        assertEquals(ChannelState.CONNECTED, receiver.state(), "while the listener blocks");
        pause(Duration.ofSeconds(1));
      }
    } finally {
      release.countDown();
    }

    await(publisher.segment("chat").publish(Payloads.text("after")), "publish after");
    delivered.await(withText("after"), "a delivery after the listener returned", DELIVERY_TIMEOUT);
    await(chat.publish(Payloads.text("from-receiver")), "publish from the receiver");

    assertFalse(states.all().contains(ChannelState.RECONNECTING), states.all().toString());
    assertEquals(ChannelState.CONNECTED, receiver.state());
  } // end method keepsTheConnectionWhileAListenerBlocksForNinetySeconds

  /**
   * The JDK loses an orderly close that arrives between delivering a message and requesting the
   * next one; the post-delivery probe finds it within seconds instead of the heartbeat's thirty.
   */
  @Test
  void detectsACloseWithoutACloseFrameRightAfterAMessageWithinSeconds() throws Exception {
    Setup setup = setUp("reconnect-lost-close", false);
    Channel receiver = setup.receiver();
    DroppingProxy proxy = setup.proxy();
    AtomicBoolean cut = new AtomicBoolean();
    receiver
        .segment("chat")
        .onMessage(
            (payload, metadata) -> {
              // While this listener runs, the next message is not requested yet: the window in
              // which the JDK loses the end of stream.
              if (Payloads.readText(payload).equals("trigger")) {
                proxy.refuseNewConnections(true);
                proxy.finishOpenConnections();
                cut.set(true);
              }
            });

    receiver.segment("chat").subscribe();
    await(receiver.connect(), "connect through the proxy");
    settle();

    publish(setup.publisher(), "chat", "trigger");
    waitUntil(cut::get, "the trigger delivery", DELIVERY_TIMEOUT);
    waitUntil(
        () -> receiver.state() == ChannelState.RECONNECTING,
        "the reconnecting state after the lost close",
        Duration.ofSeconds(10));

    pause(Duration.ofSeconds(2));
    setup.endOutage();
    assertEquals(ChannelState.CONNECTED, receiver.state());
  } // end method detectsACloseWithoutACloseFrameRightAfterAMessageWithinSeconds

  /** QUEUE-01: publishes made while reconnecting wait in the queue and go out after it. */
  @Test
  void deliversPublishesMadeDuringAnOutageAfterTheReconnectInCallOrder() throws Exception {
    Setup setup = setUp("reconnect-queued", false);
    // The roles swap here: the channel behind the proxy publishes, and the directly connected one
    // receives.
    Channel publisher = setup.receiver();
    Channel receiver = setup.publisher();
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    receiver.segment("chat").subscribe();
    await(publisher.connect(), "connect through the proxy");
    settle();

    setup.startOutage();
    List<CompletableFuture<Void>> published =
        List.of("q1", "q2", "q3").stream()
            .map(body -> publisher.segment("chat").publish(Payloads.text(body)))
            .toList();

    setup.endOutage();

    for (CompletableFuture<Void> publish : published) {
      await(publish, "a queued publish");
    }

    arrival(chat, "q3", RECOVERY_TIMEOUT);
    settle();

    assertEquals(List.of("q1", "q2", "q3"), texts(chat));
  } // end method deliversPublishesMadeDuringAnOutageAfterTheReconnectInCallOrder

  /** QUEUE-01, SUB-01: the new connection holds exactly the segments the old one held. */
  @Test
  @Timeout(150)
  void keepsTheSameSegmentsAfterAReconnect() throws Exception {
    Setup setup = setUp("reconnect-same-segments", false);
    Channel receiver = setup.receiver();
    Channel observer = setup.publisher();
    Recorder<Delivery> alpha = collect(receiver.segment("alpha"));
    Recorder<Delivery> beta = collect(receiver.segment("beta"));
    Recorder<Delivery> gamma = collect(receiver.segment("gamma"));
    Recorder<Delivery> lobby = collect(receiver.defaultSegment());
    receiver.segment("alpha").subscribe();
    receiver.segment("beta").subscribe();
    receiver.segment("alpha").subscribePresence();
    Subscription gammaSubscription = receiver.segment("gamma").subscribe();
    Recorder<PresenceEvent> observed = new Recorder<>();
    observer.segment("alpha").onPresence(observed);
    observer.segment("alpha").subscribePresence();
    settle();

    await(receiver.connect(), "connect through the proxy");
    PresenceEvent before =
        observed.await(PresenceEvent::joined, "the receiver's first join", DELIVERY_TIMEOUT);
    gammaSubscription.cancel();
    settle();

    setup.startOutage();
    observed.await(
        event -> !event.joined() && event.connectionId().equals(before.connectionId()),
        "the leave of the old connection",
        DELIVERY_TIMEOUT);
    setup.endOutage();
    PresenceEvent after =
        observed.await(
            event -> event.joined() && !event.connectionId().equals(before.connectionId()),
            "the join of the new connection",
            RECOVERY_TIMEOUT);
    settle();

    // (a), (b): the new connection is a member of alpha and beta, not gamma.
    assertFalse(after.connectionId().equals(before.connectionId()), after.toString());
    assertTrue(listed(observer, "alpha").contains(after.connectionId()), after.toString());
    assertTrue(listed(observer, "beta").contains(after.connectionId()), after.toString());
    assertFalse(listed(observer, "gamma").contains(after.connectionId()), after.toString());

    // (d): the restored presence subscription on alpha hears a new actor.
    Recorder<PresenceEvent> watched = new Recorder<>();
    receiver.segment("alpha").onPresence(watched);
    Channel actor =
        live.connectedChannel(
            setup.requests().all().get(0).channelReference(), claims().withReference("actor"));
    actor.segment("alpha").subscribe();
    watched.await(
        event -> event.joined() && event.tokenReference().equals("actor"),
        "the actor's join at the restored watcher",
        DELIVERY_TIMEOUT);

    // (c): alpha and beta deliver; the control on the default segment proves that gamma's message
    // had its chance and did not arrive.
    publish(observer, "alpha", "to-alpha");
    publish(observer, "beta", "to-beta");
    publish(observer, "gamma", "to-gamma");
    publish(observer, "default", "control");
    arrival(alpha, "to-alpha", DELIVERY_TIMEOUT);
    arrival(beta, "to-beta", DELIVERY_TIMEOUT);
    arrival(lobby, "control", DELIVERY_TIMEOUT);
    pause(Duration.ofMillis(2500));

    assertEquals(List.of("to-alpha"), texts(alpha));
    assertEquals(List.of("to-beta"), texts(beta));
    assertEquals(List.of(), texts(gamma));
  } // end method keepsTheSameSegmentsAfterAReconnect

  private static List<String> listed(Channel observer, String segmentId)
      throws InterruptedException {
    PresencePage page =
        await(observer.segment(segmentId).presenceList(1, 100), "the " + segmentId + " list");

    return page.connections().stream().map(PresenceConnection::connectionId).toList();
  } // end method listed

  /** Blocks a listener until released, bounded so a failed test never strands the thread. */
  private static void awaitRelease(CountDownLatch release) {
    try {
      boolean released =
          release.await(LISTENER_BLOCK.multipliedBy(2).toMillis(), TimeUnit.MILLISECONDS);

      if (!released) {
        throw new IllegalStateException("The test never released the blocking listener.");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  } // end method awaitRelease
} // end class ReconnectLiveTest

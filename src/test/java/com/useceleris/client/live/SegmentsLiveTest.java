package com.useceleris.client.live;

import static com.useceleris.client.live.LiveSupport.DELIVERY_TIMEOUT;
import static com.useceleris.client.live.LiveSupport.await;
import static com.useceleris.client.live.LiveSupport.claims;
import static com.useceleris.client.live.LiveSupport.client;
import static com.useceleris.client.live.LiveSupport.collect;
import static com.useceleris.client.live.LiveSupport.collectErrors;
import static com.useceleris.client.live.LiveSupport.pause;
import static com.useceleris.client.live.LiveSupport.serverErrorOfType;
import static com.useceleris.client.live.LiveSupport.settle;
import static com.useceleris.client.live.LiveSupport.uniqueChannelReference;
import static com.useceleris.client.live.LiveSupport.waitUntil;
import static com.useceleris.client.live.LiveSupport.withText;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.useceleris.client.Channel;
import com.useceleris.client.Payloads;
import com.useceleris.client.PresenceConnection;
import com.useceleris.client.Registration;
import com.useceleris.client.ServerErrorException;
import com.useceleris.client.Subscription;
import com.useceleris.client.live.LiveSupport.Claims;
import com.useceleris.client.live.LiveSupport.Delivery;
import com.useceleris.client.live.LiveSupport.Recorder;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The server's membership decides what arrives on a segment, never a listener (SEG-01). */
@Tag("live")
@Timeout(120)
final class SegmentsLiveTest {
  @RegisterExtension final LiveSupport live = new LiveSupport();

  private String reference;
  private Channel publisher;
  private Channel receiver;

  // The receiver's default-segment deliveries, where the controls arrive.
  private Recorder<Delivery> controls;

  private void pair(String label) throws InterruptedException {
    pair(label, claims());
  } // end method pair

  private void pair(String label, Claims receiverClaims) throws InterruptedException {
    reference = uniqueChannelReference(label);
    publisher = live.connectedChannel(reference);
    receiver = live.connectedChannel(reference, receiverClaims);
    controls = collect(receiver.defaultSegment());
  } // end method pair

  private static String text(byte[] payload) {
    return Payloads.readText(payload);
  } // end method text

  private static List<String> texts(Recorder<Delivery> deliveries) {
    return deliveries.all().stream().map(Delivery::text).toList();
  } // end method texts

  /** Publishes and waits until the receiver's recorder holds that payload. */
  private void publishAndAwait(Recorder<Delivery> deliveries, String segmentId, String body)
      throws InterruptedException {
    await(publisher.segment(segmentId).publish(Payloads.text(body)), "publishing " + body);
    deliveries.await(withText(body), "\"" + body + "\" on " + segmentId, DELIVERY_TIMEOUT);
  } // end method publishAndAwait

  private void publish(String segmentId, String body) throws InterruptedException {
    await(publisher.segment(segmentId).publish(Payloads.text(body)), "publishing " + body);
  } // end method publish

  /**
   * A negative check needs proof the connection was live: a control message on the default segment,
   * which always delivers, then time for a stray delivery to land.
   */
  private void confirmQuiet() throws InterruptedException {
    long before = texts(controls).stream().filter("control"::equals).count();
    publish("default", "control");
    waitUntil(
        () -> texts(controls).stream().filter("control"::equals).count() > before,
        "the control on default",
        DELIVERY_TIMEOUT);
    pause(Duration.ofMillis(2500));
  } // end method confirmQuiet

  // ---- Receiving ----

  @Test
  void deliversNothingToAListenerWithoutASubscription() throws Exception {
    pair("listener-only");
    Recorder<Delivery> chat = collect(receiver.segment("chat"));

    publish("chat", "unheard");
    confirmQuiet();

    assertEquals(List.of(), texts(chat));
  } // end method deliversNothingToAListenerWithoutASubscription

  @Test
  void deliversAfterASubscriptionMadeBeforeConnecting() throws Exception {
    String reference = uniqueChannelReference("before-connect");
    publisher = live.connectedChannel(reference);
    receiver = live.channel(client(claims()), reference);
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    receiver.segment("chat").subscribe();
    await(receiver.connect(), "connecting " + reference);
    settle();

    publishAndAwait(chat, "chat", "hello");

    assertEquals(List.of("hello"), texts(chat));
  } // end method deliversAfterASubscriptionMadeBeforeConnecting

  @Test
  void deliversAfterASubscriptionMadeOnceConnected() throws Exception {
    pair("after-connect");
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    receiver.segment("chat").subscribe();
    settle();

    publishAndAwait(chat, "chat", "hello");

    assertEquals(List.of("hello"), texts(chat));
  } // end method deliversAfterASubscriptionMadeOnceConnected

  @Test
  void deliversToAListenerAttachedAfterSubscribing() throws Exception {
    pair("listener-later");
    receiver.segment("chat").subscribe();
    settle();
    Recorder<Delivery> chat = collect(receiver.segment("chat"));

    publishAndAwait(chat, "chat", "hello");

    assertEquals(List.of("hello"), texts(chat));
  } // end method deliversToAListenerAttachedAfterSubscribing

  // ---- Leaving ----

  @Test
  void stopsOnCancelAndResumesOnANewSubscription() throws Exception {
    pair("rejoin");
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    Subscription first = receiver.segment("chat").subscribe();
    settle();
    publishAndAwait(chat, "chat", "one");

    first.cancel();
    settle();
    publish("chat", "two");
    confirmQuiet();
    assertEquals(List.of("one"), texts(chat));

    receiver.segment("chat").subscribe();
    settle();
    publishAndAwait(chat, "chat", "three");

    assertEquals(List.of("one", "three"), texts(chat));
  } // end method stopsOnCancelAndResumesOnANewSubscription

  @Test
  void leavesOnlyWhenTheLastHandleCancels() throws Exception {
    pair("refcount");
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    Subscription first = receiver.segment("chat").subscribe();
    Subscription second = receiver.segment("chat").subscribe();
    settle();

    first.cancel();
    settle();
    publishAndAwait(chat, "chat", "one");

    second.cancel();
    settle();
    publish("chat", "two");
    confirmQuiet();

    assertEquals(List.of("one"), texts(chat));
  } // end method leavesOnlyWhenTheLastHandleCancels

  @Test
  void routesEachSegmentsMessagesToItsOwnListenersOnly() throws Exception {
    pair("demux");
    Recorder<Delivery> alpha = collect(receiver.segment("alpha"));
    Recorder<Delivery> beta = collect(receiver.segment("beta"));
    receiver.segment("alpha").subscribe();
    Subscription betaMembership = receiver.segment("beta").subscribe();
    settle();

    publishAndAwait(alpha, "alpha", "a");
    publishAndAwait(beta, "beta", "b");
    assertEquals(List.of("a"), texts(alpha));
    assertEquals(List.of("b"), texts(beta));

    betaMembership.cancel();
    settle();
    publish("beta", "late");
    publishAndAwait(alpha, "alpha", "still");
    pause(Duration.ofMillis(2500));

    assertEquals(List.of("a", "still"), texts(alpha));
    assertEquals(List.of("b"), texts(beta));
  } // end method routesEachSegmentsMessagesToItsOwnListenersOnly

  @Test
  void keepsOtherListenersAndTheSubscriptionWhenOneListenerStops() throws Exception {
    pair("dispose");
    Recorder<String> stopped = new Recorder<>();
    Registration stopListening =
        receiver.segment("chat").onMessage((payload, metadata) -> stopped.accept(text(payload)));

    Recorder<Delivery> kept = collect(receiver.segment("chat"));
    receiver.segment("chat").subscribe();
    settle();
    publishAndAwait(kept, "chat", "one");

    stopListening.close();
    publishAndAwait(kept, "chat", "two");

    assertEquals(List.of("one"), stopped.all());
    assertEquals(List.of("one", "two"), texts(kept));
  } // end method keepsOtherListenersAndTheSubscriptionWhenOneListenerStops

  // ---- Joins the server makes without a message subscription (SEG-01) ----

  @Test
  void deliversToASegmentJoinedByPublishing() throws Exception {
    pair("publish-join");
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    await(receiver.segment("chat").publish(Payloads.text("joining")), "publishing joining");
    settle();

    publishAndAwait(chat, "chat", "after");

    assertEquals(List.of("after"), texts(chat));
  } // end method deliversToASegmentJoinedByPublishing

  // Watching presence is not membership: it neither joins nor holds.
  @Test
  void neverJoinsOrHoldsASegmentForAPresenceSubscription() throws Exception {
    pair("presence-watch");
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    receiver.segment("chat").subscribePresence();
    settle();
    publish("chat", "unheard");
    confirmQuiet();
    assertEquals(List.of(), texts(chat));

    Subscription messages = receiver.segment("chat").subscribe();
    settle();
    publishAndAwait(chat, "chat", "one");

    messages.cancel();
    settle();
    publish("chat", "two");
    confirmQuiet();

    assertEquals(List.of("one"), texts(chat));
  } // end method neverJoinsOrHoldsASegmentForAPresenceSubscription

  @Test
  void leavesASegmentJoinedByPublishingOnTheLastCancel() throws Exception {
    pair("publish-leave");
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    await(receiver.segment("chat").publish(Payloads.text("joining")), "publishing joining");
    Subscription membership = receiver.segment("chat").subscribe();
    settle();

    membership.cancel();
    settle();
    publish("chat", "late");
    confirmQuiet();

    assertEquals(List.of(), texts(chat));
  } // end method leavesASegmentJoinedByPublishingOnTheLastCancel

  // ---- Token permissions: publishing joins, but read access is checked at the join ----

  @Test
  void receivesAfterPublishingWithAReadWriteToken() throws Exception {
    pair("publish-read-write", claims().withPermission(true, true));
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    await(receiver.segment("chat").publish(Payloads.text("joining")), "publishing joining");
    settle();

    publishAndAwait(chat, "chat", "after");

    assertEquals(List.of("after"), texts(chat));
  } // end method receivesAfterPublishingWithAReadWriteToken

  @Test
  void receivesNothingAfterPublishingWithAWriteOnlyToken() throws Exception {
    pair("publish-write-only", claims().withPermission(false, true));
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    Recorder<Delivery> publisherChat = collect(publisher.segment("chat"));
    publisher.segment("chat").subscribe();
    settle();

    // The publish lands, which proves the write-only connection is up.
    await(receiver.segment("chat").publish(Payloads.text("joining")), "publishing joining");
    publisherChat.await(withText("joining"), "\"joining\" on chat", DELIVERY_TIMEOUT);
    publish("chat", "unheard");
    pause(Duration.ofMillis(2500));

    assertEquals(List.of(), texts(chat));
  } // end method receivesNothingAfterPublishingWithAWriteOnlyToken

  @Test
  void refusesAReadOnlyTokensPublishAndDeliversOnceSubscribed() throws Exception {
    pair("publish-read-only", claims().withPermission(true, false));
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    Recorder<RuntimeException> errors = collectErrors(receiver);

    await(receiver.segment("chat").publish(Payloads.text("refused")), "publishing refused");
    errors.await(
        serverErrorOfType(ServerErrorException.PERMISSION_DENIED_ERROR),
        "the publish denial",
        DELIVERY_TIMEOUT);
    publish("chat", "unheard");
    confirmQuiet();
    assertEquals(List.of(), texts(chat));

    receiver.segment("chat").subscribe();
    settle();
    publishAndAwait(chat, "chat", "heard");

    assertEquals(List.of("heard"), texts(chat));
  } // end method refusesAReadOnlyTokensPublishAndDeliversOnceSubscribed

  // ---- Connections: one channel is one WebSocket; its segments share it (SEG-01) ----

  private List<String> connectionsIn(String segmentId) throws InterruptedException {
    return await(publisher.segment(segmentId).presenceList(1, 25), "listing " + segmentId)
        .connections()
        .stream()
        .map(PresenceConnection::connectionId)
        .toList();
  } // end method connectionsIn

  @Test
  void multiplexesAChannelsSegmentsOverOneConnection() throws Exception {
    pair("multiplex");
    receiver.segment("alpha").subscribe();
    receiver.segment("beta").subscribe();
    pause(Duration.ofSeconds(3));

    List<String> alpha = connectionsIn("alpha");
    assertEquals(1, alpha.size());
    assertEquals(alpha, connectionsIn("beta"));

    Channel second = live.connectedChannel(reference);
    second.segment("alpha").subscribe();
    pause(Duration.ofSeconds(3));

    List<String> both = connectionsIn("alpha");
    assertEquals(2, both.size());
    assertEquals(2, Set.copyOf(both).size());
  } // end method multiplexesAChannelsSegmentsOverOneConnection

  // ---- Channel-wide listener (MSG-02) ----

  @Test
  void closingOneChannelListenerLeavesEveryOtherListener() throws Exception {
    pair("channel-listener-remove");
    Recorder<String> removed = new Recorder<>();
    Recorder<String> kept = new Recorder<>();
    Registration channelListener =
        receiver.events().onMessage((payload, metadata) -> removed.accept(text(payload)));

    receiver.events().onMessage((payload, metadata) -> kept.accept(text(payload)));
    Recorder<Delivery> chat = collect(receiver.segment("chat"));
    receiver.segment("chat").subscribe();
    settle();
    publishAndAwait(chat, "chat", "one");
    waitUntil(() -> kept.count() >= 1, "\"one\" on the kept channel listener", DELIVERY_TIMEOUT);

    channelListener.close();
    publishAndAwait(chat, "chat", "two");
    waitUntil(() -> kept.count() >= 2, "\"two\" on the kept channel listener", DELIVERY_TIMEOUT);

    assertEquals(List.of("one"), removed.all());
    assertEquals(List.of("one", "two"), kept.all());
    assertEquals(List.of("one", "two"), texts(chat));
  } // end method closingOneChannelListenerLeavesEveryOtherListener

  @Test
  void channelListenerCatchesDeliveriesNoSegmentListenerAskedFor() throws Exception {
    pair("channel-listener");
    Recorder<String> seen = new Recorder<>();
    receiver
        .events()
        .onMessage((payload, metadata) -> seen.accept(metadata.segmentId() + ":" + text(payload)));

    await(receiver.segment("joined").publish(Payloads.text("joining")), "publishing joining");
    settle();

    publish("joined", "x");
    publish("default", "y");
    waitUntil(() -> seen.count() >= 2, "both channel-wide deliveries", DELIVERY_TIMEOUT);

    assertEquals(List.of("default:y", "joined:x"), seen.all().stream().sorted().toList());
  } // end method channelListenerCatchesDeliveriesNoSegmentListenerAskedFor
} // end class SegmentsLiveTest

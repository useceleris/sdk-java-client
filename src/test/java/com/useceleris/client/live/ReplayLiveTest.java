package com.useceleris.client.live;

import static com.useceleris.client.live.LiveSupport.GENERATED_MESSAGE_ID;
import static com.useceleris.client.live.LiveSupport.await;
import static com.useceleris.client.live.LiveSupport.claims;
import static com.useceleris.client.live.LiveSupport.client;
import static com.useceleris.client.live.LiveSupport.collect;
import static com.useceleris.client.live.LiveSupport.pause;
import static com.useceleris.client.live.LiveSupport.settle;
import static com.useceleris.client.live.LiveSupport.texts;
import static com.useceleris.client.live.LiveSupport.uniqueChannelReference;
import static com.useceleris.client.live.LiveSupport.waitUntil;
import static com.useceleris.client.live.LiveSupport.withText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.useceleris.client.Channel;
import com.useceleris.client.Payloads;
import com.useceleris.client.Subscription;
import com.useceleris.client.live.LiveSupport.Delivery;
import com.useceleris.client.live.LiveSupport.Recorder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

@Tag("live")
@Timeout(120)
final class ReplayLiveTest {
  private static final long REPLAY_LOOKBACK_MILLISECONDS = 60_000;

  /** The server keeps at most this many messages per segment for replay. */
  private static final int BACKLOG_CAPACITY = 100;

  /** Publishes are paced in batches below the per-second publish limit, so none is resent. */
  private static final int PUBLISH_BATCH_SIZE = 10;

  private static final Duration PUBLISH_BATCH_PAUSE = Duration.ofMillis(1100);

  private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(25);

  @RegisterExtension final LiveSupport live = new LiveSupport();

  private Channel replayingChannel(String reference) throws InterruptedException {
    return live.connectedChannel(reference, claims().withReplay(REPLAY_LOOKBACK_MILLISECONDS));
  } // end method replayingChannel

  private static void publishAll(Channel publisher, String segmentId, List<String> bodies)
      throws InterruptedException {
    for (String body : bodies) {
      await(publisher.segment(segmentId).publish(Payloads.text(body)), "publish " + body);
    }
  } // end method publishAll

  /** Waits until the recorder holds a delivery of this body, recorded before or after the call. */
  private static void arrival(Recorder<Delivery> deliveries, String body)
      throws InterruptedException {
    deliveries.await(withText(body), "\"" + body + "\"", ARRIVAL_TIMEOUT);
  } // end method arrival

  private static List<String> textsAndIds(Recorder<Delivery> deliveries) {
    return deliveries.all().stream()
        .map(delivery -> delivery.text() + " " + delivery.metadata().messageId())
        .collect(Collectors.toList());
  } // end method textsAndIds

  private static List<String> numbered(int count) {
    List<String> bodies = new ArrayList<>();

    for (int index = 0; index < count; index++) {
      bodies.add("m" + index);
    }

    return bodies;
  } // end method numbered

  /** REV-01: a fresh connection with a replay claim receives the same messages, ids preserved. */
  @Test
  void replaysRecentMessagesWithIdenticalIds() throws Exception {
    String reference = uniqueChannelReference("replay");
    Channel publisher = live.connectedChannel(reference);
    Channel liveReceiver = live.connectedChannel(reference);
    Recorder<Delivery> liveDeliveries = collect(liveReceiver.segment("history"));
    liveReceiver.segment("history").subscribe();
    settle();

    publishAll(publisher, "history", List.of("one", "two", "three"));
    arrival(liveDeliveries, "three");

    Channel replayReceiver = replayingChannel(reference);
    Recorder<Delivery> replayed = collect(replayReceiver.segment("history"));
    replayReceiver.segment("history").subscribe();
    arrival(replayed, "three");
    settle();

    assertEquals(List.of("one", "two", "three"), texts(liveDeliveries));
    assertEquals(textsAndIds(liveDeliveries), textsAndIds(replayed));

    for (Delivery delivery : replayed.all()) {
      String messageId = delivery.metadata().messageId();
      assertTrue(GENERATED_MESSAGE_ID.matcher(messageId).matches(), messageId);
    }
  } // end method replaysRecentMessagesWithIdenticalIds

  @Test
  void replaysNothingToATokenWithoutAReplayClaim() throws Exception {
    String reference = uniqueChannelReference("no-replay");
    Channel publisher = live.connectedChannel(reference);
    publishAll(publisher, "history", List.of("old-1", "old-2"));
    settle();

    Channel receiver = live.connectedChannel(reference);
    Recorder<Delivery> history = collect(receiver.segment("history"));
    receiver.segment("history").subscribe();
    settle();

    // The live message proves the join, so a replay would have arrived first.
    publishAll(publisher, "history", List.of("live"));
    arrival(history, "live");
    settle();

    assertEquals(List.of("live"), texts(history));
  } // end method replaysNothingToATokenWithoutAReplayClaim

  @Test
  void replaysInPublishOrder() throws Exception {
    String reference = uniqueChannelReference("replay-order");
    Channel publisher = live.connectedChannel(reference);
    List<String> published = numbered(10);
    publishAll(publisher, "history", published);
    settle();

    Channel receiver = replayingChannel(reference);
    Recorder<Delivery> history = collect(receiver.segment("history"));
    receiver.segment("history").subscribe();
    arrival(history, "m9");
    settle();

    assertEquals(published, texts(history));
  } // end method replaysInPublishOrder

  @Test
  @Timeout(180)
  void replaysAtMostTheLastHundredMessagesOfASegment() throws Exception {
    String reference = uniqueChannelReference("replay-capacity");
    Channel publisher = live.connectedChannel(reference);
    List<String> published = numbered(BACKLOG_CAPACITY + 5);

    for (int start = 0; start < published.size(); start += PUBLISH_BATCH_SIZE) {
      int end = Math.min(start + PUBLISH_BATCH_SIZE, published.size());
      publishAll(publisher, "history", published.subList(start, end));
      pause(PUBLISH_BATCH_PAUSE);
    }

    Channel receiver = replayingChannel(reference);
    Recorder<Delivery> history = collect(receiver.segment("history"));
    receiver.segment("history").subscribe();
    arrival(history, published.get(published.size() - 1));
    settle();

    assertEquals(
        published.subList(published.size() - BACKLOG_CAPACITY, published.size()), texts(history));
  } // end method replaysAtMostTheLastHundredMessagesOfASegment

  @Test
  void replaysOnlyTheWindowOfANumericReplayClaim() throws Exception {
    String reference = uniqueChannelReference("replay-window");
    Channel publisher = live.connectedChannel(reference);
    publishAll(publisher, "history", List.of("old"));
    pause(Duration.ofSeconds(5));
    publishAll(publisher, "history", List.of("recent"));
    settle();

    Channel receiver = live.connectedChannel(reference, claims().withReplay(3_000));
    Recorder<Delivery> history = collect(receiver.segment("history"));
    receiver.segment("history").subscribe();
    arrival(history, "recent");
    pause(Duration.ofMillis(2_500));

    assertEquals(List.of("recent"), texts(history));
  } // end method replaysOnlyTheWindowOfANumericReplayClaim

  @Test
  void replaysTheDefaultSegmentOnConnect() throws Exception {
    String reference = uniqueChannelReference("replay-default");
    Channel publisher = live.connectedChannel(reference);
    publishAll(publisher, "default", List.of("d1", "d2"));
    settle();

    // The listener is in place before connect, when the server joins default.
    Channel receiver =
        live.channel(client(claims().withReplay(REPLAY_LOOKBACK_MILLISECONDS)), reference);
    Recorder<Delivery> lobby = collect(receiver.segment("default"));
    await(receiver.connect(), "connecting " + reference);
    arrival(lobby, "d2");
    settle();

    assertEquals(List.of("d1", "d2"), texts(lobby));
  } // end method replaysTheDefaultSegmentOnConnect

  @Test
  void replaysEachSegmentsBacklogWhenThatSegmentIsJoined() throws Exception {
    String reference = uniqueChannelReference("replay-per-join");
    Channel publisher = live.connectedChannel(reference);
    publishAll(publisher, "alpha", List.of("a1"));
    publishAll(publisher, "beta", List.of("b1"));
    settle();

    Channel receiver = replayingChannel(reference);
    Recorder<Delivery> alpha = collect(receiver.segment("alpha"));
    Recorder<Delivery> beta = collect(receiver.segment("beta"));
    receiver.segment("alpha").subscribe();
    arrival(alpha, "a1");
    settle();
    assertEquals(List.of(), texts(beta));

    receiver.segment("beta").subscribe();
    arrival(beta, "b1");
    settle();

    assertEquals(List.of("a1"), texts(alpha));
    assertEquals(List.of("b1"), texts(beta));
  } // end method replaysEachSegmentsBacklogWhenThatSegmentIsJoined

  @Test
  void replaysToASegmentJoinedByPublishing() throws Exception {
    String reference = uniqueChannelReference("replay-publish-join");
    Channel publisher = live.connectedChannel(reference);
    publishAll(publisher, "history", List.of("h1"));
    settle();

    Channel receiver = replayingChannel(reference);
    Recorder<Delivery> history = collect(receiver.segment("history"));
    publishAll(receiver, "history", List.of("joining"));
    arrival(history, "h1");
    settle();

    assertEquals(List.of("h1"), texts(history));
  } // end method replaysToASegmentJoinedByPublishing

  @Test
  void recoversWhatARejoinMissedAndDropsWhatItAlreadyDelivered() throws Exception {
    String reference = uniqueChannelReference("replay-rejoin");
    Channel publisher = live.connectedChannel(reference);
    Channel receiver = replayingChannel(reference);
    Recorder<Delivery> history = collect(receiver.segment("history"));
    Subscription first = receiver.segment("history").subscribe();
    settle();
    publishAll(publisher, "history", List.of("one"));
    arrival(history, "one");

    first.cancel();
    settle();
    publishAll(publisher, "history", List.of("two"));
    settle();

    // The re-join replays "one" and "two"; the deduplication window drops "one".
    receiver.segment("history").subscribe();
    arrival(history, "two");
    publishAll(publisher, "history", List.of("three"));
    arrival(history, "three");
    settle();

    assertEquals(List.of("one", "two", "three"), texts(history));
    assertEquals(
        3,
        history.all().stream().map(delivery -> delivery.metadata().messageId()).distinct().count());
  } // end method recoversWhatARejoinMissedAndDropsWhatItAlreadyDelivered

  @Test
  void replaysToTheChannelListenerOneTimeForEachMessage() throws Exception {
    String reference = uniqueChannelReference("replay-channel-listener");
    Channel publisher = live.connectedChannel(reference);
    publishAll(publisher, "history", List.of("h1", "h2"));
    settle();

    Channel receiver = replayingChannel(reference);
    Recorder<String> seen = new Recorder<>();
    receiver
        .events()
        .onMessage(
            (payload, metadata) ->
                seen.accept(metadata.segmentId() + ":" + Payloads.readText(payload)));
    Subscription membership = receiver.segment("history").subscribe();
    waitUntil(() -> seen.count() >= 2, "both replayed messages", ARRIVAL_TIMEOUT);

    // A re-join replays both again; the channel listener sees neither twice.
    membership.cancel();
    settle();
    receiver.segment("history").subscribe();
    pause(Duration.ofSeconds(4));

    assertEquals(List.of("history:h1", "history:h2"), seen.all());
  } // end method replaysToTheChannelListenerOneTimeForEachMessage
} // end class ReplayLiveTest

package com.useceleris.client.live;

import static com.useceleris.client.live.LiveSupport.GENERATED_MESSAGE_ID;
import static com.useceleris.client.live.LiveSupport.await;
import static com.useceleris.client.live.LiveSupport.claims;
import static com.useceleris.client.live.LiveSupport.collect;
import static com.useceleris.client.live.LiveSupport.pause;
import static com.useceleris.client.live.LiveSupport.peerWebsocketUrl;
import static com.useceleris.client.live.LiveSupport.texts;
import static com.useceleris.client.live.LiveSupport.uniqueChannelReference;
import static com.useceleris.client.live.LiveSupport.withText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.useceleris.client.Channel;
import com.useceleris.client.Payloads;
import com.useceleris.client.PresenceEvent;
import com.useceleris.client.PresencePage;
import com.useceleris.client.live.LiveSupport.Delivery;
import com.useceleris.client.live.LiveSupport.Recorder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Fan-out, presence, replay and ordering between connections on different nodes (REC-03). The
 * second connection opens through CELERIS_WS_URL_PEER, a gateway that routes to a different node;
 * without it the suite skips, because two connections through one gateway can share a node.
 */
@Tag("live")
@Timeout(120)
@EnabledIf("com.useceleris.client.live.LiveSupport#hasPeerWebsocketUrl")
final class CrossNodeLiveTest {
  @RegisterExtension final LiveSupport live = new LiveSupport();

  @Test
  void fansOutPublishesAcrossNodes() throws Exception {
    String reference = uniqueChannelReference("xnode");
    Channel primary = live.connectedChannel(reference);
    Channel secondary = live.connectedChannel(reference, claims(), peerWebsocketUrl());
    Recorder<Delivery> received = collect(secondary.segment("chat"));
    secondary.segment("chat").subscribe();
    pause(Duration.ofSeconds(2));

    await(primary.segment("chat").publish(Payloads.text("across")), "publish");

    Delivery delivery =
        received.await(withText("across"), "cross-node delivery", Duration.ofSeconds(25));
    assertTrue(
        GENERATED_MESSAGE_ID.matcher(delivery.metadata().messageId()).matches(),
        delivery.metadata().toString());
  } // end method fansOutPublishesAcrossNodes

  @Test
  void reportsConsistentPresenceAcrossNodes() throws Exception {
    String reference = uniqueChannelReference("xpres");
    Channel primary = live.connectedChannel(reference);
    Channel secondary = live.connectedChannel(reference, claims(), peerWebsocketUrl());
    primary.segment("room").subscribe();
    secondary.segment("room").subscribe();
    pause(Duration.ofSeconds(3));

    PresencePage fromPrimary = await(primary.segment("room").presenceList(1, 25), "primary list");
    PresencePage fromSecondary =
        await(secondary.segment("room").presenceList(1, 25), "secondary list");

    assertEquals(fromPrimary.total(), fromSecondary.total());
    assertTrue(fromPrimary.total() >= 2, "total " + fromPrimary.total());
  } // end method reportsConsistentPresenceAcrossNodes

  @Test
  void deliversAPresenceJoinFromAnotherNode() throws Exception {
    String reference = uniqueChannelReference("xpres-event");
    Channel watcher = live.connectedChannel(reference);
    Recorder<PresenceEvent> events = new Recorder<>();
    watcher.segment("room").onPresence(events);
    watcher.segment("room").subscribePresence();
    pause(Duration.ofSeconds(2));

    Channel joiner = live.connectedChannel(reference, claims(), peerWebsocketUrl());
    joiner.segment("room").subscribe();

    PresenceEvent joined =
        events.await(PresenceEvent::joined, "a cross-node join", Duration.ofSeconds(20));
    assertEquals("room", joined.segmentId());
  } // end method deliversAPresenceJoinFromAnotherNode

  @Test
  void deliversAPresenceLeaveFromAnotherNode() throws Exception {
    String reference = uniqueChannelReference("xpres-leave");
    Channel watcher = live.connectedChannel(reference);
    Recorder<PresenceEvent> events = new Recorder<>();
    watcher.segment("room").onPresence(events);
    watcher.segment("room").subscribePresence();
    pause(Duration.ofSeconds(2));
    Channel leaver = live.connectedChannel(reference, claims(), peerWebsocketUrl());

    leaver.segment("room").subscribe();
    PresenceEvent join =
        events.await(PresenceEvent::joined, "the cross-node join", Duration.ofSeconds(20));
    leaver.close();

    events.await(
        event -> !event.joined() && event.connectionId().equals(join.connectionId()),
        "the cross-node leave",
        Duration.ofSeconds(20));
  } // end method deliversAPresenceLeaveFromAnotherNode

  @Test
  void replaysHistoryPublishedOnAnotherNode() throws Exception {
    String reference = uniqueChannelReference("xreplay");
    Channel publisher = live.connectedChannel(reference);

    for (String body : List.of("h1", "h2", "h3")) {
      await(publisher.segment("history").publish(Payloads.text(body)), "publish " + body);
    }

    pause(Duration.ofSeconds(2));
    Channel receiver = live.connectedChannel(reference, claims().withReplay(), peerWebsocketUrl());
    Recorder<Delivery> replayed = collect(receiver.segment("history"));

    receiver.segment("history").subscribe();
    replayed.await(withText("h3"), "the cross-node replay", Duration.ofSeconds(25));
    pause(Duration.ofSeconds(2));

    assertEquals(List.of("h1", "h2", "h3"), texts(replayed));
  } // end method replaysHistoryPublishedOnAnotherNode

  @Test
  void keepsOneOriginsOrderAcrossNodes() throws Exception {
    String reference = uniqueChannelReference("xorder");
    Channel publisher = live.connectedChannel(reference);
    Channel receiver = live.connectedChannel(reference, claims(), peerWebsocketUrl());
    Recorder<Delivery> received = collect(receiver.segment("chat"));
    receiver.segment("chat").subscribe();
    pause(Duration.ofSeconds(2));
    List<String> bodies = new ArrayList<>();

    for (int index = 0; index < 30; index++) {
      bodies.add("o" + index);
    }

    for (int start = 0; start < bodies.size(); start += 10) {
      for (String body : bodies.subList(start, start + 10)) {
        await(publisher.segment("chat").publish(Payloads.text(body)), "publish " + body);
      }

      pause(Duration.ofMillis(1100));
    }

    received.await(withText("o29"), "the last ordered message", Duration.ofSeconds(30));
    pause(Duration.ofSeconds(2));

    assertEquals(bodies, texts(received));
  } // end method keepsOneOriginsOrderAcrossNodes
} // end class CrossNodeLiveTest

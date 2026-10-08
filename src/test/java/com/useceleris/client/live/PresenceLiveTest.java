package com.useceleris.client.live;

import static com.useceleris.client.live.LiveSupport.DELIVERY_TIMEOUT;
import static com.useceleris.client.live.LiveSupport.await;
import static com.useceleris.client.live.LiveSupport.claims;
import static com.useceleris.client.live.LiveSupport.collect;
import static com.useceleris.client.live.LiveSupport.collectErrors;
import static com.useceleris.client.live.LiveSupport.failureOf;
import static com.useceleris.client.live.LiveSupport.pause;
import static com.useceleris.client.live.LiveSupport.settle;
import static com.useceleris.client.live.LiveSupport.uniqueChannelReference;
import static com.useceleris.client.live.LiveSupport.waitUntil;
import static com.useceleris.client.live.LiveSupport.withText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.useceleris.client.Channel;
import com.useceleris.client.ChannelState;
import com.useceleris.client.Payloads;
import com.useceleris.client.PresenceConnection;
import com.useceleris.client.PresenceEvent;
import com.useceleris.client.PresencePage;
import com.useceleris.client.Segment;
import com.useceleris.client.ServerErrorException;
import com.useceleris.client.Subscription;
import com.useceleris.client.live.LiveSupport.Delivery;
import com.useceleris.client.live.LiveSupport.Recorder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

@Tag("live")
@Timeout(120)
final class PresenceLiveTest {
  @RegisterExtension final LiveSupport live = new LiveSupport();

  @Test
  void deliversTypedJoinAndLeaveNotificationsToWatchers() throws Exception {
    String reference = uniqueChannelReference("watch");
    Channel watcher = live.connectedChannel(reference);
    Segment watched = watcher.segment("room");
    watched.subscribePresence();
    settle();
    Recorder<PresenceEvent> events = new Recorder<>();
    watched.onPresence(events);

    Channel actor = live.connectedChannel(reference);
    actor.segment("room").subscribe();

    PresenceEvent join =
        events.await(PresenceEvent::joined, "a join notification", DELIVERY_TIMEOUT);
    assertEquals("room", join.segmentId());
    assertFalse(join.tokenReference().isEmpty());
    assertFalse(join.connectionId().isEmpty());
    assertTrue(join.timestamp() > 0);

    actor.close();

    PresenceEvent leave =
        events.await(
            event -> !event.joined() && event.connectionId().equals(join.connectionId()),
            "the actor's leave notification",
            DELIVERY_TIMEOUT);
    assertEquals("room", leave.segmentId());
    assertEquals(join.tokenReference(), leave.tokenReference());
    assertTrue(leave.timestamp() > 0);
  } // end method deliversTypedJoinAndLeaveNotificationsToWatchers

  @Test
  void pagesPresenceSnapshotsAndPastTheEnd() throws Exception {
    String reference = uniqueChannelReference("plist");
    Channel first = live.connectedChannel(reference);
    Channel second = live.connectedChannel(reference);
    first.segment("room").subscribe();
    second.segment("room").subscribe();
    pause(Duration.ofMillis(2500));

    PresencePage page = await(first.segment("room").presenceList(1, 10), "first page");

    assertEquals("room", page.segmentId());
    assertTrue(page.total() >= 2, page.toString());
    assertTrue(page.connections().size() >= 2, page.toString());
    assertEquals(1, page.from(), page.toString());
    assertEquals(1, page.currentPage(), page.toString());

    for (PresenceConnection connection : page.connections()) {
      assertFalse(connection.connectionId().isEmpty(), connection.toString());
      assertTrue(connection.timestamp() > 0, connection.toString());
    }

    PresencePage beyond = await(first.segment("room").presenceList(50, 10), "a page past the end");

    assertEquals(List.of(), beyond.connections(), beyond.toString());
    assertTrue(beyond.from() > beyond.to(), beyond.toString());
  } // end method pagesPresenceSnapshotsAndPastTheEnd

  @Test
  void listsEachConnectionOfOneTokenReferenceWithItsOwnConnectionId() throws Exception {
    String reference = uniqueChannelReference("presence-same-reference");
    Channel watcher = live.connectedChannel(reference, claims().withReference("watcher"));
    Recorder<PresenceEvent> events = new Recorder<>();
    watcher.segment("room").onPresence(events);
    watcher.segment("room").subscribePresence();
    settle();

    // One user with three tabs: three connections of one token reference.
    List<Channel> tabs = new ArrayList<>();

    for (int tab = 0; tab < 3; tab++) {
      tabs.add(live.connectedChannel(reference, claims().withReference("user_1")));
    }

    for (Channel tab : tabs) {
      tab.segment("room").subscribe();
    }

    waitUntil(
        () -> events.all().stream().filter(PresenceEvent::joined).count() >= 3,
        "three joins",
        Duration.ofSeconds(20));
    List<PresenceEvent> joins = events.all().stream().filter(PresenceEvent::joined).toList();
    List<String> joinIds = joins.stream().map(PresenceEvent::connectionId).toList();
    assertEquals(
        List.of("user_1", "user_1", "user_1"),
        joins.stream().map(PresenceEvent::tokenReference).toList());
    assertEquals(3, new HashSet<>(joinIds).size(), joinIds.toString());

    // The largest page size, 100, lists all three.
    PresencePage page = await(watcher.segment("room").presenceList(1, 100), "the list");
    assertEquals(3, page.total(), page.toString());
    assertEquals(
        List.of("user_1", "user_1", "user_1"),
        page.connections().stream().map(PresenceConnection::tokenReference).toList());
    assertEquals(
        new HashSet<>(joinIds),
        page.connections().stream()
            .map(PresenceConnection::connectionId)
            .collect(Collectors.toSet()));

    // Closing one tab removes only that connection.
    tabs.get(0).close();
    PresenceEvent leave =
        events.await(event -> !event.joined(), "the leave of one tab", DELIVERY_TIMEOUT);
    assertEquals("user_1", leave.tokenReference());
    assertTrue(joinIds.contains(leave.connectionId()), leave.toString());

    settle();
    PresencePage after = await(watcher.segment("room").presenceList(1, 100), "the list after");
    Set<String> remaining = new HashSet<>(joinIds);
    remaining.remove(leave.connectionId());
    assertEquals(2, after.total(), after.toString());
    assertEquals(
        remaining,
        after.connections().stream()
            .map(PresenceConnection::connectionId)
            .collect(Collectors.toSet()));
  } // end method listsEachConnectionOfOneTokenReferenceWithItsOwnConnectionId

  /**
   * Presence needs read access. The denial names the query by its request id, so the caller hears
   * it at once instead of waiting out the deadline.
   */
  @Test
  void rejectsAWriteOnlyTokensPresenceQueryAtOnce() throws Exception {
    Channel writeOnly =
        live.connectedChannel(
            uniqueChannelReference("pdeny"), claims().withPermission(false, true));
    Recorder<RuntimeException> reported = collectErrors(writeOnly);
    long started = System.nanoTime();

    Throwable failure = failureOf(writeOnly.segment("room").presenceList(1, 10), "presence query");

    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
    assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) <= 0, "rejected after " + elapsed);
    ServerErrorException denial = assertInstanceOf(ServerErrorException.class, failure);
    assertEquals(ServerErrorException.PERMISSION_DENIED_ERROR, denial.type());
    assertEquals(Optional.of("PRES_LIST"), denial.subType());
    assertEquals(Optional.of("1"), denial.resource());

    pause(Duration.ofMillis(500));
    assertEquals(List.of(), reported.all(), "the denial was also reported to onError");
    assertEquals(ChannelState.CONNECTED, writeOnly.state());
  } // end method rejectsAWriteOnlyTokensPresenceQueryAtOnce

  /** Cancelling presence stops its events; the message subscription keeps delivering. */
  @Test
  void stopsPresenceAfterCancellationWhileTheSubscriptionStays() throws Exception {
    String reference = uniqueChannelReference("unwatch");
    Channel watcher = live.connectedChannel(reference);
    Segment room = watcher.segment("room");
    room.subscribe();
    Subscription watching = room.subscribePresence();
    settle();
    watching.cancel();
    settle();

    Recorder<PresenceEvent> events = new Recorder<>();
    room.onPresence(events);
    Recorder<Delivery> delivered = collect(room);
    Channel actor = live.connectedChannel(reference);
    actor.segment("room").subscribe();

    await(actor.segment("room").publish(Payloads.text("still-member")), "publish");

    delivered.await(
        withText("still-member"), "delivery proving the subscription stayed", DELIVERY_TIMEOUT);
    assertEquals(List.of(), events.all(), "presence events after cancellation");
  } // end method stopsPresenceAfterCancellationWhileTheSubscriptionStays

  @Test
  void hidesAConnectionsOwnJoinAndShowsItToASiblingOfTheSameToken() throws Exception {
    String reference = uniqueChannelReference("presence-self");
    Channel first = live.connectedChannel(reference, claims().withReference("alice"));
    Channel second = live.connectedChannel(reference, claims().withReference("alice"));
    Recorder<PresenceEvent> firstSaw = new Recorder<>();
    Recorder<PresenceEvent> secondSaw = new Recorder<>();
    first.segment("room").onPresence(firstSaw);
    second.segment("room").onPresence(secondSaw);
    first.segment("room").subscribePresence();
    second.segment("room").subscribePresence();
    settle();

    first.segment("room").subscribe();
    PresenceEvent firstJoin =
        secondSaw.await(PresenceEvent::joined, "the sibling's join", DELIVERY_TIMEOUT);
    settle();

    assertEquals("alice", firstJoin.tokenReference());
    assertEquals(List.of(), firstSaw.all());
    assertEquals(1, secondSaw.count(), secondSaw.all().toString());
  } // end method hidesAConnectionsOwnJoinAndShowsItToASiblingOfTheSameToken

  @Test
  void sendsALeaveWhenAMemberUnsubscribesAndStaysConnected() throws Exception {
    String reference = uniqueChannelReference("presence-unsubscribe");
    Channel watcher = live.connectedChannel(reference);
    Channel actor = live.connectedChannel(reference, claims().withReference("actor"));
    Recorder<PresenceEvent> events = new Recorder<>();
    watcher.segment("room").onPresence(events);
    watcher.segment("room").subscribePresence();
    settle();

    Subscription membership = actor.segment("room").subscribe();
    PresenceEvent join =
        events.await(
            event -> event.joined() && event.tokenReference().equals("actor"),
            "the actor's join",
            DELIVERY_TIMEOUT);
    membership.cancel();
    PresenceEvent leave =
        events.await(
            event -> !event.joined() && event.tokenReference().equals("actor"),
            "the actor's leave",
            DELIVERY_TIMEOUT);

    assertEquals(join.connectionId(), leave.connectionId());
    assertEquals(ChannelState.CONNECTED, actor.state());
  } // end method sendsALeaveWhenAMemberUnsubscribesAndStaysConnected

  @Test
  void announcesDefaultSegmentJoinsOnConnectAndLeavesOnCloseAndListsEveryConnection()
      throws Exception {
    String reference = uniqueChannelReference("presence-default");
    Channel watcher = live.connectedChannel(reference, claims().withReference("watcher"));
    Recorder<PresenceEvent> events = new Recorder<>();
    watcher.defaultSegment().onPresence(events);
    watcher.defaultSegment().subscribePresence();
    settle();

    Channel late = live.connectedChannel(reference, claims().withReference("late"));
    PresenceEvent join =
        events.await(
            event -> event.joined() && event.tokenReference().equals("late"),
            "the join from connect",
            DELIVERY_TIMEOUT);
    assertEquals("default", join.segmentId());

    PresencePage page = await(watcher.defaultSegment().presenceList(1, 10), "default list");
    assertEquals(
        List.of("late", "watcher"),
        page.connections().stream().map(PresenceConnection::tokenReference).sorted().toList(),
        page.toString());

    late.close();
    events.await(
        event -> !event.joined() && event.connectionId().equals(join.connectionId()),
        "the leave from close",
        DELIVERY_TIMEOUT);
  } // end method announcesDefaultSegmentJoinsOnConnectAndLeavesOnCloseAndListsEveryConnection

  @Test
  void pagesThroughSeveralFullPagesAndPastTheEnd() throws Exception {
    String reference = uniqueChannelReference("presence-pages");
    List<Channel> members =
        List.of(
            live.connectedChannel(reference, claims().withReference("m1")),
            live.connectedChannel(reference, claims().withReference("m2")),
            live.connectedChannel(reference, claims().withReference("m3")));

    for (Channel member : members) {
      member.segment("room").subscribe();
    }

    pause(Duration.ofSeconds(3));
    List<PresencePage> pages = new ArrayList<>();

    for (int page = 1; page <= 4; page++) {
      pages.add(await(members.get(0).segment("room").presenceList(page, 1), "page " + page));
    }

    for (int index = 0; index < 3; index++) {
      PresencePage page = pages.get(index);
      assertEquals(3, page.total(), page.toString());
      assertEquals(1, page.perPage(), page.toString());
      assertEquals(index + 1, page.currentPage(), page.toString());
      assertEquals(index + 1, page.from(), page.toString());
      assertEquals(index + 1, page.to(), page.toString());
      assertEquals(1, page.connections().size(), page.toString());
    }

    PresencePage beyond = pages.get(3);
    assertEquals(3, beyond.total(), beyond.toString());
    assertEquals(4, beyond.from(), beyond.toString());
    assertEquals(3, beyond.to(), beyond.toString());
    assertEquals(List.of(), beyond.connections(), beyond.toString());

    List<PresenceConnection> listed =
        pages.stream().flatMap(page -> page.connections().stream()).toList();
    assertEquals(
        3,
        new HashSet<>(listed.stream().map(PresenceConnection::connectionId).toList()).size(),
        listed.toString());
    assertEquals(
        List.of("m1", "m2", "m3"),
        listed.stream().map(PresenceConnection::tokenReference).sorted().toList());
  } // end method pagesThroughSeveralFullPagesAndPastTheEnd
} // end class PresenceLiveTest

package com.useceleris.client.live;

import static com.useceleris.client.live.LiveSupport.DELIVERY_TIMEOUT;
import static com.useceleris.client.live.LiveSupport.await;
import static com.useceleris.client.live.LiveSupport.claims;
import static com.useceleris.client.live.LiveSupport.collect;
import static com.useceleris.client.live.LiveSupport.collectErrors;
import static com.useceleris.client.live.LiveSupport.failureOf;
import static com.useceleris.client.live.LiveSupport.pause;
import static com.useceleris.client.live.LiveSupport.settle;
import static com.useceleris.client.live.LiveSupport.texts;
import static com.useceleris.client.live.LiveSupport.uniqueChannelReference;
import static com.useceleris.client.live.LiveSupport.withText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.useceleris.client.Channel;
import com.useceleris.client.ChannelState;
import com.useceleris.client.Payloads;
import com.useceleris.client.PresenceConnection;
import com.useceleris.client.PresenceEvent;
import com.useceleris.client.PresencePage;
import com.useceleris.client.ServerErrorException;
import com.useceleris.client.live.LiveSupport.Claims;
import com.useceleris.client.live.LiveSupport.Delivery;
import com.useceleris.client.live.LiveSupport.Recorder;
import com.useceleris.client.live.LiveSupport.SegmentPermission;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Per-segment permissions, and the token reference as receivers see it. */
@Tag("live")
@Timeout(120)
final class PermissionsLiveTest {
  /**
   * A token that can read "readonly", write "writeonly", and nothing else: no other segment, and
   * not "default" either.
   */
  private static final Claims SEGMENT_PERMISSIONS =
      claims()
          .withReference("limited")
          .withPermissions(
              new SegmentPermission("readonly", true, false),
              new SegmentPermission("writeonly", false, true));

  @RegisterExtension final LiveSupport live = new LiveSupport();

  /** Waits for a permission denial, recorded before or after the call, naming the command. */
  private static void denial(Recorder<RuntimeException> errors, String subType, String segmentId)
      throws InterruptedException {
    errors.await(
        failure ->
            failure instanceof ServerErrorException serverError
                && serverError.type().equals(ServerErrorException.PERMISSION_DENIED_ERROR)
                && serverError.subType().equals(Optional.of(subType))
                && serverError.resource().equals(Optional.of(segmentId)),
        "a " + subType + " denial for " + segmentId,
        DELIVERY_TIMEOUT);
  } // end method denial

  @Test
  void letsAReadOnlySegmentReceiveAndRefusesItsPublish() throws Exception {
    String reference = uniqueChannelReference("perm-read");
    Channel publisher = live.connectedChannel(reference);
    Channel limited = live.connectedChannel(reference, SEGMENT_PERMISSIONS);
    Recorder<RuntimeException> errors = collectErrors(limited);
    Recorder<Delivery> readonly = collect(limited.segment("readonly"));
    limited.segment("readonly").subscribe();
    settle();

    await(publisher.segment("readonly").publish(Payloads.text("hello")), "publish hello");
    readonly.await(withText("hello"), "the read-only delivery", DELIVERY_TIMEOUT);

    await(limited.segment("readonly").publish(Payloads.text("refused")), "publish refused");
    denial(errors, "PUB", "readonly");

    assertEquals(List.of("hello"), texts(readonly));
    assertEquals(ChannelState.CONNECTED, limited.state());
  } // end method letsAReadOnlySegmentReceiveAndRefusesItsPublish

  @Test
  void letsAWriteOnlySegmentPublishAndReceiveNothing() throws Exception {
    String reference = uniqueChannelReference("perm-write");
    Channel reader = live.connectedChannel(reference);
    Channel limited = live.connectedChannel(reference, SEGMENT_PERMISSIONS);
    Recorder<Delivery> writeonly = collect(limited.segment("writeonly"));
    Recorder<Delivery> readerSaw = collect(reader.segment("writeonly"));
    reader.segment("writeonly").subscribe();
    limited.segment("writeonly").subscribe();
    settle();

    await(limited.segment("writeonly").publish(Payloads.text("from-limited")), "publish");
    readerSaw.await(
        withText("from-limited"), "the write-only publish at the reader", DELIVERY_TIMEOUT);
    await(reader.segment("writeonly").publish(Payloads.text("unheard")), "publish unheard");
    pause(Duration.ofMillis(2500));

    assertEquals(List.of(), texts(writeonly));
  } // end method letsAWriteOnlySegmentPublishAndReceiveNothing

  @Test
  void refusesPresenceOnASegmentWithoutReadAccess() throws Exception {
    Channel limited =
        live.connectedChannel(uniqueChannelReference("perm-presence"), SEGMENT_PERMISSIONS);
    Recorder<RuntimeException> errors = collectErrors(limited);

    limited.segment("writeonly").subscribePresence();
    denial(errors, "PRES_SUB", "writeonly");

    Throwable failure =
        failureOf(limited.segment("writeonly").presenceList(1, 10), "the write-only list");
    ServerErrorException refused = assertInstanceOf(ServerErrorException.class, failure);
    assertEquals(ServerErrorException.PERMISSION_DENIED_ERROR, refused.type());
    assertEquals(Optional.of("PRES_LIST"), refused.subType());

    PresencePage page = await(limited.segment("readonly").presenceList(1, 10), "read-only list");
    assertEquals(List.of(), page.connections(), page.toString());
  } // end method refusesPresenceOnASegmentWithoutReadAccess

  @Test
  void refusesEveryCommandOnASegmentTheTokenDoesNotList() throws Exception {
    Channel limited =
        live.connectedChannel(uniqueChannelReference("perm-unlisted"), SEGMENT_PERMISSIONS);
    Recorder<RuntimeException> errors = collectErrors(limited);

    limited.segment("secret").subscribe();
    denial(errors, "SUB", "secret");
    await(limited.segment("secret").publish(Payloads.text("x")), "publish");
    denial(errors, "PUB", "secret");
    limited.segment("secret").subscribePresence();
    denial(errors, "PRES_SUB", "secret");

    assertEquals(ChannelState.CONNECTED, limited.state());
  } // end method refusesEveryCommandOnASegmentTheTokenDoesNotList

  @Test
  void givesAnUnlistedDefaultSegmentNoReadAndNoWriteAccess() throws Exception {
    String reference = uniqueChannelReference("perm-default");
    Channel publisher = live.connectedChannel(reference);
    Channel limited = live.connectedChannel(reference, SEGMENT_PERMISSIONS);
    Recorder<RuntimeException> errors = collectErrors(limited);
    Recorder<Delivery> lobby = collect(limited.defaultSegment());
    Recorder<Delivery> readonly = collect(limited.segment("readonly"));
    limited.segment("readonly").subscribe();
    settle();

    await(publisher.defaultSegment().publish(Payloads.text("unheard")), "publish unheard");
    await(publisher.segment("readonly").publish(Payloads.text("control")), "publish control");
    readonly.await(withText("control"), "the read-only control", DELIVERY_TIMEOUT);
    await(limited.defaultSegment().publish(Payloads.text("refused")), "publish refused");
    denial(errors, "PUB", "default");
    settle();

    assertEquals(List.of(), texts(lobby));
    assertEquals(List.of("control"), texts(readonly));
  } // end method givesAnUnlistedDefaultSegmentNoReadAndNoWriteAccess

  @Test
  void showsTheReferenceClaimInMessageMetadataPresenceEventsAndPresenceLists() throws Exception {
    String reference = uniqueChannelReference("token-reference");
    Channel watcher = live.connectedChannel(reference, claims().withReference("watcher"));
    Channel alice = live.connectedChannel(reference, claims().withReference("alice"));
    Recorder<PresenceEvent> events = new Recorder<>();
    watcher.segment("room").onPresence(events);
    Recorder<Delivery> messages = collect(watcher.segment("room"));
    watcher.segment("room").subscribe();
    watcher.segment("room").subscribePresence();
    settle();

    alice.segment("room").subscribe();
    events.await(
        event -> event.joined() && event.tokenReference().equals("alice"),
        "alice's join",
        DELIVERY_TIMEOUT);

    await(alice.segment("room").publish(Payloads.text("hi")), "publish");
    Delivery message = messages.await(withText("hi"), "alice's message", DELIVERY_TIMEOUT);
    assertEquals("alice", message.metadata().tokenReference());

    PresencePage page = await(watcher.segment("room").presenceList(1, 10), "presence list");
    assertEquals(
        List.of("alice", "watcher"),
        page.connections().stream().map(PresenceConnection::tokenReference).sorted().toList(),
        page.toString());
  } // end method showsTheReferenceClaimInMessageMetadataPresenceEventsAndPresenceLists
} // end class PermissionsLiveTest

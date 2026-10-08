package com.useceleris.client.live;

import static com.useceleris.client.live.LiveSupport.DELIVERY_TIMEOUT;
import static com.useceleris.client.live.LiveSupport.GENERATED_MESSAGE_ID;
import static com.useceleris.client.live.LiveSupport.await;
import static com.useceleris.client.live.LiveSupport.claims;
import static com.useceleris.client.live.LiveSupport.collect;
import static com.useceleris.client.live.LiveSupport.collectErrors;
import static com.useceleris.client.live.LiveSupport.failureOf;
import static com.useceleris.client.live.LiveSupport.patterned;
import static com.useceleris.client.live.LiveSupport.pause;
import static com.useceleris.client.live.LiveSupport.sdkFailureOf;
import static com.useceleris.client.live.LiveSupport.serverErrorOfType;
import static com.useceleris.client.live.LiveSupport.settle;
import static com.useceleris.client.live.LiveSupport.signing;
import static com.useceleris.client.live.LiveSupport.texts;
import static com.useceleris.client.live.LiveSupport.uniqueChannelReference;
import static com.useceleris.client.live.LiveSupport.websocketUrl;
import static com.useceleris.client.live.LiveSupport.withText;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.useceleris.client.CelerisClient;
import com.useceleris.client.CelerisException;
import com.useceleris.client.Channel;
import com.useceleris.client.ChannelState;
import com.useceleris.client.ClientOptions;
import com.useceleris.client.ErrorCode;
import com.useceleris.client.Payloads;
import com.useceleris.client.Segment;
import com.useceleris.client.ServerErrorException;
import com.useceleris.client.live.LiveSupport.Delivery;
import com.useceleris.client.live.LiveSupport.Recorder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("live")
@Timeout(120)
final class MessagingLiveTest {
  @RegisterExtension final LiveSupport live = new LiveSupport();

  @Test
  void deliversBinaryPayloadsWithTheirIdsAndPerConnectionEcho() throws Exception {
    String reference = uniqueChannelReference("msg");
    Channel publisher = live.connectedChannel(reference);
    Channel receiver = live.connectedChannel(reference);
    Recorder<Delivery> publisherSaw = collect(publisher.segment("chat"));
    Recorder<Delivery> receiverSaw = collect(receiver.segment("chat"));
    publisher.segment("chat").subscribe();
    receiver.segment("chat").subscribe();
    settle();

    await(publisher.segment("chat").publish(Payloads.text("hello-바이너리")), "publish");

    Delivery delivery =
        receiverSaw.await(any -> true, "cross-connection delivery", DELIVERY_TIMEOUT);
    // REV-01: the id the SDK generated arrives unchanged.
    assertTrue(GENERATED_MESSAGE_ID.matcher(delivery.metadata().messageId()).matches());
    assertEquals("hello-바이너리", Payloads.readText(delivery.payload()));
    assertEquals("chat", delivery.metadata().segmentId());
    assertTrue(delivery.metadata().timestamp() > 0);

    // The publishing connection itself is echo-suppressed.
    settle();
    assertEquals(List.of(), publisherSaw.all(), "the publisher received its own publish");
  } // end method deliversBinaryPayloadsWithTheirIdsAndPerConnectionEcho

  @Test
  void echoesToThePublisherWhenTheTokenAllowsEcho() throws Exception {
    Channel channel =
        live.connectedChannel(uniqueChannelReference("echo"), claims().withAllowEcho());
    Segment chat = channel.segment("chat");
    Recorder<Delivery> received = collect(chat);
    chat.subscribe();
    settle();

    await(chat.publish(Payloads.text("self")), "publish");

    received.await(withText("self"), "an echoed publish", DELIVERY_TIMEOUT);
  } // end method echoesToThePublisherWhenTheTokenAllowsEcho

  @Test
  void deliversOnTheDefaultSegmentWithoutSubscribing() throws Exception {
    String reference = uniqueChannelReference("default");
    Channel publisher = live.connectedChannel(reference);
    Channel receiver = live.connectedChannel(reference);
    Recorder<Delivery> received = collect(receiver.defaultSegment());
    settle();

    await(publisher.defaultSegment().publish(Payloads.text("lobby")), "publish");

    Delivery delivery =
        received.await(withText("lobby"), "default-segment delivery", DELIVERY_TIMEOUT);
    assertEquals("default", delivery.metadata().segmentId());
  } // end method deliversOnTheDefaultSegmentWithoutSubscribing

  /**
   * Needs the qualification app on a plan with message_size_limit_in_kb of at least 1024. Framed,
   * the full payload is larger than 1 MiB (LIMIT-01).
   */
  @ParameterizedTest(name = "{0} KiB")
  @ValueSource(ints = {100, 1024})
  void roundTripsLargeBinaryPayloads(int kibibytes) throws Exception {
    String reference = uniqueChannelReference("large");
    Channel publisher = live.connectedChannel(reference);
    Channel receiver = live.connectedChannel(reference);
    Recorder<Delivery> received = collect(receiver.segment("bulk"));
    receiver.segment("bulk").subscribe();
    settle();
    byte[] payload = patterned(kibibytes * 1024);

    await(publisher.segment("bulk").publish(payload), "publish");

    Delivery delivery =
        received.await(
            any -> any.payload().length == payload.length,
            "the large delivery",
            Duration.ofSeconds(30));
    assertArrayEquals(payload, delivery.payload(), "the payload changed in transit");
  } // end method roundTripsLargeBinaryPayloads

  @Test
  void reportsAPublishOverThePlanCapAsMessageSizeLimit() throws Exception {
    Channel channel = live.connectedChannel(uniqueChannelReference("oversize"));
    Recorder<RuntimeException> errors = collectErrors(channel);

    // Over every plan's cap but under the 2 MiB ceiling, so it completes locally before the
    // server rejects it.
    await(channel.segment("bulk").publish(new byte[1536 * 1024]), "publish");

    RuntimeException rejection =
        errors.await(
            serverErrorOfType(ServerErrorException.MESSAGE_SIZE_LIMIT_ERROR),
            "the MessageSizeLimitError frame",
            Duration.ofSeconds(20));
    assertTrue(
        rejection.getMessage().contains("Message size limit exceeded"), rejection.getMessage());
    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method reportsAPublishOverThePlanCapAsMessageSizeLimit

  @Test
  void refusesACommandOverTwoMebibytesLocallyAndStaysConnected() throws Exception {
    String reference = uniqueChannelReference("ceiling");
    Channel publisher = live.connectedChannel(reference);
    Channel receiver = live.connectedChannel(reference);
    Recorder<Delivery> received = collect(receiver.segment("bulk"));
    receiver.segment("bulk").subscribe();
    settle();

    Throwable failure =
        failureOf(publisher.segment("bulk").publish(new byte[2 * 1024 * 1024]), "publish");

    assertEquals(ErrorCode.CONFIGURATION, assertInstanceOf(CelerisException.class, failure).code());
    await(publisher.segment("bulk").publish(Payloads.text("after")), "publish after");
    received.await(withText("after"), "the publish after the refusal", DELIVERY_TIMEOUT);
    assertEquals(List.of("after"), texts(received));
    assertEquals(ChannelState.CONNECTED, publisher.state());
  } // end method refusesACommandOverTwoMebibytesLocallyAndStaysConnected

  @Test
  void reportsAReadOnlyTokensPublishAsPermissionDenied() throws Exception {
    Channel readOnly =
        live.connectedChannel(uniqueChannelReference("perm"), claims().withPermission(true, false));
    Recorder<RuntimeException> errors = collectErrors(readOnly);

    // The publish completes locally; the denial arrives later through onError.
    await(readOnly.segment("chat").publish(Payloads.text("denied")), "publish");

    ServerErrorException denial =
        (ServerErrorException)
            errors.await(
                serverErrorOfType(ServerErrorException.PERMISSION_DENIED_ERROR),
                "the PermissionDeniedError frame",
                DELIVERY_TIMEOUT);
    assertEquals(Optional.of("PUB"), denial.subType());
    assertEquals(Optional.of("chat"), denial.resource());
    assertFalse(denial.getMessage().isEmpty());
    assertEquals(ChannelState.CONNECTED, readOnly.state());
  } // end method reportsAReadOnlyTokensPublishAsPermissionDenied

  @Test
  void keepsAWriteOnlyTokenPublishingWhileReceivingNothing() throws Exception {
    String reference = uniqueChannelReference("writeonly");
    Channel writeOnly = live.connectedChannel(reference, claims().withPermission(false, true));
    Channel reader = live.connectedChannel(reference);
    Recorder<Delivery> writerSaw = collect(writeOnly.segment("chat"));
    Recorder<Delivery> readerSaw = collect(reader.segment("chat"));
    writeOnly.segment("chat").subscribe();
    reader.segment("chat").subscribe();
    settle();

    await(writeOnly.segment("chat").publish(Payloads.text("one-way")), "publish");

    readerSaw.await(withText("one-way"), "delivery to the reader", DELIVERY_TIMEOUT);
    settle();
    assertEquals(List.of(), writerSaw.all(), "a write-only token received");
  } // end method keepsAWriteOnlyTokenPublishingWhileReceivingNothing

  /** RES-05: subscriptions the server drops under its rate limit recover. */
  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  void recoversSubscriptionsTheServerDropsUnderItsRateLimit() throws Exception {
    String reference = uniqueChannelReference("limit");
    // Separate token references keep separate per-connection limits.
    Channel subscriber =
        live.connectedChannel(reference, claims().withReference("limit-subscriber"));
    Channel publisher = live.connectedChannel(reference, claims().withReference("limit-publisher"));
    Recorder<RuntimeException> subscriberErrors = collectErrors(subscriber);
    Set<String> delivered = ConcurrentHashMap.newKeySet();
    List<String> segmentIds = new ArrayList<>();

    // The limiter tolerates bursts, so subscriptions go out in growing batches until one trips
    // it; some are then dropped, and only recovery can restore them.
    while (!subscriberErrors.any(serverErrorOfType(ServerErrorException.RATE_LIMIT_ERROR))
        && segmentIds.size() < 2000) {
      for (int batchIndex = 0; batchIndex < 250; batchIndex++) {
        String segmentId = "limit-" + segmentIds.size();
        segmentIds.add(segmentId);
        Segment segment = subscriber.segment(segmentId);
        segment.onMessage((payload, metadata) -> delivered.add(segmentId));
        segment.subscribe();
      }

      pause(Duration.ofMillis(500));
    }

    // The writer sends one frame at a time, so the last subscriptions may still be on their way.
    subscriberErrors.await(
        serverErrorOfType(ServerErrorException.RATE_LIMIT_ERROR),
        "the rate limit tripped by the subscription burst",
        Duration.ofSeconds(15));

    // Publish to every segment not yet delivered, round after round, until each subscription has
    // recovered. A publish the publisher's own limit drops is published again next round.
    long deadline = System.nanoTime() + Duration.ofSeconds(150).toNanos();

    while (delivered.size() < segmentIds.size() && System.nanoTime() - deadline < 0) {
      pause(Duration.ofSeconds(3));

      for (String segmentId : segmentIds) {
        if (delivered.contains(segmentId)) {
          continue;
        }

        Optional<CelerisException> failure =
            sdkFailureOf(publisher.segment(segmentId).publish(Payloads.text(segmentId)), "publish");

        if (failure.isPresent()) {
          switch (failure.get().code()) {
            // The publisher trips its own limit: let it drain.
            case BACKPRESSURE -> pause(Duration.ofSeconds(2));
            // Under this load the server sheds connections ("channel broadcast processing lag
            // exceeded threshold"); the channel recovers by itself, restoring its subscriptions.
            // A publish being written when that happens is DeliveryUnknown.
            case NOT_CONNECTED, DELIVERY_UNKNOWN -> pause(Duration.ofSeconds(1));
            default -> fail("publish failed: " + failure.get());
          }
        }

        pause(Duration.ofMillis(10));
      }
    }

    List<String> missing =
        segmentIds.stream()
            .filter(segmentId -> !delivered.contains(segmentId))
            .collect(Collectors.toList());
    assertEquals(
        List.of(),
        missing,
        missing.size() + " of " + segmentIds.size() + " subscriptions never recovered");
  } // end method recoversSubscriptionsTheServerDropsUnderItsRateLimit

  /** RESEND-01: a publish resent after a rate limit reaches the receiver at most once. */
  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  void deliversNoDuplicateUnderAPublishRateLimit() throws Exception {
    String reference = uniqueChannelReference("limit-publish");
    // Separate token references keep separate per-connection limits.
    Channel receiver = live.connectedChannel(reference, claims().withReference("limit-receiver"));
    // A queue large enough for the bursts: the default of 64 refuses most of them with
    // BACKPRESSURE, so too little reaches the server to trip its limit.
    Channel publisher =
        live.channel(
            CelerisClient.create(
                ClientOptions.builder(signing(claims().withReference("limit-publisher")))
                    .baseUrl(websocketUrl())
                    .allowInsecureLoopback(true)
                    .publishQueueSize(10_000)
                    .build()),
            reference);
    await(publisher.connect(), "connect the publisher");
    Recorder<Delivery> delivered = collect(receiver.segment("burst"));
    receiver.segment("burst").subscribe();
    Recorder<RuntimeException> publisherErrors = collectErrors(publisher);
    settle();

    // Bursts that double from 100 until one trips the limit. The ceiling on the total keeps the
    // test finite.
    Set<String> published = new HashSet<>();
    int burstSize = 100;

    while (!publisherErrors.any(serverErrorOfType(ServerErrorException.RATE_LIMIT_ERROR))
        && published.size() < 5000) {
      List<CompletableFuture<Void>> burst = new ArrayList<>();

      for (int index = 0; index < burstSize && published.size() < 5000; index++) {
        String body = "burst-" + published.size();
        published.add(body);
        burst.add(
            publisher.segment("burst").publish(Payloads.text(body)).exceptionally(failure -> null));
      }

      // A burst paused by the limit drains after the pause, so it may take a while.
      await(
          CompletableFuture.allOf(burst.toArray(CompletableFuture[]::new)),
          "the burst",
          Duration.ofSeconds(60));
      burstSize *= 2;
      pause(Duration.ofMillis(200));
    }

    // The writer sends one frame at a time, so the report may still be on its way.
    publisherErrors.await(
        serverErrorOfType(ServerErrorException.RATE_LIMIT_ERROR),
        "the rate limit tripped by the publish burst",
        Duration.ofSeconds(15));

    pause(Duration.ofSeconds(3));
    published.add("marker");
    await(publisher.segment("burst").publish(Payloads.text("marker")), "publish the marker");
    delivered.await(withText("marker"), "the marker", Duration.ofSeconds(30));
    settle();

    List<String> messageIds =
        delivered.all().stream().map(delivery -> delivery.metadata().messageId()).toList();
    List<String> strangers =
        texts(delivered).stream().filter(body -> !published.contains(body)).toList();
    assertEquals(messageIds.size(), new HashSet<>(messageIds).size(), messageIds.toString());
    assertEquals(List.of(), strangers);
    assertTrue(texts(delivered).contains("marker"));
    assertEquals(ChannelState.CONNECTED, receiver.state());
    assertEquals(ChannelState.CONNECTED, publisher.state());
  } // end method deliversNoDuplicateUnderAPublishRateLimit

  @Test
  void deliversACustomMessageIdUnchangedAndDropsARepeatOfIt() throws Exception {
    String reference = uniqueChannelReference("custom-id");
    Channel publisher = live.connectedChannel(reference);
    Channel receiver = live.connectedChannel(reference);
    Recorder<Delivery> received = collect(receiver.segment("chat"));
    receiver.segment("chat").subscribe();
    settle();
    String messageId = "order-" + System.currentTimeMillis();

    await(publisher.segment("chat").publish(Payloads.text("first"), messageId), "publish first");
    await(publisher.segment("chat").publish(Payloads.text("repeat"), messageId), "publish repeat");
    await(publisher.segment("chat").publish(Payloads.text("marker")), "publish marker");
    received.await(withText("marker"), "the marker after the repeat", DELIVERY_TIMEOUT);

    assertEquals(List.of("first", "marker"), texts(received));
    assertEquals(messageId, received.all().get(0).metadata().messageId());
  } // end method deliversACustomMessageIdUnchangedAndDropsARepeatOfIt

  @Test
  void roundTripsAnEmptyPayload() throws Exception {
    String reference = uniqueChannelReference("empty");
    Channel publisher = live.connectedChannel(reference);
    Channel receiver = live.connectedChannel(reference);
    Recorder<Delivery> received = collect(receiver.segment("chat"));
    receiver.segment("chat").subscribe();
    settle();

    await(publisher.segment("chat").publish(new byte[0]), "publish");

    Delivery delivery =
        received.await(any -> any.payload().length == 0, "the empty payload", DELIVERY_TIMEOUT);
    assertArrayEquals(new byte[0], delivery.payload());
    assertTrue(
        GENERATED_MESSAGE_ID.matcher(delivery.metadata().messageId()).matches(),
        delivery.metadata().toString());
  } // end method roundTripsAnEmptyPayload

  @Test
  void refusesAPayloadOneByteOverThe1024KibPlanCap() throws Exception {
    Channel channel = live.connectedChannel(uniqueChannelReference("cap-plus-one"));
    Recorder<RuntimeException> errors = collectErrors(channel);

    await(channel.segment("bulk").publish(new byte[1024 * 1024 + 1]), "publish");

    RuntimeException rejection =
        errors.await(
            serverErrorOfType(ServerErrorException.MESSAGE_SIZE_LIMIT_ERROR),
            "the MessageSizeLimitError frame",
            Duration.ofSeconds(20));
    assertTrue(rejection.getMessage().contains("size limit = 1024 KB"), rejection.getMessage());
    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method refusesAPayloadOneByteOverThe1024KibPlanCap

  @Test
  void deliversAPacedBurstOf50MessagesInPublishOrderOneTimeEach() throws Exception {
    String reference = uniqueChannelReference("burst");
    Channel publisher = live.connectedChannel(reference);
    Channel receiver = live.connectedChannel(reference);
    Recorder<Delivery> received = collect(receiver.segment("chat"));
    receiver.segment("chat").subscribe();
    settle();
    List<String> bodies = new ArrayList<>();

    for (int index = 0; index < 50; index++) {
      bodies.add("b" + index);
    }

    // Paced below the per-second publish limit.
    for (int start = 0; start < bodies.size(); start += 10) {
      for (String body : bodies.subList(start, start + 10)) {
        await(publisher.segment("chat").publish(Payloads.text(body)), "publish " + body);
      }

      pause(Duration.ofMillis(1100));
    }

    received.await(withText("b49"), "the last message of the burst", Duration.ofSeconds(30));
    settle();

    assertEquals(bodies, texts(received));
    Set<String> messageIds = new HashSet<>();

    for (Delivery delivery : received.all()) {
      messageIds.add(delivery.metadata().messageId());
    }

    assertEquals(50, messageIds.size(), received.all().toString());
  } // end method deliversAPacedBurstOf50MessagesInPublishOrderOneTimeEach
} // end class MessagingLiveTest

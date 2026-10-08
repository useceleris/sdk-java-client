package com.useceleris.client;

import static com.useceleris.client.TestRuntime.assertCode;
import static com.useceleris.client.TestRuntime.assertProtocolError;
import static com.useceleris.client.TestRuntime.assertServerError;
import static com.useceleris.client.TestRuntime.assertSucceeded;
import static com.useceleris.client.TestRuntime.bytes;
import static com.useceleris.client.TestRuntime.errorFrame;
import static com.useceleris.client.TestRuntime.failureOf;
import static com.useceleris.client.TestRuntime.messageFrame;
import static com.useceleris.client.TestRuntime.publishFrame;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class MessagingTest {
  private static TestRuntime.Recorder<String> recordMessageIds(Segment segment) {
    TestRuntime.Recorder<String> identifiers = new TestRuntime.Recorder<>();
    segment.onMessage((payload, metadata) -> identifiers.accept(metadata.messageId()));

    return identifiers;
  } // end method recordMessageIds

  // ---- Subscriptions ----

  @Test
  void segmentHandlesShareOneInterestCount() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    Subscription first = channel.segment("chat").subscribe();
    Subscription second = channel.segment("chat").subscribe();
    runtime.run();
    assertEquals(List.of("@SUB\n$4\nchat\n"), socket.commands());

    first.cancel();
    first.close();
    runtime.run();
    assertEquals(List.of("@SUB\n$4\nchat\n"), socket.commands());

    second.cancel();
    runtime.run();
    assertEquals(List.of("@SUB\n$4\nchat\n", "@UNSUB\n$4\nchat\n"), socket.commands());
  } // end method segmentHandlesShareOneInterestCount

  // One channel is one WebSocket; its segments share it (SEG-01).
  @Test
  void segmentsMultiplexOverOneSocketAndAnotherChannelOpensAnother() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    channel.segment("alpha").subscribe();
    channel.segment("beta").subscribe();
    CompletableFuture<Void> published = channel.segment("gamma").publish(bytes("x"));
    runtime.run();
    assertSucceeded(published);
    assertEquals(1, runtime.sockets.size());
    assertEquals(3, runtime.socket().commands().size());

    CompletableFuture<Void> connected = runtime.client.channel("room-2").connect();
    runtime.run();
    assertSucceeded(connected);

    assertEquals(2, runtime.sockets.size());
  } // end method segmentsMultiplexOverOneSocketAndAnotherChannelOpensAnother

  @Test
  void defaultSegmentIsNeverSubscribed() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();

    channel.defaultSegment().subscribe().cancel();
    runtime.run();

    assertTrue(runtime.socket().commands().isEmpty());
  } // end method defaultSegmentIsNeverSubscribed

  @Test
  void listenersAloneSendNoFrames() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();

    channel.segment("chat").onMessage((payload, metadata) -> {});
    channel.events().onMessage((payload, metadata) -> {});
    runtime.run();

    assertTrue(runtime.socket().commands().isEmpty());
  } // end method listenersAloneSendNoFrames

  @Test
  void interestsAreRestoredOnConnectInRegistrationOrder() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.channel();
    channel.segment("beta").subscribe();
    channel.segment("alpha").subscribe();
    channel.defaultSegment().subscribe();
    channel.segment("gamma").subscribePresence();
    channel.defaultSegment().subscribePresence();

    CompletableFuture<Void> connected = channel.connect();
    runtime.run();

    assertSucceeded(connected);
    assertEquals(
        List.of(
            "@SUB\n$4\nbeta\n",
            "@SUB\n$5\nalpha\n",
            "@PRES_SUB\n$5\ngamma\n",
            "@PRES_SUB\n$7\ndefault\n"),
        runtime.socket().commands());
  } // end method interestsAreRestoredOnConnectInRegistrationOrder

  @Test
  void subscriptionsWaitBehindAFullWriterAheadOfPublishes() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    socket.holdWrites();
    List<CompletableFuture<Void>> results = new ArrayList<>();

    for (int index = 0; index < 64; index++) {
      results.add(channel.defaultSegment().publish(bytes("x"), "m-" + index));
    }

    results.add(channel.segment("lobby").publish(bytes("y"), "m-lobby"));
    channel.segment("chat").subscribe();
    runtime.run();
    socket.releaseWrites();
    runtime.run();

    results.forEach(TestRuntime::assertSucceeded);
    List<String> commands = socket.commands();
    assertEquals(
        List.of("@SUB\n$4\nchat\n", publishFrame("lobby", "m-lobby", "y")),
        commands.subList(64, commands.size()));
    assertTrue(errors.all().isEmpty(), () -> "errors " + errors.all());
  } // end method subscriptionsWaitBehindAFullWriterAheadOfPublishes

  @Test
  void moreThan64SubscriptionsAllRestore() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.channel();

    for (int index = 0; index < 65; index++) {
      channel.segment("segment-" + index).subscribe();
    }

    channel.connect();
    runtime.run();

    List<String> commands = runtime.socket().commands();
    assertEquals(65, commands.size());
    assertEquals("@SUB\n$10\nsegment-64\n", commands.get(64));
  } // end method moreThan64SubscriptionsAllRestore

  @Test
  void subscribeOnAClosedChannelFails() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.channel();
    channel.closeAsync();
    runtime.run();

    CelerisException failure =
        assertThrows(CelerisException.class, () -> channel.segment("chat").subscribe());

    assertEquals(ErrorCode.NOT_CONNECTED, failure.code());
    assertEquals(
        "Channel is closed; create a new one with client.channel().", failure.getMessage());
  } // end method subscribeOnAClosedChannelFails

  // ---- Publishing ----

  @Test
  void publishCompletesOnLocalAcceptance() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();

    CompletableFuture<Void> generated = channel.defaultSegment().publish(bytes("hi"));
    CompletableFuture<Void> named = channel.segment("chat").publish(bytes("yo"), "m-1");
    runtime.run();

    assertSucceeded(generated);
    assertSucceeded(named);
    List<String> commands = runtime.socket().commands();
    assertEquals(2, commands.size());
    assertTrue(
        commands.get(0).matches("@PUB\n\\$7\ndefault\n\\$32\n[0-9a-f]{32}\n\\$2\nhi\n"),
        commands.get(0));
    assertEquals("@PUB\n$4\nchat\n$3\nm-1\n$2\nyo\n", commands.get(1));
  } // end method publishCompletesOnLocalAcceptance

  @Test
  void publishCopiesThePayloadBeforeReturning() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    runtime.socket().holdWrites();
    byte[] payload = bytes("before");

    CompletableFuture<Void> published = channel.defaultSegment().publish(payload, "m-1");
    payload[0] = 'X';
    runtime.socket().releaseWrites();
    runtime.run();

    assertSucceeded(published);
    assertEquals(List.of(publishFrame("default", "m-1", "before")), runtime.socket().commands());
  } // end method publishCopiesThePayloadBeforeReturning

  @Test
  void publishFailsWhileNotConnected() {
    TestRuntime idleRuntime = new TestRuntime();
    CompletableFuture<Void> idle = idleRuntime.channel().defaultSegment().publish(bytes("x"));
    idleRuntime.run();
    assertCode(idle, ErrorCode.NOT_CONNECTED);
    assertEquals("Channel is not connected; it is idle.", failureOf(idle).getMessage());

    // No recovery is in progress during the first connect or once it failed, so nothing waits.
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.channel();
    Segment chat = channel.segment("chat");
    runtime.blockDials = true;
    CompletableFuture<Void> connecting = channel.connect();
    runtime.run();

    CompletableFuture<Void> duringConnect = chat.publish(bytes("x"));
    runtime.run();
    assertCode(duringConnect, ErrorCode.NOT_CONNECTED);
    assertEquals(
        "Channel is not connected; it is connecting.", failureOf(duringConnect).getMessage());

    runtime.advance(Duration.ofSeconds(15));
    assertCode(connecting, ErrorCode.TIMEOUT);
    CompletableFuture<Void> afterFailed = chat.publish(bytes("x"));
    runtime.run();
    assertCode(afterFailed, ErrorCode.NOT_CONNECTED);
    assertEquals("Channel is not connected; it is failed.", failureOf(afterFailed).getMessage());

    runtime.blockDials = false;
    CompletableFuture<Void> connected = channel.connect();
    runtime.run();
    assertSucceeded(connected);
    FakeWebSocket socket = runtime.socket();

    channel.closeAsync();
    runtime.run();
    CompletableFuture<Void> closed = chat.publish(bytes("x"));
    runtime.run();
    assertCode(closed, ErrorCode.NOT_CONNECTED);
    assertTrue(socket.commands().isEmpty());
  } // end method publishFailsWhileNotConnected

  @Test
  void publishRefusesInvalidInputBeforeWriting() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    Segment lobby = channel.defaultSegment();

    CompletableFuture<Void> emptyId = lobby.publish(bytes("x"), "");
    CompletableFuture<Void> tooLarge = lobby.publish(new byte[Constants.MAXIMUM_COMMAND_BYTES]);
    runtime.run();

    assertCode(emptyId, ErrorCode.CONFIGURATION);
    assertEquals("Invalid command. messageId: Must not be empty.", failureOf(emptyId).getMessage());
    assertCode(tooLarge, ErrorCode.CONFIGURATION);
    assertTrue(
        failureOf(tooLarge).getMessage().startsWith("Encoded command exceeds 2 MiB."),
        failureOf(tooLarge).getMessage());
    assertTrue(socket.commands().isEmpty());
    assertThrows(NullPointerException.class, () -> lobby.publish(null));
    assertThrows(NullPointerException.class, () -> lobby.publish(bytes("x"), null));

    CompletableFuture<Void> empty = lobby.publish(new byte[0], "m-1");
    runtime.run();

    assertSucceeded(empty);
    assertEquals(List.of("@PUB\n$7\ndefault\n$3\nm-1\n$0\n\n"), socket.commands());
  } // end method publishRefusesInvalidInputBeforeWriting

  // A command of the maximum size fits only an empty writer: anything still in it, even a
  // subscription queued after the large publish, goes first.
  @Test
  void maximumSizeCommandFitsOnlyAnEmptyWriter() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    Segment segment = channel.segment("s");
    socket.holdWrites();
    CompletableFuture<Void> small = segment.publish(bytes("x"), "m");

    // "@PUB\n$1\ns\n$1\nl\n$2097127\n" and the closing LF are 25 bytes.
    CompletableFuture<Void> large =
        segment.publish(new byte[Constants.MAXIMUM_COMMAND_BYTES - 25], "l");
    channel.segment("other").subscribe();
    runtime.run();
    socket.releaseWrites();
    runtime.run();

    assertSucceeded(small);
    assertSucceeded(large);
    List<String> commands = socket.commands();
    assertEquals(3, commands.size());
    assertEquals(publishFrame("s", "m", "x"), commands.get(0));
    assertEquals("@SUB\n$5\nother\n", commands.get(1));
    assertEquals(
        Constants.MAXIMUM_COMMAND_BYTES, commands.get(2).getBytes(StandardCharsets.UTF_8).length);
  } // end method maximumSizeCommandFitsOnlyAnEmptyWriter

  // A write the socket refuses breaks the connection, so the publish reports DeliveryUnknown, is
  // never resent, and the channel reconnects.
  @Test
  void failedWriteIsDeliveryUnknown() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    runtime.socket().failWrites();

    CompletableFuture<Void> published = channel.defaultSegment().publish(bytes("x"));
    runtime.run();

    assertCode(published, ErrorCode.DELIVERY_UNKNOWN);
    assertEquals(ChannelState.CONNECTED, channel.state());
    assertEquals(2, runtime.sockets.size());
    assertTrue(errors.all().isEmpty(), () -> "errors " + errors.all());
    assertTrue(runtime.sockets.get(1).commands().isEmpty());
  } // end method failedWriteIsDeliveryUnknown

  // ---- Deliveries ----

  @Test
  void deliveriesReachOnlyTheirSegment() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<String> chat = recordMessageIds(channel.segment("chat"));
    TestRuntime.Recorder<String> lobby = recordMessageIds(channel.defaultSegment());

    runtime.receive(
        runtime.socket(),
        messageFrame("chat", "id-1", "hi"),
        messageFrame("default", "id-2", "yo"),
        messageFrame("other", "id-3", "no"));

    assertEquals(List.of("id-1"), chat.all());
    assertEquals(List.of("id-2"), lobby.all());
  } // end method deliveriesReachOnlyTheirSegment

  @Test
  void deliveriesReachEveryHandleWithEveryFieldAndTheirOwnPayload() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    List<byte[]> payloads = new ArrayList<>();
    TestRuntime.Recorder<MessageMetadata> metadata = new TestRuntime.Recorder<>();

    for (int index = 0; index < 2; index++) {
      channel
          .segment("chat")
          .onMessage(
              (payload, delivered) -> {
                payloads.add(payload);
                metadata.accept(delivered);
              });
    }

    runtime.receive(runtime.socket(), messageFrame("chat", "id-1", "hi"));

    MessageMetadata expected = new MessageMetadata("user", "chat", "id-1", 1);
    assertEquals(List.of(expected, expected), metadata.all());
    assertEquals(2, payloads.size());
    assertArrayEquals(bytes("hi"), payloads.get(0));
    assertArrayEquals(bytes("hi"), payloads.get(1));
    assertNotSame(payloads.get(0), payloads.get(1));
  } // end method deliveriesReachEveryHandleWithEveryFieldAndTheirOwnPayload

  @Test
  void deliveriesAreDeduplicatedEvenWithoutListeners() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    runtime.receive(socket, messageFrame("chat", "id-1", "hi"));
    TestRuntime.Recorder<String> delivered = recordMessageIds(channel.segment("chat"));

    runtime.receive(
        socket,
        messageFrame("chat", "id-1", "hi"),
        messageFrame("chat", "id-2", "hi"),
        messageFrame("chat", "id-2", "hi"));

    assertEquals(List.of("id-2"), delivered.all());
  } // end method deliveriesAreDeduplicatedEvenWithoutListeners

  @Test
  void deduplicationSurvivesReconnectAndClearsOnConnect() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<String> delivered = recordMessageIds(channel.segment("chat"));
    channel.segment("chat").subscribe();
    runtime.receive(runtime.socket(), messageFrame("chat", "id-1", "x"));

    runtime.socket().drop();
    runtime.run();
    runtime.receive(runtime.socket(), messageFrame("chat", "id-1", "x"));
    assertEquals(2, runtime.sockets.size());
    assertEquals(List.of("id-1"), delivered.all());

    // A reconnect refused for its credentials fails the channel; connecting again starts over.
    runtime.credentials = new Credentials("", "");
    runtime.socket().drop();
    runtime.run();
    assertEquals(ChannelState.FAILED, channel.state());

    runtime.credentials = TestRuntime.CREDENTIALS;
    CompletableFuture<Void> connected = channel.connect();
    runtime.run();
    assertSucceeded(connected);
    runtime.receive(runtime.socket(), messageFrame("chat", "id-1", "x"));

    assertEquals(List.of("id-1", "id-1"), delivered.all());
  } // end method deduplicationSurvivesReconnectAndClearsOnConnect

  @Test
  void deliveryWithoutAnIdIsDroppedWithoutDroppingTheConnection() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    TestRuntime.Recorder<String> delivered = recordMessageIds(channel.segment("chat"));

    runtime.receive(socket, messageFrame("chat", null, "x"));

    assertTrue(delivered.all().isEmpty());
    assertEquals(1, errors.all().size());
    assertProtocolError(
        errors.all().get(0), "Server message is missing its identifier.", "messageId", 0);
    assertEquals(ChannelState.CONNECTED, channel.state());
    assertFalse(socket.isAborted());
  } // end method deliveryWithoutAnIdIsDroppedWithoutDroppingTheConnection

  @Test
  void unknownCommandsAreSkippedSilently() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    TestRuntime.Recorder<ChannelState> states = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    channel.events().onStateChange(states);
    TestRuntime.Recorder<String> delivered = recordMessageIds(channel.segment("chat"));

    runtime.receive(
        runtime.socket(), "@NODE_PUB\n+node-1\n$4\nbody\n", messageFrame("chat", "id-1", "x"));

    assertTrue(errors.all().isEmpty(), () -> "errors " + errors.all());
    assertTrue(states.all().isEmpty(), () -> "states " + states.all());
    assertEquals(List.of("id-1"), delivered.all());
  } // end method unknownCommandsAreSkippedSilently

  @Test
  void undecodableMessagesAreReportedAndTheConnectionStays() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    TestRuntime.Recorder<String> delivered = recordMessageIds(channel.segment("chat"));

    runtime.receive(
        socket,
        "*2\n@FUTURE\n+a\n" + messageFrame("chat", "id-0", "x"),
        messageFrame("chat", "id-1", "x"));
    socket.receiveText("text");
    runtime.run();

    assertEquals(2, errors.all().size());
    assertProtocolError(
        errors.all().get(0),
        "Unknown command inside array has ambiguous boundaries.",
        "command",
        3);
    assertProtocolError(errors.all().get(1), "Expected a binary WebSocket message.", "message", 0);
    assertEquals(List.of("id-1"), delivered.all());
    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method undecodableMessagesAreReportedAndTheConnectionStays

  @Test
  void batchesFanOutInArrivalOrder() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<String> delivered = recordMessageIds(channel.segment("chat"));

    runtime.receive(
        runtime.socket(),
        "*2\n" + messageFrame("chat", "al-1", "a") + messageFrame("chat", "al-2", "b"));

    assertEquals(List.of("al-1", "al-2"), delivered.all());
  } // end method batchesFanOutInArrivalOrder

  @Test
  void messagesSplitIntoPartsAreAssembled() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<String> delivered = recordMessageIds(channel.segment("chat"));

    runtime.socket().receiveInParts(bytes(messageFrame("chat", "id-1", "split body")), 3);
    runtime.run();

    assertEquals(List.of("id-1"), delivered.all());
  } // end method messagesSplitIntoPartsAreAssembled

  // LIMIT-01: a received message is never size-checked.
  @Test
  void deliveriesOverOneMebibyteArrive() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<Integer> sizes = new TestRuntime.Recorder<>();
    channel.segment("chat").onMessage((payload, metadata) -> sizes.accept(payload.length));

    runtime.receive(runtime.socket(), messageFrame("chat", "id-1", "x".repeat(1024 * 1024)));

    assertEquals(List.of(1024 * 1024), sizes.all());
  } // end method deliveriesOverOneMebibyteArrive

  @Test
  void timestampsKeepTheirFullRange() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<Long> timestamps = new TestRuntime.Recorder<>();
    channel
        .segment("chat")
        .onMessage((payload, metadata) -> timestamps.accept(metadata.timestamp()));

    runtime.receive(runtime.socket(), "@MSG\n+u\n+chat\n+m\n:9223372036854775807\n$0\n\n");

    assertEquals(List.of(Long.MAX_VALUE), timestamps.all());
  } // end method timestampsKeepTheirFullRange

  @Test
  void throwingMessageListenersAreContained() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    TestRuntime.Recorder<String> order = new TestRuntime.Recorder<>();
    Segment chat = channel.segment("chat");
    Registration[] second = new Registration[1];
    chat.onMessage(
        (payload, metadata) -> {
          order.accept("first");
          second[0].close();

          throw new IllegalStateException("listener-secret");
        });

    second[0] = chat.onMessage((payload, metadata) -> order.accept("second"));
    chat.onMessage((payload, metadata) -> order.accept("third"));

    runtime.receive(runtime.socket(), messageFrame("chat", "id-1", "x"));

    assertEquals(List.of("first", "third"), order.all());
    assertEquals(1, errors.all().size());
    assertFalse(errors.all().get(0).getMessage().contains("secret"));
    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method throwingMessageListenersAreContained

  // ---- Channel-wide delivery ----

  @Test
  void channelListenerReceivesEverySegmentWithOrWithoutSegmentListeners() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<String> seen = new TestRuntime.Recorder<>();
    channel
        .events()
        .onMessage(
            (payload, metadata) ->
                seen.accept(
                    metadata.segmentId() + ":" + new String(payload, StandardCharsets.UTF_8)));

    channel.segment("chat").onMessage((payload, metadata) -> {});

    runtime.receive(
        runtime.socket(),
        messageFrame("chat", "id-1", "hi"),
        messageFrame("default", "id-2", "yo"),
        messageFrame("joined-by-publish", "id-3", "ok"));

    assertEquals(List.of("chat:hi", "default:yo", "joined-by-publish:ok"), seen.all());
  } // end method channelListenerReceivesEverySegmentWithOrWithoutSegmentListeners

  @Test
  void segmentListenersRunBeforeChannelListeners() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<String> order = new TestRuntime.Recorder<>();
    channel.events().onMessage((payload, metadata) -> order.accept("channel"));
    channel.segment("chat").onMessage((payload, metadata) -> order.accept("segment"));

    runtime.receive(runtime.socket(), messageFrame("chat", "id-1", "x"));

    assertEquals(List.of("segment", "channel"), order.all());
  } // end method segmentListenersRunBeforeChannelListeners

  @Test
  void channelListenerSeesADuplicateOnceAndNeverADeliveryWithoutAnId() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<String> delivered = new TestRuntime.Recorder<>();
    channel.events().onMessage((payload, metadata) -> delivered.accept(metadata.messageId()));

    runtime.receive(
        runtime.socket(),
        messageFrame("chat", "id-1", "x"),
        messageFrame("other", "id-1", "x"),
        messageFrame("chat", null, "x"));

    assertEquals(List.of("id-1"), delivered.all());
  } // end method channelListenerSeesADuplicateOnceAndNeverADeliveryWithoutAnId

  @Test
  void throwingChannelListenerIsContainedAndStopsAfterClose() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    TestRuntime.Recorder<String> delivered = new TestRuntime.Recorder<>();
    Registration throwing =
        channel
            .events()
            .onMessage(
                (payload, metadata) -> {
                  throw new IllegalStateException("listener-secret");
                });

    Registration recording =
        channel.events().onMessage((payload, metadata) -> delivered.accept(metadata.messageId()));

    runtime.receive(runtime.socket(), messageFrame("chat", "id-1", "x"));
    throwing.close();
    recording.close();
    runtime.receive(runtime.socket(), messageFrame("chat", "id-2", "x"));

    assertEquals(List.of("id-1"), delivered.all());
    assertEquals(1, errors.all().size());
    assertEquals(
        "A listener callback threw; the channel caught the error and kept running.",
        errors.all().get(0).getMessage());
    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method throwingChannelListenerIsContainedAndStopsAfterClose

  @Test
  void closingAChannelListenerRemovesOnlyThatListener() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<String> delivered = new TestRuntime.Recorder<>();
    Registration channelListener =
        channel.events().onMessage((payload, metadata) -> delivered.accept("removed"));

    channel.events().onMessage((payload, metadata) -> delivered.accept("channel"));
    channel.segment("chat").onMessage((payload, metadata) -> delivered.accept("segment"));
    channel.segment("chat").subscribe();
    runtime.run();

    channelListener.close();
    channelListener.close();
    runtime.receive(socket, messageFrame("chat", "id-1", "x"));

    assertEquals(List.of("segment", "channel"), delivered.all());
    assertEquals(List.of("@SUB\n$4\nchat\n"), socket.commands());
  } // end method closingAChannelListenerRemovesOnlyThatListener

  // The server decides what arrives; the SDK never gates on subscriptions.
  @Test
  void deliversWhateverTheSubscriptionState() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<String> delivered = recordMessageIds(channel.segment("chat"));
    channel.segment("chat").subscribe().cancel();
    runtime.run();

    runtime.receive(runtime.socket(), messageFrame("chat", "id-1", "x"));

    assertEquals(List.of("id-1"), delivered.all());
  } // end method deliversWhateverTheSubscriptionState

  // Each event takes its listener snapshot when its turn comes, so a channel listener registered by
  // a segment listener still receives that delivery, and still with its own array.
  @Test
  void segmentAndChannelListenersEachOwnTheirPayload() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    List<byte[]> payloads = new ArrayList<>();
    channel.segment("chat").onMessage((payload, metadata) -> payloads.add(payload));
    channel.events().onMessage((payload, metadata) -> payloads.add(payload));
    runtime.receive(runtime.socket(), messageFrame("chat", "id-1", "hi"));

    Channel lateChannel = runtime.connectedChannel();
    lateChannel
        .segment("chat")
        .onMessage(
            (payload, metadata) -> {
              payloads.add(payload);
              lateChannel.events().onMessage((late, lateMetadata) -> payloads.add(late));
            });

    runtime.receive(runtime.socket(), messageFrame("chat", "id-2", "hi"));

    assertEquals(4, payloads.size());

    for (int index = 0; index < payloads.size(); index++) {
      assertArrayEquals(bytes("hi"), payloads.get(index));
    }

    assertNotSame(payloads.get(0), payloads.get(1));
    assertNotSame(payloads.get(2), payloads.get(3));
  } // end method segmentAndChannelListenersEachOwnTheirPayload

  // ---- Notices and server errors ----

  @Test
  void noticesAreRawAndServerErrorsKeepTheConnection() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    TestRuntime.Recorder<ServerNotice> notices = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    channel.events().onNotice(notices);
    TestRuntime.Recorder<String> delivered = recordMessageIds(channel.segment("chat"));

    runtime.receive(
        runtime.socket(),
        "@SERVER_MSG\n:1\n$29\nSuccessfully connected to \"x\"\n",
        errorFrame("RateLimitError", "slow down", null, null),
        messageFrame("chat", "id-1", "x"));

    assertEquals(
        List.of(new ServerNotice(1, bytes("Successfully connected to \"x\""))), notices.all());
    assertEquals(1, errors.all().size());
    assertServerError(errors.all().get(0), "RateLimitError", null, "slow down", null);
    assertEquals(ChannelState.CONNECTED, channel.state());
    assertEquals(List.of("id-1"), delivered.all());
  } // end method noticesAreRawAndServerErrorsKeepTheConnection

  @Test
  void permissionDenialNamesItsCommandAndSegment() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);

    // A denied publish completes locally; the error arrives afterwards.
    CompletableFuture<Void> published = channel.segment("chat").publish(bytes("x"), "m-1");
    runtime.run();
    assertSucceeded(published);
    runtime.receive(
        runtime.socket(), errorFrame("PermissionDeniedError", "denied", "PUB", "$4\nchat\n"));

    assertEquals(1, errors.all().size());
    assertServerError(errors.all().get(0), "PermissionDeniedError", "PUB", "denied", "chat");
  } // end method permissionDenialNamesItsCommandAndSegment

  @Test
  void everyServerErrorTypeSurfacesWithEveryField() {
    for (String errorType :
        List.of(
            "ParserError",
            "SendError",
            "PermissionDeniedError",
            "RateLimitError",
            "MessageSizeLimitError",
            "InternalError",
            "SomeFutureError")) {
      TestRuntime runtime = new TestRuntime();
      Channel channel = runtime.connectedChannel();
      TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
      channel.events().onError(errors);

      runtime.receive(
          runtime.socket(), errorFrame(errorType, "what happened", "SUB", "*2\n+a\n:7\n"));

      assertEquals(1, errors.all().size(), errorType);
      assertServerError(errors.all().get(0), errorType, "SUB", "what happened", List.of("a", 7L));
      assertEquals(ChannelState.CONNECTED, channel.state(), errorType);
    }
  } // end method everyServerErrorTypeSurfacesWithEveryField

  @Test
  void malformedServerErrorTextStillSurfaces() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    ByteArrayOutputStream frame = new ByteArrayOutputStream();
    frame.writeBytes(bytes("-Err\n+SendError\n$-1\n$6\nbad "));
    frame.write(0xff);
    frame.write(0xfe);
    frame.writeBytes(bytes("\n$-1\n"));

    runtime.socket().receive(frame.toByteArray());
    runtime.run();

    assertEquals(1, errors.all().size());
    ServerErrorException serverError =
        assertInstanceOf(ServerErrorException.class, errors.all().get(0));
    assertEquals("SendError", serverError.type());
    assertTrue(serverError.getMessage().startsWith("bad "), serverError.getMessage());
  } // end method malformedServerErrorTextStillSurfaces

  // ---- Ownership, release and equality ----

  // Each listener owns its payload: one that writes into its copy changes nothing the next
  // listener receives, for deliveries and notices alike.
  @Test
  void listenerWritingIntoItsPayloadLeavesTheNextListenersCopyIntact() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<String> received = new TestRuntime.Recorder<>();
    channel.defaultSegment().onMessage((payload, metadata) -> Arrays.fill(payload, (byte) '!'));
    channel
        .defaultSegment()
        .onMessage(
            (payload, metadata) -> received.accept(new String(payload, StandardCharsets.UTF_8)));

    channel.events().onNotice(notice -> Arrays.fill(notice.payload(), (byte) '!'));
    channel
        .events()
        .onNotice(notice -> received.accept(new String(notice.payload(), StandardCharsets.UTF_8)));

    runtime.receive(
        runtime.socket(), messageFrame("default", "id-1", "body"), "@SERVER_MSG\n:1\n$5\nhello\n");

    assertEquals(List.of("body", "hello"), received.all());
  } // end method listenerWritingIntoItsPayloadLeavesTheNextListenersCopyIntact

  @Test
  void closingASubscriptionReleasesItsInterest() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();

    channel.segment("chat").subscribe().close();
    runtime.run();

    assertEquals(List.of("@SUB\n$4\nchat\n", "@UNSUB\n$4\nchat\n"), runtime.socket().commands());
  } // end method closingASubscriptionReleasesItsInterest

  @Test
  void noticesCompareByTimestampAndContentsAndPrintOnlyTheirSize() {
    ServerNotice notice = new ServerNotice(0, bytes("hello"));
    ServerNotice same = new ServerNotice(0, bytes("hello"));
    ServerNotice otherContents = new ServerNotice(0, bytes("hellp"));

    assertEquals(notice, same);
    assertEquals(notice.hashCode(), same.hashCode());
    assertNotEquals(notice, otherContents);
    assertNotEquals(notice.hashCode(), otherContents.hashCode());
    assertNotEquals(notice, new ServerNotice(1, bytes("hello")));
    assertEquals("ServerNotice[timestamp=0, payload=5 bytes]", notice.toString());
  } // end method noticesCompareByTimestampAndContentsAndPrintOnlyTheirSize

  // An explicit connect clears the window completely: an id delivered again afterwards, behind a
  // full window of older ones, is remembered like any new id.
  @Test
  void clearedWindowRemembersIdsDeliveredAfterIt() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<String> delivered = recordMessageIds(channel.defaultSegment());

    for (int index = 0; index < 1024; index++) {
      runtime.receive(runtime.socket(), messageFrame("default", "id-" + index, "x"));
    }

    runtime.credentials = new Credentials("", "");
    runtime.socket().drop();
    runtime.run();
    runtime.credentials = TestRuntime.CREDENTIALS;
    CompletableFuture<Void> connected = channel.connect();
    runtime.run();
    assertSucceeded(connected);

    runtime.receive(
        runtime.socket(),
        messageFrame("default", "id-0", "x"),
        messageFrame("default", "id-0", "x"));

    assertEquals(1025, delivered.all().size());
  } // end method clearedWindowRemembersIdsDeliveredAfterIt

  // ---- Text frames ----

  @Test
  void textMessageInPartsIsReportedOnceAndReadingContinues() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    TestRuntime.Recorder<String> delivered = recordMessageIds(channel.defaultSegment());

    socket.receiveTextInParts("te", "xt");
    socket.receive(messageFrame("default", "id-1", "x"));
    runtime.run();

    assertEquals(1, errors.all().size());
    assertProtocolError(errors.all().get(0), "Expected a binary WebSocket message.", "message", 0);
    assertEquals(List.of("id-1"), delivered.all());
  } // end method textMessageInPartsIsReportedOnceAndReadingContinues
} // end class MessagingTest

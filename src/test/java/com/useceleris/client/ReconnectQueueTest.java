package com.useceleris.client;

import static com.useceleris.client.TestRuntime.assertCode;
import static com.useceleris.client.TestRuntime.assertSucceeded;
import static com.useceleris.client.TestRuntime.bytes;
import static com.useceleris.client.TestRuntime.failureOf;
import static com.useceleris.client.TestRuntime.publishFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * QUEUE-01: publishes wait in the queue across a reconnect and go out after the restored
 * subscriptions; a publish the socket took is never sent again.
 */
class ReconnectQueueTest {
  // With jitter 0.5, the first reconnect attempt runs 250 ms after the drop, the second 500 ms
  // after the first failed.
  private static final Duration FIRST_RETRY = Duration.ofMillis(250);

  private static final Duration SECOND_RETRY = Duration.ofMillis(500);

  /** A connected channel whose connection just dropped, with the first retry 250 ms away. */
  private record Outage(
      TestRuntime runtime, Channel channel, TestRuntime.Recorder<RuntimeException> errors) {
    Segment chat() {
      return channel.segment("chat");
    } // end method chat
  } // end record Outage

  private static Outage dropped() {
    return dropped(options -> {}, (runtime, channel) -> {});
  } // end method dropped

  /** As {@link #dropped()}, with the options, and beforeDrop run on the connected channel. */
  private static Outage dropped(
      Consumer<ClientOptions.Builder> options, BiConsumer<TestRuntime, Channel> beforeDrop) {
    TestRuntime runtime = new TestRuntime(options);
    runtime.answerPings = true;
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    beforeDrop.accept(runtime, channel);
    runtime.run();
    runtime.random = 0.5;

    runtime.socket().drop();
    runtime.run();
    assertEquals(ChannelState.RECONNECTING, channel.state());

    return new Outage(runtime, channel, errors);
  } // end method dropped

  @Test
  void aPublishWhileReconnectingIsSentAfterTheReconnect() {
    Outage outage = dropped();
    TestRuntime runtime = outage.runtime();

    CompletableFuture<Void> queued = outage.chat().publish(bytes("x"), "m-1");
    runtime.advance(FIRST_RETRY.minusMillis(1));
    assertFalse(queued.isDone());

    runtime.advance(Duration.ofMillis(1));
    assertEquals(ChannelState.CONNECTED, outage.channel().state());
    assertEquals(List.of(publishFrame("chat", "m-1", "x")), runtime.socket().commands());
    assertSucceeded(queued);
  } // end method aPublishWhileReconnectingIsSentAfterTheReconnect

  @Test
  void publishesWaitingBehindAFullWriterAreSentAfterTheReconnectInOrder() {
    TestRuntime runtime = new TestRuntime();
    runtime.answerPings = true;
    Channel channel = runtime.connectedChannel();
    runtime.socket().holdWrites();
    List<CompletableFuture<Void>> published = new ArrayList<>();
    List<String> expected = new ArrayList<>();

    // One being written, 63 more in the writer, and two waiting for room in it.
    for (int index = 1; index <= 66; index++) {
      published.add(channel.segment("chat").publish(bytes("x"), "m-" + index));

      if (index > 1) {
        expected.add(publishFrame("chat", "m-" + index, "x"));
      }
    }

    runtime.run();

    runtime.socket().drop();
    runtime.run();

    assertEquals(ChannelState.CONNECTED, channel.state());
    assertEquals(expected, runtime.socket().commands());
    assertCode(published.get(0), ErrorCode.DELIVERY_UNKNOWN);

    for (CompletableFuture<Void> later : published.subList(1, published.size())) {
      assertSucceeded(later);
    }
  } // end method publishesWaitingBehindAFullWriterAreSentAfterTheReconnectInOrder

  @Test
  void restoredSubscriptionsGoOutBeforeEveryQueuedPublish() {
    Outage outage =
        dropped(
            options -> {},
            (runtime, channel) -> {
              channel.segment("alpha").subscribe();
              channel.segment("beta").subscribePresence();
              channel.segment("gamma").subscribe();
            });

    TestRuntime runtime = outage.runtime();
    Channel channel = outage.channel();

    CompletableFuture<Void> toAlpha = channel.segment("alpha").publish(bytes("a"), "m-1");
    CompletableFuture<Void> toChat = outage.chat().publish(bytes("c"), "m-2");
    runtime.advance(FIRST_RETRY);

    assertEquals(
        List.of(
            "@SUB\n$5\nalpha\n",
            "@SUB\n$5\ngamma\n",
            "@PRES_SUB\n$4\nbeta\n",
            publishFrame("alpha", "m-1", "a"),
            publishFrame("chat", "m-2", "c")),
        runtime.socket().commands());
    assertSucceeded(toAlpha);
    assertSucceeded(toChat);
  } // end method restoredSubscriptionsGoOutBeforeEveryQueuedPublish

  @Test
  void aPublishQueuedBeforeTheDropFollowsTheRestorationOfItsSegment() {
    TestRuntime runtime = new TestRuntime();
    runtime.answerPings = true;
    Channel channel = runtime.connectedChannel();
    Segment chat = channel.segment("chat");
    chat.subscribe();
    channel.segment("lobby").subscribePresence();
    runtime.run();
    runtime.socket().holdWrites();
    CompletableFuture<Void> writing = chat.publish(bytes("w"), "m-1");
    CompletableFuture<Void> unstarted = chat.publish(bytes("u"), "m-2");
    runtime.run();
    runtime.random = 0.5;

    runtime.socket().drop();
    runtime.run();
    CompletableFuture<Void> whileReconnecting = chat.publish(bytes("r"), "m-3");
    runtime.advance(FIRST_RETRY);

    assertEquals(
        List.of(
            "@SUB\n$4\nchat\n",
            "@PRES_SUB\n$5\nlobby\n",
            publishFrame("chat", "m-2", "u"),
            publishFrame("chat", "m-3", "r")),
        runtime.socket().commands());
    assertCode(writing, ErrorCode.DELIVERY_UNKNOWN);
    assertSucceeded(unstarted);
    assertSucceeded(whileReconnecting);
  } // end method aPublishQueuedBeforeTheDropFollowsTheRestorationOfItsSegment

  @Test
  void aFullQueueRefusesAPublishWhileReconnecting() {
    Outage outage = dropped(options -> options.publishQueueSize(1), (runtime, channel) -> {});

    CompletableFuture<Void> queued = outage.chat().publish(bytes("x"), "m-1");
    CompletableFuture<Void> refused = outage.chat().publish(bytes("y"), "m-2");
    outage.runtime().run();

    assertFalse(queued.isDone());
    assertCode(refused, ErrorCode.BACKPRESSURE);
    assertEquals(
        "The publish queue is full (size 1). Retry once some publishes have gone out.",
        failureOf(refused).getMessage());
  } // end method aFullQueueRefusesAPublishWhileReconnecting

  @Test
  void aFailedReconnectAttemptKeepsTheQueue() {
    Outage outage = dropped(options -> {}, (runtime, channel) -> runtime.failDials = 1);
    TestRuntime runtime = outage.runtime();
    CompletableFuture<Void> queued = outage.chat().publish(bytes("x"), "m-1");

    runtime.advance(FIRST_RETRY);
    assertEquals(ChannelState.RECONNECTING, outage.channel().state());
    assertFalse(queued.isDone());

    runtime.advance(SECOND_RETRY);
    assertEquals(ChannelState.CONNECTED, outage.channel().state());
    assertEquals(List.of(publishFrame("chat", "m-1", "x")), runtime.socket().commands());
    assertSucceeded(queued);
  } // end method aFailedReconnectAttemptKeepsTheQueue

  @Test
  void runningOutOfRetriesRejectsEachQueuedPublishWithTheTerminalError() {
    Outage outage =
        dropped(
            options -> options.maximumReconnectAttempts(1),
            (runtime, channel) -> runtime.failDials = 1);
    CompletableFuture<Void> first = outage.chat().publish(bytes("x"), "m-1");
    CompletableFuture<Void> second = outage.chat().publish(bytes("y"), "m-2");

    outage.runtime().advance(FIRST_RETRY);

    assertEquals(ChannelState.FAILED, outage.channel().state());
    assertEquals(1, outage.errors().all().size());
    RuntimeException terminal = outage.errors().all().get(0);
    assertCode(terminal, ErrorCode.TRANSPORT);

    for (CompletableFuture<Void> queued : List.of(first, second)) {
      assertCode(queued, ErrorCode.TRANSPORT);
      assertEquals(terminal.getMessage(), failureOf(queued).getMessage());
    }
  } // end method runningOutOfRetriesRejectsEachQueuedPublishWithTheTerminalError

  @Test
  void anExplicitConnectAfterFailedStartsWithAnEmptyQueue() {
    Outage outage =
        dropped(
            options -> options.maximumReconnectAttempts(1),
            (runtime, channel) -> runtime.failDials = 1);
    TestRuntime runtime = outage.runtime();
    CompletableFuture<Void> queued = outage.chat().publish(bytes("x"), "m-1");
    runtime.advance(FIRST_RETRY);
    assertCode(queued, ErrorCode.TRANSPORT);

    CompletableFuture<Void> connected = outage.channel().connect();
    runtime.run();

    assertSucceeded(connected);
    assertEquals(List.of(), runtime.socket().commands());
  } // end method anExplicitConnectAfterFailedStartsWithAnEmptyQueue

  @Test
  void closeWhileReconnectingRejectsQueuedPublishesAsCancelled() {
    Outage outage = dropped();
    CompletableFuture<Void> queued = outage.chat().publish(bytes("x"), "m-1");

    outage.channel().closeAsync();
    outage.runtime().run();

    assertCode(queued, ErrorCode.CANCELLED);
    assertEquals("Channel closed before the publish was sent.", failureOf(queued).getMessage());
    assertEquals(1, outage.runtime().sockets.size());
  } // end method closeWhileReconnectingRejectsQueuedPublishesAsCancelled

  @Test
  void aPublishTheSocketTookBeforeTheDropIsNotResent() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    CompletableFuture<Void> written = channel.segment("chat").publish(bytes("x"), "m-1");
    runtime.run();
    assertSucceeded(written);

    runtime.socket().drop();
    runtime.run();

    assertEquals(ChannelState.CONNECTED, channel.state());
    assertEquals(2, runtime.sockets.size());
    assertEquals(List.of(), runtime.socket().commands());
  } // end method aPublishTheSocketTookBeforeTheDropIsNotResent

  @Test
  void cancellingAPublishQueuedWhileReconnectingWithdrawsIt() {
    Outage outage = dropped();
    CompletableFuture<Void> queued = outage.chat().publish(bytes("x"), "m-1");

    assertTrue(queued.cancel(true));
    outage.runtime().advance(FIRST_RETRY);

    assertTrue(queued.isCancelled());
    assertEquals(ChannelState.CONNECTED, outage.channel().state());
    assertEquals(List.of(), outage.runtime().socket().commands());
  } // end method cancellingAPublishQueuedWhileReconnectingWithdrawsIt
} // end class ReconnectQueueTest

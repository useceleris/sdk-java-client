package com.useceleris.client;

import static com.useceleris.client.TestRuntime.assertCode;
import static com.useceleris.client.TestRuntime.assertSucceeded;
import static com.useceleris.client.TestRuntime.failureOf;
import static com.useceleris.client.TestRuntime.publishFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** LANG-02 in Java: cancelling the future the SDK returned is the cancellation. */
class CancellationTest {
  @Test
  void cancellingAQueuedPublishWithdrawsIt() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    socket.holdWrites();
    CompletableFuture<Void> writing =
        channel.defaultSegment().publish(TestRuntime.bytes("a"), "m-1");
    CompletableFuture<Void> queued =
        channel.defaultSegment().publish(TestRuntime.bytes("b"), "m-2");
    runtime.run();

    assertTrue(queued.cancel(true));
    socket.releaseWrites();
    runtime.run();

    assertSucceeded(writing);
    assertInstanceOf(CancellationException.class, failureOf(queued));
    assertEquals(List.of(publishFrame("default", "m-1", "a")), socket.commands());
  } // end method cancellingAQueuedPublishWithdrawsIt

  @Test
  void cancellingAPublishMidWriteReportsDeliveryUnknown() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    socket.holdWrites();
    CompletableFuture<Void> writing =
        channel.defaultSegment().publish(TestRuntime.bytes("a"), "m-1");
    runtime.run();

    assertFalse(writing.cancel(true));
    assertTrue(writing.isDone());
    assertCode(writing, ErrorCode.DELIVERY_UNKNOWN);
    socket.releaseWrites();
    runtime.run();
    assertCode(writing, ErrorCode.DELIVERY_UNKNOWN);
  } // end method cancellingAPublishMidWriteReportsDeliveryUnknown

  @Test
  void cancellingASettledOperationReportsItsOutcome() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    CompletableFuture<Void> published = channel.defaultSegment().publish(TestRuntime.bytes("a"));
    runtime.run();

    assertFalse(published.cancel(true));
    assertSucceeded(published);
  } // end method cancellingASettledOperationReportsItsOutcome

  @Test
  void aTimeoutAppliedByTheCallerAbandonsThePublish() throws Exception {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    socket.holdWrites();
    channel.defaultSegment().publish(TestRuntime.bytes("a"), "m-1");
    CompletableFuture<Void> queued =
        channel.defaultSegment().publish(TestRuntime.bytes("b"), "m-2");
    runtime.run();

    queued.completeExceptionally(new TimeoutException());
    socket.releaseWrites();
    runtime.run();

    assertInstanceOf(TimeoutException.class, failureOf(queued));
    assertEquals(List.of(publishFrame("default", "m-1", "a")), socket.commands());
  } // end method aTimeoutAppliedByTheCallerAbandonsThePublish

  @Test
  void orTimeoutAbandonsAPresenceQueryAndFreesItsSlot() throws Exception {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    CompletableFuture<PresencePage> query =
        channel.segment("chat").presenceList(1, 25).orTimeout(1, TimeUnit.MILLISECONDS);

    Thread.sleep(50);
    runtime.run();
    assertInstanceOf(TimeoutException.class, failureOf(query));

    CompletableFuture<PresencePage> next = channel.segment("chat").presenceList(1, 25);
    runtime.run();
    assertFalse(next.isDone(), "the slot was not freed");
  } // end method orTimeoutAbandonsAPresenceQueryAndFreesItsSlot

  @Test
  void cancellingADerivedStageLeavesTheOperationAlone() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    FakeWebSocket socket = runtime.socket();
    socket.holdWrites();
    channel.defaultSegment().publish(TestRuntime.bytes("a"), "m-1");
    CompletableFuture<Void> queued =
        channel.defaultSegment().publish(TestRuntime.bytes("b"), "m-2");
    CompletableFuture<Void> derived = queued.thenRun(() -> {});
    runtime.run();

    derived.cancel(true);
    socket.releaseWrites();
    runtime.run();

    assertSucceeded(queued);
    assertEquals(2, socket.commands().size());
  } // end method cancellingADerivedStageLeavesTheOperationAlone

  @Test
  void cancellingAPresenceQueryFreesTheSlotAndDropsTheLateReply() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    CompletableFuture<PresencePage> first = channel.segment("chat").presenceList(1, 25);
    runtime.run();

    assertTrue(first.cancel(true));
    CompletableFuture<PresencePage> second = channel.segment("chat").presenceList(1, 25);
    runtime.run();
    // The reply to the first query carries its own request id and is dropped.
    runtime.receive(
        runtime.socket(),
        "@PRES_LIST_RESPONSE\n"
            + TestRuntime.bulk("chat")
            + TestRuntime.bulk("1")
            + ";0\n;25\n;1\n;1\n;0\n*0\n");

    assertFalse(second.isDone());
    runtime.receive(
        runtime.socket(),
        "@PRES_LIST_RESPONSE\n"
            + TestRuntime.bulk("chat")
            + TestRuntime.bulk("2")
            + ";0\n;25\n;1\n;1\n;0\n*0\n");
    assertSucceeded(second);
  } // end method cancellingAPresenceQueryFreesTheSlotAndDropsTheLateReply

  @Test
  void closingFailsPendingOperationsAsCancelledByTheSdk() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    runtime.socket().holdWrites();
    channel.defaultSegment().publish(TestRuntime.bytes("a"), "m-1");
    CompletableFuture<Void> queued =
        channel.defaultSegment().publish(TestRuntime.bytes("b"), "m-2");
    runtime.run();

    channel.closeAsync();
    runtime.advance(Duration.ofSeconds(6));

    assertCode(queued, ErrorCode.CANCELLED);
    assertEquals("Channel closed before the publish was sent.", failureOf(queued).getMessage());
  } // end method closingFailsPendingOperationsAsCancelledByTheSdk

  @Test
  void cancellingAPublishThatAlreadyFailedKeepsItsOutcome() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.channel();
    CompletableFuture<Void> published = channel.defaultSegment().publish(TestRuntime.bytes("a"));

    // The failure is recorded; the future completes once the worker runs.
    boolean cancelled = published.cancel(true);
    runtime.run();

    assertFalse(cancelled);
    assertCode(published, ErrorCode.NOT_CONNECTED);
  } // end method cancellingAPublishThatAlreadyFailedKeepsItsOutcome

  // A cancel racing the write: it finds no outcome yet, then waits for the channel's lock while
  // the write completes and records success. Once it has the lock it reports that outcome rather
  // than a cancellation.
  @Test
  void cancelThatLosesTheRaceToTheWriteReportsItsOutcome() throws Exception {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    CompletableFuture<Void> published =
        channel.defaultSegment().publish(TestRuntime.bytes("a"), "m-1");
    AtomicBoolean cancelled = new AtomicBoolean(true);
    Thread canceller = new Thread(() -> cancelled.set(published.cancel(true)), "canceller");

    channel.lock.lock();

    try {
      canceller.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

      while (canceller.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }

      assertEquals(Thread.State.WAITING, canceller.getState());

      // The write completes while the cancel waits.
      assertTrue(runtime.executor.runOne());
    } finally {
      channel.lock.unlock();
    }

    canceller.join(TimeUnit.SECONDS.toMillis(5));
    runtime.run();

    assertFalse(cancelled.get());
    assertSucceeded(published);
    assertEquals(List.of(publishFrame("default", "m-1", "a")), runtime.socket().commands());
  } // end method cancelThatLosesTheRaceToTheWriteReportsItsOutcome
} // end class CancellationTest

package com.useceleris.client;

import static com.useceleris.client.TestRuntime.assertCode;
import static com.useceleris.client.TestRuntime.assertSucceeded;
import static com.useceleris.client.TestRuntime.bulk;
import static com.useceleris.client.TestRuntime.bytes;
import static com.useceleris.client.TestRuntime.errorFrame;
import static com.useceleris.client.TestRuntime.failureOf;
import static com.useceleris.client.TestRuntime.publishFrame;
import static com.useceleris.client.TestRuntime.rateLimitFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * BATCH-01. Most tests hold the writer on a first publish, so the commands queued behind it wait
 * and go out packed once it is released.
 */
class BatchingTest {
  private final TestRuntime runtime = new TestRuntime();
  private final Channel channel = runtime.connectedChannel();
  private final FakeWebSocket socket = runtime.socket();
  private final Segment chat = channel.segment("chat");

  /** Holds the writer on a publish of its own, which goes out alone. */
  private CompletableFuture<Void> holdWriter() {
    socket.holdWrites();
    CompletableFuture<Void> held = chat.publish(bytes("x"), "h");
    runtime.run();

    return held;
  } // end method holdWriter

  private void release() {
    socket.releaseWrites();
    runtime.run();
  } // end method release

  private static void assertAllSucceeded(List<CompletableFuture<Void>> publishes) {
    publishes.forEach(TestRuntime::assertSucceeded);
  } // end method assertAllSucceeded

  @Test
  void commandsWaitingForTheWriterGoOutAsOneArrayInOrder() {
    CompletableFuture<Void> held = holdWriter();
    chat.subscribe();
    List<CompletableFuture<Void>> published =
        List.of(chat.publish(bytes("m1"), "id-1"), chat.publish(bytes("m2"), "id-2"));
    runtime.run();

    release();

    assertSucceeded(held);
    assertAllSucceeded(published);
    assertEquals(
        List.of(
            publishFrame("chat", "h", "x"),
            "*3\n@SUB\n$4\nchat\n"
                + publishFrame("chat", "id-1", "m1")
                + publishFrame("chat", "id-2", "m2")),
        socket.frames());
  } // end method commandsWaitingForTheWriterGoOutAsOneArrayInOrder

  @Test
  void theWriterPacksAtMost16Commands() {
    CompletableFuture<Void> held = holdWriter();
    List<CompletableFuture<Void>> published = new ArrayList<>();
    List<String> expected = new ArrayList<>(List.of(publishFrame("chat", "h", "x")));

    for (int index = 0; index < 17; index++) {
      published.add(chat.publish(bytes("x"), "m-" + index));
      expected.add(publishFrame("chat", "m-" + index, "x"));
    }

    runtime.run();

    release();

    assertSucceeded(held);
    assertAllSucceeded(published);
    List<String> frames = socket.frames();
    assertEquals(3, frames.size());
    assertTrue(frames.get(1).startsWith("*16\n@PUB"));
    assertEquals(publishFrame("chat", "m-16", "x"), frames.get(2));
    assertEquals(expected, socket.commands());
  } // end method theWriterPacksAtMost16Commands

  @Test
  void theWriterPacksUpTo64KiBWithTheArrayHeaderAndNoFurther() {
    // A publish here is 26 bytes of framing plus its payload, so "*2\n" and payloads of 32 740
    // and 32 741 bytes make exactly 65 536.
    CompletableFuture<Void> held = holdWriter();
    List<CompletableFuture<Void>> fits =
        List.of(chat.publish(new byte[32_740], "a"), chat.publish(new byte[32_741], "b"));
    runtime.run();

    release();

    assertSucceeded(held);
    assertAllSucceeded(fits);
    assertEquals(2, socket.frames().size());
    assertEquals(65_536, socket.frames().get(1).length());

    socket.clearCommands();
    CompletableFuture<Void> heldAgain = holdWriter();
    List<CompletableFuture<Void>> overflows =
        List.of(chat.publish(new byte[32_740], "c"), chat.publish(new byte[32_742], "d"));
    runtime.run();

    release();

    assertSucceeded(heldAgain);
    assertAllSucceeded(overflows);
    List<String> frames = socket.frames();
    assertEquals(3, frames.size());
    assertTrue(frames.get(1).startsWith("@PUB") && frames.get(2).startsWith("@PUB"));
  } // end method theWriterPacksUpTo64KiBWithTheArrayHeaderAndNoFurther

  @Test
  void aCommandLargerThan64KiBGoesAlone() {
    String large = "x".repeat(70 * 1024);
    CompletableFuture<Void> held = holdWriter();
    List<CompletableFuture<Void>> published =
        List.of(
            chat.publish(bytes("x"), "a"),
            chat.publish(bytes(large), "b"),
            chat.publish(bytes("x"), "c"));
    runtime.run();

    release();

    assertSucceeded(held);
    assertAllSucceeded(published);
    assertEquals(
        List.of(
            publishFrame("chat", "h", "x"),
            publishFrame("chat", "a", "x"),
            publishFrame("chat", "b", large),
            publishFrame("chat", "c", "x")),
        socket.frames());
  } // end method aCommandLargerThan64KiBGoesAlone

  @Test
  void aFailedWriteFailsEveryPublishInItsBatch() {
    CompletableFuture<Void> held = holdWriter();
    List<CompletableFuture<Void>> published =
        List.of(
            chat.publish(bytes("x"), "a"),
            chat.publish(bytes("x"), "b"),
            chat.publish(bytes("x"), "c"));
    runtime.run();

    socket.failWrites();
    release();

    assertSucceeded(held);
    published.forEach(publish -> assertCode(publish, ErrorCode.DELIVERY_UNKNOWN));
    assertEquals(List.of(publishFrame("chat", "h", "x")), socket.frames());
  } // end method aFailedWriteFailsEveryPublishInItsBatch

  @Test
  void cancellingAWaitingPublishTakesItOutOfTheNextBatch() {
    CompletableFuture<Void> held = holdWriter();
    CompletableFuture<Void> first = chat.publish(bytes("x"), "a");
    CompletableFuture<Void> cancelled = chat.publish(bytes("x"), "b");
    CompletableFuture<Void> last = chat.publish(bytes("x"), "c");
    runtime.run();

    assertTrue(cancelled.cancel(true));
    release();

    assertSucceeded(held);
    assertAllSucceeded(List.of(first, last));
    assertInstanceOf(CancellationException.class, failureOf(cancelled));
    assertEquals(
        List.of(
            publishFrame("chat", "h", "x"),
            "*2\n" + publishFrame("chat", "a", "x") + publishFrame("chat", "c", "x")),
        socket.frames());
  } // end method cancellingAWaitingPublishTakesItOutOfTheNextBatch

  @Test
  void cancellingAPublishInTheBatchBeingWrittenIsDeliveryUnknown() {
    CompletableFuture<Void> held = holdWriter();
    List<CompletableFuture<Void>> published =
        List.of(
            chat.publish(bytes("x"), "a"),
            chat.publish(bytes("x"), "b"),
            chat.publish(bytes("x"), "c"));
    runtime.run();

    // The batch starts writing once the held publish completes, and is held in turn.
    socket.releaseWrites();
    socket.holdWrites();
    runtime.run();

    published.get(1).cancel(true);
    assertCode(published.get(1), ErrorCode.DELIVERY_UNKNOWN);

    release();

    assertSucceeded(held);
    assertSucceeded(published.get(0));
    assertSucceeded(published.get(2));
    assertEquals(
        List.of(
            publishFrame("chat", "h", "x"),
            "*3\n"
                + publishFrame("chat", "a", "x")
                + publishFrame("chat", "b", "x")
                + publishFrame("chat", "c", "x")),
        socket.frames());
  } // end method cancellingAPublishInTheBatchBeingWrittenIsDeliveryUnknown

  @Test
  void aPublishRefusedForItsSizeIsNotResent() {
    TestRuntime.Recorder<RuntimeException> errors = new TestRuntime.Recorder<>();
    channel.events().onError(errors);
    assertSucceededNow(chat.publish(bytes("big"), "too-big"));
    assertSucceededNow(chat.publish(bytes("ok"), "fits"));
    socket.clearCommands();

    runtime.receive(
        socket,
        errorFrame(
            "MessageSizeLimitError",
            "Message size limit exceeded; payload size = 3 bytes; size limit = 1 KB",
            "PUB",
            bulk("too-big")),
        rateLimitFrame());
    runtime.advance(Duration.ofSeconds(1));

    assertEquals(List.of(publishFrame("chat", "fits", "ok")), socket.commands());
    assertEquals(2, errors.all().size());
    ServerErrorException refused =
        assertInstanceOf(ServerErrorException.class, errors.all().get(0));
    assertEquals(ServerErrorException.MESSAGE_SIZE_LIMIT_ERROR, refused.type());
    assertEquals(Optional.of("PUB"), refused.subType());
    assertEquals(Optional.of("too-big"), refused.resource());
  } // end method aPublishRefusedForItsSizeIsNotResent

  private void assertSucceededNow(CompletableFuture<Void> publish) {
    runtime.run();
    assertSucceeded(publish);
  } // end method assertSucceededNow
} // end class BatchingTest

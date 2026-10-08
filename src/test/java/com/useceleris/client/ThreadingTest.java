package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Which threads run what, with the client's real threads and the JDK's real WebSocket client. */
@org.junit.jupiter.api.Tag("transport")
class ThreadingTest {
  @Test
  void listenersRunOnClientWorkersOneAtATimeAndMayBlockOnFutures() throws Exception {
    try (TestWebSocketServer server = TestWebSocketServer.plain()) {
      Channel channel = client(server).channel("room-1");
      Set<String> threads = ConcurrentHashMap.newKeySet();
      AtomicInteger running = new AtomicInteger();
      List<String> overlaps = new CopyOnWriteArrayList<>();
      CompletableFuture<Void> blockedOnPublish = new CompletableFuture<>();
      channel
          .defaultSegment()
          .onMessage(
              (payload, metadata) -> {
                if (running.incrementAndGet() > 1) {
                  overlaps.add(metadata.messageId());
                }

                threads.add(Thread.currentThread().getName());

                if (metadata.messageId().equals("m-1")) {
                  // A listener may wait for a future the channel returns: futures complete
                  // independently of event delivery.
                  channel.defaultSegment().publish(TestRuntime.bytes("echo")).join();
                  blockedOnPublish.complete(null);
                }

                running.decrementAndGet();
              });

      CompletableFuture<Void> connected = channel.connect();
      TestWebSocketServer.Peer peer = server.accept();
      connected.get(10, TimeUnit.SECONDS);

      assertTimeoutPreemptively(
          Duration.ofSeconds(10),
          () -> {
            for (int index = 1; index <= 20; index++) {
              peer.sendBinary(
                  TestRuntime.bytes(TestRuntime.messageFrame("default", "m-" + index, "x")));
            }

            peer.readBinary();
            blockedOnPublish.get();
          });

      assertTrue(overlaps.isEmpty(), "listeners overlapped: " + overlaps);
      assertTrue(
          threads.stream()
              .allMatch(name -> name.startsWith("celeris-") && name.contains("-worker-")),
          "listener threads " + threads);
      peer.answerCloseInBackground();
      channel.close();
      assertEquals(ChannelState.CLOSED, channel.state());
    }
  } // end method listenersRunOnClientWorkersOneAtATimeAndMayBlockOnFutures

  @Test
  void aStageThatWaitsForAnotherFutureOfTheChannelCompletes() throws Exception {
    try (TestWebSocketServer server = TestWebSocketServer.plain()) {
      Channel channel = client(server).channel("room-1");

      // Attached before the handshake, so it runs where the SDK completes the connect future.
      CompletableFuture<Void> connected = channel.connect();
      CompletableFuture<Void> publishedFromStage =
          connected.thenRun(() -> channel.defaultSegment().publish(TestRuntime.bytes("x")).join());
      TestWebSocketServer.Peer peer = server.accept();

      assertTimeoutPreemptively(Duration.ofSeconds(10), () -> publishedFromStage.get());

      peer.readBinary();
      peer.answerCloseInBackground();
      channel.close();
    }
  } // end method aStageThatWaitsForAnotherFutureOfTheChannelCompletes

  private static CelerisClient client(TestWebSocketServer server) {
    return CelerisClient.create(
        ClientOptions.builder(
                request ->
                    CompletableFuture.completedFuture(new Credentials("payload", "signature")))
            .baseUrl("ws://127.0.0.1:" + server.port())
            .allowInsecureLoopback(true)
            .build());
  } // end method client

  @Test
  void theLongestTimeoutsWorkWithTheRealTimers() throws Exception {
    try (TestWebSocketServer server = TestWebSocketServer.plain()) {
      Channel channel =
          CelerisClient.create(
                  ClientOptions.builder(
                          request ->
                              CompletableFuture.completedFuture(
                                  new Credentials("payload", "signature")))
                      .baseUrl("ws://127.0.0.1:" + server.port())
                      .allowInsecureLoopback(true)
                      .connectTimeout(Duration.ofMinutes(15))
                      .presenceQueryTimeout(Duration.ofMinutes(15))
                      .build())
              .channel("room-1");
      CompletableFuture<Void> connected = channel.connect();
      TestWebSocketServer.Peer peer = server.accept();
      connected.get(10, TimeUnit.SECONDS);

      CompletableFuture<PresencePage> query = channel.segment("chat").presenceList(1, 25);
      peer.readBinary();

      assertTrue(query.cancel(true));
      peer.answerCloseInBackground();
      channel.close();
    }
  } // end method theLongestTimeoutsWorkWithTheRealTimers
} // end class ThreadingTest

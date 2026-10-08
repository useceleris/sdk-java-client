package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The real JDK WebSocket client against a raw RFC 6455 server on loopback. */
@Tag("transport")
class TransportTest {
  private static final Credentials CREDENTIALS = new Credentials("payload-1", "signature-1");

  private static CelerisClient client(String baseUrl) {
    return CelerisClient.create(
        ClientOptions.builder(request -> CompletableFuture.completedFuture(CREDENTIALS))
            .baseUrl(baseUrl)
            .allowInsecureLoopback(true)
            .connectTimeout(Duration.ofSeconds(5))
            .build());
  } // end method client

  private static CelerisException failure(CompletableFuture<?> future) throws Exception {
    try {
      future.get(10, TimeUnit.SECONDS);
    } catch (ExecutionException failed) {
      return assertInstanceOf(CelerisException.class, failed.getCause());
    }

    throw new AssertionError("future completed normally");
  } // end method failure

  @Test
  void deliversFragmentedMessagesAndWritesCommands() throws Exception {
    try (TestWebSocketServer server = TestWebSocketServer.plain()) {
      Channel channel = client("ws://127.0.0.1:" + server.port()).channel("room-1");
      byte[] large = new byte[1536 * 1024];
      new SecureRandom().nextBytes(large);
      CompletableFuture<byte[]> received = new CompletableFuture<>();
      CompletableFuture<RuntimeException> error = new CompletableFuture<>();
      channel.defaultSegment().onMessage((payload, metadata) -> received.complete(payload));
      channel.events().onError(error::complete);

      CompletableFuture<Void> connected = channel.connect();
      TestWebSocketServer.Peer peer = server.accept();
      connected.get(10, TimeUnit.SECONDS);

      assertEquals("/channel/room-1?payload=payload-1&signature=signature-1", peer.requestTarget);

      ByteArrayBuilder frame = new ByteArrayBuilder();
      frame.text("@MSG\n$4\nuser\n$7\ndefault\n$3\nm-1\n:1\n$" + large.length + "\n");
      frame.bytes(large);
      frame.text("\n");
      peer.sendFragmented(frame.build(), 64 * 1024);
      assertArrayEquals(large, received.get(10, TimeUnit.SECONDS));

      CompletableFuture<Void> published =
          channel.defaultSegment().publish(TestRuntime.bytes("hi"), "m-2");
      published.get(10, TimeUnit.SECONDS);
      assertEquals(
          TestRuntime.publishFrame("default", "m-2", "hi"),
          new String(peer.readBinary(), StandardCharsets.UTF_8));

      peer.sendText("not binary");
      CelerisException protocol =
          assertInstanceOf(CelerisException.class, error.get(10, TimeUnit.SECONDS));
      assertEquals(ErrorCode.PROTOCOL, protocol.code());
      assertEquals(
          "Expected a binary WebSocket message. Field: message, byte offset 0.",
          protocol.getMessage());
      assertEquals(ChannelState.CONNECTED, channel.state());

      peer.answerCloseInBackground();
      channel.close();
    }
  } // end method deliversFragmentedMessagesAndWritesCommands

  @Test
  void framesSentWithTheHandshakeArriveAfterTheConnectedEvent() throws Exception {
    try (TestWebSocketServer server = TestWebSocketServer.plain()) {
      Channel channel = client("ws://127.0.0.1:" + server.port()).channel("room-1");
      List<String> order = Collections.synchronizedList(new ArrayList<>());
      CountDownLatch delivered = new CountDownLatch(1);
      channel.events().onStateChange(state -> order.add(state.toString()));
      channel
          .defaultSegment()
          .onMessage(
              (payload, metadata) -> {
                order.add("message");
                delivered.countDown();
              });

      CompletableFuture<Void> connected = channel.connect();
      TestWebSocketServer.Peer peer = server.accept();
      // Sent before the client could have installed the socket.
      peer.sendBinary(TestRuntime.bytes(TestRuntime.messageFrame("default", "m-1", "early")));
      connected.get(10, TimeUnit.SECONDS);

      assertTrue(delivered.await(10, TimeUnit.SECONDS));
      assertEquals(List.of("connecting", "connected", "message"), order);
      peer.answerCloseInBackground();
      channel.close();
    }
  } // end method framesSentWithTheHandshakeArriveAfterTheConnectedEvent

  @Test
  void aRefusedHandshakeIsATransportFailureThatQuotesNothing() throws Exception {
    try (TestWebSocketServer server = TestWebSocketServer.plain()) {
      server.refuseWith(401);
      Channel channel = client("ws://127.0.0.1:" + server.port()).channel("room-1");

      CelerisException refused = failure(channel.connect());

      assertEquals(ErrorCode.TRANSPORT, refused.code());
      assertFalse(
          refused.getMessage().contains("payload-1") || refused.getMessage().contains("signature"));
      assertEquals(null, refused.getCause());
      assertEquals(ChannelState.FAILED, channel.state());
    }
  } // end method aRefusedHandshakeIsATransportFailureThatQuotesNothing

  @Test
  void certificatesAreVerifiedEvenWhenTheDefaultContextTrustsEverything() throws Exception {
    SSLContext original = SSLContext.getDefault();
    SSLContext trustingEverything = SSLContext.getInstance("TLS");
    trustingEverything.init(null, new TrustManager[] {new TrustingEverything()}, null);

    try (TestWebSocketServer server = TestWebSocketServer.tls()) {
      SSLContext.setDefault(trustingEverything);
      Channel channel = client("wss://localhost:" + server.port()).channel("room-1");

      CelerisException refused = failure(channel.connect());

      assertEquals(ErrorCode.TRANSPORT, refused.code());
    } finally {
      SSLContext.setDefault(original);
    }
  } // end method certificatesAreVerifiedEvenWhenTheDefaultContextTrustsEverything

  @Test
  void serverPingsAreAnsweredWhileConnected() throws Exception {
    try (TestWebSocketServer server = TestWebSocketServer.plain()) {
      Channel channel = client("ws://127.0.0.1:" + server.port()).channel("room-1");
      CompletableFuture<Void> connected = channel.connect();
      TestWebSocketServer.Peer peer = server.accept();
      connected.get(10, TimeUnit.SECONDS);

      peer.sendPing();
      TestWebSocketServer.Frame pong = peer.readFrame();

      assertEquals(0xA, pong.opcode());
      assertArrayEquals(new byte[] {1, 2, 3}, pong.payload());
      peer.answerCloseInBackground();
      channel.close();
    }
  } // end method serverPingsAreAnsweredWhileConnected

  @Test
  void aDroppedConnectionRecoversOnANewSocket() throws Exception {
    try (TestWebSocketServer server = TestWebSocketServer.plain()) {
      Channel channel = client("ws://127.0.0.1:" + server.port()).channel("room-1");
      CompletableFuture<RecoveryEvent> recovered = new CompletableFuture<>();
      channel.events().onRecovery(recovered::complete);
      CompletableFuture<Void> connected = channel.connect();
      TestWebSocketServer.Peer first = server.accept();
      connected.get(10, TimeUnit.SECONDS);

      first.close();
      TestWebSocketServer.Peer second = server.accept();
      RecoveryEvent recovery = recovered.get(10, TimeUnit.SECONDS);

      assertTrue(recovery.possibleGaps() && recovery.possibleDuplicates());
      assertEquals(ChannelState.CONNECTED, channel.state());
      second.answerCloseInBackground();
      channel.close();
    }
  } // end method aDroppedConnectionRecoversOnANewSocket

  @Test
  void closeIsBoundedAgainstAPeerThatNeverAnswers() throws Exception {
    try (TestWebSocketServer server = TestWebSocketServer.plain()) {
      Channel channel = client("ws://127.0.0.1:" + server.port()).channel("room-1");
      CompletableFuture<Void> connected = channel.connect();
      TestWebSocketServer.Peer peer = server.accept();
      connected.get(10, TimeUnit.SECONDS);
      long start = System.nanoTime();

      channel.close();

      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
      assertTrue(elapsed.compareTo(Duration.ofSeconds(7)) < 0, "close took " + elapsed);
      assertEquals(ChannelState.CLOSED, channel.state());
      peer.close();
    }
  } // end method closeIsBoundedAgainstAPeerThatNeverAnswers

  private static final class TrustingEverything implements X509TrustManager {
    @Override
    public void checkClientTrusted(
        X509Certificate[] chain, String authType) {} // end method checkClientTrusted

    @Override
    public void checkServerTrusted(
        X509Certificate[] chain, String authType) {} // end method checkServerTrusted

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    } // end method getAcceptedIssuers
  } // end class TrustingEverything

  private static final class ByteArrayBuilder {
    private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

    void text(String text) {
      bytes.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    } // end method text

    void bytes(byte[] value) {
      bytes.writeBytes(value);
    } // end method bytes

    byte[] build() {
      return bytes.toByteArray();
    } // end method build
  } // end class ByteArrayBuilder
} // end class TransportTest

package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * A client whose sockets, credentials, threads and clock are all fakes the test drives: nothing
 * happens until it calls {@link #run()} or {@link #advance(Duration)}, and both return only once
 * every task due has run. Jitter is zero unless a test sets {@link #random}.
 */
final class TestRuntime {
  static final Credentials CREDENTIALS = new Credentials("payload-1", "signature-1");
  static final PresenceConnection PRESENCE_CONNECTION =
      new PresenceConnection("user", "connection-1", 123);

  final ManualExecutor executor = new ManualExecutor();
  final FakeTimers timers = new FakeTimers(executor);
  final List<FakeWebSocket> sockets = new ArrayList<>();
  final List<CredentialRequest> credentialRequests = new ArrayList<>();
  final List<URI> dialedUrls = new ArrayList<>();
  final List<Duration> dialTimeouts = new ArrayList<>();
  final List<CompletableFuture<Credentials>> blockedProviders = new ArrayList<>();
  final List<CompletableFuture<WebSocket>> blockedDials = new ArrayList<>();

  // How the fakes answer, changed by tests.
  int failDials;
  boolean blockDials;
  boolean blockProvider;
  boolean failProvider;
  Credentials credentials = CREDENTIALS;

  // The jitter every delay draws, read at each draw; zero keeps delays at their floor.
  double random;

  // Makes every socket dialed from now on answer the client's pings, as a live server does.
  boolean answerPings;

  // Runs on every socket as it opens, before the channel reads anything from it.
  Consumer<FakeWebSocket> onOpen = socket -> {};

  final CelerisClient client;

  TestRuntime() {
    this(builder -> {});
  } // end constructor TestRuntime

  TestRuntime(Consumer<ClientOptions.Builder> configure) {
    ClientOptions.Builder builder =
        ClientOptions.builder(this::provide).baseUrl("wss://example.test/");
    configure.accept(builder);
    client = new CelerisClient(builder.build(), this::dial, timers, executor, () -> random);
  } // end constructor TestRuntime

  private CompletionStage<Credentials> provide(CredentialRequest request) {
    credentialRequests.add(request);

    if (blockProvider) {
      CompletableFuture<Credentials> blocked = new CompletableFuture<>();
      blockedProviders.add(blocked);

      return blocked;
    }

    if (failProvider) {
      return CompletableFuture.failedFuture(new IllegalStateException("synthetic-secret failure"));
    }

    return CompletableFuture.completedFuture(credentials);
  } // end method provide

  private CompletableFuture<WebSocket> dial(
      URI url, Duration timeout, WebSocket.Listener listener) {
    dialedUrls.add(url);
    dialTimeouts.add(timeout);

    if (failDials > 0) {
      failDials--;

      // Real handshake errors quote the URL; the channel must never pass it on.
      return CompletableFuture.failedFuture(new java.io.IOException("refused: " + url));
    }

    if (blockDials) {
      CompletableFuture<WebSocket> blocked = new CompletableFuture<>();
      blockedDials.add(blocked);

      return blocked;
    }

    FakeWebSocket socket = new FakeWebSocket(url, listener, executor);
    sockets.add(socket);

    if (answerPings) {
      socket.answerPings();
    }

    listener.onOpen(socket);
    onOpen.accept(socket);

    return CompletableFuture.completedFuture(socket);
  } // end method dial

  /** Runs every runnable task and every timer already due. */
  void run() {
    while (executor.runOne() || timers.fireNextDueBy(timers.monotonicNanos())) {
      // Keep going until nothing is left to do now.
    }
  } // end method run

  /** Moves the clock forward, running each timer as its time comes, then everything left. */
  void advance(Duration duration) {
    long target = timers.monotonicNanos() + duration.toNanos();
    run();

    while (timers.fireNextDueBy(target)) {
      run();
    }

    timers.moveTo(target);
    run();
  } // end method advance

  Channel channel() {
    return client.channel("room-1");
  } // end method channel

  /** A connected channel for "room-1". */
  Channel connectedChannel() {
    Channel channel = channel();
    CompletableFuture<Void> connected = channel.connect();
    run();
    assertTrue(connected.isDone() && !connected.isCompletedExceptionally(), "connect failed");

    return channel;
  } // end method connectedChannel

  FakeWebSocket socket() {
    return sockets.get(sockets.size() - 1);
  } // end method socket

  /** Delivers frames from the server one at a time, letting each be routed. */
  void receive(FakeWebSocket socket, String... frames) {
    for (String frame : frames) {
      socket.receive(frame);
      run();
    }
  } // end method receive

  // ---- Assertions ----

  static Throwable failureOf(CompletableFuture<?> future) {
    assertTrue(future.isDone(), "future is still pending");

    try {
      future.get();
    } catch (CancellationException cancelled) {
      return cancelled;
    } catch (ExecutionException failed) {
      return failed.getCause();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }

    return fail("future completed normally");
  } // end method failureOf

  static void assertCode(CompletableFuture<?> future, ErrorCode code) {
    CelerisException failure = assertInstanceOf(CelerisException.class, failureOf(future));
    assertEquals(code, failure.code(), failure.getMessage());
  } // end method assertCode

  static void assertCode(Throwable failure, ErrorCode code) {
    CelerisException sdkFailure = assertInstanceOf(CelerisException.class, failure);
    assertEquals(code, sdkFailure.code(), sdkFailure.getMessage());
  } // end method assertCode

  static void assertSucceeded(CompletableFuture<?> future) {
    assertTrue(future.isDone(), "future is still pending");
    assertTrue(!future.isCompletedExceptionally(), () -> "future failed: " + failureOf(future));
  } // end method assertSucceeded

  static void assertProtocolError(Throwable failure, String reason, String field, int offset) {
    CelerisException protocol = assertInstanceOf(CelerisException.class, failure);
    assertEquals(ErrorCode.PROTOCOL, protocol.code());
    assertEquals(
        reason + " Field: " + field + ", byte offset " + offset + ".", protocol.getMessage());
    assertEquals(Optional.of(field), protocol.field());
    assertEquals(offset, protocol.offset().orElseThrow());
  } // end method assertProtocolError

  static void assertServerError(
      Throwable failure,
      String type,
      @Nullable String subType,
      String message,
      @Nullable Object resource) {
    ServerErrorException serverError = assertInstanceOf(ServerErrorException.class, failure);
    assertEquals(type, serverError.type());
    assertEquals(Optional.ofNullable(subType), serverError.subType());
    assertEquals(message, serverError.getMessage());
    assertEquals(Optional.ofNullable(resource), serverError.resource());
  } // end method assertServerError

  // ---- Frames ----

  static String bulk(String value) {
    return "$" + value.getBytes(StandardCharsets.UTF_8).length + "\n" + value + "\n";
  } // end method bulk

  /** A delivery laid out as the server sends it; a null message id is sent as null. */
  static String messageFrame(String segmentId, @Nullable String messageId, String body) {
    String identifier = messageId == null ? "$-1\n" : bulk(messageId);

    return "@MSG\n$4\nuser\n" + bulk(segmentId) + identifier + ":1\n" + bulk(body);
  } // end method messageFrame

  /** An error frame; a null sub type or resource is sent as null. */
  static String errorFrame(
      String errorType, String message, @Nullable String subType, @Nullable String resource) {
    String subTypeField = subType == null ? "$-1\n" : "+" + subType + "\n";

    return "-Err\n+"
        + errorType
        + "\n"
        + subTypeField
        + bulk(message)
        + (resource == null ? "$-1\n" : resource);
  } // end method errorFrame

  static String rateLimitFrame() {
    return errorFrame("RateLimitError", "Rate limit exceeded", null, null);
  } // end method rateLimitFrame

  static String publishFrame(String segmentId, String messageId, String body) {
    return "@PUB\n" + bulk(segmentId) + bulk(messageId) + bulk(body);
  } // end method publishFrame

  /** A presence page laid out as the server sends it. */
  static String presenceResponseFrame(
      String segmentId,
      String requestId,
      int total,
      int perPage,
      int currentPage,
      int from,
      int to,
      List<PresenceConnection> connections) {
    StringBuilder frame =
        new StringBuilder("@PRES_LIST_RESPONSE\n+" + segmentId + "\n" + bulk(requestId));

    for (int figure : new int[] {total, perPage, currentPage, from, to}) {
      frame.append(';').append(figure).append('\n');
    }

    frame.append('*').append(connections.size()).append('\n');

    for (PresenceConnection connection : connections) {
      frame
          .append("*3\n+")
          .append(connection.tokenReference())
          .append("\n+")
          .append(connection.connectionId())
          .append("\n:")
          .append(connection.timestamp())
          .append('\n');
    }

    return frame.toString();
  } // end method presenceResponseFrame

  /** A one-connection page of "chat" answering the request id. */
  static String presenceResponseFrame(String requestId) {
    return presenceResponseFrame("chat", requestId, 1, 25, 1, 1, 1, List.of(PRESENCE_CONNECTION));
  } // end method presenceResponseFrame

  /** An error frame answering the presence query with the request id. */
  static String presenceErrorFrame(String errorType, String requestId) {
    return errorFrame(errorType, "failed", "PRES_LIST", bulk(requestId));
  } // end method presenceErrorFrame

  static String presenceNotifyFrame(String segmentId, boolean joined, long timestamp) {
    return "@PRES_NOTIFY\n+"
        + segmentId
        + "\n+user\n+connection-1\n;"
        + (joined ? 1 : 0)
        + "\n:"
        + timestamp
        + "\n";
  } // end method presenceNotifyFrame

  static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  } // end method bytes

  /** Collects what listeners receive. */
  static final class Recorder<T> implements Consumer<T> {
    private final List<T> values = new ArrayList<>();

    @Override
    public synchronized void accept(T value) {
      values.add(value);
    } // end method accept

    synchronized List<T> all() {
      return List.copyOf(values);
    } // end method all
  } // end class Recorder
} // end class TestRuntime

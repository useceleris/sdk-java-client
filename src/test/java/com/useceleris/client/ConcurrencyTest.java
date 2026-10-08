package com.useceleris.client;

import static com.useceleris.client.TestRuntime.assertCode;
import static com.useceleris.client.TestRuntime.assertSucceeded;
import static com.useceleris.client.TestRuntime.bytes;
import static com.useceleris.client.TestRuntime.messageFrame;
import static com.useceleris.client.TestRuntime.presenceNotifyFrame;
import static com.useceleris.client.TestRuntime.presenceResponseFrame;
import static com.useceleris.client.TestRuntime.publishFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Listeners calling back into the channel, then the channel under real threads: the client's own
 * workers and timers, with the in-memory peer. The threaded tests bound every wait, so a hang fails
 * the test instead of stalling the build.
 */
class ConcurrencyTest {
  private static final Duration BOUND = Duration.ofSeconds(10);
  private static final Duration CHAOS_DURATION = Duration.ofSeconds(2);

  // ---- Listeners calling back ----

  // Listeners never run under an SDK lock, so they may call back into the channel. Calls they make
  // never wait for their own events: those follow once the listener returns.
  @Test
  void listenersMayCallBackIntoTheChannel() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    Segment chat = channel.segment("chat");
    TestRuntime.Recorder<String> outcomes = new TestRuntime.Recorder<>();
    List<CompletableFuture<Void>> echoes = new ArrayList<>();
    Registration[] self = new Registration[1];
    self[0] =
        chat.onMessage(
            (payload, metadata) -> {
              echoes.add(chat.publish(bytes("echo"), "echo-" + metadata.messageId()));
              chat.subscribe().cancel();
              channel.events().onNotice(notice -> {}).close();
              self[0].close();
              outcomes.accept("done " + metadata.messageId());
            });

    runtime.receive(
        runtime.socket(), messageFrame("chat", "id-1", "x"), messageFrame("chat", "id-2", "x"));

    assertEquals(List.of("done id-1"), outcomes.all());
    assertEquals(1, echoes.size());
    assertSucceeded(echoes.get(0));
    assertEquals(
        List.of(
            publishFrame("chat", "echo-id-1", "echo"), "@SUB\n$4\nchat\n", "@UNSUB\n$4\nchat\n"),
        runtime.socket().commands());
  } // end method listenersMayCallBackIntoTheChannel

  @Test
  void closeFromInsideAListener() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<String> log = new TestRuntime.Recorder<>();
    channel.events().onStateChange(state -> log.accept(state.toString()));
    channel
        .events()
        .onNotice(
            notice -> {
              channel.closeAsync();
              log.accept("closed in listener");
            });

    channel.segment("chat").onMessage((payload, metadata) -> log.accept("message"));

    runtime.socket().receive("*2\n@SERVER_MSG\n:1\n$0\n\n" + messageFrame("chat", "id-1", "x"));
    runtime.run();

    // The batch's later entry is never routed once the channel closed.
    assertEquals(List.of("closed in listener", "closing", "closed"), log.all());
  } // end method closeFromInsideAListener

  @Test
  void connectFromInsideAFailedStateListener() {
    TestRuntime runtime = new TestRuntime();
    runtime.failDials = 1;
    Channel channel = runtime.channel();
    List<CompletableFuture<Void>> retried = new ArrayList<>();
    channel
        .events()
        .onStateChange(
            state -> {
              if (state == ChannelState.FAILED) {
                retried.add(channel.connect());
              }
            });

    CompletableFuture<Void> connected = channel.connect();
    runtime.run();

    assertCode(connected, ErrorCode.TRANSPORT);
    assertEquals(1, retried.size());
    assertSucceeded(retried.get(0));
    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method connectFromInsideAFailedStateListener

  @Test
  void eventsFollowTheOrderStateChanged() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    TestRuntime.Recorder<String> log = new TestRuntime.Recorder<>();
    channel.events().onStateChange(state -> log.accept(state.toString()));
    channel.events().onError(error -> log.accept("error"));
    runtime.credentials = new Credentials("", "");

    runtime.socket().drop();
    runtime.run();

    // A terminal failure reports its error before the failed state.
    assertEquals(List.of("reconnecting", "error", "failed"), log.all());
  } // end method eventsFollowTheOrderStateChanged

  // Go's listener may end its goroutine; Java's analog is a fatal error leaving the worker.
  // Dispatch
  // is not left marked as running: the rest of the queue, and every later event, is delivered.
  @Test
  void listenerThrowingAFatalErrorLeavesDispatchUsable() {
    TestRuntime runtime = new TestRuntime();
    runtime.failProvider = true;
    Channel channel = runtime.channel();
    TestRuntime.Recorder<ChannelState> seen = new TestRuntime.Recorder<>();
    boolean[] thrown = new boolean[1];
    channel
        .events()
        .onStateChange(
            state -> {
              seen.accept(state);

              if (!thrown[0]) {
                thrown[0] = true;

                throw new StackOverflowError("injected");
              }
            });

    channel.connect();
    assertThrows(StackOverflowError.class, runtime::run);
    runtime.run();
    runtime.failProvider = false;
    channel.connect();
    runtime.run();

    assertEquals(
        List.of(
            ChannelState.CONNECTING,
            ChannelState.FAILED,
            ChannelState.CONNECTING,
            ChannelState.CONNECTED),
        seen.all());
  } // end method listenerThrowingAFatalErrorLeavesDispatchUsable

  // ---- Real threads ----

  /** A client on its own daemon workers and real timers, dialing in-memory peers. */
  private static final class ThreadedRuntime {
    final ClientThreads threads = new ClientThreads();
    final List<FakeWebSocket> sockets = new CopyOnWriteArrayList<>();
    final Queue<CredentialRequest> credentialRequests = new ConcurrentLinkedQueue<>();
    final CelerisClient client;

    // The jitter every delay draws, read at each draw.
    volatile double random;

    ThreadedRuntime(Duration presenceQueryTimeout) {
      client =
          new CelerisClient(
              ClientOptions.builder(this::provide)
                  .baseUrl("wss://example.test/")
                  .presenceQueryTimeout(presenceQueryTimeout)
                  .build(),
              this::dial,
              threads,
              threads,
              () -> random);
    } // end constructor ThreadedRuntime

    private CompletableFuture<Credentials> provide(CredentialRequest request) {
      credentialRequests.add(request);

      return CompletableFuture.completedFuture(TestRuntime.CREDENTIALS);
    } // end method provide

    private CompletableFuture<WebSocket> dial(
        URI url, Duration timeout, WebSocket.Listener listener) {
      FakeWebSocket socket = new FakeWebSocket(url, listener, threads);
      socket.answerPings();
      sockets.add(socket);
      listener.onOpen(socket);

      return CompletableFuture.completedFuture(socket);
    } // end method dial

    FakeWebSocket socket() {
      return sockets.get(sockets.size() - 1);
    } // end method socket

    Channel connectedChannel() throws Exception {
      Channel channel = client.channel("room-1");
      channel.connect().get(BOUND.toMillis(), TimeUnit.MILLISECONDS);

      return channel;
    } // end method connectedChannel
  } // end class ThreadedRuntime

  /**
   * Wraps listeners to catch any two running at once, or one running under the channel's lock;
   * either would break DEV-01.
   */
  private static final class ListenerGuard {
    private final Channel channel;
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger overlaps = new AtomicInteger();
    private final AtomicInteger underLock = new AtomicInteger();

    ListenerGuard(Channel channel) {
      this.channel = channel;
    } // end constructor ListenerGuard

    void run(Runnable listener) {
      if (running.incrementAndGet() != 1) {
        overlaps.incrementAndGet();
      }

      if (channel.lock.isHeldByCurrentThread()) {
        underLock.incrementAndGet();
      }

      try {
        listener.run();
      } finally {
        running.decrementAndGet();
      }
    } // end method run

    void assertSerial() {
      assertEquals(0, overlaps.get(), "listeners ran concurrently");
      assertEquals(0, underLock.get(), "listeners ran under the channel's lock");
    } // end method assertSerial
  } // end class ListenerGuard

  /** Waits for every future, failing if any is still pending at the bound. */
  private static void awaitAll(Queue<CompletableFuture<?>> futures) throws Exception {
    CompletableFuture<?>[] settled =
        futures.stream()
            .map(future -> future.handle((value, failure) -> null))
            .toArray(CompletableFuture<?>[]::new);
    CompletableFuture.allOf(settled).get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
  } // end method awaitAll

  private static void awaitCondition(String description, BooleanSupplier done)
      throws InterruptedException {
    long deadline = System.nanoTime() + BOUND.toNanos();

    while (!done.getAsBoolean()) {
      assertTrue(System.nanoTime() < deadline, "timed out waiting for " + description);
      Thread.sleep(1);
    }
  } // end method awaitCondition

  // LIFE-04: concurrent use from many threads is safe. Each worker waits for its publish, as Go's
  // blocking publish does.
  @Test
  void concurrentUseIsSafe() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () -> {
          ThreadedRuntime runtime = new ThreadedRuntime(Duration.ofSeconds(10));
          Channel channel = runtime.connectedChannel();
          ListenerGuard guard = new ListenerGuard(channel);
          FakeWebSocket socket = runtime.socket();
          Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
          Queue<Subscription> subscriptions = new ConcurrentLinkedQueue<>();
          List<Thread> threads = new ArrayList<>();

          for (int worker = 0; worker < 8; worker++) {
            int workerIndex = worker;
            threads.add(
                new Thread(
                    () -> {
                      Segment chat = channel.segment("chat-" + workerIndex % 3);

                      for (int index = 0; index < 20; index++) {
                        Registration registration =
                            chat.onMessage((payload, metadata) -> guard.run(channel::state));
                        subscriptions.add(chat.subscribe());

                        try {
                          chat.publish(bytes("x"), "m-" + workerIndex + "-" + index)
                              .get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
                        } catch (Exception failure) {
                          failures.add(failure);
                        }

                        registration.close();
                      }
                    }));
          }

          threads.add(
              new Thread(
                  () -> {
                    for (int index = 0; index < 50; index++) {
                      socket.receive(messageFrame("chat-" + index % 3, "id-" + index, "x"));
                    }
                  }));

          threads.forEach(Thread::start);

          for (Thread thread : threads) {
            thread.join(BOUND.toMillis());
          }

          assertTrue(failures.isEmpty(), () -> "publishes failed: " + failures);
          subscriptions.forEach(Subscription::cancel);
          assertEquals(ChannelState.CONNECTED, channel.state());
          channel.closeAsync().get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
          guard.assertSerial();
        });
  } // end method concurrentUseIsSafe

  @Test
  void repeatedConnectAndCloseUnderLoad() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () -> {
          ThreadedRuntime runtime = new ThreadedRuntime(Duration.ofSeconds(10));

          for (int round = 0; round < 50; round++) {
            Channel channel = runtime.connectedChannel();
            FakeWebSocket socket = runtime.socket();
            channel.segment("chat").subscribe();
            channel.segment("chat").onMessage((payload, metadata) -> {});
            Thread feeder =
                new Thread(
                    () -> {
                      for (int index = 0; index < 5; index++) {
                        socket.receive(messageFrame("chat", "id-" + index, "x"));
                      }
                    });

            feeder.start();

            channel.close();
            feeder.join(BOUND.toMillis());
            assertEquals(ChannelState.CLOSED, channel.state());
          }
        });
  } // end method repeatedConnectAndCloseUnderLoad

  // A listener that calls the blocking close while close is delivering events returns: the close it
  // waits for completes on another worker.
  @Test
  void blockingCloseFromAListenerDuringCloseReturns() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () -> {
          ThreadedRuntime runtime = new ThreadedRuntime(Duration.ofSeconds(10));
          Channel channel = runtime.connectedChannel();
          CompletableFuture<Void> innerReturned = new CompletableFuture<>();
          channel
              .events()
              .onStateChange(
                  state -> {
                    if (state == ChannelState.CLOSING) {
                      channel.close();
                      innerReturned.complete(null);
                    }
                  });

          channel.close();

          innerReturned.get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
          assertEquals(ChannelState.CLOSED, channel.state());
        });
  } // end method blockingCloseFromAListenerDuringCloseReturns

  // The reply is routed by the thread running listeners, so a query awaited inside one cannot
  // complete: it times out, deterministically.
  @Test
  void presenceQueryAwaitedInsideAListenerTimesOut() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () -> {
          ThreadedRuntime runtime = new ThreadedRuntime(Duration.ofMillis(300));
          Channel channel = runtime.connectedChannel();
          FakeWebSocket socket = runtime.socket();
          AtomicReference<Throwable> outcome = new AtomicReference<>();
          channel
              .events()
              .onNotice(
                  notice -> {
                    try {
                      channel.segment("chat").presenceList(1, 25).get();
                      outcome.set(new AssertionError("the query completed"));
                    } catch (ExecutionException failed) {
                      outcome.set(failed.getCause());
                    } catch (InterruptedException interrupted) {
                      Thread.currentThread().interrupt();
                      outcome.set(interrupted);
                    }
                  });

          socket.receive("@SERVER_MSG\n:1\n$0\n\n");
          socket.receive(presenceResponseFrame("1"));
          awaitCondition("the query's outcome", () -> outcome.get() != null);

          assertCode(outcome.get(), ErrorCode.TIMEOUT);
          assertEquals("Presence query timed out after 300 ms.", outcome.get().getMessage());
          channel.closeAsync().get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        });
  } // end method presenceQueryAwaitedInsideAListenerTimesOut

  @Test
  void threadedListenerFailuresStayContained() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () -> {
          ThreadedRuntime runtime = new ThreadedRuntime(Duration.ofSeconds(10));
          Channel channel = runtime.connectedChannel();
          Queue<RuntimeException> errors = new ConcurrentLinkedQueue<>();
          AtomicInteger after = new AtomicInteger();
          channel.events().onError(errors::add);
          channel
              .defaultSegment()
              .onMessage(
                  (payload, metadata) -> {
                    throw new IllegalStateException("listener-secret");
                  });

          channel.defaultSegment().onMessage((payload, metadata) -> after.incrementAndGet());

          for (int index = 0; index < 20; index++) {
            runtime.socket().receive(messageFrame("default", "id-" + index, "x"));
          }

          awaitCondition("every delivery", () -> after.get() == 20 && errors.size() == 20);
          assertInstanceOf(CelerisException.class, errors.peek());
          assertEquals(ChannelState.CONNECTED, channel.state());
          channel.closeAsync().get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        });
  } // end method threadedListenerFailuresStayContained

  /**
   * Publishes with random cancels, subscriptions, presence queries, listeners calling back, server
   * traffic, rate limits and dropped sockets, all at once. Every future settles within the bound,
   * listeners never overlap or run under the lock, and the channel still closes.
   */
  @Test
  void chaosNeverHangsAndKeepsListenersSerial() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(25),
        () -> {
          ThreadedRuntime runtime = new ThreadedRuntime(Duration.ofMillis(300));
          Channel channel = runtime.connectedChannel();
          ListenerGuard guard = new ListenerGuard(channel);
          Queue<CompletableFuture<?>> futures = new ConcurrentLinkedQueue<>();
          AtomicInteger delivered = new AtomicInteger();
          List<String> segments = List.of("chat-0", "chat-1", "chat-2");

          channel.events().onStateChange(state -> guard.run(channel::state));
          channel.events().onError(error -> guard.run(() -> {}));
          channel.events().onNotice(notice -> guard.run(() -> {}));
          channel.events().onRecovery(recovery -> guard.run(() -> {}));

          for (String segmentId : segments) {
            Segment segment = channel.segment(segmentId);
            segment.onPresence(event -> guard.run(() -> {}));
            segment.onMessage(
                (payload, metadata) ->
                    guard.run(
                        () -> {
                          delivered.incrementAndGet();

                          // Listeners call back into the channel, never blocking on it.
                          if (delivered.get() % 5 == 0) {
                            futures.add(
                                segment.publish(bytes("echo"), "echo-" + metadata.messageId()));
                          }

                          if (delivered.get() % 7 == 0) {
                            segment.subscribe().cancel();
                            channel.events().onNotice(notice -> {}).close();
                          }
                        }));
          }

          List<Thread> workers = new ArrayList<>();

          for (int worker = 0; worker < 4; worker++) {
            long seed = 0xC0FFEEL + worker;
            int workerIndex = worker;
            workers.add(
                new Thread(
                    () ->
                        runWorker(
                            channel, segments, futures, new SplittableRandom(seed), workerIndex)));
          }

          Thread server = new Thread(() -> runServer(runtime, segments, workers));
          workers.forEach(Thread::start);
          server.start();

          for (Thread worker : workers) {
            worker.join(BOUND.toMillis());
          }

          server.join(BOUND.toMillis());
          assertTrue(workers.stream().noneMatch(Thread::isAlive), "a worker hung");
          assertFalse(server.isAlive(), "the server hung");

          awaitAll(futures);
          channel.closeAsync().get(BOUND.toMillis(), TimeUnit.MILLISECONDS);

          assertEquals(ChannelState.CLOSED, channel.state());
          guard.assertSerial();
          assertTrue(delivered.get() > 0, "nothing was delivered");
          assertTrue(runtime.sockets.size() > 1, "no socket was dropped");
          assertTrue(
              futures.stream().anyMatch(future -> !future.isCompletedExceptionally()),
              "nothing succeeded");
        });
  } // end method chaosNeverHangsAndKeepsListenersSerial

  private static void runWorker(
      Channel channel,
      List<String> segments,
      Queue<CompletableFuture<?>> futures,
      SplittableRandom random,
      int workerIndex) {
    List<Subscription> held = new ArrayList<>();
    long deadline = System.nanoTime() + CHAOS_DURATION.toNanos();

    for (int index = 0; System.nanoTime() < deadline; index++) {
      Segment segment = channel.segment(segments.get(random.nextInt(segments.size())));
      int operation = random.nextInt(10);

      if (operation < 5) {
        CompletableFuture<Void> published =
            segment.publish(bytes("x"), "m-" + workerIndex + "-" + index);
        futures.add(published);

        if (random.nextInt(4) == 0) {
          published.cancel(true);
        }
      } else if (operation < 7) {
        held.add(segment.subscribe());

        if (held.size() > 3) {
          held.remove(random.nextInt(held.size())).cancel();
        }
      } else if (operation < 9) {
        CompletableFuture<PresencePage> query = segment.presenceList(1, 25);
        futures.add(query);

        if (random.nextInt(3) == 0) {
          query.cancel(true);
        }
      } else {
        segment.onMessage((payload, metadata) -> {}).close();
      }

      if (index % 2 == 0) {
        try {
          Thread.sleep(1);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();

          break;
        }
      }
    }

    held.forEach(Subscription::cancel);
  } // end method runWorker

  /** Answers presence queries and sends traffic until the workers finish, dropping sockets. */
  private static void runServer(
      ThreadedRuntime runtime, List<String> segments, List<Thread> workers) {
    SplittableRandom random = new SplittableRandom(42);
    Map<FakeWebSocket, Integer> answered = new HashMap<>();
    int iteration = 0;

    while (workers.stream().anyMatch(Thread::isAlive)) {
      FakeWebSocket socket = runtime.socket();
      List<String> commands = socket.commands();

      for (int index = answered.getOrDefault(socket, 0); index < commands.size(); index++) {
        String[] fields = commands.get(index).split("\n");

        if (fields[0].equals("@PRES_LIST") && random.nextInt(4) != 0) {
          socket.receive(
              presenceResponseFrame(
                  fields[2], fields[6], 1, 25, 1, 1, 1, List.of(TestRuntime.PRESENCE_CONNECTION)));
        }
      }

      answered.put(socket, commands.size());
      String segmentId = segments.get(random.nextInt(segments.size()));
      socket.receive(messageFrame(segmentId, "id-" + iteration, "x"));

      if (iteration % 10 == 0) {
        socket.receive(presenceNotifyFrame(segmentId, iteration % 20 == 0, iteration));
        socket.receive("@SERVER_MSG\n:1\n$5\nhello\n");
      }

      if (iteration % 400 == 200) {
        socket.receive(TestRuntime.rateLimitFrame());
      }

      if (iteration % 60 == 59) {
        socket.drop();
      }

      iteration++;

      try {
        Thread.sleep(1);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();

        return;
      }
    }
  } // end method runServer

  // The client's threads are daemons, so they never keep an application's JVM alive.
  @Test
  void listenersRunOnTheClientsDaemonThreads() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () -> {
          ThreadedRuntime runtime = new ThreadedRuntime(Duration.ofSeconds(10));
          Channel channel = runtime.connectedChannel();
          Queue<Thread> threads = new ConcurrentLinkedQueue<>();
          channel.events().onStateChange(state -> threads.add(Thread.currentThread()));

          channel.closeAsync().get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
          awaitCondition("the closing and closed states", () -> threads.size() == 2);

          for (Thread thread : threads) {
            assertTrue(thread.isDaemon(), thread.getName());
          }
        });
  } // end method listenersRunOnTheClientsDaemonThreads

  // The client's own clock measures an outage: the reconnect asks to replay the time since the drop
  // on top of the five-second overlap.
  @Test
  void realClockMeasuresTheOutageAReconnectReplays() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () -> {
          ThreadedRuntime runtime = new ThreadedRuntime(Duration.ofSeconds(10));
          Channel channel = runtime.connectedChannel();

          // Full jitter at its maximum, so the first retry waits its whole 500 ms.
          runtime.random = 1;
          runtime.socket().drop();
          awaitCondition("the reconnect", () -> runtime.sockets.size() == 2);

          CredentialRequest reconnect =
              runtime.credentialRequests.stream()
                  .filter(CredentialRequest::reconnect)
                  .findFirst()
                  .orElseThrow();
          Duration lookback = reconnect.replayLookback().orElseThrow();
          assertTrue(
              lookback.compareTo(Duration.ofMillis(5500)) >= 0, () -> "lookback " + lookback);
          channel.closeAsync().get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        });
  } // end method realClockMeasuresTheOutageAReconnectReplays
} // end class ConcurrencyTest

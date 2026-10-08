package com.useceleris.client;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/**
 * One WebSocket connection. Every segment of the channel is multiplexed over it; another channel,
 * even for the same reference, is another connection (SEG-01). Obtain one with {@link
 * CelerisClient#channel(String)}.
 *
 * <p>A channel is safe for concurrent use. Listeners run one at a time on the SDK's threads, in the
 * order events happened, never under an SDK lock, and may call back into the channel; a slow
 * listener delays the next event rather than growing a queue (DEV-01). Futures the channel returns
 * complete on the SDK's worker threads too, each as its own task, independently of event delivery
 * and of one another.
 *
 * <p>Close it when done.
 */
public final class Channel implements AutoCloseable {
  /** Guards every field of the channel, its segments, its connection and its command queue. */
  final ReentrantLock lock = new ReentrantLock();

  final Timers timers;
  final Executor executor;
  final DoubleSupplier random;
  final int publishQueueSize;
  final CommandQueue queue;

  private final CelerisClient client;
  private final String reference;

  @GuardedBy("lock")
  private ChannelState state = ChannelState.IDLE;

  @GuardedBy("lock")
  private long generation;

  @GuardedBy("lock")
  @Nullable
  Connection connection;

  @GuardedBy("lock")
  private int retriesUsed;

  @GuardedBy("lock")
  private Timers.@Nullable Cancellable retryTimer;

  @GuardedBy("lock")
  private long connectedAt;

  @GuardedBy("lock")
  private boolean disconnected;

  // When the connection dropped; meaningful only while disconnected is true.
  @GuardedBy("lock")
  private long disconnectedAt;

  @GuardedBy("lock")
  private Instant disconnectedAtWallClock = Instant.EPOCH;

  @GuardedBy("lock")
  private @Nullable Attempt attempt;

  @GuardedBy("lock")
  private boolean closeStarted;

  private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

  @GuardedBy("lock")
  private final DeduplicationWindow deduplication;

  @GuardedBy("lock")
  private final LinkedHashMap<String, Integer> messageInterests = new LinkedHashMap<>();

  @GuardedBy("lock")
  private final LinkedHashMap<String, Integer> presenceInterests = new LinkedHashMap<>();

  // Issues presence query request ids. Kept per channel rather than per socket, so an id is never
  // reused across reconnects (QUERY-01).
  @GuardedBy("lock")
  private long presenceRequestCount;

  @GuardedBy("lock")
  private @Nullable PresenceQuery pendingPresence;

  @GuardedBy("lock")
  private final Map<String, ListenerSet<MessageListener>> messageListeners = new HashMap<>();

  @GuardedBy("lock")
  private final Map<String, ListenerSet<Consumer<PresenceEvent>>> presenceListeners =
      new HashMap<>();

  final ListenerSet<Consumer<ChannelState>> stateListeners = new ListenerSet<>();
  final ListenerSet<Consumer<RecoveryEvent>> recoveryListeners = new ListenerSet<>();
  final ListenerSet<Consumer<ServerNotice>> noticeListeners = new ListenerSet<>();
  final ListenerSet<Consumer<RuntimeException>> errorListeners = new ListenerSet<>();
  final ListenerSet<MessageListener> channelMessageListeners = new ListenerSet<>();

  /**
   * An event, queued under the lock at the moment of the change that causes it. Its turn comes on
   * the one thread delivering the queue, which prepares it under the lock, taking the listener
   * snapshot then, and runs the returned delivery without the lock.
   */
  private interface Event {
    Runnable prepare();
  } // end interface Event

  @GuardedBy("lock")
  private final ArrayDeque<Event> events = new ArrayDeque<>();

  @GuardedBy("lock")
  private boolean draining;

  Channel(CelerisClient client, String reference) {
    this.client = client;
    this.reference = reference;
    this.timers = client.timers;
    this.executor = client.executor;
    this.random = client.random;
    this.publishQueueSize = client.publishQueueSize;
    this.deduplication = new DeduplicationWindow(client.deduplicationWindowSize);
    this.queue = new CommandQueue(this);
  } // end constructor Channel

  /**
   * Returns the current state. It may change as soon as it is read.
   *
   * @return the channel's state
   */
  public ChannelState state() {
    lock.lock();

    try {
      return state;
    } finally {
      lock.unlock();
    }
  } // end method state

  /**
   * Returns the handler for channel-wide listeners.
   *
   * @return the event handler
   */
  public ChannelEventHandler events() {
    return new ChannelEventHandler(this);
  } // end method events

  /**
   * Returns a handle for the segment with the given id. It does no network work, and any number of
   * handles for one segment share its subscriptions and listeners.
   *
   * @param segmentId the segment's id: non-empty, without CR, LF or unpaired surrogates
   * @return the segment handle
   * @throws CelerisException with {@link ErrorCode#CONFIGURATION} for an invalid id
   */
  public Segment segment(String segmentId) {
    Objects.requireNonNull(segmentId, "segmentId");
    String rule = Identifiers.identifierRule(segmentId);

    if (rule != null) {
      throw Identifiers.configurationError("segment ID", List.of(Identifiers.failure(null, rule)));
    }

    return new Segment(this, segmentId);
  } // end method segment

  /**
   * Returns a handle for the segment every connection joins automatically (SEG-01).
   *
   * @return the default segment's handle
   */
  public Segment defaultSegment() {
    return new Segment(this, Constants.DEFAULT_SEGMENT_ID);
  } // end method defaultSegment

  /**
   * Opens the connection. The future completes once the WebSocket is established and held
   * subscriptions are queued to be restored ahead of any publish, or fails without retrying: only a
   * connection that was up recovers automatically.
   *
   * <p>It fails with {@link ErrorCode#OPERATION_IN_PROGRESS} while connecting, connected or
   * reconnecting, and with {@link ErrorCode#NOT_CONNECTED} once the channel is closing or closed. A
   * failed attempt leaves the channel {@link ChannelState#FAILED}, from which {@code connect} may
   * be called again. Cancelling the future abandons the attempt and leaves the channel failed.
   *
   * @return a future completing once connected
   */
  public CompletableFuture<Void> connect() {
    OperationFuture<Void> future = new OperationFuture<>(executor);
    Attempt started = null;

    lock.lock();

    try {
      switch (state) {
        case CONNECTING, CONNECTED, RECONNECTING ->
            future.settle(
                null,
                new CelerisException(
                    ErrorCode.OPERATION_IN_PROGRESS,
                    "connect() was already called; the channel is " + state + "."));
        case CLOSING, CLOSED -> future.settle(null, channelClosed());
        default -> {
          generation++;
          retriesUsed = 0;
          disconnected = false;
          deduplication.clear();
          queueStateChange(ChannelState.CONNECTING);
          started = new Attempt(generation, null, 0, future);
          Attempt connecting = started;
          future.attach(() -> cancelAttempt(connecting));
        }
      }
    } finally {
      lock.unlock();
    }

    afterUnlock();

    if (started != null) {
      Attempt connecting = started;
      executor.execute(() -> runAttempt(connecting));
    }

    return future;
  } // end method connect

  /**
   * Closes the connection and every segment handle with it, blocking the calling thread until
   * {@link #closeAsync()} completes. It is terminal and idempotent, and returns within about five
   * seconds even when the server does not answer the closing handshake. A listener may call it.
   */
  @Override
  public void close() {
    closeAsync().join();
  } // end method close

  /**
   * Closes the connection and every segment handle with it. It is terminal and idempotent, and the
   * future completes within about five seconds even when the server does not answer the closing
   * handshake. Publishes and presence queries still waiting fail with {@link ErrorCode#CANCELLED};
   * publishes already handed to the socket are flushed first. Listeners may still receive events
   * queued before it, including the closing and closed state changes.
   *
   * @return a future completing once closed; a fresh copy on every call
   */
  public CompletableFuture<Void> closeAsync() {
    Connection detached;
    CelerisException cancelled = closedBeforeSent();

    lock.lock();

    try {
      if (closeStarted) {
        return closeFuture.copy();
      }

      closeStarted = true;
      rejectPendingPresence(
          new CelerisException(
              ErrorCode.CANCELLED, "Channel closed while the presence query was pending."));
      generation++;
      cancelRetry();
      Attempt current = attempt;

      if (current != null) {
        failAttempt(current, attemptCancelled(), true);
      }

      queue.reset(cancelled);
      detached = connection;
      connection = null;

      if (detached != null) {
        detached.closing = true;
      }

      queueStateChange(ChannelState.CLOSING);
    } finally {
      lock.unlock();
    }

    afterUnlock();

    if (detached == null) {
      finishClose();
    } else {
      Connection closing = detached;
      closing.drainInbound();
      closing.pump();
      Timers.Cancellable budget =
          timers.schedule(Constants.CLOSE_BUDGET, () -> withLock(() -> closing.abandon(cancelled)));
      var unused =
          closing.closed.whenCompleteAsync(
              (ignored, failure) -> {
                budget.cancel();
                finishClose();
              },
              executor);
    }

    return closeFuture.copy();
  } // end method closeAsync

  private void finishClose() {
    lock.lock();

    try {
      if (state != ChannelState.CLOSED) {
        queueStateChange(ChannelState.CLOSED);
      }
    } finally {
      lock.unlock();
    }

    closeFuture.complete(null);
    dispatch();
  } // end method finishClose

  // ---- Connection attempts ----

  /** One connection attempt: fresh credentials, the handshake, then installing the socket. */
  private final class Attempt {
    final long generation;
    final @Nullable CredentialRequest reconnect;
    final int retryIndex;
    final @Nullable OperationFuture<Void> connectFuture;
    final Connection connection = new Connection(Channel.this);
    final long startedAt = timers.monotonicNanos();

    @GuardedBy("lock")
    boolean finished;

    Timers.@Nullable Cancellable deadline;
    @Nullable CompletionStage<Credentials> provided;
    @Nullable CompletableFuture<WebSocket> dialing;

    Attempt(
        long generation,
        @Nullable CredentialRequest reconnect,
        int retryIndex,
        @Nullable OperationFuture<Void> connectFuture) {
      this.generation = generation;
      this.reconnect = reconnect;
      this.retryIndex = retryIndex;
      this.connectFuture = connectFuture;
    } // end constructor Attempt
  } // end class Attempt

  private void runAttempt(Attempt current) {
    lock.lock();

    try {
      if (current.generation != generation) {
        failAttempt(current, attemptCancelled(), true);

        return;
      }

      attempt = current;
      current.deadline =
          timers.schedule(
              attemptTimeout(current),
              () -> withLock(() -> failAttempt(current, attemptTimedOut(current), true)));
    } finally {
      lock.unlock();
    }

    afterUnlock();

    CredentialRequest request =
        current.reconnect != null
            ? current.reconnect
            : new CredentialRequest(reference, false, Optional.empty(), Optional.empty());
    CompletionStage<Credentials> stage;

    try {
      stage = Objects.requireNonNull(client.credentialProvider.provide(request));
    } catch (VirtualMachineError failure) {
      throw failure;
    } catch (Throwable failure) {
      withLock(() -> failAttempt(current, providerFailed(), true));

      return;
    }

    boolean abandoned;

    lock.lock();

    try {
      abandoned = current.finished;
      current.provided = stage;
    } finally {
      lock.unlock();
    }

    if (abandoned) {
      cancelStage(stage);

      return;
    }

    var unused =
        stage.whenCompleteAsync(
            (credentials, failure) -> credentialsArrived(current, credentials, failure), executor);
  } // end method runAttempt

  private void credentialsArrived(
      Attempt current, @Nullable Credentials credentials, @Nullable Throwable failure) {
    if (failure != null) {
      withLock(() -> failAttempt(current, providerFailed(), true));

      return;
    }

    CelerisException invalid = validateCredentials(credentials);

    if (invalid != null) {
      withLock(() -> failAttempt(current, invalid, true));

      return;
    }

    URI url =
        ConnectionUrl.credentialUrl(client.baseUrl, reference, Objects.requireNonNull(credentials));
    Duration elapsed = Duration.ofNanos(timers.monotonicNanos() - current.startedAt);
    Duration remaining = attemptTimeout(current).minus(elapsed);

    if (remaining.toMillis() < 1) {
      remaining = Duration.ofMillis(1);
    }

    lock.lock();

    try {
      if (current.finished) {
        return;
      }
    } finally {
      lock.unlock();
    }

    CompletableFuture<WebSocket> dialing;

    try {
      dialing = client.dialer.dial(url, remaining, current.connection);
    } catch (RuntimeException dialFailure) {
      dialing = CompletableFuture.failedFuture(dialFailure);
    }

    boolean abandoned;

    lock.lock();

    try {
      abandoned = current.finished;
      current.dialing = dialing;
    } finally {
      lock.unlock();
    }

    if (abandoned) {
      abortLateSocket(dialing);

      return;
    }

    var unused =
        dialing.whenCompleteAsync(
            (socket, dialFailure) -> dialed(current, socket, dialFailure), executor);
  } // end method credentialsArrived

  private void dialed(Attempt current, @Nullable WebSocket socket, @Nullable Throwable failure) {
    boolean installed = false;

    lock.lock();

    try {
      if (current.finished) {
        if (socket != null) {
          executor.execute(socket::abort);
        }

        return;
      }

      if (failure != null || socket == null) {
        failAttempt(current, handshakeFailed(current, failure), true);

        return;
      }

      if (current.generation != generation) {
        executor.execute(socket::abort);
        failAttempt(current, attemptCancelled(), true);

        return;
      }

      // Its socket may have failed before it could be installed.
      if (current.connection.broken) {
        executor.execute(socket::abort);
        failAttempt(current, handshakeFailed(current, null), true);

        return;
      }

      current.finished = true;
      Timers.cancel(current.deadline);
      attempt = null;
      Connection installing = current.connection;
      connection = installing;
      installing.install(socket);
      connectedAt = timers.monotonicNanos();
      disconnected = false;

      // Restoration goes through the queue, so it waits for writer room and always reaches the
      // server before any publish: messages first, then presence, each in registration order.
      queue.restore(messageInterests.keySet(), presenceInterests.keySet());

      queueStateChange(ChannelState.CONNECTED);

      if (current.reconnect != null) {
        RecoveryEvent recovery = new RecoveryEvent(current.retryIndex, true, true);
        queueEvent(recoveryListeners, (listener, last) -> listener.accept(recovery));
      }

      if (current.connectFuture != null) {
        current.connectFuture.settle(null, null);
      }

      installed = true;
    } finally {
      // Every path out delivers what it queued: a refused handshake queues the failed state.
      lock.unlock();
      afterUnlock();
    }

    if (installed) {
      current.connection.resume();
    }
  } // end method dialed

  /**
   * Ends an attempt that did not install its socket. The caller holds the lock. For the attempt
   * connect started, the channel becomes failed; for a reconnect, a transport failure or timeout
   * retries until the budget is used up, and anything else fails the channel.
   */
  private void failAttempt(Attempt current, CelerisException failure, boolean settleConnect) {
    if (current.finished) {
      return;
    }

    current.finished = true;
    Timers.cancel(current.deadline);

    if (attempt == current) {
      attempt = null;
    }

    CompletionStage<Credentials> provided = current.provided;
    CompletableFuture<WebSocket> dialing = current.dialing;
    executor.execute(
        () -> {
          if (provided != null) {
            cancelStage(provided);
          }

          if (dialing != null) {
            abortLateSocket(dialing);
          }
        });

    if (current.connectFuture != null) {
      // A failed first connect leaves nothing queued for a later one; a failed reconnect attempt
      // keeps the queue for the next attempt (QUEUE-01).
      queue.reset(connectionLost());

      if (current.generation == generation) {
        generation++;
        queueStateChange(ChannelState.FAILED);
      }

      if (settleConnect) {
        current.connectFuture.settle(null, failure);
      }

      return;
    }

    if (current.generation != generation) {
      return;
    }

    if (failure.code() == ErrorCode.TRANSPORT || failure.code() == ErrorCode.TIMEOUT) {
      retriesUsed++;

      if (retriesUsed >= client.maximumReconnectAttempts) {
        failTerminal(failure);
      } else {
        scheduleRetry();
      }
    } else {
      failTerminal(failure);
    }
  } // end method failAttempt

  private boolean cancelAttempt(Attempt current) {
    lock.lock();

    try {
      if (current.finished) {
        return false;
      }

      failAttempt(current, attemptCancelled(), false);

      return true;
    } finally {
      lock.unlock();
      afterUnlock();
    }
  } // end method cancelAttempt

  private static void cancelStage(CompletionStage<Credentials> stage) {
    try {
      stage.toCompletableFuture().cancel(true);
    } catch (UnsupportedOperationException unsupported) {
      // A stage that cannot be cancelled is discarded when it completes.
    }
  } // end method cancelStage

  /** Aborts a socket the handshake produces after its attempt was abandoned. */
  private void abortLateSocket(CompletableFuture<WebSocket> dialing) {
    dialing.cancel(true);
    var unused = dialing.thenAcceptAsync(WebSocket::abort, executor);
  } // end method abortLateSocket

  private static @Nullable CelerisException validateCredentials(@Nullable Credentials credentials) {
    if (credentials == null) {
      return Identifiers.configurationError(
          "credentials", List.of(Identifiers.failure(null, "Must not be null")));
    }

    List<String> failures = new ArrayList<>();
    addCredentialFailure(failures, "payload", credentials.payload());
    addCredentialFailure(failures, "signature", credentials.signature());

    return failures.isEmpty() ? null : Identifiers.configurationError("credentials", failures);
  } // end method validateCredentials

  private static void addCredentialFailure(
      List<String> failures, String field, @Nullable String value) {
    String rule = value == null ? "Required" : Identifiers.credentialValueRule(value);

    if (rule != null) {
      failures.add(Identifiers.failure(field, rule));
    }
  } // end method addCredentialFailure

  /**
   * Maps a handshake failure to fixed text: the JDK's error, which may quote the credential URL, is
   * never passed on. A refused handshake is a transport failure, whatever its HTTP status (DEV-02).
   */
  private CelerisException handshakeFailed(Attempt current, @Nullable Throwable failure) {
    Throwable cause =
        failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause()
            : failure;

    if (cause instanceof HttpTimeoutException) {
      return attemptTimedOut(current);
    }

    return new CelerisException(
        ErrorCode.TRANSPORT,
        "WebSocket handshake failed: the server refused the connection or could not be reached."
            + " Check the base URL, the credentials and the channel reference.");
  } // end method handshakeFailed

  private static CelerisException providerFailed() {
    return new CelerisException(
        ErrorCode.TRANSPORT,
        "Credential acquisition failed: the credential provider threw or rejected.");
  } // end method providerFailed

  private static CelerisException attemptCancelled() {
    return new CelerisException(
        ErrorCode.CANCELLED,
        "Connection attempt cancelled: the channel was closed or a newer attempt started.");
  } // end method attemptCancelled

  private static CelerisException channelClosed() {
    return new CelerisException(
        ErrorCode.NOT_CONNECTED, "Channel is closed; create a new one with client.channel().");
  } // end method channelClosed

  private static CelerisException presenceConnectionLost() {
    return new CelerisException(
        ErrorCode.TRANSPORT,
        "Connection lost during the presence query; query again once the channel reconnects.");
  } // end method presenceConnectionLost

  /** The deadline of one attempt: reconnects may run on their own, usually shorter, timeout. */
  private Duration attemptTimeout(Attempt current) {
    return current.reconnect != null ? client.reconnectTimeout : client.connectTimeout;
  } // end method attemptTimeout

  private CelerisException attemptTimedOut(Attempt current) {
    return new CelerisException(
        ErrorCode.TIMEOUT,
        "Connection attempt timed out after " + attemptTimeout(current).toMillis() + " ms.");
  } // end method attemptTimedOut

  static CelerisException connectionLost() {
    return new CelerisException(
        ErrorCode.NOT_CONNECTED,
        "Connection lost before the publish was sent; publish again once the channel reconnects.");
  } // end method connectionLost

  static CelerisException closedBeforeSent() {
    return new CelerisException(ErrorCode.CANCELLED, "Channel closed before the publish was sent.");
  } // end method closedBeforeSent

  // ---- Recovery ----

  /** Starts recovery when the channel's own socket closed or failed. Called without the lock. */
  void socketFailed(Connection failed) {
    withLock(() -> connectionFailed(failed, timers.monotonicNanos()));
  } // end method socketFailed

  /**
   * Starts recovery from an outage that began at outageStart, a monotonic reading, when the failed
   * connection is the channel's own; any other is only stopped. The heartbeat passes when the
   * server was last heard from, not when the silence was noticed, so the replay lookback covers
   * what was published meanwhile. The caller holds the lock.
   */
  void connectionFailed(Connection failed, long outageStart) {
    if (connection != failed) {
      failed.abandon(failed.closing ? closedBeforeSent() : connectionLost());

      return;
    }

    rejectPendingPresence(presenceConnectionLost());
    queue.keepAcrossReconnect(failed.takeUnstarted());

    long now = timers.monotonicNanos();

    if (now - connectedAt >= Constants.RETRY_BUDGET_RESET.toNanos()) {
      retriesUsed = 0;
    }

    disconnected = true;
    disconnectedAt = outageStart;
    disconnectedAtWallClock = timers.now().minusNanos(now - outageStart);
    Connection lost = Objects.requireNonNull(connection);
    lost.abandon(connectionLost());
    connection = null;
    queueStateChange(ChannelState.RECONNECTING);
    scheduleRetry();
  } // end method connectionFailed

  @GuardedBy("lock")
  private void scheduleRetry() {
    long retryGeneration = generation;
    Duration delay = Reconnect.retryDelay(retriesUsed, random);
    Timers.Cancellable[] timer = new Timers.Cancellable[1];

    // The task reads the array under the lock, which this thread holds until both are filled: a
    // retry due at once may run before schedule returns.
    timer[0] = timers.schedule(delay, () -> runReconnectAttempt(retryGeneration, timer));
    retryTimer = timer[0];
  } // end method scheduleRetry

  @SuppressWarnings("ReferenceEquality") // a timer is matched by identity, as its own token
  private void runReconnectAttempt(long retryGeneration, Timers.@Nullable Cancellable[] timer) {
    Attempt started;

    lock.lock();

    try {
      if (retryTimer != timer[0] || retryGeneration != generation || !disconnected) {
        return;
      }

      retryTimer = null;
      CredentialRequest request =
          new CredentialRequest(
              reference,
              true,
              Optional.of(disconnectedAtWallClock),
              Optional.of(
                  Reconnect.replayLookback(
                      Duration.ofNanos(timers.monotonicNanos() - disconnectedAt))));
      started = new Attempt(retryGeneration, request, retriesUsed, null);
    } finally {
      lock.unlock();
    }

    runAttempt(started);
  } // end method runReconnectAttempt

  @GuardedBy("lock")
  private void failTerminal(CelerisException failure) {
    rejectPendingPresence(presenceConnectionLost());
    queue.reset(failure);
    generation++;
    cancelRetry();
    queueError(failure);
    queueStateChange(ChannelState.FAILED);
  } // end method failTerminal

  @GuardedBy("lock")
  private void cancelRetry() {
    Timers.cancel(retryTimer);
    retryTimer = null;
  } // end method cancelRetry

  // ---- Receiving ----

  /**
   * Queues routing of one decoded transport message. Called without the lock, from the JDK's
   * listener; a message for a socket that is no longer the channel's is dropped.
   */
  void receive(Connection receiving, ServerMessage message) {
    lock.lock();

    try {
      if (connection != receiving) {
        return;
      }

      List<ServerMessage> entries = new ArrayList<>();
      flatten(message, entries);
      events.add(routeEvent(receiving, entries, 0));
    } finally {
      lock.unlock();
    }

    dispatch();
  } // end method receive

  private static void flatten(ServerMessage message, List<ServerMessage> entries) {
    if (message instanceof ServerMessage.Batch batch) {
      for (ServerMessage entry : batch.messages()) {
        flatten(entry, entries);
      }
    } else {
      entries.add(message);
    }
  } // end method flatten

  /**
   * Routes one entry of a message when its turn comes, then queues a marker behind the deliveries
   * it caused. The marker queues the next entry behind anything queued meanwhile, so no entry is
   * routed while a listener runs, and the next transport message is requested only once every
   * entry's listeners have run.
   */
  private Event routeEvent(Connection receiving, List<ServerMessage> entries, int index) {
    return () -> {
      if (connection != receiving) {
        return Channel::doNothing;
      }

      if (index == entries.size()) {
        return receiving::resume;
      }

      routeEntry(entries.get(index));
      events.add(
          () -> {
            events.add(routeEvent(receiving, entries, index + 1));

            return Channel::doNothing;
          });

      return Channel::doNothing;
    };
  } // end method routeEvent

  @GuardedBy("lock")
  private void routeEntry(ServerMessage message) {
    if (message instanceof ServerMessage.Delivery delivery) {
      deliver(delivery);
    } else if (message instanceof ServerMessage.Notice notice) {
      queueEvent(
          noticeListeners,
          (listener, last) ->
              listener.accept(
                  new ServerNotice(
                      notice.timestamp(), last ? notice.payload() : notice.payload().clone())));
    } else if (message instanceof ServerMessage.PresenceNotify presence) {
      deliverPresence(presence.event());
    } else if (message instanceof ServerMessage.PresenceListResponse response) {
      receivePresenceResponse(response);
    } else if (message instanceof ServerMessage.ErrorFrame error) {
      receiveServerError(error);
    } else if (message instanceof ServerMessage.DecodeFailure failure) {
      queueError(failure.error());
    }
  } // end method routeEntry

  @GuardedBy("lock")
  private void deliver(ServerMessage.Delivery delivery) {
    String messageId = delivery.messageId();

    // Every delivery carries an id, the publisher's or one the server assigns (REV-01). One without
    // leaves the message undeliverable, since it cannot be deduplicated, so it is dropped and
    // reported without taking the connection down (DECODE-01).
    if (messageId == null) {
      queueError(
          CelerisException.protocol("Server message is missing its identifier.", "messageId", 0));

      return;
    }

    // Ids are recorded before fanout, even with no listeners.
    if (!deduplication.recordIfNew(messageId)) {
      return;
    }

    MessageMetadata metadata =
        new MessageMetadata(
            delivery.tokenReference(), delivery.segmentId(), messageId, delivery.timestamp());
    byte[] payload = delivery.payload();
    ListenerSet<MessageListener> segmentListeners = messageListeners.get(delivery.segmentId());

    // The segment's listeners first, then the channel's (MSG-02). Every segment listener gets a
    // copy even when the channel has no listeners now: each event takes its listener snapshot when
    // its turn comes, so a segment listener may register a channel listener mid-delivery, and only
    // the channel event, queued last, may hand over the original.
    if (segmentListeners != null) {
      queueEvent(
          segmentListeners, (listener, last) -> listener.onMessage(payload.clone(), metadata));
    }

    queueEvent(
        channelMessageListeners,
        (listener, last) -> listener.onMessage(last ? payload : payload.clone(), metadata));
  } // end method deliver

  @GuardedBy("lock")
  private void deliverPresence(PresenceEvent event) {
    ListenerSet<Consumer<PresenceEvent>> listeners = presenceListeners.get(event.segmentId());

    if (listeners == null) {
      return;
    }

    queueEvent(listeners, (listener, last) -> listener.accept(event));
  } // end method deliverPresence

  /** Reports an error frame once, every field as sent, and leaves the connection up (ERR-01). */
  @GuardedBy("lock")
  private void receiveServerError(ServerMessage.ErrorFrame error) {
    ServerErrorException serverError =
        new ServerErrorException(
            error.type(), error.subType(), Payloads.lenient(error.message()), error.resource());

    // A presence query error names its query by request id and answers that query alone. A stale
    // id belongs to a query that was already rejected and reported, so it is dropped (QUERY-01).
    if (Constants.PRESENCE_LIST_COMMAND.equals(error.subType())) {
      PresenceQuery pending = pendingPresence;

      if (pending != null && pending.requestId.equals(error.resource())) {
        rejectPendingPresence(serverError);
      }

      return;
    }

    if (ServerErrorException.RATE_LIMIT_ERROR.equals(error.type())) {
      queue.receiveRateLimit();
    }

    queueError(serverError);
  } // end method receiveServerError

  @GuardedBy("lock")
  private void receivePresenceResponse(ServerMessage.PresenceListResponse response) {
    PresenceQuery pending = pendingPresence;

    // A response carrying any other request id answers a query that already failed, so it is
    // dropped; a pending query keeps waiting for its own.
    if (pending == null || !response.requestId().equals(pending.requestId)) {
      return;
    }

    takePendingPresence();
    pending.future.settle(response.page(), null);
  } // end method receivePresenceResponse

  // ---- Presence queries ----

  CompletableFuture<PresencePage> presenceList(String segmentId, int page, int perPage) {
    OperationFuture<PresencePage> future = new OperationFuture<>(executor);

    lock.lock();

    try {
      Connection current = connection;

      if (state != ChannelState.CONNECTED || current == null) {
        future.settle(null, notConnected());

        return future;
      }

      if (pendingPresence != null) {
        future.settle(
            null,
            new CelerisException(
                ErrorCode.OPERATION_IN_PROGRESS,
                "A presence query is already in flight; wait for it to settle before starting"
                    + " another."));

        return future;
      }

      presenceRequestCount++;
      String requestId = Long.toString(presenceRequestCount);

      try {
        // A query the writer refuses fails without taking the query slot.
        queue.sendNow(current, CommandEncoder.presenceList(segmentId, page, perPage, requestId));
      } catch (CelerisException failure) {
        future.settle(null, failure);

        return future;
      }

      // A timed-out or cancelled query frees its slot and leaves the connection alone: a late
      // reply carries the old request id and is dropped.
      PresenceQuery query = new PresenceQuery(requestId, future);
      query.timer =
          timers.schedule(
              client.presenceQueryTimeout,
              () ->
                  withLock(
                      () -> {
                        if (pendingPresence == query) {
                          rejectPendingPresence(
                              new CelerisException(
                                  ErrorCode.TIMEOUT,
                                  "Presence query timed out after "
                                      + client.presenceQueryTimeout.toMillis()
                                      + " ms."));
                        }
                      }));

      pendingPresence = query;
      future.attach(() -> cancelPresence(query));
    } finally {
      lock.unlock();
      afterUnlock();
    }

    return future;
  } // end method presenceList

  private boolean cancelPresence(PresenceQuery query) {
    lock.lock();

    try {
      if (pendingPresence != query) {
        return false;
      }

      takePendingPresence();

      return true;
    } finally {
      lock.unlock();
    }
  } // end method cancelPresence

  @GuardedBy("lock")
  private @Nullable PresenceQuery takePendingPresence() {
    PresenceQuery pending = pendingPresence;

    if (pending != null) {
      pendingPresence = null;
      Timers.cancel(pending.timer);
    }

    return pending;
  } // end method takePendingPresence

  @GuardedBy("lock")
  private void rejectPendingPresence(RuntimeException failure) {
    PresenceQuery pending = takePendingPresence();

    if (pending != null) {
      pending.future.settle(null, failure);
    }
  } // end method rejectPendingPresence

  // ---- Publishing ----

  CompletableFuture<Void> publish(String segmentId, byte[] payload, String messageId) {
    // Encoding copies up to 2 MiB, so it happens before taking the lock; its failures are still
    // reported in the reference's order, after the connection check.
    byte[] data = null;
    CelerisException encodeFailure = null;

    try {
      data = CommandEncoder.publish(segmentId, messageId, payload);
    } catch (CelerisException failure) {
      encodeFailure = failure;
    }

    OperationFuture<Void> future = new OperationFuture<>(executor);

    lock.lock();

    try {
      // While reconnecting, a publish waits in the queue for the next socket (QUEUE-01).
      boolean accepting =
          state == ChannelState.RECONNECTING
              || (state == ChannelState.CONNECTED && connection != null);

      if (!accepting) {
        future.settle(null, notConnected());
      } else if (encodeFailure != null) {
        future.settle(null, encodeFailure);
      } else {
        try {
          QueuedPublish publish = queue.publish(segmentId, Objects.requireNonNull(data), future);
          future.attach(() -> cancelPublish(publish));
        } catch (CelerisException failure) {
          future.settle(null, failure);
        }
      }
    } finally {
      lock.unlock();
    }

    afterUnlock();

    return future;
  } // end method publish

  /**
   * Settles a publish whose future was cancelled. A rate limit can requeue a publish still in the
   * writer, so it may have a copy queued and copies in the writer at once: every copy not yet
   * written is taken back, and the publish is cancelled unless the socket is writing one, which
   * makes it DeliveryUnknown. Either way no later rate limit resends it.
   */
  private boolean cancelPublish(QueuedPublish publish) {
    boolean withdrawn;

    lock.lock();

    try {
      if (publish.settled()) {
        return false;
      }

      Connection handedTo = publish.connection;
      queue.withdraw(publish);
      boolean removed = handedTo != null && handedTo.remove(publish);
      withdrawn = handedTo == null || !handedTo.isWriting(publish);

      if (!withdrawn) {
        publish.settle(
            new CelerisException(
                ErrorCode.DELIVERY_UNKNOWN,
                "Publish cancelled while the socket was writing it, so it may or may not have been"
                    + " sent."));
      }

      // The room taken back from the writer may let a waiting command through.
      if (removed && handedTo == connection) {
        queue.drain();
      }
    } finally {
      lock.unlock();
    }

    afterUnlock();

    return withdrawn;
  } // end method cancelPublish

  private CelerisException notConnected() {
    return new CelerisException(
        ErrorCode.NOT_CONNECTED, "Channel is not connected; it is " + state + ".");
  } // end method notConnected

  // ---- Interests ----

  /** Counts one interest; the first registration and the last cancellation sync the segment. */
  Subscription addInterest(InterestKind kind, String segmentId) {
    Subscription subscription;

    lock.lock();

    try {
      if (state == ChannelState.CLOSING || state == ChannelState.CLOSED) {
        throw channelClosed();
      }

      Map<String, Integer> interests = interests(kind);
      int count = interests.getOrDefault(segmentId, 0);
      interests.put(segmentId, count + 1);

      if (count == 0) {
        queueInterestSync(kind, segmentId);
      }

      subscription = new Subscription(this, kind, segmentId);
    } finally {
      lock.unlock();
    }

    afterUnlock();

    return subscription;
  } // end method addInterest

  void releaseInterest(Subscription subscription) {
    lock.lock();

    try {
      if (!subscription.markCancelled()) {
        return;
      }

      Map<String, Integer> interests = interests(subscription.kind());
      int count = interests.getOrDefault(subscription.segmentId(), 0);

      if (count > 1) {
        interests.put(subscription.segmentId(), count - 1);

        return;
      }

      interests.remove(subscription.segmentId());
      queueInterestSync(subscription.kind(), subscription.segmentId());
    } finally {
      lock.unlock();
      afterUnlock();
    }
  } // end method releaseInterest

  @GuardedBy("lock")
  private Map<String, Integer> interests(InterestKind kind) {
    return kind == InterestKind.MESSAGE ? messageInterests : presenceInterests;
  } // end method interests

  /** Does nothing without a socket: installing one syncs every held interest. */
  @GuardedBy("lock")
  private void queueInterestSync(InterestKind kind, String segmentId) {
    if (connection != null) {
      queue.queueInterest(kind, segmentId);
    }
  } // end method queueInterestSync

  /**
   * Names the command that brings the server in line with the segment's interest as it stands now,
   * or null when none is needed. Subscriptions are synced as state, so a resend is always safe.
   */
  @GuardedBy("lock")
  @Nullable
  String interestCommand(InterestKind kind, String segmentId) {
    // Presence applies to every segment, the default one included: the server's connect-time
    // auto-join grants message membership only.
    if (kind == InterestKind.PRESENCE) {
      return presenceInterests.containsKey(segmentId)
          ? Constants.PRESENCE_SUBSCRIBE_COMMAND
          : Constants.PRESENCE_UNSUBSCRIBE_COMMAND;
    }

    // The server joins the default segment on connect and never leaves it.
    if (segmentId.equals(Constants.DEFAULT_SEGMENT_ID)) {
      return null;
    }

    // Watching presence is not membership, so it never holds the segment.
    return messageInterests.containsKey(segmentId)
        ? Constants.SUBSCRIBE_COMMAND
        : Constants.UNSUBSCRIBE_COMMAND;
  } // end method interestCommand

  // ---- Listeners and events ----

  <L> Registration addListener(ListenerSet<L> set, L listener) {
    Objects.requireNonNull(listener, "listener");

    lock.lock();

    try {
      ListenerSet.Entry<L> entry = set.add(listener);

      return new Registration(() -> withLock(() -> set.remove(entry)));
    } finally {
      lock.unlock();
    }
  } // end method addListener

  Registration addMessageListener(String segmentId, MessageListener listener) {
    Objects.requireNonNull(listener, "listener");

    lock.lock();

    try {
      return addListener(
          messageListeners.computeIfAbsent(segmentId, id -> new ListenerSet<>()), listener);
    } finally {
      lock.unlock();
    }
  } // end method addMessageListener

  Registration addPresenceListener(String segmentId, Consumer<PresenceEvent> listener) {
    Objects.requireNonNull(listener, "listener");

    lock.lock();

    try {
      return addListener(
          presenceListeners.computeIfAbsent(segmentId, id -> new ListenerSet<>()), listener);
    } finally {
      lock.unlock();
    }
  } // end method addPresenceListener

  /** Delivers one event to one listener; last marks the snapshot's final listener. */
  private interface Invoker<L> {
    void invoke(L listener, boolean last);
  } // end interface Invoker

  /**
   * Queues delivery of one event to the listeners registered when its turn comes. The caller holds
   * the lock. A listener that throws is reported through the error listeners; an error listener
   * that throws is not, since that would report into the delivery that failed.
   */
  @GuardedBy("lock")
  private <L> void queueEvent(ListenerSet<L> set, Invoker<L> invoker) {
    events.add(eventFor(set, invoker));
  } // end method queueEvent

  private <L> Event eventFor(ListenerSet<L> set, Invoker<L> invoker) {
    boolean reportFailures = set != errorListeners;

    return () -> {
      List<ListenerSet.Entry<L>> entries = set.snapshot();

      return () -> deliverEntries(entries, invoker, reportFailures);
    };
  } // end method eventFor

  private <L> void deliverEntries(
      List<ListenerSet.Entry<L>> entries, Invoker<L> invoker, boolean reportFailures) {
    for (int index = 0; index < entries.size(); index++) {
      ListenerSet.Entry<L> entry = entries.get(index);

      if (entry.removed) {
        continue;
      }

      boolean last = index == entries.size() - 1;

      try {
        invoker.invoke(entry.listener, last);
      } catch (VirtualMachineError failure) {
        throw failure;
      } catch (Throwable failure) {
        if (reportFailures) {
          reportListenerFailure();
        }
      }
    }
  } // end method deliverEntries

  /** Reports a listener that threw, ahead of events already queued. */
  private void reportListenerFailure() {
    CelerisException failure =
        new CelerisException(
            ErrorCode.TRANSPORT,
            "A listener callback threw; the channel caught the error and kept running.");

    lock.lock();

    try {
      events.addFirst(eventFor(errorListeners, (listener, last) -> listener.accept(failure)));
    } finally {
      lock.unlock();
    }
  } // end method reportListenerFailure

  @GuardedBy("lock")
  private void queueStateChange(ChannelState changed) {
    state = changed;
    queueEvent(stateListeners, (listener, last) -> listener.accept(changed));
  } // end method queueStateChange

  @GuardedBy("lock")
  private void queueError(RuntimeException failure) {
    queueEvent(errorListeners, (listener, last) -> listener.accept(failure));
  } // end method queueError

  /** Starts delivering queued events unless a thread is already delivering them. */
  void dispatch() {
    lock.lock();

    try {
      if (draining || events.isEmpty()) {
        return;
      }

      draining = true;
    } finally {
      lock.unlock();
    }

    executor.execute(this::drain);
  } // end method dispatch

  private void drain() {
    lock.lock();

    try {
      while (true) {
        Event event = events.poll();

        if (event == null) {
          draining = false;

          return;
        }

        Runnable delivery = event.prepare();
        lock.unlock();

        try {
          delivery.run();
        } finally {
          lock.lock();
        }
      }
    } catch (Throwable failure) {
      draining = false;

      if (!events.isEmpty()) {
        draining = true;
        executor.execute(this::drain);
      }

      throw failure;
    } finally {
      lock.unlock();
    }
  } // end method drain

  /** Runs action under the lock, then delivers events and starts the writer. */
  void withLock(Runnable action) {
    lock.lock();

    try {
      action.run();
    } finally {
      lock.unlock();
    }

    afterUnlock();
  } // end method withLock

  /** Delivers queued events and starts the current connection's writer. Called without the lock. */
  void afterUnlock() {
    dispatch();
    Connection current;

    lock.lock();

    try {
      current = connection;
    } finally {
      lock.unlock();
    }

    if (current != null) {
      current.pump();
    }
  } // end method afterUnlock

  private static void doNothing() {}
} // end class Channel

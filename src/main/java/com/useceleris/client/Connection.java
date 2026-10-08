package com.useceleris.client;

import java.io.ByteArrayOutputStream;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * One socket and what drives it: the receiver that assembles transport messages, the writer that
 * sends handed-off frames one at a time and in order, the heartbeat, and the close.
 *
 * <p>Its state is guarded by the channel's lock, except the frame being assembled, which only the
 * JDK's listener callbacks touch; the JDK invokes them one at a time. No WebSocket method is ever
 * called while the lock is held, and the listener callbacks never run user code: a complete message
 * is handed to the channel, whose event queue delivers it, and the next one is requested only once
 * its listeners have run. The read-ahead is therefore one transport message (DEV-01).
 */
final class Connection implements WebSocket.Listener {
  private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

  /** One command handed to the writer, compared by identity; a publish settles once written. */
  static final class OutboundFrame {
    final byte[] data;
    final @Nullable QueuedPublish publish;

    OutboundFrame(byte[] data, @Nullable QueuedPublish publish) {
      this.data = data;
      this.publish = publish;
    } // end constructor OutboundFrame
  } // end class OutboundFrame

  private final Channel channel;

  // Only the JDK's listener callbacks touch these.
  private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
  private boolean textMessage;

  @GuardedBy("channel.lock")
  private @Nullable WebSocket socket;

  // Frames handed to the writer and not yet written, the one being written first. They are bounded
  // by count and bytes, so a maximum-size command always fits an empty writer.
  @GuardedBy("channel.lock")
  private final ArrayDeque<OutboundFrame> outbound = new ArrayDeque<>();

  @GuardedBy("channel.lock")
  private int outboundBytes;

  @GuardedBy("channel.lock")
  @Nullable
  OutboundFrame writing;

  // closing asks the writer to flush, then perform the closing handshake; broken stops it at once.
  @GuardedBy("channel.lock")
  boolean closing;

  @GuardedBy("channel.lock")
  boolean broken;

  @GuardedBy("channel.lock")
  private boolean closeSent;

  // Heartbeat (HEARTBEAT-01). Reading time accumulates only while the next message is requested,
  // so a listener holding the read side never makes the connection look dead.
  @GuardedBy("channel.lock")
  private long lastHeard;

  @GuardedBy("channel.lock")
  private long lastPing;

  @GuardedBy("channel.lock")
  private boolean pingInFlight;

  @GuardedBy("channel.lock")
  private boolean reading;

  @GuardedBy("channel.lock")
  private long readingSince;

  @GuardedBy("channel.lock")
  private long readingAccumulated;

  // The reading time at which the oldest unanswered ping went out, or -1.
  @GuardedBy("channel.lock")
  private long unansweredPingAt = -1;

  @GuardedBy("channel.lock")
  private Timers.@Nullable Cancellable heartbeatTimer;

  // Post-delivery probe (see Constants.POST_DELIVERY_PROBE_DELAY). A message was delivered since
  // the last request; each arming or cancelling starts a new round, so a stale timer does nothing.
  @GuardedBy("channel.lock")
  private boolean deliveredSinceRequest;

  @GuardedBy("channel.lock")
  private long probeRound;

  @GuardedBy("channel.lock")
  private Timers.@Nullable Cancellable probeTimer;

  /** Completes once the socket is closed, by the closing handshake, a failure or an abort. */
  final CompletableFuture<Void> closed = new CompletableFuture<>();

  Connection(Channel channel) {
    this.channel = channel;
  } // end constructor Connection

  // ---- Installation and reading ----

  /** Installs the socket the dial produced and starts the heartbeat. The caller holds the lock. */
  void install(WebSocket installed) {
    socket = installed;
    long now = channel.timers.monotonicNanos();
    lastHeard = now;
    lastPing = now;
    scheduleHeartbeat();
  } // end method install

  /** Requests the next transport message. Called without the lock. */
  void resume() {
    WebSocket current;

    channel.lock.lock();

    try {
      current = socket;

      if (current == null || broken) {
        return;
      }

      if (!reading) {
        reading = true;
        readingSince = channel.timers.monotonicNanos();
      }

      if (deliveredSinceRequest) {
        deliveredSinceRequest = false;
        armProbe();
      }
    } finally {
      channel.lock.unlock();
    }

    current.request(1);
  } // end method resume

  @Override
  public void onOpen(WebSocket webSocket) {
    // Nothing is read until the channel has queued its connected event (S10).
  } // end method onOpen

  @Override
  public @Nullable CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
    heard();
    byte[] part = new byte[data.remaining()];
    data.get(part);
    partial.writeBytes(part);

    if (!last) {
      webSocket.request(1);

      return null;
    }

    byte[] message = partial.toByteArray();
    partial.reset();
    boolean text = textMessage;
    textMessage = false;
    stopReading();
    ServerMessage decoded;

    if (text) {
      decoded = notBinary();
    } else {
      try {
        decoded = MessageDecoder.decode(message);
      } catch (CelerisException failure) {
        decoded = new ServerMessage.DecodeFailure(failure);
      }
    }

    channel.receive(this, decoded);

    return null;
  } // end method onBinary

  @Override
  public @Nullable CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
    heard();
    textMessage = true;

    if (!last) {
      webSocket.request(1);

      return null;
    }

    partial.reset();
    textMessage = false;
    stopReading();
    channel.receive(this, notBinary());

    return null;
  } // end method onText

  private static ServerMessage notBinary() {
    return new ServerMessage.DecodeFailure(
        CelerisException.protocol("Expected a binary WebSocket message.", "message", 0));
  } // end method notBinary

  @Override
  public @Nullable CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
    // The JDK answers with a pong itself.
    heard();
    webSocket.request(1);

    return null;
  } // end method onPing

  @Override
  public @Nullable CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
    heard();
    webSocket.request(1);

    return null;
  } // end method onPong

  @Override
  public @Nullable CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
    closed.complete(null);
    channel.socketFailed(this);

    return null;
  } // end method onClose

  @Override
  public void onError(WebSocket webSocket, Throwable error) {
    closed.complete(null);
    channel.socketFailed(this);
  } // end method onError

  private void heard() {
    channel.lock.lock();

    try {
      lastHeard = channel.timers.monotonicNanos();
      unansweredPingAt = -1;
      cancelProbe();
    } finally {
      channel.lock.unlock();
    }
  } // end method heard

  private void stopReading() {
    channel.lock.lock();

    try {
      if (reading) {
        readingAccumulated += channel.timers.monotonicNanos() - readingSince;
        reading = false;
      }

      deliveredSinceRequest = true;
    } finally {
      channel.lock.unlock();
    }
  } // end method stopReading

  // ---- Writer ----

  /** The caller holds the lock. */
  boolean hasRoom(int size) {
    return hasCommandRoom() && (long) outboundBytes + size <= Constants.MAXIMUM_BUFFERED_BYTES;
  } // end method hasRoom

  /** The caller holds the lock. */
  boolean hasCommandRoom() {
    return outbound.size() < Constants.MAXIMUM_PENDING_COMMANDS;
  } // end method hasCommandRoom

  /** The caller holds the lock, and calls {@link #pump()} once it releases it. */
  void handOff(OutboundFrame frame) {
    outbound.add(frame);
    outboundBytes += frame.data.length;
  } // end method handOff

  /**
   * Takes back every frame of the publish the writer has not started, and reports whether there was
   * one. A rate limit can requeue a publish still in the writer, so it may have been handed over
   * twice. The caller holds the lock.
   */
  boolean remove(QueuedPublish publish) {
    boolean removed = false;

    for (var frames = outbound.iterator(); frames.hasNext(); ) {
      OutboundFrame frame = frames.next();

      if (frame.publish == publish && frame != writing) {
        frames.remove();
        outboundBytes -= frame.data.length;
        removed = true;
      }
    }

    return removed;
  } // end method remove

  /** Reports whether the socket is writing a frame of the publish. The caller holds the lock. */
  boolean isWriting(QueuedPublish publish) {
    OutboundFrame current = writing;

    return current != null && current.publish == publish;
  } // end method isWriting

  /**
   * Starts the next write, or the closing handshake once a closing writer is flushed. Called
   * without the lock; does nothing while a write is outstanding, since the JDK allows one at a
   * time.
   */
  void pump() {
    WebSocket current;
    OutboundFrame frame;

    channel.lock.lock();

    try {
      current = socket;

      if (current == null || broken || writing != null) {
        return;
      }

      frame = outbound.peek();

      if (frame == null) {
        if (!closing || closeSent) {
          return;
        }

        closeSent = true;
      } else {
        writing = frame;
      }
    } finally {
      channel.lock.unlock();
    }

    if (frame == null) {
      sendClose(current);

      return;
    }

    CompletableFuture<WebSocket> sent;

    try {
      sent = current.sendBinary(ByteBuffer.wrap(frame.data), true);
    } catch (RuntimeException failure) {
      sent = CompletableFuture.failedFuture(failure);
    }

    var unused =
        sent.whenCompleteAsync(
            (ignored, failure) -> writeCompleted(frame, failure), channel.executor);
  } // end method pump

  private void writeCompleted(OutboundFrame frame, @Nullable Throwable failure) {
    channel.lock.lock();

    try {
      writing = null;

      if (outbound.peek() == frame) {
        outbound.poll();
        outboundBytes -= frame.data.length;
      }

      QueuedPublish publish = frame.publish;

      if (failure != null) {
        if (publish != null) {
          publish.settle(
              new CelerisException(
                  ErrorCode.DELIVERY_UNKNOWN,
                  "WebSocket send failed after the command was handed over, so it may or may not"
                      + " have been sent."));
        }

        // A write the socket refuses leaves the server's view of this connection unknown, so the
        // socket is replaced and reconnecting restores every subscription (RESEND-01).
        channel.connectionFailed(this, channel.timers.monotonicNanos());
      } else {
        if (publish != null) {
          publish.settle(null);
        }

        if (channel.connection == this) {
          channel.queue.drain();
        }
      }
    } finally {
      channel.lock.unlock();
    }

    channel.afterUnlock();

    // A closing connection is no longer the channel's, so it keeps its own writer going.
    pump();
  } // end method writeCompleted

  /**
   * Takes back, in order, every unsettled publish handed to this connection that the socket never
   * started writing; each counts as never handed over. A publish with a frame being written stays:
   * that write settles it. The caller holds the lock.
   */
  List<QueuedPublish> takeUnstarted() {
    OutboundFrame current = writing;
    QueuedPublish beingWritten = current == null ? null : current.publish;
    List<QueuedPublish> unstarted = new ArrayList<>();

    for (OutboundFrame frame : outbound) {
      QueuedPublish publish = frame.publish;

      if (frame != current
          && publish != null
          && publish != beingWritten
          && !publish.settled()
          && !unstarted.contains(publish)) {
        publish.connection = null;
        unstarted.add(publish);
      }
    }

    outbound.clear();
    outboundBytes = 0;

    if (current != null) {
      outbound.add(current);
      outboundBytes = current.data.length;
    }

    return unstarted;
  } // end method takeUnstarted

  // ---- Heartbeat ----

  private void scheduleHeartbeat() {
    heartbeatTimer =
        channel.timers.schedule(Constants.HEARTBEAT_CHECK_INTERVAL, this::checkHeartbeat);
  } // end method scheduleHeartbeat

  private long readingClock(long now) {
    return readingAccumulated + (reading ? now - readingSince : 0);
  } // end method readingClock

  private void checkHeartbeat() {
    WebSocket current = null;

    channel.lock.lock();

    try {
      if (broken || socket == null) {
        return;
      }

      long now = channel.timers.monotonicNanos();
      long readingNow = readingClock(now);

      if (unansweredPingAt >= 0
          && readingNow - unansweredPingAt >= Constants.HEARTBEAT_TIMEOUT.toNanos()) {
        // Nothing arrived for a heartbeat timeout of reading time: the path is dead.
        channel.connectionFailed(this, lastHeard);
      } else {
        long idle = Constants.HEARTBEAT_IDLE.toNanos();

        if (!pingInFlight && now - lastHeard >= idle && now - lastPing >= idle) {
          pingInFlight = true;
          lastPing = now;

          if (unansweredPingAt < 0) {
            unansweredPingAt = readingNow;
          }

          current = socket;
        }

        scheduleHeartbeat();
      }
    } finally {
      channel.lock.unlock();
    }

    if (current != null) {
      CompletableFuture<WebSocket> sent;

      try {
        sent = current.sendPing(EMPTY);
      } catch (RuntimeException failure) {
        sent = CompletableFuture.failedFuture(failure);
      }

      var unused =
          sent.whenCompleteAsync(
              (ignored, failure) -> {
                channel.lock.lock();

                try {
                  pingInFlight = false;
                } finally {
                  channel.lock.unlock();
                }
              },
              channel.executor);
    }

    channel.afterUnlock();
  } // end method checkHeartbeat

  // ---- Post-delivery probe ----

  /** Starts the quiet period after a delivery. The caller holds the lock. */
  private void armProbe() {
    cancelProbe();
    long round = probeRound;
    probeTimer =
        channel.timers.schedule(Constants.POST_DELIVERY_PROBE_DELAY, () -> sendProbe(round));
  } // end method armProbe

  /** The caller holds the lock. */
  private void cancelProbe() {
    probeRound++;
    Timers.cancel(probeTimer);
    probeTimer = null;
  } // end method cancelProbe

  /**
   * Pings once the server stayed quiet for the probe delay, and waits for anything to arrive. A
   * ping already in flight is not doubled: its pong answers the probe as well.
   */
  private void sendProbe(long round) {
    WebSocket current = null;

    channel.lock.lock();

    try {
      if (round != probeRound || broken || closing || socket == null) {
        return;
      }

      probeTimer =
          channel.timers.schedule(
              Constants.POST_DELIVERY_PONG_TIMEOUT, () -> probeUnanswered(round));

      if (!pingInFlight) {
        pingInFlight = true;
        current = socket;
      }
    } finally {
      channel.lock.unlock();
    }

    if (current == null) {
      return;
    }

    CompletableFuture<WebSocket> sent;

    try {
      sent = current.sendPing(EMPTY);
    } catch (RuntimeException failure) {
      sent = CompletableFuture.failedFuture(failure);
    }

    var unused = sent.whenCompleteAsync(this::probeSent, channel.executor);
  } // end method sendProbe

  /** A probe ping that cannot be written means the connection is gone. */
  private void probeSent(@Nullable WebSocket ignored, @Nullable Throwable failure) {
    channel.lock.lock();

    try {
      pingInFlight = false;

      if (failure != null && !broken && !closing) {
        channel.connectionFailed(this, lastHeard);
      }
    } finally {
      channel.lock.unlock();
    }

    channel.afterUnlock();
  } // end method probeSent

  /** Nothing arrived within the pong timeout: the path is dead, as for the heartbeat. */
  private void probeUnanswered(long round) {
    channel.lock.lock();

    try {
      if (round != probeRound || broken || closing) {
        return;
      }

      channel.connectionFailed(this, lastHeard);
    } finally {
      channel.lock.unlock();
    }

    channel.afterUnlock();
  } // end method probeUnanswered

  // ---- Closing ----

  /**
   * Lifts the read-ahead bound for a socket that is closing: everything that arrives is read and
   * discarded, so the server's close reply is seen even while a listener holds the event queue.
   * Called without the lock.
   */
  void drainInbound() {
    WebSocket current;

    channel.lock.lock();

    try {
      current = socket;
    } finally {
      channel.lock.unlock();
    }

    if (current != null) {
      current.request(Long.MAX_VALUE);
    }
  } // end method drainInbound

  private void sendClose(WebSocket current) {
    CompletableFuture<WebSocket> sent;

    try {
      sent = current.sendClose(WebSocket.NORMAL_CLOSURE, "");
    } catch (RuntimeException failure) {
      sent = CompletableFuture.failedFuture(failure);
    }

    var unused =
        sent.whenCompleteAsync(
            (ignored, failure) -> {
              if (failure != null) {
                channel.withLock(() -> abandon(Channel.closedBeforeSent()));
              }
            },
            channel.executor);
  } // end method sendClose

  /**
   * Stops the connection without a closing handshake. The caller holds the lock and has detached
   * the connection; publishes not yet written fail with failure.
   */
  void abandon(Throwable failure) {
    if (broken) {
      return;
    }

    broken = true;

    for (QueuedPublish publish : takeUnstarted()) {
      publish.settle(failure);
    }

    Timers.cancel(heartbeatTimer);
    heartbeatTimer = null;
    cancelProbe();

    channel.executor.execute(this::abortNow);
  } // end method abandon

  private void abortNow() {
    WebSocket current;

    channel.lock.lock();

    try {
      current = socket;
    } finally {
      channel.lock.unlock();
    }

    if (current != null) {
      current.abort();
    }

    closed.complete(null);
  } // end method abortNow
} // end class Connection

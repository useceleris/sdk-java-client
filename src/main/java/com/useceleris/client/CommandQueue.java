package com.useceleris.client;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Sends subscription changes ahead of publishes, waits for room in the writer, and resends recent
 * commands after a rate limit (RESEND-01). The server never says which frame a rate limit dropped,
 * so everything sent within the suspect window is resent: subscriptions as their current state,
 * publishes once and with their original message id, so receivers drop any copy that got through.
 *
 * <p>It belongs to its channel and is guarded by the channel's lock.
 */
final class CommandQueue {
  private final Channel channel;

  // Per kind: segment → the sequence of its latest change, in the order segments were first
  // marked.
  private final Map<InterestKind, LinkedHashMap<String, Long>> pendingInterests =
      new EnumMap<>(InterestKind.class);

  private final ArrayList<QueuedPublish> publishes = new ArrayList<>();
  private long sequence;

  private final ArrayDeque<SentInterest> recentInterests = new ArrayDeque<>();

  // Holds payloads, so it keeps no more than MAXIMUM_PENDING_COMMANDS.
  private final ArrayDeque<SentPublish> recentPublishes = new ArrayDeque<>();

  private Timers.@Nullable Cancellable pauseTimer;
  private int rateLimitStreak;
  private long rateLimitStreakEndsAt;

  // Subscriptions dropped while the limit was treated as a used-up quota, re-sent by a probe on a
  // slow, doubling schedule.
  private final Map<InterestKind, LinkedHashSet<String>> abandonedInterests =
      new EnumMap<>(InterestKind.class);
  private Timers.@Nullable Cancellable probeTimer;
  private int probeCount;

  // Zero until a command is sent after the latest rate limit; otherwise a monotonic reading.
  private long firstSentSinceRateLimitAt;
  private boolean sentSinceRateLimit;

  private record SentInterest(InterestKind kind, String segmentId, long sentAt) {}

  private record SentPublish(QueuedPublish publish, long sentAt) {}

  CommandQueue(Channel channel) {
    this.channel = channel;

    for (InterestKind kind : InterestKind.values()) {
      pendingInterests.put(kind, new LinkedHashMap<>());
      abandonedInterests.put(kind, new LinkedHashSet<>());
    }
  } // end constructor CommandQueue

  private Map<String, Long> pending(InterestKind kind) {
    return Objects.requireNonNull(pendingInterests.get(kind));
  } // end method pending

  private Set<String> abandoned(InterestKind kind) {
    return Objects.requireNonNull(abandonedInterests.get(kind));
  } // end method abandoned

  void queueInterest(InterestKind kind, String segmentId) {
    markInterest(kind, segmentId);
    drain();
  } // end method queueInterest

  /** Queues a publish, which settles once written to the socket. */
  QueuedPublish publish(String segmentId, byte[] data, OperationFuture<Void> future) {
    if (publishes.size() >= channel.publishQueueSize) {
      throw new CelerisException(
          ErrorCode.BACKPRESSURE,
          "The publish queue is full (size "
              + channel.publishQueueSize
              + "). Retry once some publishes have gone out.");
    }

    sequence++;
    QueuedPublish publish = new QueuedPublish(segmentId, data, sequence, future);
    publishes.add(publish);
    drain();

    return publish;
  } // end method publish

  /** Takes back a cancelled publish: out of the queue, and out of the resend record. */
  void withdraw(QueuedPublish publish) {
    publishes.remove(publish);
    recentPublishes.removeIf(sent -> sent.publish() == publish);
  } // end method withdraw

  /** Hands over a command that is never queued or resent, such as a presence query. */
  void sendNow(Connection connection, byte[] data) {
    if (pauseTimer != null) {
      throw new CelerisException(
          ErrorCode.BACKPRESSURE, "Sending is paused after a rate limit; try again in a moment.");
    }

    if (!connection.hasCommandRoom()) {
      throw new CelerisException(
          ErrorCode.BACKPRESSURE,
          "Command writer is full: 64 commands are waiting to be sent. Retry once the socket has"
              + " flushed them.");
    }

    if (!connection.hasRoom(data.length)) {
      throw new CelerisException(
          ErrorCode.BACKPRESSURE,
          "WebSocket buffer is full: this command would take unsent data past 2 MiB. Retry once the"
              + " buffer drains.");
    }

    handOff(connection, new Connection.OutboundFrame(data, null));
  } // end method sendNow

  @SuppressWarnings("ReferenceEquality") // a timer is matched by identity, as its own token
  void receiveRateLimit() {
    // A limit arriving while sending is paused, with nothing sent since the last one, reports the
    // same episode through another limit type (the server throttles each type separately). It
    // carries nothing new.
    if (pauseTimer != null && !sentSinceRateLimit) {
      return;
    }

    long now = channel.timers.monotonicNanos();
    endProbingIfQuotaReturned(now, Constants.QUOTA_RETURN_CONFIRMATION);

    // While probing the streak holds, so a probe's own limit cannot start another full run of
    // resends.
    if (probeCount == 0 && now - rateLimitStreakEndsAt > 0) {
      rateLimitStreak = 0;
    }

    // A limit that keeps returning is a used-up quota rather than a burst, and resending into it
    // would never succeed.
    if (rateLimitStreak < Constants.MAXIMUM_CONSECUTIVE_RATE_LIMITS) {
      requeueRecent(now);
    } else {
      abandonRecent();
    }

    recentInterests.clear();
    recentPublishes.clear();
    sentSinceRateLimit = false;

    Duration delay =
        Constants.RATE_LIMIT_COOLDOWN.plus(Reconnect.retryDelay(rateLimitStreak, channel.random));
    rateLimitStreak++;
    rateLimitStreakEndsAt = now + delay.toNanos() + Constants.RATE_LIMIT_SUSPECT_WINDOW.toNanos();

    Timers.cancel(pauseTimer);
    Timers.Cancellable[] timer = new Timers.Cancellable[1];
    timer[0] =
        channel.timers.schedule(
            delay,
            () ->
                channel.withLock(
                    () -> {
                      if (pauseTimer != timer[0]) {
                        return;
                      }

                      pauseTimer = null;
                      drain();
                    }));

    pauseTimer = timer[0];
  } // end method receiveRateLimit

  /**
   * Fails waiting publishes and forgets everything tied to the socket. A publish still in a writer
   * is left to that writer, which settles it.
   */
  void reset(Throwable failure) {
    forgetSocket();
    List<QueuedPublish> waiting = new ArrayList<>(publishes);
    publishes.clear();

    for (QueuedPublish publish : waiting) {
      if (publish.connection == null) {
        publish.settle(failure);
      }
    }
  } // end method reset

  /**
   * Keeps every publish the lost socket never took for the next one (QUEUE-01): first those its
   * writer never started, in order, then those still waiting. A publish queued again for a
   * rate-limit resend after a socket took it is dropped, since that socket's writer settles it and
   * a publish is never resent after a reconnect.
   */
  void keepAcrossReconnect(List<QueuedPublish> unstarted) {
    forgetSocket();
    List<QueuedPublish> kept = new ArrayList<>(unstarted);

    for (QueuedPublish publish : publishes) {
      if (publish.connection == null && !kept.contains(publish)) {
        kept.add(publish);
      }
    }

    publishes.clear();
    publishes.addAll(kept);
  } // end method keepAcrossReconnect

  /**
   * Queues the subscriptions a new socket restores, ahead of every queued publish: messages first,
   * then presence, each in the order given.
   */
  void restore(Set<String> messageSegments, Set<String> presenceSegments) {
    markRestored(InterestKind.MESSAGE, messageSegments);
    markRestored(InterestKind.PRESENCE, presenceSegments);
    drain();
  } // end method restore

  /** Sequence zero precedes every publish, so no queued publish holds a restoration back. */
  private void markRestored(InterestKind kind, Set<String> segmentIds) {
    for (String segmentId : segmentIds) {
      pending(kind).remove(segmentId);
      pending(kind).put(segmentId, 0L);
    }
  } // end method markRestored

  /**
   * Forgets everything tied to the socket: the next one re-syncs every subscription itself. The
   * rate-limit streak and the probe count stay, since a reconnect does not refill a quota.
   */
  private void forgetSocket() {
    Timers.cancel(pauseTimer);
    pauseTimer = null;
    Timers.cancel(probeTimer);
    probeTimer = null;

    for (InterestKind kind : InterestKind.values()) {
      pending(kind).clear();
      abandoned(kind).clear();
    }

    recentInterests.clear();
    recentPublishes.clear();
    sentSinceRateLimit = false;
  } // end method forgetSocket

  /**
   * Gives a command to the writer and records it as sent, as the reference does when it hands a
   * command to the socket: a rate limit resends it, and it shows that commands flowed since the
   * last limit.
   */
  private void handOff(Connection connection, Connection.OutboundFrame frame) {
    connection.handOff(frame);

    if (frame.publish != null) {
      recordSentPublish(frame.publish);
    }

    if (!sentSinceRateLimit) {
      sentSinceRateLimit = true;
      firstSentSinceRateLimitAt = channel.timers.monotonicNanos();
    }
  } // end method handOff

  /** Gives the change a newer sequence, so the sync follows every publish queued before it. */
  private void markInterest(InterestKind kind, String segmentId) {
    sequence++;
    pending(kind).put(segmentId, sequence);
  } // end method markInterest

  private void requeueRecent(long now) {
    long window = Constants.RATE_LIMIT_SUSPECT_WINDOW.toNanos();

    for (SentInterest sent : recentInterests) {
      if (now - sent.sentAt() <= window) {
        markInterest(sent.kind(), sent.segmentId());
      }
    }

    List<QueuedPublish> resent = new ArrayList<>();

    for (SentPublish sent : recentPublishes) {
      if (now - sent.sentAt() <= window
          && sent.publish().resends < Constants.MAXIMUM_PUBLISH_RESENDS) {
        sent.publish().resends++;
        resent.add(sent.publish());
      }
    }

    publishes.addAll(0, resent);
  } // end method requeueRecent

  /**
   * Drops recent publishes, and hands every subscription sent since the previous limit to the quota
   * probe, however late the report: syncs are idempotent, so over-abandoning costs at most a
   * redundant frame, while missing one loses the subscription.
   */
  @SuppressWarnings("ReferenceEquality") // a timer is matched by identity, as its own token
  private void abandonRecent() {
    for (SentInterest sent : recentInterests) {
      abandoned(sent.kind()).add(sent.segmentId());
    }

    boolean abandoned =
        abandonedInterests.values().stream().anyMatch(segments -> !segments.isEmpty());

    if (!abandoned || probeTimer != null) {
      return;
    }

    Duration delay = Constants.QUOTA_PROBE_MAXIMUM_DELAY;

    if (probeCount < 16) {
      Duration doubled = Constants.QUOTA_PROBE_FIRST_DELAY.multipliedBy(1L << probeCount);
      delay = doubled.compareTo(delay) < 0 ? doubled : delay;
    }

    probeCount++;

    Timers.Cancellable[] timer = new Timers.Cancellable[1];
    timer[0] =
        channel.timers.schedule(
            delay,
            () ->
                channel.withLock(
                    () -> {
                      if (probeTimer != timer[0]) {
                        return;
                      }

                      probeTimer = null;
                      restoreAbandoned();
                      drain();
                    }));

    probeTimer = timer[0];
  } // end method abandonRecent

  private void restoreAbandoned() {
    for (InterestKind kind : InterestKind.values()) {
      for (String segmentId : abandoned(kind)) {
        markInterest(kind, segmentId);
      }

      abandoned(kind).clear();
    }
  } // end method restoreAbandoned

  /**
   * Restores abandoned subscriptions at once when commands went quietSpan without a rate limit
   * following them: the quota is back.
   */
  private void endProbingIfQuotaReturned(long now, Duration quietSpan) {
    if (probeCount == 0
        || !sentSinceRateLimit
        || now - firstSentSinceRateLimitAt <= quietSpan.toNanos()) {
      return;
    }

    Timers.cancel(probeTimer);
    probeTimer = null;
    probeCount = 0;
    rateLimitStreak = 0;
    restoreAbandoned();
  } // end method endProbingIfQuotaReturned

  /**
   * Hands commands to the writer while it has room. The writer drains again whenever it finishes a
   * write, so a full writer only delays commands.
   */
  void drain() {
    Connection connection = channel.connection;

    if (connection == null || pauseTimer != null) {
      return;
    }

    endProbingIfQuotaReturned(channel.timers.monotonicNanos(), Constants.RATE_LIMIT_SUSPECT_WINDOW);

    while (true) {
      PendingInterest interest = nextReadyInterest();

      if (interest != null) {
        if (!handOffInterest(connection, interest.kind(), interest.segmentId())) {
          return;
        }

        continue;
      }

      if (publishes.isEmpty() || !connection.hasRoom(publishes.get(0).data.length)) {
        return;
      }

      QueuedPublish publish = publishes.remove(0);
      publish.connection = connection;
      handOff(connection, new Connection.OutboundFrame(publish.data, publish));
    }
  } // end method drain

  private record PendingInterest(InterestKind kind, String segmentId) {}

  /**
   * Finds the first subscription change with no earlier publish to its segment still queued:
   * publishing joins the segment, so a change has to follow the publishes queued before it for the
   * segment to end up as asked.
   */
  private @Nullable PendingInterest nextReadyInterest() {
    for (InterestKind kind : InterestKind.values()) {
      for (Map.Entry<String, Long> pending : pending(kind).entrySet()) {
        String segmentId = pending.getKey();
        long changeSequence = pending.getValue();
        boolean blocked = false;

        for (QueuedPublish publish : publishes) {
          if (publish.segmentId.equals(segmentId) && publish.sequence < changeSequence) {
            blocked = true;

            break;
          }
        }

        if (!blocked) {
          return new PendingInterest(kind, segmentId);
        }
      }
    }

    return null;
  } // end method nextReadyInterest

  /**
   * Hands the writer the command that brings the server in line with the segment's interest as it
   * stands now. Reports false when the writer has no room.
   */
  private boolean handOffInterest(Connection connection, InterestKind kind, String segmentId) {
    String command = channel.interestCommand(kind, segmentId);

    if (command != null) {
      // The segment was validated when its handle was made, so encoding cannot fail.
      byte[] data = CommandEncoder.segmentCommand(command, segmentId);

      if (!connection.hasRoom(data.length)) {
        return false;
      }

      handOff(connection, new Connection.OutboundFrame(data, null));
      recordSentInterest(kind, segmentId);
    }

    pending(kind).remove(segmentId);

    return true;
  } // end method handOffInterest

  private void recordSentInterest(InterestKind kind, String segmentId) {
    long now = channel.timers.monotonicNanos();
    long window = Constants.RATE_LIMIT_SUSPECT_WINDOW.toNanos();

    while (!recentInterests.isEmpty() && now - recentInterests.peek().sentAt() > window) {
      recentInterests.poll();
    }

    recentInterests.add(new SentInterest(kind, segmentId, now));
  } // end method recordSentInterest

  private void recordSentPublish(QueuedPublish publish) {
    long now = channel.timers.monotonicNanos();
    long window = Constants.RATE_LIMIT_SUSPECT_WINDOW.toNanos();
    Iterator<SentPublish> sent = recentPublishes.iterator();

    while (sent.hasNext()) {
      SentPublish oldest = sent.next();

      if (recentPublishes.size() >= Constants.MAXIMUM_PENDING_COMMANDS
          || now - oldest.sentAt() > window) {
        sent.remove();
      } else {
        break;
      }
    }

    recentPublishes.add(new SentPublish(publish, now));
  } // end method recordSentPublish
} // end class CommandQueue

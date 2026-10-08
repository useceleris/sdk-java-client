package com.useceleris.client;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;

/** A clock that moves only when the test advances it; due tasks run on the executor. */
final class FakeTimers implements Timers {
  private static final long START = 1_000_000_000_000L;

  private final Executor executor;
  private final List<Scheduled> scheduled = new ArrayList<>();
  private long now = START;
  private long order;

  private final class Scheduled implements Cancellable {
    final long due;
    final long sequence;
    final Runnable task;
    boolean cancelled;

    Scheduled(long due, long sequence, Runnable task) {
      this.due = due;
      this.sequence = sequence;
      this.task = task;
    } // end constructor Scheduled

    @Override
    public void cancel() {
      synchronized (FakeTimers.this) {
        cancelled = true;
        scheduled.remove(this);
      }
    } // end method cancel
  } // end class Scheduled

  FakeTimers(Executor executor) {
    this.executor = executor;
  } // end constructor FakeTimers

  @Override
  public synchronized Cancellable schedule(Duration delay, Runnable task) {
    Scheduled entry = new Scheduled(now + delay.toNanos(), order++, task);
    scheduled.add(entry);

    return entry;
  } // end method schedule

  @Override
  public synchronized long monotonicNanos() {
    return now;
  } // end method monotonicNanos

  @Override
  public synchronized Instant now() {
    return Instant.ofEpochSecond(1_800_000_000L).plusNanos(now - START);
  } // end method now

  /** Hands the earliest task due by the given time to the executor, and reports whether one was. */
  boolean fireNextDueBy(long time) {
    Scheduled next;

    synchronized (this) {
      next =
          scheduled.stream()
              .filter(entry -> entry.due <= time)
              .min(
                  Comparator.comparingLong((Scheduled entry) -> entry.due)
                      .thenComparingLong(entry -> entry.sequence))
              .orElse(null);

      if (next == null) {
        return false;
      }

      scheduled.remove(next);

      if (next.due > now) {
        now = next.due;
      }
    }

    Scheduled firing = next;
    executor.execute(
        () -> {
          if (!firing.cancelled) {
            firing.task.run();
          }
        });

    return true;
  } // end method fireNextDueBy

  synchronized void moveTo(long time) {
    if (time > now) {
      now = time;
    }
  } // end method moveTo

  synchronized int pendingCount() {
    return scheduled.size();
  } // end method pendingCount
} // end class FakeTimers

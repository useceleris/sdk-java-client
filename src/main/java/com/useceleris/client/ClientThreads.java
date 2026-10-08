package com.useceleris.client;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A client's threads, created on first use, never when the class loads or the client is created: a
 * cached pool of workers, which also serves the HTTP client, and one timer thread that only hands
 * due tasks to the workers. All are daemon threads.
 */
final class ClientThreads implements java.util.concurrent.Executor, Timers {
  private static final AtomicInteger CLIENT_NUMBER = new AtomicInteger();

  private final int clientNumber = CLIENT_NUMBER.incrementAndGet();
  private @Nullable ExecutorService workers;
  private @Nullable ScheduledThreadPoolExecutor timer;

  @Override
  public void execute(Runnable task) {
    workers().execute(task);
  } // end method execute

  @Override
  public Cancellable schedule(Duration delay, Runnable task) {
    ScheduledFuture<?> scheduled =
        timer().schedule(() -> execute(task), delay.toNanos(), TimeUnit.NANOSECONDS);

    return () -> scheduled.cancel(false);
  } // end method schedule

  @Override
  public long monotonicNanos() {
    return System.nanoTime();
  } // end method monotonicNanos

  @Override
  public Instant now() {
    return Instant.now();
  } // end method now

  private synchronized ExecutorService workers() {
    if (workers == null) {
      workers = Executors.newCachedThreadPool(threads("worker"));
    }

    return workers;
  } // end method workers

  private synchronized ScheduledThreadPoolExecutor timer() {
    if (timer == null) {
      ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(1, threads("timer"));
      created.setRemoveOnCancelPolicy(true);
      created.setKeepAliveTime(60, TimeUnit.SECONDS);
      created.allowCoreThreadTimeOut(true);
      timer = created;
    }

    return timer;
  } // end method timer

  private ThreadFactory threads(String role) {
    AtomicInteger threadNumber = new AtomicInteger();

    return task -> {
      Thread thread =
          new Thread(
              task, "celeris-" + clientNumber + "-" + role + "-" + threadNumber.incrementAndGet());
      thread.setDaemon(true);

      return thread;
    };
  } // end method threads
} // end class ClientThreads

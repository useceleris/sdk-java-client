package com.useceleris.client;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/** Runs submitted tasks only when the test asks, on the test's own thread. */
final class ManualExecutor implements Executor {
  private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

  @Override
  public synchronized void execute(Runnable task) {
    tasks.add(task);
  } // end method execute

  /** Runs one task, and reports whether there was one. */
  boolean runOne() {
    Runnable task;

    synchronized (this) {
      task = tasks.poll();
    }

    if (task == null) {
      return false;
    }

    task.run();

    return true;
  } // end method runOne
} // end class ManualExecutor

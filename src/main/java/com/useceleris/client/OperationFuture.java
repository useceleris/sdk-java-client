package com.useceleris.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The future the SDK returns for connect, publish and presence queries. Cancelling it withdraws the
 * operation when the SDK still can, which completes it with a {@link
 * java.util.concurrent.CancellationException}. When it cannot, because a publish is already being
 * written or the operation already settled, the future completes with the actual outcome instead
 * and {@code cancel} returns false.
 *
 * <p>The SDK records its outcome under the channel's lock and completes the future afterwards, as
 * its own task on a worker thread, so a dependent stage that waits for another of the channel's
 * futures never holds up that future's completion. A future completed by anyone else, through
 * {@code orTimeout} or {@code complete}, abandons the operation the same way a cancel does.
 */
final class OperationFuture<T> extends CompletableFuture<T> {
  /** The channel's side of an operation: decides a cancellation under the channel's lock. */
  interface Operation {
    /**
     * Returns true when the operation was withdrawn, so nothing reached the server; false when its
     * outcome is recorded and the future completes with it.
     */
    boolean cancel();
  } // end interface Operation

  private final Executor executor;
  private volatile @Nullable Operation operation;
  private volatile boolean recorded;
  private volatile @Nullable T value;
  private volatile @Nullable Throwable failure;

  OperationFuture(Executor executor) {
    this.executor = executor;
    var unused = whenComplete((ignoredValue, ignoredFailure) -> abandonUnlessRecorded());
  } // end constructor OperationFuture

  void attach(Operation operation) {
    this.operation = operation;
  } // end method attach

  boolean isRecorded() {
    return recorded;
  } // end method isRecorded

  /**
   * Records the outcome and completes the future soon after. The caller holds the channel's lock.
   */
  void settle(@Nullable T settledValue, @Nullable Throwable settledFailure) {
    if (recorded) {
      return;
    }

    value = settledValue;
    failure = settledFailure;
    recorded = true;
    executor.execute(this::finish);
  } // end method settle

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    if (isDone()) {
      return false;
    }

    // An outcome recorded before any operation was attached, such as NOT_CONNECTED, stands.
    Operation current = operation;
    boolean withdrawn = !recorded && (current == null || current.cancel());

    if (withdrawn) {
      recorded = true;

      return super.cancel(mayInterruptIfRunning);
    }

    finish();

    return false;
  } // end method cancel

  private void finish() {
    Throwable settledFailure = failure;

    if (settledFailure == null) {
      complete(value);
    } else {
      completeExceptionally(settledFailure);
    }
  } // end method finish

  private void abandonUnlessRecorded() {
    Operation current = operation;

    if (!recorded && current != null) {
      current.cancel();
    }
  } // end method abandonUnlessRecorded
} // end class OperationFuture

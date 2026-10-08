package com.useceleris.client;

import java.util.concurrent.atomic.AtomicBoolean;

/** A registered listener. Closing it removes exactly that listener; closing again does nothing. */
public final class Registration implements AutoCloseable {
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Runnable removal;

  Registration(Runnable removal) {
    this.removal = removal;
  } // end constructor Registration

  /** Removes the listener. An event already being delivered to it may still arrive. */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      removal.run();
    }
  } // end method close
} // end class Registration

package com.useceleris.client;

import java.util.ArrayDeque;
import java.util.HashSet;

/**
 * Remembers the last deduplicationWindowSize delivered message ids, 1024 unless configured. It
 * survives reconnects, absorbing replay duplicates, and clears on connect (REV-01).
 */
final class DeduplicationWindow {
  private final int size;
  private final HashSet<String> identifiers = new HashSet<>();
  private final ArrayDeque<String> order = new ArrayDeque<>();

  DeduplicationWindow(int size) {
    this.size = size;
  } // end constructor DeduplicationWindow

  /** Returns false for an id already in the window; records a new one, evicting the oldest. */
  boolean recordIfNew(String identifier) {
    if (!identifiers.add(identifier)) {
      return false;
    }

    order.add(identifier);

    if (order.size() > size) {
      identifiers.remove(order.poll());
    }

    return true;
  } // end method recordIfNew

  void clear() {
    identifiers.clear();
    order.clear();
  } // end method clear
} // end class DeduplicationWindow

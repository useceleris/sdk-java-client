package com.useceleris.client;

import java.util.ArrayList;
import java.util.List;

/**
 * The listeners registered for one event. Guarded by the channel's lock; an entry's removed flag is
 * also read while events are delivered, so an entry removed before its turn is skipped.
 */
final class ListenerSet<L> {
  private final ArrayList<Entry<L>> entries = new ArrayList<>();

  static final class Entry<L> {
    final L listener;
    volatile boolean removed;

    Entry(L listener) {
      this.listener = listener;
    } // end constructor Entry
  } // end class Entry

  Entry<L> add(L listener) {
    Entry<L> entry = new Entry<>(listener);
    entries.add(entry);

    return entry;
  } // end method add

  void remove(Entry<L> entry) {
    entry.removed = true;
    entries.remove(entry);
  } // end method remove

  List<Entry<L>> snapshot() {
    return List.copyOf(entries);
  } // end method snapshot
} // end class ListenerSet

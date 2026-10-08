package com.useceleris.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * An in-memory peer with the JDK's rules: listener methods run one at a time and only while demand
 * is outstanding, one binary send and one ping may be outstanding at a time, and a send after the
 * output closed fails.
 */
final class FakeWebSocket implements WebSocket {
  final URI url;
  private final Listener listener;
  private final Executor executor;

  private final ArrayDeque<Runnable> incoming = new ArrayDeque<>();
  private final List<String> written = new ArrayList<>();
  private long demand;
  private boolean delivering;
  private boolean outputClosed;
  private boolean inputClosed;
  private boolean aborted;
  private boolean closeSent;
  private int pings;

  private @Nullable CompletableFuture<WebSocket> pendingSend;
  private boolean holdingWrites;
  private boolean failingWrites;
  private final ArrayDeque<Runnable> heldWrites = new ArrayDeque<>();
  private boolean answerClose = true;
  private boolean failingClose;
  private boolean answeringPings;
  private boolean failingPings;

  FakeWebSocket(URI url, Listener listener, Executor executor) {
    this.url = url;
    this.listener = listener;
    this.executor = executor;
  } // end constructor FakeWebSocket

  // ---- The peer's side ----

  /** Delivers a binary message from the server, once demand allows. */
  void receive(String frame) {
    receive(frame.getBytes(StandardCharsets.UTF_8));
  } // end method receive

  void receive(byte[] frame) {
    enqueue(() -> listener.onBinary(this, ByteBuffer.wrap(frame), true));
  } // end method receive

  /** Delivers a binary message split into parts. */
  void receiveInParts(byte[] frame, int partSize) {
    for (int start = 0; start < frame.length; start += partSize) {
      int end = Math.min(frame.length, start + partSize);
      byte[] part = java.util.Arrays.copyOfRange(frame, start, end);
      boolean last = end == frame.length;
      enqueue(() -> listener.onBinary(this, ByteBuffer.wrap(part), last));
    }
  } // end method receiveInParts

  void receiveText(String text) {
    enqueue(() -> listener.onText(this, CharBuffer.wrap(text), true));
  } // end method receiveText

  /** Delivers a text message split into two parts. */
  void receiveTextInParts(String first, String second) {
    enqueue(() -> listener.onText(this, CharBuffer.wrap(first), false));
    enqueue(() -> listener.onText(this, CharBuffer.wrap(second), true));
  } // end method receiveTextInParts

  /** The server starts the closing handshake, once demand allows. */
  void receiveClose() {
    enqueue(
        () -> {
          synchronized (this) {
            inputClosed = true;
          }

          listener.onClose(this, NORMAL_CLOSURE, "");
        });
  } // end method receiveClose

  void receivePing() {
    enqueue(() -> listener.onPing(this, ByteBuffer.allocate(0)));
  } // end method receivePing

  void receivePong() {
    enqueue(() -> listener.onPong(this, ByteBuffer.allocate(0)));
  } // end method receivePong

  /** The server closes the connection abnormally, as a dropped network does. */
  synchronized void drop() {
    if (inputClosed) {
      return;
    }

    inputClosed = true;
    outputClosed = true;
    failPendingSend();
    executor.execute(() -> listener.onError(this, new IOException("connection reset")));
  } // end method drop

  /** Makes sends wait until released; held sends complete in order. */
  synchronized void holdWrites() {
    holdingWrites = true;
  } // end method holdWrites

  void releaseWrites() {
    List<Runnable> released;

    synchronized (this) {
      holdingWrites = false;
      released = new ArrayList<>(heldWrites);
      heldWrites.clear();
    }

    released.forEach(Runnable::run);
  } // end method releaseWrites

  synchronized void failWrites() {
    failingWrites = true;
  } // end method failWrites

  /** Leaves the client's close unanswered, as a silent server does. */
  synchronized void ignoreClose() {
    answerClose = false;
  } // end method ignoreClose

  /** Makes the client's closing handshake fail to send, closing output as the JDK does. */
  synchronized void failClose() {
    failingClose = true;
  } // end method failClose

  /** Makes every ping the client sends fail to write. */
  synchronized void failPings() {
    failingPings = true;
  } // end method failPings

  /** Answers every ping the client sends with a pong, as a live server does. */
  synchronized void answerPings() {
    answeringPings = true;
  } // end method answerPings

  synchronized List<String> commands() {
    return List.copyOf(written);
  } // end method commands

  synchronized void clearCommands() {
    written.clear();
  } // end method clearCommands

  synchronized boolean isAborted() {
    return aborted;
  } // end method isAborted

  synchronized boolean isCloseSent() {
    return closeSent;
  } // end method isCloseSent

  synchronized int pingCount() {
    return pings;
  } // end method pingCount

  synchronized long demand() {
    return demand;
  } // end method demand

  // ---- The JDK's side ----

  @Override
  public synchronized CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
    throw new UnsupportedOperationException("the client sends binary only");
  } // end method sendText

  @Override
  public synchronized CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
    if (pendingSend != null && !pendingSend.isDone()) {
      throw new IllegalStateException("Send pending");
    }

    if (outputClosed) {
      return CompletableFuture.failedFuture(new IOException("Output closed"));
    }

    byte[] bytes = new byte[data.remaining()];
    data.get(bytes);
    CompletableFuture<WebSocket> sent = new CompletableFuture<>();
    pendingSend = sent;

    if (failingWrites) {
      sent.completeExceptionally(new IOException("synthetic write failure"));

      return sent;
    }

    Runnable write =
        () -> {
          synchronized (this) {
            if (outputClosed) {
              sent.completeExceptionally(new IOException("Output closed"));

              return;
            }

            written.add(new String(bytes, StandardCharsets.UTF_8));
          }

          sent.complete(this);
        };

    if (holdingWrites) {
      heldWrites.add(write);
    } else {
      written.add(new String(bytes, StandardCharsets.UTF_8));
      sent.complete(this);
    }

    return sent;
  } // end method sendBinary

  @Override
  public synchronized CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
    if (outputClosed) {
      return CompletableFuture.failedFuture(new IOException("Output closed"));
    }

    pings++;

    if (failingPings) {
      return CompletableFuture.failedFuture(new IOException("synthetic ping failure"));
    }

    if (answeringPings && !inputClosed) {
      enqueue(() -> listener.onPong(this, ByteBuffer.allocate(0)));
    }

    return CompletableFuture.completedFuture(this);
  } // end method sendPing

  @Override
  public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
    return CompletableFuture.completedFuture(this);
  } // end method sendPong

  @Override
  public synchronized CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
    if (outputClosed) {
      return CompletableFuture.failedFuture(new IOException("Output closed"));
    }

    if (failingClose) {
      outputClosed = true;

      return CompletableFuture.failedFuture(new IOException("broken pipe"));
    }

    outputClosed = true;
    closeSent = true;

    if (answerClose && !inputClosed) {
      inputClosed = true;
      enqueue(() -> listener.onClose(this, NORMAL_CLOSURE, ""));
    }

    return CompletableFuture.completedFuture(this);
  } // end method sendClose

  @Override
  public synchronized void request(long n) {
    demand = demand + n < 0 ? Long.MAX_VALUE : demand + n;
    scheduleDelivery();
  } // end method request

  @Override
  public String getSubprotocol() {
    return "";
  } // end method getSubprotocol

  @Override
  public synchronized boolean isOutputClosed() {
    return outputClosed;
  } // end method isOutputClosed

  @Override
  public synchronized boolean isInputClosed() {
    return inputClosed;
  } // end method isInputClosed

  @Override
  public synchronized void abort() {
    aborted = true;
    outputClosed = true;
    inputClosed = true;
    incoming.clear();
    failPendingSend();
  } // end method abort

  // ---- Delivery ----

  private synchronized void enqueue(Runnable delivery) {
    incoming.add(delivery);
    scheduleDelivery();
  } // end method enqueue

  private void scheduleDelivery() {
    if (delivering || demand == 0 || incoming.isEmpty() || aborted) {
      return;
    }

    delivering = true;
    Runnable delivery = incoming.poll();

    if (demand != Long.MAX_VALUE) {
      demand--;
    }

    executor.execute(
        () -> {
          delivery.run();

          synchronized (this) {
            delivering = false;
            scheduleDelivery();
          }
        });
  } // end method scheduleDelivery

  private void failPendingSend() {
    CompletableFuture<WebSocket> pending = pendingSend;

    if (pending != null && !pending.isDone()) {
      pending.completeExceptionally(new IOException("Output closed"));
    }

    for (Runnable held : heldWrites) {
      held.run();
    }

    heldWrites.clear();
  } // end method failPendingSend
} // end class FakeWebSocket

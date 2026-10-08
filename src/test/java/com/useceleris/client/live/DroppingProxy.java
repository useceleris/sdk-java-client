package com.useceleris.client.live;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forwards TCP connections to the realtime service, and fails them as networks do: it can cut every
 * open connection, refuse new ones, blackhole the open ones, which stop forwarding in both
 * directions while their sockets stay open, blackhole new ones, which stay open but never reach the
 * service, or stall upstream, reading nothing more that clients send so their socket buffers fill.
 */
final class DroppingProxy implements AutoCloseable {
  private static final int CONNECT_TIMEOUT_MILLISECONDS = 5_000;

  private static final int BUFFER_BYTES = 64 * 1024;

  private static final int STALL_CHECK_MILLISECONDS = 100;

  private final ServerSocket listener;
  private final InetSocketAddress target;
  private final Set<Link> links = ConcurrentHashMap.newKeySet();
  private final Object stallMonitor = new Object();
  private volatile boolean refusing;
  private volatile boolean blackholingNewConnections;
  private boolean stalled;

  private DroppingProxy(ServerSocket listener, InetSocketAddress target) {
    this.listener = listener;
    this.target = target;
  } // end constructor DroppingProxy

  /** Starts forwarding to the host and port of the WebSocket URL. */
  static DroppingProxy start(String websocketUrl) throws IOException {
    URI url = URI.create(websocketUrl);
    int port = url.getPort();

    if (port == -1) {
      port = "wss".equals(url.getScheme()) ? 443 : 80;
    }

    DroppingProxy proxy =
        new DroppingProxy(
            new ServerSocket(0, 50, InetAddress.getLoopbackAddress()),
            new InetSocketAddress(url.getHost(), port));
    startDaemon("live-proxy-accept", proxy::acceptConnections);

    return proxy;
  } // end method start

  /** The base URL that reaches the realtime service through this proxy. */
  String url() {
    return "ws://127.0.0.1:" + listener.getLocalPort();
  } // end method url

  /** While refusing, every new connection is closed as soon as it is accepted. */
  void refuseNewConnections(boolean refusing) {
    this.refusing = refusing;
  } // end method refuseNewConnections

  /**
   * While blackholing, every new connection stays open, discards what it reads and sends nothing.
   */
  void blackholeNewConnections(boolean blackholing) {
    blackholingNewConnections = blackholing;
  } // end method blackholeNewConnections

  /** While stalled, nothing more is read from any client, so what they send backs up. */
  void stallUpstream(boolean stalled) {
    synchronized (stallMonitor) {
      this.stalled = stalled;
      stallMonitor.notifyAll();
    }
  } // end method stallUpstream

  /**
   * Resets every open connection at once. A reset rather than an orderly close: the JDK's WebSocket
   * client loses an end of stream that arrives while no message is requested, and only the
   * heartbeat notices such a connection is gone, about thirty seconds later.
   */
  void cutOpenConnections() {
    for (Link link : links) {
      link.reset();
    }
  } // end method cutOpenConnections

  /**
   * Ends every open connection with an orderly close (FIN) and no WebSocket close frame, as a
   * server or middlebox that drops a connection cleanly does.
   */
  void finishOpenConnections() {
    for (Link link : links) {
      link.close();
    }
  } // end method finishOpenConnections

  /** Stops forwarding on every open connection without closing it; new connections still work. */
  void blackholeOpenConnections() {
    for (Link link : links) {
      link.blackholed = true;
    }
  } // end method blackholeOpenConnections

  @Override
  public void close() {
    closeQuietly(listener);
    stallUpstream(false);
    cutOpenConnections();
  } // end method close

  private void acceptConnections() {
    while (true) {
      Socket client;

      try {
        client = listener.accept();
      } catch (IOException closed) {
        return;
      }

      if (refusing) {
        closeQuietly(client);
      } else if (blackholingNewConnections) {
        startDaemon("live-proxy-blackhole", () -> discard(client));
      } else {
        startDaemon("live-proxy-link", () -> forward(client));
      }
    }
  } // end method acceptConnections

  /** Reads and drops everything the client sends until it closes or the proxy does. */
  private void discard(Socket client) {
    Link link = new Link(client, client);
    link.blackholed = true;
    links.add(link);
    link.pump(client, client);
    links.remove(link);
  } // end method discard

  private void forward(Socket client) {
    Socket upstream = new Socket();

    try {
      upstream.connect(target, CONNECT_TIMEOUT_MILLISECONDS);
      upstream.setTcpNoDelay(true);
      client.setTcpNoDelay(true);
    } catch (IOException failure) {
      closeQuietly(client);
      closeQuietly(upstream);

      return;
    }

    Link link = new Link(client, upstream);
    links.add(link);
    startDaemon("live-proxy-upstream", () -> link.pump(client, upstream));
    link.pump(upstream, client);
    links.remove(link);
  } // end method forward

  /**
   * Waits while upstream is stalled; false once the socket closed or the thread was interrupted.
   */
  private boolean awaitUnstalled(Socket from) {
    synchronized (stallMonitor) {
      try {
        while (stalled && !from.isClosed()) {
          stallMonitor.wait(STALL_CHECK_MILLISECONDS);
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();

        return false;
      }

      return !from.isClosed();
    }
  } // end method awaitUnstalled

  /**
   * One forwarded connection: the accepted socket and its upstream twin, or the accepted socket
   * twice when it is blackholed from the start.
   */
  private final class Link {
    private final Socket client;
    private final Socket upstream;
    volatile boolean blackholed;

    Link(Socket client, Socket upstream) {
      this.client = client;
      this.upstream = upstream;
    } // end constructor Link

    /**
     * Copies bytes until either side closes, discarding them while blackholed, and reading nothing
     * from the client while upstream is stalled.
     */
    void pump(Socket from, Socket to) {
      byte[] buffer = new byte[BUFFER_BYTES];
      boolean towardsUpstream = from == client && to != client;

      try {
        InputStream input = from.getInputStream();
        OutputStream output = to.getOutputStream();

        while (!towardsUpstream || awaitUnstalled(from)) {
          int read = input.read(buffer);

          if (read < 0) {
            break;
          }

          if (!blackholed) {
            output.write(buffer, 0, read);
            output.flush();
          }
        }
      } catch (IOException closed) {
        // Either side closed, or the link was cut: the connection is over.
      } finally {
        close();
      }
    } // end method pump

    void close() {
      closeQuietly(client);
      closeQuietly(upstream);
    } // end method close

    /** Closes both sockets with a reset (RST), which the peer sees at once. */
    void reset() {
      resetQuietly(client);
      resetQuietly(upstream);
    } // end method reset
  } // end class Link

  private static void startDaemon(String name, Runnable task) {
    Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    thread.start();
  } // end method startDaemon

  private static void resetQuietly(Socket socket) {
    try {
      socket.setSoLinger(true, 0);
    } catch (IOException alreadyClosed) {
      // A closed socket needs no reset.
    }

    closeQuietly(socket);
  } // end method resetQuietly

  private static void closeQuietly(Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException ignored) {
      // Already closed or broken; nothing is left to release.
    }
  } // end method closeQuietly
} // end class DroppingProxy

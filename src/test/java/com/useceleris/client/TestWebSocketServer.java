package com.useceleris.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * A minimal RFC 6455 server on loopback for exercising the JDK's real WebSocket client: one
 * connection at a time, frames written exactly as the test asks. Optionally TLS with the synthetic
 * loopback identity, which no default trust store trusts.
 */
final class TestWebSocketServer implements AutoCloseable {
  private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

  private final ServerSocket serverSocket;
  private final BlockingQueue<Peer> peers = new LinkedBlockingQueue<>();
  private volatile int refuseStatus;
  private final Thread acceptor;

  /** One accepted connection, after its handshake. */
  static final class Peer implements AutoCloseable {
    final Socket socket;
    final String requestTarget;
    private final InputStream input;
    private final OutputStream output;

    Peer(Socket socket, String requestTarget, InputStream input, OutputStream output) {
      this.socket = socket;
      this.requestTarget = requestTarget;
      this.input = input;
      this.output = output;
    } // end constructor Peer

    synchronized void sendBinary(byte[] payload) throws IOException {
      sendFrame(0x2, payload, true);
    } // end method sendBinary

    /** Sends the payload as a binary message split into frames of at most partSize bytes. */
    synchronized void sendFragmented(byte[] payload, int partSize) throws IOException {
      for (int start = 0; start < payload.length; start += partSize) {
        int end = Math.min(payload.length, start + partSize);
        byte[] part = java.util.Arrays.copyOfRange(payload, start, end);
        sendFrame(start == 0 ? 0x2 : 0x0, part, end == payload.length);
      }
    } // end method sendFragmented

    synchronized void sendText(String text) throws IOException {
      sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8), true);
    } // end method sendText

    synchronized void sendPing() throws IOException {
      sendFrame(0x9, new byte[] {1, 2, 3}, true);
    } // end method sendPing

    synchronized void sendClose() throws IOException {
      sendFrame(0x8, new byte[] {0x03, (byte) 0xE8}, true);
    } // end method sendClose

    private void sendFrame(int opcode, byte[] payload, boolean last) throws IOException {
      ByteArrayOutputStream frame = new ByteArrayOutputStream();
      frame.write((last ? 0x80 : 0) | opcode);

      if (payload.length < 126) {
        frame.write(payload.length);
      } else if (payload.length < 65536) {
        frame.write(126);
        frame.write(payload.length >> 8);
        frame.write(payload.length);
      } else {
        frame.write(127);

        for (int shift = 56; shift >= 0; shift -= 8) {
          frame.write((int) ((long) payload.length >> shift));
        }
      }

      frame.write(payload);
      output.write(frame.toByteArray());
      output.flush();
    } // end method sendFrame

    /** Reads the next frame the client sent, unmasked. Returns its opcode and payload. */
    Frame readFrame() throws IOException {
      int first = readByte();
      int second = readByte();
      long length = second & 0x7F;

      if (length == 126) {
        length = (readByte() << 8) | readByte();
      } else if (length == 127) {
        length = 0;

        for (int index = 0; index < 8; index++) {
          length = (length << 8) | readByte();
        }
      }

      byte[] mask = input.readNBytes(4);
      byte[] payload = input.readNBytes((int) length);

      for (int index = 0; index < payload.length; index++) {
        payload[index] ^= mask[index % 4];
      }

      return new Frame(first & 0x0F, payload);
    } // end method readFrame

    /** Reads frames until a data frame arrives, skipping control frames. */
    byte[] readBinary() throws IOException {
      while (true) {
        Frame frame = readFrame();

        if (frame.opcode() == 0x2) {
          return frame.payload();
        }
      }
    } // end method readBinary

    /** Answers the client's close from a background reader, discarding everything else. */
    void answerCloseInBackground() {
      Thread reader =
          new Thread(
              () -> {
                try {
                  while (readFrame().opcode() != 0x8) {
                    // Discard until the client closes.
                  }

                  sendClose();
                } catch (IOException closed) {
                  // The connection ended first.
                }
              },
              "test-websocket-close");
      reader.setDaemon(true);
      reader.start();
    } // end method answerCloseInBackground

    private int readByte() throws IOException {
      int value = input.read();

      if (value < 0) {
        throw new IOException("client closed the connection");
      }

      return value;
    } // end method readByte

    @Override
    public void close() throws IOException {
      socket.close();
    } // end method close
  } // end class Peer

  record Frame(int opcode, byte[] payload) {}

  private TestWebSocketServer(ServerSocket serverSocket) {
    this.serverSocket = serverSocket;
    this.acceptor = new Thread(this::acceptLoop, "test-websocket-server");
    acceptor.setDaemon(true);
    acceptor.start();
  } // end constructor TestWebSocketServer

  static TestWebSocketServer plain() throws IOException {
    return new TestWebSocketServer(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
  } // end method plain

  static TestWebSocketServer tls() throws Exception {
    SSLContext context = SSLContext.getInstance("TLS");
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(syntheticIdentity(), new char[0]);
    context.init(keys.getKeyManagers(), null, null);

    return new TestWebSocketServer(
        context
            .getServerSocketFactory()
            .createServerSocket(0, 50, InetAddress.getLoopbackAddress()));
  } // end method tls

  int port() {
    return serverSocket.getLocalPort();
  } // end method port

  /** Answers every following handshake with this HTTP status instead of upgrading. */
  void refuseWith(int status) {
    refuseStatus = status;
  } // end method refuseWith

  /** Waits for the next upgraded connection. */
  Peer accept() throws InterruptedException {
    Peer peer = peers.poll(10, TimeUnit.SECONDS);

    if (peer == null) {
      throw new AssertionError("no connection arrived");
    }

    return peer;
  } // end method accept

  private void acceptLoop() {
    while (!serverSocket.isClosed()) {
      try {
        Socket socket = serverSocket.accept();
        handshake(socket);
      } catch (IOException closed) {
        return;
      }
    }
  } // end method acceptLoop

  private void handshake(Socket socket) throws IOException {
    InputStream input = socket.getInputStream();
    OutputStream output = socket.getOutputStream();
    String requestTarget = "";
    String key = "";
    String line;
    boolean first = true;

    while (!(line = readLine(input)).isEmpty()) {
      if (first) {
        requestTarget = line.split(" ")[1];
        first = false;
      } else if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:")) {
        key = line.substring(line.indexOf(':') + 1).trim();
      }
    }

    if (refuseStatus != 0) {
      output.write(
          ("HTTP/1.1 "
                  + refuseStatus
                  + " Refused\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      output.flush();
      socket.close();

      return;
    }

    String accept;

    try {
      accept =
          Base64.getEncoder()
              .encodeToString(
                  MessageDigest.getInstance("SHA-1")
                      .digest((key + ACCEPT_GUID).getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }

    output.write(
        ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: "
                + accept
                + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    output.flush();
    peers.add(new Peer(socket, requestTarget, input, output));
  } // end method handshake

  private static String readLine(InputStream input) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    int value;

    while ((value = input.read()) >= 0 && value != '\n') {
      if (value != '\r') {
        line.write(value);
      }
    }

    return line.toString(StandardCharsets.US_ASCII);
  } // end method readLine

  private static KeyStore syntheticIdentity() throws Exception {
    String certificatePem = resource("transport/certificate.pem").replaceAll("(?m)^#.*\\R", "");
    String keyPem = resource("transport/private-key.pem").replaceAll("(?m)^#.*\\R", "");
    Certificate certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(
                new java.io.ByteArrayInputStream(
                    certificatePem.getBytes(StandardCharsets.US_ASCII)));
    String keyBase64 =
        keyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    java.security.PrivateKey key =
        KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyBase64)));
    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    store.setKeyEntry("loopback", key, new char[0], new Certificate[] {certificate});

    return store;
  } // end method syntheticIdentity

  private static String resource(String name) throws IOException {
    try (InputStream stream =
        TestWebSocketServer.class.getClassLoader().getResourceAsStream(name)) {
      if (stream == null) {
        throw new IOException("missing test resource " + name);
      }

      return new String(stream.readAllBytes(), StandardCharsets.US_ASCII);
    }
  } // end method resource

  @Override
  public void close() throws IOException {
    serverSocket.close();
  } // end method close
} // end class TestWebSocketServer

/**
 * Realtime client for Celeris channels: connection lifecycle with automatic recovery, segment
 * messaging, and presence. Start with {@link com.useceleris.client.CelerisClient}.
 */
module com.useceleris.client {
  requires java.net.http;

  exports com.useceleris.client;
}

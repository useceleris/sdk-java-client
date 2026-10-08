package com.useceleris.client;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Dials with the JDK's WebSocket client over the client's own {@link HttpClient}, created on first
 * use. Its TLS context is built from the default trust store, so an application replacing the JVM's
 * default SSL context cannot weaken this connection (SEC-02). Redirects are never followed, and the
 * JVM's default proxy selector applies.
 */
final class WebSocketDialer implements Dialer {
  private final Executor executor;
  private @Nullable HttpClient httpClient;

  WebSocketDialer(Executor executor) {
    this.executor = executor;
  } // end constructor WebSocketDialer

  @Override
  public CompletableFuture<WebSocket> dial(URI url, Duration timeout, WebSocket.Listener listener) {
    return httpClient().newWebSocketBuilder().connectTimeout(timeout).buildAsync(url, listener);
  } // end method dial

  private synchronized HttpClient httpClient() {
    if (httpClient == null) {
      HttpClient.Builder builder =
          HttpClient.newBuilder()
              .executor(executor)
              .sslContext(trustingDefaultRoots())
              .followRedirects(HttpClient.Redirect.NEVER);
      ProxySelector proxySelector = ProxySelector.getDefault();

      if (proxySelector != null) {
        builder.proxy(proxySelector);
      }

      httpClient = builder.build();
    }

    return httpClient;
  } // end method httpClient

  private static SSLContext trustingDefaultRoots() {
    try {
      TrustManagerFactory trust =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init((KeyStore) null);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trust.getTrustManagers(), null);

      return context;
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("The JVM provides no TLS implementation", failure);
    }
  } // end method trustingDefaultRoots
} // end class WebSocketDialer

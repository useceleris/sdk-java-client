package com.useceleris.client;

import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * The one transport seam: opens a WebSocket to a URL, which carries the credentials. Tests
 * substitute an in-memory peer.
 */
interface Dialer {
  CompletableFuture<WebSocket> dial(URI url, Duration timeout, WebSocket.Listener listener);
} // end interface Dialer

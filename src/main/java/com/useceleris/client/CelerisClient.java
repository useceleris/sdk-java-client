package com.useceleris.client;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Creates channels. It holds configuration and the client's daemon threads, which start when first
 * needed, never when a class loads or the client is created; creating it does no network work. It
 * is safe for concurrent use; create one per application.
 *
 * <pre>{@code
 * CelerisClient client = CelerisClient.create(ClientOptions.builder(credentialProvider).build());
 * Channel channel = client.channel("room-42");
 * }</pre>
 */
public final class CelerisClient {
  final CredentialProvider credentialProvider;
  final URI baseUrl;
  final Duration connectTimeout;
  final Duration reconnectTimeout;
  final Duration presenceQueryTimeout;
  final int publishQueueSize;
  final int deduplicationWindowSize;
  final int maximumReconnectAttempts;
  final Dialer dialer;
  final Timers timers;
  final Executor executor;
  final DoubleSupplier random;

  CelerisClient(
      ClientOptions options,
      Dialer dialer,
      Timers timers,
      Executor executor,
      DoubleSupplier random) {
    this.credentialProvider = options.credentialProvider;
    this.baseUrl = options.baseUrl;
    this.connectTimeout = options.connectTimeout;
    this.reconnectTimeout = options.reconnectTimeout;
    this.presenceQueryTimeout = options.presenceQueryTimeout;
    this.publishQueueSize = options.publishQueueSize;
    this.deduplicationWindowSize = options.deduplicationWindowSize;
    this.maximumReconnectAttempts = options.maximumReconnectAttempts;
    this.dialer = dialer;
    this.timers = timers;
    this.executor = executor;
    this.random = random;
  } // end constructor CelerisClient

  /**
   * Creates a client.
   *
   * @param options the validated options
   * @return the client
   */
  public static CelerisClient create(ClientOptions options) {
    Objects.requireNonNull(options, "options");
    ClientThreads threads = new ClientThreads();

    return new CelerisClient(
        options,
        new WebSocketDialer(threads),
        threads,
        threads,
        () -> ThreadLocalRandom.current().nextDouble());
  } // end method create

  /**
   * Returns a handle for the channel with the given reference. It does no network work; each call
   * returns a new channel with its own connection.
   *
   * @param reference 1 to 255 ASCII letters, digits, hyphens or underscores
   * @return the channel
   * @throws CelerisException with {@link ErrorCode#CONFIGURATION} for an invalid reference
   */
  public Channel channel(String reference) {
    Objects.requireNonNull(reference, "reference");
    List<String> rules = Identifiers.channelReferenceRules(reference);

    if (!rules.isEmpty()) {
      throw Identifiers.configurationError(
          "channel reference",
          rules.stream().map(rule -> Identifiers.failure(null, rule)).toList());
    }

    return new Channel(this, reference);
  } // end method channel
} // end class CelerisClient

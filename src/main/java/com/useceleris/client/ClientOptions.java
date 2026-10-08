package com.useceleris.client;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configures a {@link CelerisClient}. Only the credential provider is required.
 *
 * <pre>{@code
 * ClientOptions options = ClientOptions.builder(credentialProvider).build();
 * }</pre>
 */
public final class ClientOptions {
  final CredentialProvider credentialProvider;
  final URI baseUrl;
  final Duration connectTimeout;
  final Duration reconnectTimeout;
  final Duration presenceQueryTimeout;
  final int publishQueueSize;
  final int deduplicationWindowSize;
  final int maximumReconnectAttempts;

  private ClientOptions(Builder builder, URI baseUrl) {
    this.credentialProvider = builder.credentialProvider;
    this.baseUrl = baseUrl;
    this.connectTimeout = builder.connectTimeout;
    this.reconnectTimeout =
        builder.reconnectTimeout == null ? builder.connectTimeout : builder.reconnectTimeout;
    this.presenceQueryTimeout = builder.presenceQueryTimeout;
    this.publishQueueSize = builder.publishQueueSize;
    this.deduplicationWindowSize = builder.deduplicationWindowSize;
    this.maximumReconnectAttempts = builder.maximumReconnectAttempts;
  } // end constructor ClientOptions

  /**
   * Starts options with the credential provider.
   *
   * @param credentialProvider fetches fresh credentials for every connection attempt
   * @return a builder
   */
  public static Builder builder(CredentialProvider credentialProvider) {
    return new Builder(credentialProvider);
  } // end method builder

  /** Builds {@link ClientOptions}. */
  public static final class Builder {
    private final CredentialProvider credentialProvider;
    private String baseUrl = Constants.DEFAULT_BASE_URL;
    private boolean allowInsecureLoopback;
    private Duration connectTimeout = Constants.DEFAULT_CONNECT_TIMEOUT;
    private @Nullable Duration reconnectTimeout;
    private Duration presenceQueryTimeout = Constants.DEFAULT_PRESENCE_QUERY_TIMEOUT;
    private int publishQueueSize = Constants.MAXIMUM_PENDING_COMMANDS;
    private int deduplicationWindowSize = Constants.DEDUPLICATION_WINDOW_SIZE;
    private int maximumReconnectAttempts = Constants.DEFAULT_MAXIMUM_RECONNECT_ATTEMPTS;

    private Builder(CredentialProvider credentialProvider) {
      this.credentialProvider = credentialProvider;
    } // end constructor Builder

    /**
     * Overrides the production endpoint. Set it only for local, CI or self-hosted targets.
     *
     * @param baseUrl a wss:// URL, or ws:// for a loopback host with {@link
     *     #allowInsecureLoopback(boolean)}
     * @return this builder
     */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;

      return this;
    } // end method baseUrl

    /**
     * Permits a ws:// base URL for a loopback host.
     *
     * @param allowInsecureLoopback whether to permit it
     * @return this builder
     */
    public Builder allowInsecureLoopback(boolean allowInsecureLoopback) {
      this.allowInsecureLoopback = allowInsecureLoopback;

      return this;
    } // end method allowInsecureLoopback

    /**
     * Bounds credential acquisition and the handshake together. Defaults to 15 seconds.
     *
     * @param connectTimeout from one millisecond to 15 minutes
     * @return this builder
     */
    public Builder connectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout;

      return this;
    } // end method connectTimeout

    /**
     * Bounds credential acquisition and the handshake together for each reconnect attempt. Unset,
     * it defaults to the connect timeout; the initial connect always uses {@link
     * #connectTimeout(Duration)}.
     *
     * @param reconnectTimeout from one millisecond to 15 minutes
     * @return this builder
     */
    public Builder reconnectTimeout(Duration reconnectTimeout) {
      this.reconnectTimeout = reconnectTimeout;

      return this;
    } // end method reconnectTimeout

    /**
     * Bounds each presence query. Defaults to 10 seconds.
     *
     * @param presenceQueryTimeout from one millisecond to 15 minutes
     * @return this builder
     */
    public Builder presenceQueryTimeout(Duration presenceQueryTimeout) {
      this.presenceQueryTimeout = presenceQueryTimeout;

      return this;
    } // end method presenceQueryTimeout

    /**
     * Bounds how many publishes may wait for room in the writer before another fails with {@link
     * ErrorCode#BACKPRESSURE}. Defaults to 64.
     *
     * @param publishQueueSize at least 1
     * @return this builder
     */
    public Builder publishQueueSize(int publishQueueSize) {
      this.publishQueueSize = publishQueueSize;

      return this;
    } // end method publishQueueSize

    /**
     * Bounds how many delivered message ids each channel remembers to absorb duplicates. Defaults
     * to 1024.
     *
     * @param deduplicationWindowSize at least 1
     * @return this builder
     */
    public Builder deduplicationWindowSize(int deduplicationWindowSize) {
      this.deduplicationWindowSize = deduplicationWindowSize;

      return this;
    } // end method deduplicationWindowSize

    /**
     * Bounds the failed reconnect attempts after a connection drops: once that many attempts in a
     * row fail, the error is reported and the channel becomes {@link ChannelState#FAILED}. The
     * count starts again when a connection had stayed up for 60 seconds. Defaults to 10.
     *
     * @param maximumReconnectAttempts from 1 to 100
     * @return this builder
     */
    public Builder maximumReconnectAttempts(int maximumReconnectAttempts) {
      this.maximumReconnectAttempts = maximumReconnectAttempts;

      return this;
    } // end method maximumReconnectAttempts

    /**
     * Validates and builds the options.
     *
     * @return the options
     * @throws CelerisException with {@link ErrorCode#CONFIGURATION} for a missing provider, a
     *     timeout under a millisecond or over 15 minutes, a queue or window size under 1, a maximum
     *     of reconnect attempts outside 1 to 100, or a base URL that is not wss://, or ws:// for a
     *     loopback host when allowed
     */
    public ClientOptions build() {
      List<String> failures = new ArrayList<>();

      if (credentialProvider == null) {
        failures.add(Identifiers.failure("credentialProvider", "Required"));
      }

      if (baseUrl == null) {
        failures.add(Identifiers.failure("baseUrl", "Required"));
      }

      addTimeoutFailure(failures, "connectTimeout", connectTimeout);

      if (reconnectTimeout != null) {
        addTimeoutFailure(failures, "reconnectTimeout", reconnectTimeout);
      }

      addTimeoutFailure(failures, "presenceQueryTimeout", presenceQueryTimeout);
      addSizeFailure(failures, "publishQueueSize", publishQueueSize);
      addSizeFailure(failures, "deduplicationWindowSize", deduplicationWindowSize);
      addSizeFailure(failures, "maximumReconnectAttempts", maximumReconnectAttempts);

      if (maximumReconnectAttempts > Constants.MAXIMUM_RECONNECT_ATTEMPTS_CEILING) {
        failures.add(
            Identifiers.failure(
                "maximumReconnectAttempts",
                "Must be at most " + Constants.MAXIMUM_RECONNECT_ATTEMPTS_CEILING));
      }

      if (!failures.isEmpty()) {
        throw Identifiers.configurationError("client options", failures);
      }

      return new ClientOptions(this, ConnectionUrl.validateBaseUrl(baseUrl, allowInsecureLoopback));
    } // end method build

    private static void addTimeoutFailure(
        List<String> failures, String field, @Nullable Duration timeout) {
      if (timeout == null) {
        failures.add(Identifiers.failure(field, "Required"));
      } else if (timeout.compareTo(Constants.MINIMUM_TIMEOUT) < 0) {
        failures.add(Identifiers.failure(field, "Must be at least 1 ms"));
      } else if (timeout.compareTo(Constants.MAXIMUM_TIMEOUT) > 0) {
        failures.add(
            Identifiers.failure(
                field, "Must be at most " + Constants.MAXIMUM_TIMEOUT.toMinutes() + " minutes"));
      }
    } // end method addTimeoutFailure

    private static void addSizeFailure(List<String> failures, String field, int size) {
      if (size < 1) {
        failures.add(Identifiers.failure(field, "Must be at least 1"));
      }
    } // end method addSizeFailure
  } // end class Builder
} // end class ClientOptions

package com.useceleris.client;

/**
 * The kind of failure the SDK itself detected. The codes are a closed set; each carries the name
 * every Celeris SDK uses for it.
 *
 * <p>There is no authentication code: a handshake the server refuses for its credentials cannot be
 * told apart from a network failure, so it is reported as {@link #TRANSPORT}.
 */
public enum ErrorCode {
  /** Invalid input: options, identifiers, payloads, or credentials a provider returned. */
  CONFIGURATION("Configuration"),

  /** A connection attempt or presence query ran past its deadline. */
  TIMEOUT("Timeout"),

  /** An operation cancelled by {@link Channel#close()}, or a connection attempt abandoned. */
  CANCELLED("Cancelled"),

  /** The credential provider failed, the handshake was refused, or the socket broke. */
  TRANSPORT("Transport"),

  /** The operation needs a connection the channel does not have. */
  NOT_CONNECTED("NotConnected"),

  /**
   * The publish queue ({@code publishQueueSize} publishes, 64 by default) is already full; or, for
   * a presence query, the writer is full or sending is paused after a rate limit.
   */
  BACKPRESSURE("Backpressure"),

  /** A second connect or presence query while the first is still running. */
  OPERATION_IN_PROGRESS("OperationInProgress"),

  /** A publish that may or may not have left the socket. It is never resent automatically. */
  DELIVERY_UNKNOWN("DeliveryUnknown"),

  /** A server message could not be decoded. It is dropped and the connection stays up. */
  PROTOCOL("ProtocolError");

  private final String code;

  ErrorCode(String code) {
    this.code = code;
  } // end constructor ErrorCode

  /**
   * Returns the code's name as every Celeris SDK spells it, such as {@code "NotConnected"}.
   *
   * @return the cross-SDK name of this code
   */
  public String code() {
    return code;
  } // end method code
} // end enum ErrorCode

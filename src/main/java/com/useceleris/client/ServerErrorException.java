package com.useceleris.client;

import java.util.Optional;

/**
 * An error frame the server sent, every field exactly as sent (ERR-01). The server keeps the
 * connection open after sending one.
 *
 * <p>It carries no {@link ErrorCode}: it is shaped differently from the SDK's own errors. Its type
 * set stays open, so a type a newer server adds still reaches you unchanged.
 */
public final class ServerErrorException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** The server could not parse a command. */
  public static final String PARSER_ERROR = "ParserError";

  /** The server failed to send. */
  public static final String SEND_ERROR = "SendError";

  /** The credentials do not permit the command, such as reading or writing a segment. */
  public static final String PERMISSION_DENIED_ERROR = "PermissionDeniedError";

  /** A per-second, per-hour, per-month or per-connection message limit was exceeded. */
  public static final String RATE_LIMIT_ERROR = "RateLimitError";

  /** A publish exceeded the plan's payload cap. */
  public static final String MESSAGE_SIZE_LIMIT_ERROR = "MessageSizeLimitError";

  /** A fault inside the server, such as a failed presence read. */
  public static final String INTERNAL_ERROR = "InternalError";

  /** The error type exactly as sent. */
  private final String type;

  /** The command the error answers, or null when the server named none. */
  private final @Nullable String subType;

  // Resources are strings, integers and lists of them; serialization keeps only the message.
  private final transient @Nullable Object resource;

  ServerErrorException(
      String type, @Nullable String subType, String message, @Nullable Object resource) {
    super(message, null, false, false);
    this.type = type;
    this.subType = subType;
    this.resource = resource;
  } // end constructor ServerErrorException

  /**
   * Returns the kind of error, such as {@link #PERMISSION_DENIED_ERROR}.
   *
   * @return the error type exactly as sent
   */
  public String type() {
    return type;
  } // end method type

  /**
   * Returns the command the error answers, such as {@code "SUB"} or {@code "PRES_LIST"}.
   *
   * @return the sub type, or empty when the server named none
   */
  public Optional<String> subType() {
    return Optional.ofNullable(subType);
  } // end method subType

  /**
   * Returns whatever the type and sub type define the error to carry, such as the segment a denial
   * refers to: a {@link String}, an {@link Integer}, a {@link Long}, or an unmodifiable {@link
   * java.util.List} of these.
   *
   * @return the resource, or empty when the server sent null
   */
  public Optional<Object> resource() {
    return Optional.ofNullable(resource);
  } // end method resource
} // end class ServerErrorException

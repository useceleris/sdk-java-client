package com.useceleris.client;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * A failure the SDK detected. Its message names what failed and the rule or limit it broke; it
 * never carries input values, credentials, or bytes the server sent, and it never wraps a cause.
 *
 * <p>Errors the server sends are {@link ServerErrorException}s instead.
 */
public final class CelerisException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** What kind of failure this is. */
  private final ErrorCode code;

  /** The field of a server message that failed to decode, or null. */
  private final @Nullable String field;

  /** The byte offset of that field; meaningful only with a field. */
  private final int offset;

  /**
   * Creates an exception with the given code and message.
   *
   * @param code what kind of failure this is
   * @param message what failed and the rule it broke, without input values
   */
  public CelerisException(ErrorCode code, String message) {
    this(code, message, null, -1);
  } // end constructor CelerisException

  private CelerisException(ErrorCode code, String message, @Nullable String field, int offset) {
    super(message, null, false, false);
    this.code = code;
    this.field = field;
    this.offset = offset;
  } // end constructor CelerisException

  static CelerisException protocol(String reason, String field, int offset) {
    return new CelerisException(
        ErrorCode.PROTOCOL,
        reason + " Field: " + field + ", byte offset " + offset + ".",
        field,
        offset);
  } // end method protocol

  /**
   * Returns what kind of failure this is.
   *
   * @return the error code
   */
  public ErrorCode code() {
    return code;
  } // end method code

  /**
   * Returns the field of the server message that failed to decode: a name this package chose, never
   * received bytes. Present only for {@link ErrorCode#PROTOCOL}.
   *
   * @return the field name, or empty
   */
  public Optional<String> field() {
    return Optional.ofNullable(field);
  } // end method field

  /**
   * Returns the zero-based byte offset of the field that failed to decode. Present only for {@link
   * ErrorCode#PROTOCOL}.
   *
   * @return the byte offset, or empty
   */
  public OptionalInt offset() {
    return field == null ? OptionalInt.empty() : OptionalInt.of(offset);
  } // end method offset
} // end class CelerisException

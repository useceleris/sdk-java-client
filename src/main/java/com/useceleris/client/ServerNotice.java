package com.useceleris.client;

import java.util.Arrays;

/**
 * A raw notice from the server: greetings, subscription acknowledgements and refusals, as
 * human-readable prose. It names no segment and no command, so nothing may gate on its content.
 *
 * @param timestamp Unix milliseconds
 * @param payload the notice's bytes; the listener's own copy
 */
@SuppressWarnings("ArrayRecordComponent") // equals and hashCode compare the payload's contents
public record ServerNotice(long timestamp, byte[] payload) {
  /**
   * Compares timestamps and payload contents.
   *
   * @param other the object to compare with
   * @return whether both carry the same timestamp and bytes
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof ServerNotice notice
        && notice.timestamp == timestamp
        && Arrays.equals(notice.payload, payload);
  } // end method equals

  /**
   * Hashes the timestamp and payload contents.
   *
   * @return the hash code
   */
  @Override
  public int hashCode() {
    return 31 * Long.hashCode(timestamp) + Arrays.hashCode(payload);
  } // end method hashCode

  /**
   * Describes the notice by timestamp and payload size, never its contents.
   *
   * @return a description of this notice
   */
  @Override
  public String toString() {
    return "ServerNotice[timestamp=" + timestamp + ", payload=" + payload.length + " bytes]";
  } // end method toString
} // end record ServerNotice

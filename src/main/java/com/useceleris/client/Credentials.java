package com.useceleris.client;

/**
 * Authorizes one connection attempt. Both values are opaque: the SDK never parses, re-serializes or
 * generates them. A trusted server signs them; a client only passes them on.
 *
 * <p>The record serializes as {@code {"payload": "...", "signature": "..."}} with Jackson or Gson,
 * the shape every Celeris credential endpoint returns. Its {@code toString} never shows either
 * value.
 *
 * @param payload the signed token payload
 * @param signature the token's signature
 */
public record Credentials(String payload, String signature) {
  /**
   * Returns a fixed redacted text.
   *
   * @return {@code "Credentials[redacted]"}
   */
  @Override
  public String toString() {
    return "Credentials[redacted]";
  } // end method toString
} // end record Credentials

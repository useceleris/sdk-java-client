package com.useceleris.client;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Gives every publish an id, so a resent copy is recognisable and receivers drop it (RESEND-01).
 */
final class MessageIds {
  private MessageIds() {}

  /** Created on first use, never at class load. */
  private static final class Holder {
    static final SecureRandom RANDOM = new SecureRandom();
  } // end class Holder

  static String generate() {
    byte[] random = new byte[Constants.MESSAGE_ID_RANDOM_BYTES];
    Holder.RANDOM.nextBytes(random);

    return HexFormat.of().formatHex(random);
  } // end method generate
} // end class MessageIds

package com.useceleris.client;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Gives every publish an id, so a resent copy is recognisable and receivers drop it (RESEND-01). A
 * generated id names the SDK that made it, then holds 16 random bytes as hex: {@code
 * msg__java_v0.1.0__0123456789abcdef0123456789abcdef}. The double underscores keep it apart from
 * the server's own {@code msg_{node}_} ids.
 */
final class MessageIds {
  private MessageIds() {}

  /** Created on first use, never at class load. */
  private static final class Holder {
    static final SecureRandom RANDOM = new SecureRandom();
  } // end class Holder

  private static final String PREFIX = prefix(Constants.SDK_LANGUAGE, Constants.SDK_VERSION);

  static String generate() {
    byte[] random = new byte[Constants.MESSAGE_ID_RANDOM_BYTES];
    Holder.RANDOM.nextBytes(random);

    return PREFIX + HexFormat.of().formatHex(random);
  } // end method generate

  /**
   * "msg__", the language, "_v", the version, "__". An id holds only letters, digits, dots and
   * underscores, so any other character of the version, such as the hyphen of a pre-release,
   * becomes an underscore.
   */
  static String prefix(String language, String version) {
    return "msg__" + language + "_v" + version.replaceAll("[^A-Za-z0-9.]", "_") + "__";
  } // end method prefix
} // end class MessageIds

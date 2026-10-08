package com.useceleris.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Validation shared by every identifier the client sends: rules are named, values never are
 * (D-003).
 */
final class Identifiers {
  private Identifiers() {}

  static final String EMPTY = "Must not be empty";

  static final String CONTROL_OR_SURROGATE =
      "Must not contain CR, LF or unpaired UTF-16 surrogates";

  static final String SURROGATE = "Must not contain unpaired UTF-16 surrogates";

  /** Returns the rule a segment id, message id or request id breaks, or null when it is valid. */
  static @Nullable String identifierRule(String identifier) {
    if (identifier.isEmpty()) {
      return EMPTY;
    }

    if (identifier.indexOf('\r') >= 0
        || identifier.indexOf('\n') >= 0
        || hasUnpairedSurrogate(identifier)) {
      return CONTROL_OR_SURROGATE;
    }

    return null;
  } // end method identifierRule

  /** Returns the rule a credential value breaks, or null when it is valid. */
  static @Nullable String credentialValueRule(String value) {
    if (value.isEmpty()) {
      return EMPTY;
    }

    return hasUnpairedSurrogate(value) ? SURROGATE : null;
  } // end method credentialValueRule

  /** Returns every rule a channel reference breaks, empty when it is valid. */
  static List<String> channelReferenceRules(String reference) {
    if (reference.isEmpty()) {
      return List.of(EMPTY);
    }

    List<String> rules = new ArrayList<>();

    if (reference.length() > Constants.MAXIMUM_CHANNEL_REFERENCE_LENGTH) {
      rules.add("Must be at most 255 characters");
    }

    for (int index = 0; index < reference.length(); index++) {
      char character = reference.charAt(index);
      boolean letter =
          (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
      boolean digit = character >= '0' && character <= '9';

      if (!letter && !digit && character != '-' && character != '_') {
        rules.add("Must contain only ASCII letters, digits, hyphens (-) or underscores (_)");

        break;
      }
    }

    return rules;
  } // end method channelReferenceRules

  /** Reports whether text holds a high surrogate not followed by a low one, or a lone low one. */
  static boolean hasUnpairedSurrogate(String text) {
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);

      if (Character.isHighSurrogate(character)) {
        if (index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1))) {
          index++;

          continue;
        }

        return true;
      }

      if (Character.isLowSurrogate(character)) {
        return true;
      }
    }

    return false;
  } // end method hasUnpairedSurrogate

  /** Describes one failed field; a null path describes the value as a whole. */
  static String failure(@Nullable String path, String rule) {
    return path == null ? rule + "." : path + ": " + rule + ".";
  } // end method failure

  /**
   * Names every field that failed and the rule it broke, never the value: "Invalid client options.
   * credentialProvider: Required."
   */
  static CelerisException configurationError(String subject, List<String> failures) {
    return new CelerisException(
        ErrorCode.CONFIGURATION, "Invalid " + subject + ". " + String.join(" ", failures));
  } // end method configurationError
} // end class Identifiers

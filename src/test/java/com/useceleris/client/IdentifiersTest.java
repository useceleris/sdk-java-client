package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class IdentifiersTest {
  // Rule texts hand-copied from the reference's zod schemas.
  private static final String EMPTY = "Must not be empty";

  private static final String CONTROL_OR_SURROGATE =
      "Must not contain CR, LF or unpaired UTF-16 surrogates";

  private static final String SURROGATE = "Must not contain unpaired UTF-16 surrogates";

  private static final String TOO_LONG = "Must be at most 255 characters";

  private static final String CHARACTERS =
      "Must contain only ASCII letters, digits, hyphens (-) or underscores (_)";

  static Stream<String> invalidIdentifierVectors() {
    return CodecVectors.invalidIdentifierVectors().stream();
  } // end method invalidIdentifierVectors

  /** Spells out characters a reader could not tell apart, or see, in a literal. */
  private static String codePoint(int value) {
    return new String(Character.toChars(value));
  } // end method codePoint

  static Stream<String> wellFormedIdentifiers() {
    return Stream.of(
        "chat",
        "😀",
        codePoint(0x10000),
        codePoint(0x10FFFF),
        codePoint(0xFFFD),
        "e" + codePoint(0x0301),
        "é",
        "a\u0000b",
        "a b",
        "\t",
        "識-9");
  } // end method wellFormedIdentifiers

  @ParameterizedTest
  @MethodSource("wellFormedIdentifiers")
  void acceptsWellFormedIdentifiers(String identifier) {
    assertNull(Identifiers.identifierRule(identifier));
    assertFalse(Identifiers.hasUnpairedSurrogate(identifier));
  } // end method acceptsWellFormedIdentifiers

  @Test
  void rejectsEmptyIdentifiers() {
    assertEquals(EMPTY, Identifiers.identifierRule(""));
  } // end method rejectsEmptyIdentifiers

  @ParameterizedTest
  @ValueSource(strings = {"\r", "\n", "a\r\nb", "chat\n", "\rchat"})
  void rejectsIdentifiersWithLineBreaks(String identifier) {
    assertEquals(CONTROL_OR_SURROGATE, Identifiers.identifierRule(identifier));
  } // end method rejectsIdentifiersWithLineBreaks

  @ParameterizedTest
  @MethodSource("invalidIdentifierVectors")
  void rejectsIdentifiersWithUnpairedSurrogates(String identifier) {
    assertTrue(Identifiers.hasUnpairedSurrogate(identifier));
    assertEquals(CONTROL_OR_SURROGATE, Identifiers.identifierRule(identifier));
    assertEquals(SURROGATE, Identifiers.credentialValueRule(identifier));
  } // end method rejectsIdentifiersWithUnpairedSurrogates

  @Test
  void acceptsCredentialValuesWithAnyOtherText() {
    assertEquals(EMPTY, Identifiers.credentialValueRule(""));
    assertNull(Identifiers.credentialValueRule("eyJhbGciOi.payload-_"));
    assertNull(Identifiers.credentialValueRule("line\r\nbreaks"));
    assertNull(Identifiers.credentialValueRule("+/%=&識😀"));
  } // end method acceptsCredentialValuesWithAnyOtherText

  @Test
  void acceptsChannelReferencesOfAsciiLettersDigitsHyphensAndUnderscores() {
    assertEquals(List.of(), Identifiers.channelReferenceRules("room-1_A"));
    assertEquals(List.of(), Identifiers.channelReferenceRules("x".repeat(255)));
    assertEquals(List.of(), Identifiers.channelReferenceRules("-"));
    assertEquals(List.of(), Identifiers.channelReferenceRules("_"));
  } // end method acceptsChannelReferencesOfAsciiLettersDigitsHyphensAndUnderscores

  @Test
  void rejectsInvalidChannelReferences() {
    assertEquals(List.of(EMPTY), Identifiers.channelReferenceRules(""));
    assertEquals(List.of(TOO_LONG), Identifiers.channelReferenceRules("x".repeat(256)));

    for (String reference : List.of("x:y", "a\n", "é", "a b", "a/b", "a.b", "a%2F", "😀")) {
      assertEquals(List.of(CHARACTERS), Identifiers.channelReferenceRules(reference), reference);
    }
  } // end method rejectsInvalidChannelReferences

  @Test
  void reportsEveryRuleAChannelReferenceBreaks() {
    assertEquals(List.of(TOO_LONG, CHARACTERS), Identifiers.channelReferenceRules("é".repeat(300)));
  } // end method reportsEveryRuleAChannelReferenceBreaks

  @Test
  void describesFailuresByPathAndRuleOnly() {
    assertEquals("segmentId: Must not be empty.", Identifiers.failure("segmentId", EMPTY));
    assertEquals("Must not be empty.", Identifiers.failure(null, EMPTY));

    CelerisException error =
        Identifiers.configurationError(
            "command",
            List.of(
                Identifiers.failure("page", "Must be at least 1"),
                Identifiers.failure("perPage", "Must be at most 100")));

    assertEquals(ErrorCode.CONFIGURATION, error.code());
    assertEquals(
        "Invalid command. page: Must be at least 1. perPage: Must be at most 100.",
        error.getMessage());
    assertNull(error.getCause());
  } // end method describesFailuresByPathAndRuleOnly

  @Test
  void acceptsTheFirstAndLastCharacterOfEachRangeInChannelReferences() {
    assertEquals(List.of(), Identifiers.channelReferenceRules("azAZ09"));
  } // end method acceptsTheFirstAndLastCharacterOfEachRangeInChannelReferences
} // end class IdentifiersTest

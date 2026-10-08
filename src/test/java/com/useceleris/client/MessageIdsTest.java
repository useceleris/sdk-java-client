package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MessageIdsTest {
  @Test
  void generatesThirtyTwoLowercaseHexCharacters() {
    for (int attempt = 0; attempt < 100; attempt++) {
      String identifier = MessageIds.generate();

      assertEquals(32, identifier.length(), identifier);
      assertTrue(identifier.matches("[0-9a-f]{32}"), identifier);
    }
  } // end method generatesThirtyTwoLowercaseHexCharacters

  @Test
  void generatesDistinctIdentifiers() {
    Set<String> seen = new HashSet<>();

    for (int attempt = 0; attempt < 10_000; attempt++) {
      assertTrue(seen.add(MessageIds.generate()));
    }
  } // end method generatesDistinctIdentifiers

  @Test
  void generatesIdentifiersTheEncoderAccepts() {
    String identifier = MessageIds.generate();

    assertNull(Identifiers.identifierRule(identifier));
  } // end method generatesIdentifiersTheEncoderAccepts
} // end class MessageIdsTest

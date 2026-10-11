package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MessageIdsTest {
  @Test
  void namesTheSdkAndItsVersionThenThirtyTwoHexDigits() {
    String identifier = MessageIds.generate();

    String prefix = MessageIds.prefix("java", Constants.SDK_VERSION);

    assertTrue(identifier.startsWith(prefix), identifier);
    assertTrue(identifier.substring(prefix.length()).matches("[0-9a-f]{32}"), identifier);
  } // end method namesTheSdkAndItsVersionThenThirtyTwoHexDigits

  @Test
  void holdsOnlyLettersDigitsDotsAndUnderscores() {
    for (int attempt = 0; attempt < 100; attempt++) {
      String identifier = MessageIds.generate();

      assertTrue(identifier.matches("[A-Za-z0-9._]+"), identifier);
    }
  } // end method holdsOnlyLettersDigitsDotsAndUnderscores

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

  @Test
  void usesTheVersionThatPomXmlDeclares() throws Exception {
    String pom = Files.readString(Path.of("pom.xml"));
    Matcher version =
        Pattern.compile("<artifactId>celeris-client</artifactId>\\s*<version>([^<]+)</version>")
            .matcher(pom);

    assertTrue(version.find(), "pom.xml declares no version for celeris-client");
    assertEquals("java", Constants.SDK_LANGUAGE);
    assertEquals(version.group(1), Constants.SDK_VERSION);
  } // end method usesTheVersionThatPomXmlDeclares

  @Test
  void turnsEveryOtherVersionCharacterIntoAnUnderscore() {
    assertEquals("msg__java_v1.2.0_beta.1__", MessageIds.prefix("java", "1.2.0-beta.1"));
    assertEquals("msg__java_v1.2.0_build_7__", MessageIds.prefix("java", "1.2.0+build/7"));
    assertEquals("msg__java_v0.2.0__", MessageIds.prefix("java", "0.2.0"));
  } // end method turnsEveryOtherVersionCharacterIntoAnUnderscore

  /**
   * Client commands write identifiers as bulk strings: "$", the length, LF, the bytes, LF. The
   * server reads them without a scan for the end.
   */
  @Test
  void sendsAGeneratedIdAsABulkString() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();

    CompletableFuture<Void> published = channel.segment("chat").publish(bytes("hi"));
    runtime.run();

    assertTrue(published.isDone() && !published.isCompletedExceptionally());
    String frame = runtime.socket().commands().get(0);
    String prefix = MessageIds.prefix("java", Constants.SDK_VERSION);

    assertTrue(
        frame.matches(
            "@PUB\n\\$4\nchat\n\\$"
                + (prefix.length() + 32)
                + "\n"
                + Pattern.quote(prefix)
                + "[0-9a-f]{32}\n\\$2\nhi\n"),
        frame);
  } // end method sendsAGeneratedIdAsABulkString

  /** Your own id needs at least one character. It is sent exactly as given, as a bulk string. */
  @Test
  void sendsYourOwnIdUnchangedAsABulkStringFromOneCharacter() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    Segment lobby = channel.defaultSegment();

    lobby.publish(bytes("x"), "a");
    lobby.publish(bytes("y"), "order-1042:shipped é");
    runtime.run();

    assertEquals(
        List.of(
            "@PUB\n$7\ndefault\n$1\na\n$1\nx\n",
            "@PUB\n$7\ndefault\n$21\norder-1042:shipped é\n$1\ny\n"),
        runtime.socket().commands());
  } // end method sendsYourOwnIdUnchangedAsABulkStringFromOneCharacter

  @Test
  void refusesYourOwnIdWhenEmptyOrWithALineBreakAndWritesNothing() {
    TestRuntime runtime = new TestRuntime();
    Channel channel = runtime.connectedChannel();
    Segment lobby = channel.defaultSegment();
    String lineBreak =
        "Invalid command. messageId: Must not contain CR, LF or unpaired UTF-16 surrogates.";

    List<List<String>> refusals =
        List.of(
            List.of("", "Invalid command. messageId: Must not be empty."),
            List.of("a\nb", lineBreak),
            List.of("a\rb", lineBreak));

    for (List<String> refusal : refusals) {
      CompletableFuture<Void> refused = lobby.publish(bytes("x"), refusal.get(0));
      runtime.run();

      assertTrue(refused.isCompletedExceptionally(), refusal.get(0));
      CelerisException failure =
          (CelerisException) refused.handle((ignored, error) -> error).join();

      assertEquals(ErrorCode.CONFIGURATION, failure.code());
      assertEquals(refusal.get(1), failure.getMessage());
    }

    // Nothing was written; the control publish is the first frame.
    lobby.publish(bytes("x"), "control");
    runtime.run();

    assertEquals(List.of("@PUB\n$7\ndefault\n$7\ncontrol\n$1\nx\n"), runtime.socket().commands());
  } // end method refusesYourOwnIdWhenEmptyOrWithALineBreakAndWritesNothing

  private static byte[] bytes(String text) {
    return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  } // end method bytes
} // end class MessageIdsTest

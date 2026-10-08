package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConnectionUrlTest {
  // Messages hand-copied from the reference's connection-url.ts.
  private static final String NOT_ABSOLUTE =
      "Invalid connection URL. baseUrl is not an absolute URL.";

  private static final String USER_INFO =
      "Invalid connection URL. baseUrl must not contain a username or password.";

  private static final String QUERY =
      "Invalid connection URL. baseUrl must not contain a query string or fragment.";

  private static final String SCHEME =
      "Invalid connection URL. baseUrl must use wss://, or ws:// for a loopback host when"
          + " allowInsecureLoopback is true.";

  private static void assertRefused(String baseUrl, boolean allowInsecureLoopback, String message) {
    CelerisException failure =
        assertThrows(
            CelerisException.class,
            () -> ConnectionUrl.validateBaseUrl(baseUrl, allowInsecureLoopback));

    assertEquals(ErrorCode.CONFIGURATION, failure.code());
    assertEquals(message, failure.getMessage());
    assertNull(failure.getCause());
  } // end method assertRefused

  @Test
  void acceptsSecureUrls() {
    URI url = ConnectionUrl.validateBaseUrl("wss://realtime.useceleris.com", false);

    assertEquals("wss", url.getScheme());
    assertEquals("realtime.useceleris.com", url.getHost());
    assertEquals(
        "example.test", ConnectionUrl.validateBaseUrl("WSS://example.test/", false).getHost());
  } // end method acceptsSecureUrls

  @Test
  void refusesUrlsThatAreNotAbsolute() {
    for (String baseUrl :
        new String[] {"not a url", "", "wss:", "/relative/path", "wss:opaque", "example.test"}) {
      assertRefused(baseUrl, true, NOT_ABSOLUTE);
    }
  } // end method refusesUrlsThatAreNotAbsolute

  @Test
  void refusesUserInformation() {
    assertRefused("wss://user:pass@example.test", true, USER_INFO);
    assertRefused("wss://user@example.test", true, USER_INFO);
    // Checked before the query and the scheme, as the reference does.
    assertRefused("ws://user@example.test?x#y", true, USER_INFO);
  } // end method refusesUserInformation

  @Test
  void refusesQueriesAndFragmentsEvenWhenEmpty() {
    assertRefused("wss://example.test?", true, QUERY);
    assertRefused("wss://example.test#", true, QUERY);
    assertRefused("wss://example.test/?payload=x", true, QUERY);
    assertRefused("wss://example.test/path#fragment", true, QUERY);
    assertRefused("https://example.test?x", true, QUERY);
  } // end method refusesQueriesAndFragmentsEvenWhenEmpty

  @Test
  void refusesSchemesOtherThanSecureWebSockets() {
    assertRefused("ws://example.test", true, SCHEME);
    assertRefused("https://example.test", true, SCHEME);
    assertRefused("http://localhost", true, SCHEME);
    assertRefused("ftp://example.test", true, SCHEME);
  } // end method refusesSchemesOtherThanSecureWebSockets

  @ParameterizedTest
  @ValueSource(
      strings = {
        "localhost",
        "LOCALHOST",
        "LocalHost",
        "127.0.0.1",
        "127.1.2.3",
        "127.255.255.255",
        "[::1]"
      })
  void acceptsLoopbackOnlyWithTheExplicitOptIn(String host) {
    assertTrue(ConnectionUrl.isLoopback(host));
    assertRefused("ws://" + host + ":19002", false, SCHEME);

    URI url = ConnectionUrl.validateBaseUrl("ws://" + host + ":19002", true);

    assertEquals("ws", url.getScheme());
    assertEquals(19002, url.getPort());
  } // end method acceptsLoopbackOnlyWithTheExplicitOptIn

  // Matched as written: expanded and mapped forms that a URL parser might normalise are refused.
  @ParameterizedTest
  @ValueSource(
      strings = {
        "localhost.evil.test",
        "evil-localhost",
        "128.0.0.1",
        "0.0.0.0",
        "[::]",
        "[::ffff:127.0.0.1]",
        "[0:0:0:0:0:0:0:1]",
        "127.0.0.1.evil.test"
      })
  void refusesHostsThatAreNotLoopback(String host) {
    assertFalse(ConnectionUrl.isLoopback(host));
    assertRefused("ws://" + host, true, SCHEME);
  } // end method refusesHostsThatAreNotLoopback

  @Test
  void matchesLoopbackHostsAsWrittenOnly() {
    for (String host : new String[] {"127.1", "127.0.0", "1127.0.0.1", "::1", "localhost:19002"}) {
      assertFalse(ConnectionUrl.isLoopback(host), host);
    }
  } // end method matchesLoopbackHostsAsWrittenOnly

  // The reference's URL parser reads 127.1 as 127.0.0.1; java.net.URI does not read these as hosts.
  @ParameterizedTest
  @ValueSource(strings = {"127.1", "127.0.0", "1127.0.0.1", "127.999.999.999"})
  void refusesIpv4ShorthandAndOutOfRangeAddresses(String host) {
    CelerisException failure =
        assertThrows(
            CelerisException.class, () -> ConnectionUrl.validateBaseUrl("ws://" + host, true));

    assertEquals(ErrorCode.CONFIGURATION, failure.code());
    assertTrue(failure.getMessage().startsWith("Invalid connection URL. "));
  } // end method refusesIpv4ShorthandAndOutOfRangeAddresses

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "wss://example.test | wss://example.test/channel/",
        "wss://example.test/ | wss://example.test/channel/",
        "wss://example.test/prefix/// | wss://example.test/prefix/channel/",
        "wss://example.test:8443/a/b/ | wss://example.test:8443/a/b/channel/",
        "wss://example.test/a%2Fb/ | wss://example.test/a%2Fb/channel/",
        "ws://[::1]:19002 | ws://[::1]:19002/channel/",
        "ws://127.0.0.1:19002/// | ws://127.0.0.1:19002/channel/"
      })
  void keepsTheBasePathWithoutTrailingSlashes(String baseUrl, String expectedPrefix) {
    URI base = ConnectionUrl.validateBaseUrl(baseUrl, true);
    URI url = ConnectionUrl.credentialUrl(base, "room-1", new Credentials("p", "s"));

    assertEquals(expectedPrefix + "room-1?payload=p&signature=s", url.toString());
    assertEquals(baseUrl, base.toString());
  } // end method keepsTheBasePathWithoutTrailingSlashes

  @Test
  void keepsAMaximumLengthChannelReference() {
    String reference = "a".repeat(255);
    URI url =
        ConnectionUrl.credentialUrl(
            ConnectionUrl.validateBaseUrl("wss://example.test", false),
            reference,
            new Credentials("p", "s"));

    assertEquals("/channel/" + reference, url.getPath());
  } // end method keepsAMaximumLengthChannelReference

  // Expected encodings worked by hand from the URLSearchParams serializer: ASCII alphanumerics and
  // *-._ stay, a space becomes +, and every other byte of the UTF-8 encoding is %XX in upper case.
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      value = {
        "+/%=&識 | %2B%2F%25%3D%26%E8%AD%98",
        "%2B | %252B",
        "a b~*-._ | a+b%7E*-._",
        "!'()?#[]@$,;: | %21%27%28%29%3F%23%5B%5D%40%24%2C%3B%3A",
        "eyJhbGciOi.AbC-_09 | eyJhbGciOi.AbC-_09",
        "😀é | %F0%9F%98%80%C3%A9"
      })
  void encodesCredentialValuesLikeUrlSearchParams(String value, String encoded) {
    URI url =
        ConnectionUrl.credentialUrl(
            ConnectionUrl.validateBaseUrl("wss://example.test", false),
            "room",
            new Credentials(value, value));

    assertEquals(
        "wss://example.test/channel/room?payload=" + encoded + "&signature=" + encoded,
        url.toString());

    // Decoding the query as a form gives back each value exactly: encoded once, never twice.
    for (String parameter : url.getRawQuery().split("&")) {
      assertEquals(
          value,
          URLDecoder.decode(
              parameter.substring(parameter.indexOf('=') + 1), StandardCharsets.UTF_8));
    }
  } // end method encodesCredentialValuesLikeUrlSearchParams

  @Test
  void encodesLineBreaksAndControlCharacters() {
    URI url =
        ConnectionUrl.credentialUrl(
            ConnectionUrl.validateBaseUrl("wss://example.test", false),
            "room",
            new Credentials("a\r\nb", "\u0000\t"));

    assertEquals(
        "wss://example.test/channel/room?payload=a%0D%0Ab&signature=%00%09", url.toString());
  } // end method encodesLineBreaksAndControlCharacters

  @Test
  void refusesPortsAboveTheTcpRange() {
    assertEquals(
        NOT_ABSOLUTE,
        assertThrows(
                CelerisException.class,
                () -> ConnectionUrl.validateBaseUrl("wss://example.test:65536", false))
            .getMessage());
    ConnectionUrl.validateBaseUrl("wss://example.test:65535", false);
  } // end method refusesPortsAboveTheTcpRange

  @Test
  void anEmptyUserPartIsNotAUsername() {
    URI base = ConnectionUrl.validateBaseUrl("wss://@example.test/base", false);

    assertEquals(
        "wss://example.test/base/channel/room-1?payload=p&signature=s",
        ConnectionUrl.credentialUrl(base, "room-1", new Credentials("p", "s")).toString());
  } // end method anEmptyUserPartIsNotAUsername
} // end class ConnectionUrlTest

package com.useceleris.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** The base URL rules (ENDPOINT-01, SEC-02) and the URL one attempt connects to. */
final class ConnectionUrl {
  private static final Pattern DOTTED_LOOPBACK = Pattern.compile("127\\.[0-9]+\\.[0-9]+\\.[0-9]+");

  private ConnectionUrl() {}

  /** Accepts wss://, or ws:// for a loopback host when the caller opted in. */
  static URI validateBaseUrl(String baseUrl, boolean allowInsecureLoopback) {
    URI url;

    try {
      url = new URI(baseUrl);
    } catch (URISyntaxException failure) {
      throw invalid("baseUrl is not an absolute URL.");
    }

    if (url.getScheme() == null || url.getHost() == null || url.isOpaque()) {
      throw invalid("baseUrl is not an absolute URL.");
    }

    if (url.getPort() > Constants.MAXIMUM_PORT) {
      throw invalid("baseUrl is not an absolute URL.");
    }

    if (url.getRawUserInfo() != null && !url.getRawUserInfo().isEmpty()) {
      throw invalid("baseUrl must not contain a username or password.");
    }

    if (baseUrl.indexOf('?') >= 0 || baseUrl.indexOf('#') >= 0) {
      throw invalid("baseUrl must not contain a query string or fragment.");
    }

    String scheme = url.getScheme().toLowerCase(Locale.ROOT);

    if (!scheme.equals("wss")
        && !(scheme.equals("ws") && allowInsecureLoopback && isLoopback(url.getHost()))) {
      throw invalid(
          "baseUrl must use wss://, or ws:// for a loopback host when allowInsecureLoopback is"
              + " true.");
    }

    return url;
  } // end method validateBaseUrl

  /**
   * Accepts localhost, [::1] and dotted 127.x.x.x hosts, matched as written: an IPv4-mapped IPv6
   * address and shorthand such as 127.1 are not loopback here.
   */
  static boolean isLoopback(String host) {
    return host.equalsIgnoreCase("localhost")
        || host.equals("[::1]")
        || DOTTED_LOOPBACK.matcher(host).matches();
  } // end method isLoopback

  /**
   * Where one attempt connects. The credentials travel in its query, so it must never reach an
   * error, a log or a caller.
   */
  static URI credentialUrl(URI baseUrl, String channelReference, Credentials credentials) {
    String path = baseUrl.getRawPath() == null ? "" : baseUrl.getRawPath();
    int end = path.length();

    while (end > 0 && path.charAt(end - 1) == '/') {
      end--;
    }

    // An empty user part, as in "wss://@host", is dropped, as a URL parser does.
    String authority = baseUrl.getRawAuthority();

    if (authority.startsWith("@")) {
      authority = authority.substring(1);
    }

    String address =
        baseUrl.getScheme()
            + "://"
            + authority
            + path.substring(0, end)
            + "/channel/"
            + channelReference
            + "?payload="
            + URLEncoder.encode(credentials.payload(), StandardCharsets.UTF_8)
            + "&signature="
            + URLEncoder.encode(credentials.signature(), StandardCharsets.UTF_8);

    try {
      return new URI(address);
    } catch (URISyntaxException failure) {
      // Unreachable for validated inputs; the parser's message would quote the credentials.
      throw invalid("baseUrl cannot be combined with the channel reference.");
    }
  } // end method credentialUrl

  private static CelerisException invalid(String rule) {
    return new CelerisException(ErrorCode.CONFIGURATION, "Invalid connection URL. " + rule);
  } // end method invalid
} // end class ConnectionUrl

package com.useceleris.client.live;

import static com.useceleris.client.live.LiveSupport.claims;
import static com.useceleris.client.live.LiveSupport.sign;
import static com.useceleris.client.live.LiveSupport.websocketUrl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.useceleris.client.Credentials;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Tag("live")
@Timeout(180)
final class ExamplesLiveTest {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private static final String SESSION = "demo-session";

  private static final Pattern QUICKSTART_SUCCESS =
      Pattern.compile("example: ok delivered=[1-9]\\d* present=\\d+");

  @RegisterExtension final LiveSupport live = new LiveSupport();

  /** The body Quickstart posts to its credential endpoint. */
  record CredentialEndpointRequest(String channelReference, long replayLookbackMs) {}

  /**
   * Runs the quickstart in its own JVM against an in-process credential endpoint that signs with
   * this suite's test signer.
   */
  @Test
  void runsTheQuickstart(@TempDir Path workingDirectory) throws Exception {
    HttpServer endpoint =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    live.closeAfterTest(() -> endpoint.stop(0));
    endpoint.createContext("/credentials", ExamplesLiveTest::issueCredentials);
    endpoint.start();

    Path output = workingDirectory.resolve("quickstart.log");
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    ProcessBuilder quickstart =
        new ProcessBuilder(java, "-cp", classpath, "com.useceleris.examples.Quickstart")
            .redirectErrorStream(true)
            .redirectOutput(output.toFile());
    Map<String, String> environment = quickstart.environment();
    // The example only ever talks to the endpoint; it has no use for the application's secrets.
    environment.remove("CELERIS_CLIENT_ID");
    environment.remove("CELERIS_SIGNING_SECRET");
    environment.put(
        "CELERIS_CREDENTIAL_URL",
        "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/credentials");
    environment.put("CELERIS_SESSION", SESSION);
    environment.put("CELERIS_WS_URL", websocketUrl());

    Process process = quickstart.start();

    try {
      boolean exited = process.waitFor(2, TimeUnit.MINUTES);
      String log = Files.readString(output);

      assertTrue(exited, "The quickstart did not finish within two minutes:\n" + log);
      assertEquals(0, process.exitValue(), log);
      assertTrue(QUICKSTART_SUCCESS.matcher(log).find(), log);
    } finally {
      process.destroyForcibly();
    }
  } // end method runsTheQuickstart

  /** Authenticates the session, then signs for the requested channel only. */
  private static void issueCredentials(HttpExchange exchange) throws IOException {
    try {
      if (!("Bearer " + SESSION).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        exchange.sendResponseHeaders(401, -1);

        return;
      }

      CredentialEndpointRequest request =
          MAPPER.readValue(
              exchange.getRequestBody().readAllBytes(), CredentialEndpointRequest.class);

      if (request.channelReference() == null || request.channelReference().isEmpty()) {
        exchange.sendResponseHeaders(400, -1);

        return;
      }

      // The quickstart publishes and receives on one connection.
      Credentials credentials =
          sign(claims().withChannelReferences(request.channelReference()).withAllowEcho());
      byte[] body = MAPPER.writeValueAsBytes(credentials);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
    } finally {
      exchange.close();
    }
  } // end method issueCredentials
} // end class ExamplesLiveTest

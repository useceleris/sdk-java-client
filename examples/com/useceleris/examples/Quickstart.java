package com.useceleris.examples;

import com.useceleris.client.CelerisClient;
import com.useceleris.client.Channel;
import com.useceleris.client.ClientOptions;
import com.useceleris.client.CredentialRequest;
import com.useceleris.client.Credentials;
import com.useceleris.client.Payloads;
import com.useceleris.client.PresencePage;
import com.useceleris.client.Segment;
import com.useceleris.client.Subscription;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Connects to Celeris, publishes on a segment it subscribes to, and reads who is present.
 *
 * <p>A trusted server signs credentials; this client fetches them from your credential endpoint and
 * never sees a signing secret. The endpoint must grant the user read and write on the "chat"
 * segment of any "quickstart-" channel. Run it with CELERIS_CREDENTIAL_URL pointing at that
 * endpoint, CELERIS_SESSION holding the user's bearer token, and CELERIS_WS_URL at a local ws://
 * stack.
 */
public final class Quickstart {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private Quickstart() {}

  /**
   * Asks your credential endpoint for fresh credentials. It is called once per connection attempt,
   * reconnects included. The endpoint authenticates the user and decides the claims.
   */
  static CompletionStage<Credentials> fetchCredentials(CredentialRequest request) {
    String body =
        MAPPER.writeValueAsString(
            Map.of(
                "channelReference", request.channelReference(),
                "replayLookbackMs", request.replayLookback().map(Duration::toMillis).orElse(0L)));
    HttpRequest httpRequest =
        HttpRequest.newBuilder(URI.create(System.getenv("CELERIS_CREDENTIAL_URL")))
            .header("Authorization", "Bearer " + System.getenv("CELERIS_SESSION"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    return HTTP.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
        .thenApply(
            response -> {
              if (response.statusCode() != 200) {
                throw new IllegalStateException(
                    "credential endpoint answered " + response.statusCode());
              }

              return MAPPER.readValue(response.body(), Credentials.class);
            });
  } // end method fetchCredentials

  public static void main(String[] arguments) throws InterruptedException {
    CelerisClient client =
        CelerisClient.create(
            ClientOptions.builder(Quickstart::fetchCredentials)
                .baseUrl(System.getenv("CELERIS_WS_URL"))
                // A local ws:// stack; production uses the built-in wss:// endpoint.
                .allowInsecureLoopback(true)
                .build());

    try (Channel channel = client.channel("quickstart-" + System.currentTimeMillis())) {
      channel.events().onStateChange(state -> System.out.println("state: " + state));
      channel.events().onError(error -> System.out.println("error: " + error.getMessage()));
      channel.connect().join();

      Segment chat = channel.segment("chat");
      CountDownLatch delivered = new CountDownLatch(1);
      chat.onMessage(
          (payload, metadata) -> {
            // Payload first; metadata carries the sender, the id and the time.
            System.out.println(
                "received " + metadata.messageId() + " " + Payloads.readText(payload));
            delivered.countDown();
          });

      Subscription membership = chat.subscribe();

      try {
        Thread.sleep(1000);

        // Completing means the local socket accepted the bytes, never a receipt.
        chat.publish(MAPPER.writeValueAsBytes(Map.of("hello", "world"))).join();
        boolean received = delivered.await(15, TimeUnit.SECONDS);

        // Presence: who is in the segment right now. One query may be in flight per channel.
        PresencePage page = chat.presenceList(1, 10).join();
        System.out.println(
            "example: ok delivered=" + (received ? 1 : 0) + " present=" + page.total());
      } finally {
        membership.cancel();
      }
    }
  } // end method main
} // end class Quickstart

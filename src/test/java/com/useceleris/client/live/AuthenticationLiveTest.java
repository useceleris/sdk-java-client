package com.useceleris.client.live;

import static com.useceleris.client.live.LiveSupport.await;
import static com.useceleris.client.live.LiveSupport.claims;
import static com.useceleris.client.live.LiveSupport.client;
import static com.useceleris.client.live.LiveSupport.clientId;
import static com.useceleris.client.live.LiveSupport.failureOf;
import static com.useceleris.client.live.LiveSupport.sign;
import static com.useceleris.client.live.LiveSupport.signRawPayload;
import static com.useceleris.client.live.LiveSupport.signingSecret;
import static com.useceleris.client.live.LiveSupport.uniqueChannelReference;
import static com.useceleris.client.live.LiveSupport.waitUntil;
import static com.useceleris.client.live.LiveSupport.websocketUrl;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.useceleris.client.CelerisClient;
import com.useceleris.client.CelerisException;
import com.useceleris.client.Channel;
import com.useceleris.client.ChannelState;
import com.useceleris.client.CredentialRequest;
import com.useceleris.client.Credentials;
import com.useceleris.client.ErrorCode;
import com.useceleris.client.ServerNotice;
import com.useceleris.client.live.LiveSupport.Recorder;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("live")
@Timeout(120)
final class AuthenticationLiveTest {
  @RegisterExtension final LiveSupport live = new LiveSupport();

  @Test
  void connectsWithValidCredentialsAndReceivesTheGreetings() throws Exception {
    Channel channel = live.channel(client(claims()), uniqueChannelReference("auth"));
    Recorder<ServerNotice> notices = new Recorder<>();
    channel.events().onNotice(notices);

    await(channel.connect(), "connect");
    waitUntil(
        () -> notices.count() >= 2,
        "the connect and default-subscribe greetings",
        Duration.ofSeconds(15));

    String greetings =
        notices.all().stream()
            .map(notice -> new String(notice.payload(), UTF_8))
            .collect(Collectors.joining("\n"));

    assertTrue(greetings.contains("Successfully connected"), greetings);
    assertTrue(greetings.contains("segment \"default\""), greetings);
  } // end method connectsWithValidCredentialsAndReceivesTheGreetings

  /** DEV-02: a refused handshake is never labelled an authorization failure. */
  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "invalid signature",
        "unknown client",
        "expired",
        "an hour old",
        "future",
        "other channel"
      })
  void reportsRefusedCredentialsAsTransportFailures(String refusal) throws Exception {
    long now = System.currentTimeMillis();
    Credentials credentials =
        switch (refusal) {
          case "invalid signature" -> sign(clientId(), "wrong-secret", claims());
          case "unknown client" -> sign("no-such-client", signingSecret(), claims());
          case "expired" -> sign(claims().withTimestamp(now - Duration.ofSeconds(61).toMillis()));
          case "an hour old" ->
              sign(claims().withTimestamp(now - Duration.ofMinutes(59).toMillis()));
          case "future" -> sign(claims().withTimestamp(now + Duration.ofMinutes(5).toMillis()));
          case "other channel" -> sign(claims().withChannelReferences("some-other-channel"));
          default -> throw new IllegalArgumentException("Unknown refusal case.");
        };

    CelerisClient client =
        client(websocketUrl(), request -> CompletableFuture.completedFuture(credentials));
    Channel channel = live.channel(client, uniqueChannelReference("refused"));

    Throwable failure = failureOf(channel.connect(), "connecting with refused credentials");

    CelerisException refused = assertInstanceOf(CelerisException.class, failure);
    assertEquals(ErrorCode.TRANSPORT, refused.code());
    assertEquals(ChannelState.FAILED, channel.state());
    assertFalse(refused.getMessage().contains(credentials.signature()), "the error leaks them");
    assertFalse(refused.getMessage().contains(credentials.payload()), "the error leaks them");
  } // end method reportsRefusedCredentialsAsTransportFailures

  @Test
  void acceptsATimestampInsideTheSixtySecondWindow() throws Exception {
    long recentTimestamp = System.currentTimeMillis() - Duration.ofSeconds(30).toMillis();

    Channel channel =
        live.connectedChannel(
            uniqueChannelReference("window"), claims().withTimestamp(recentTimestamp));

    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method acceptsATimestampInsideTheSixtySecondWindow

  @Test
  void acceptsAChannelInsideTheTokensRestriction() throws Exception {
    String reference = uniqueChannelReference("allowed");

    Channel channel = live.connectedChannel(reference, claims().withChannelReferences(reference));

    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method acceptsAChannelInsideTheTokensRestriction

  /** Claims the server cannot accept, signed correctly, are refused at the handshake (DEV-02). */
  @ParameterizedTest(name = "refuses {0} as Transport")
  @ValueSource(
      strings = {
        "an empty reference",
        "a payload that is not JSON",
        "a payload without a timestamp",
        "a timestamp that is a string",
        "a timestamp 30 seconds in the future"
      })
  void refusesClaimsAsTransport(String refusal) throws Exception {
    CelerisClient client =
        client(
            websocketUrl(),
            request -> {
              long now = System.currentTimeMillis();
              String payloadText =
                  switch (refusal) {
                    case "an empty reference" -> "{\"timestamp\":" + now + ",\"reference\":\"\"}";
                    case "a payload that is not JSON" -> "not json";
                    case "a payload without a timestamp" -> "{\"reference\":\"x\"}";
                    case "a timestamp that is a string" -> "{\"timestamp\":\"now\"}";
                    case "a timestamp 30 seconds in the future" ->
                        "{\"timestamp\":" + (now + 30_000) + "}";
                    default -> throw new IllegalArgumentException("Unknown refusal case.");
                  };

              return CompletableFuture.completedFuture(signRawPayload(payloadText));
            });

    Channel channel = live.channel(client, uniqueChannelReference("refused-claims"));

    Throwable failure = failureOf(channel.connect(), "connecting with refused claims");

    assertEquals(ErrorCode.TRANSPORT, assertInstanceOf(CelerisException.class, failure).code());
    assertEquals(ChannelState.FAILED, channel.state());
  } // end method refusesClaimsAsTransport

  @Test
  void acceptsAnEmptyChannelRestrictionWhichPermitsEveryChannel() throws Exception {
    Channel channel =
        live.connectedChannel(uniqueChannelReference("any"), claims().withChannelReferences());

    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method acceptsAnEmptyChannelRestrictionWhichPermitsEveryChannel

  @Test
  void acceptsAChannelThatIsOneOfSeveralInTheRestriction() throws Exception {
    String reference = uniqueChannelReference("several");

    Channel channel =
        live.connectedChannel(
            reference, claims().withChannelReferences("some-other-channel", reference));

    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method acceptsAChannelThatIsOneOfSeveralInTheRestriction

  /** Credentials are requested fresh for every attempt (D-001). */
  @Test
  void requestsFreshCredentialsForEveryConnect() throws Exception {
    Recorder<CredentialRequest> requests = new Recorder<>();
    CelerisClient client =
        client(
            websocketUrl(),
            request -> {
              requests.accept(request);

              return CompletableFuture.completedFuture(sign(claims()));
            });

    List<String> references =
        List.of(uniqueChannelReference("fresh"), uniqueChannelReference("fresh"));

    for (String reference : references) {
      Channel channel = live.channel(client, reference);
      await(channel.connect(), "connect");
      channel.close();
    }

    List<CredentialRequest> recorded = requests.all();
    assertEquals(2, recorded.size(), recorded.toString());

    for (int index = 0; index < recorded.size(); index++) {
      assertEquals(references.get(index), recorded.get(index).channelReference());
      assertFalse(recorded.get(index).reconnect());
      assertTrue(recorded.get(index).disconnectedAt().isEmpty());
      assertTrue(recorded.get(index).replayLookback().isEmpty());
    }
  } // end method requestsFreshCredentialsForEveryConnect
} // end class AuthenticationLiveTest

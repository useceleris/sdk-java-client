package com.useceleris.client.live;

import static com.useceleris.client.live.LiveSupport.DELIVERY_TIMEOUT;
import static com.useceleris.client.live.LiveSupport.await;
import static com.useceleris.client.live.LiveSupport.claims;
import static com.useceleris.client.live.LiveSupport.collect;
import static com.useceleris.client.live.LiveSupport.failureOf;
import static com.useceleris.client.live.LiveSupport.pause;
import static com.useceleris.client.live.LiveSupport.settle;
import static com.useceleris.client.live.LiveSupport.signing;
import static com.useceleris.client.live.LiveSupport.texts;
import static com.useceleris.client.live.LiveSupport.uniqueChannelReference;
import static com.useceleris.client.live.LiveSupport.websocketUrl;
import static com.useceleris.client.live.LiveSupport.withText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.useceleris.client.CelerisClient;
import com.useceleris.client.CelerisException;
import com.useceleris.client.Channel;
import com.useceleris.client.ChannelState;
import com.useceleris.client.ClientOptions;
import com.useceleris.client.ErrorCode;
import com.useceleris.client.Payloads;
import com.useceleris.client.Subscription;
import com.useceleris.client.live.LiveSupport.Claims;
import com.useceleris.client.live.LiveSupport.Delivery;
import com.useceleris.client.live.LiveSupport.Recorder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Client options at non-default values, against the real server. */
@Tag("live")
@Timeout(120)
final class OptionsLiveTest {
  @RegisterExtension final LiveSupport live = new LiveSupport();

  /** A channel whose client has these options, connected through the gateway with the claims. */
  private Channel openWith(
      String reference,
      String baseUrl,
      UnaryOperator<ClientOptions.Builder> options,
      Claims tokenClaims)
      throws InterruptedException {
    ClientOptions.Builder builder =
        ClientOptions.builder(signing(tokenClaims)).baseUrl(baseUrl).allowInsecureLoopback(true);
    Channel channel = live.channel(CelerisClient.create(options.apply(builder).build()), reference);
    await(channel.connect(), "connecting " + reference);

    return channel;
  } // end method openWith

  @Test
  void timesOutAPresenceQueryAtAOneMillisecondPresenceTimeoutAndStaysConnected() throws Exception {
    Channel channel =
        openWith(
            uniqueChannelReference("option-presence"),
            websocketUrl(),
            builder -> builder.presenceQueryTimeout(Duration.ofMillis(1)),
            claims());

    Throwable failure = failureOf(channel.segment("room").presenceList(1, 10), "presence query");

    CelerisException timedOut = assertInstanceOf(CelerisException.class, failure);
    assertEquals(ErrorCode.TIMEOUT, timedOut.code());
    assertEquals("Presence query timed out after 1 ms.", timedOut.getMessage());
    assertEquals(ChannelState.CONNECTED, channel.state());
  } // end method timesOutAPresenceQueryAtAOneMillisecondPresenceTimeoutAndStaysConnected

  @Test
  void deliversReplayedIdsAgainBeyondADeduplicationWindowOfOne() throws Exception {
    String reference = uniqueChannelReference("option-window");
    Channel publisher = live.connectedChannel(reference);
    Channel receiver =
        openWith(
            reference,
            websocketUrl(),
            builder -> builder.deduplicationWindowSize(1),
            claims().withReplay());
    Recorder<Delivery> history = collect(receiver.segment("history"));
    Subscription first = receiver.segment("history").subscribe();
    settle();

    await(publisher.segment("history").publish(Payloads.text("one")), "publish one");
    await(publisher.segment("history").publish(Payloads.text("two")), "publish two");
    history.await(withText("two"), "the live delivery of two", DELIVERY_TIMEOUT);

    // The window holds only "two". The re-join replays "one", which pushes "two" out of the
    // window, so the replayed "two" is delivered again too.
    first.cancel();
    settle();
    receiver.segment("history").subscribe();
    pause(Duration.ofSeconds(4));

    assertEquals(List.of("one", "two", "one", "two"), texts(history));
  } // end method deliversReplayedIdsAgainBeyondADeduplicationWindowOfOne

  @Test
  void refusesTheSecondWaitingPublishWithAPublishQueueOfOne() throws Exception {
    DroppingProxy proxy = live.closeAfterTest(DroppingProxy.start(websocketUrl()));
    Channel channel =
        openWith(
            uniqueChannelReference("option-queue"),
            proxy.url(),
            builder -> builder.publishQueueSize(1),
            claims());

    // The proxy stops reading, so the socket buffer fills and publishes wait.
    proxy.stallUpstream(true);
    byte[] payload = new byte[900 * 1024];
    List<CompletableFuture<String>> outcomes = new ArrayList<>();

    for (int index = 0; index < 40; index++) {
      outcomes.add(
          channel
              .segment("bulk")
              .publish(payload)
              .handle((sent, failure) -> failure == null ? "sent" : failure.getMessage()));
    }

    pause(Duration.ofSeconds(2));
    proxy.stallUpstream(false);
    List<String> results = new ArrayList<>();

    for (CompletableFuture<String> outcome : outcomes) {
      results.add(await(outcome, "a publish behind the stalled proxy"));
    }

    assertTrue(
        results.contains(
            "The publish queue is full (size 1). Retry once some publishes have gone out."),
        results.toString());
    assertTrue(results.contains("sent"), results.toString());
  } // end method refusesTheSecondWaitingPublishWithAPublishQueueOfOne
} // end class OptionsLiveTest

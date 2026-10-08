package com.useceleris.client.live;

import static com.useceleris.client.live.LiveSupport.DELIVERY_TIMEOUT;
import static com.useceleris.client.live.LiveSupport.GENERATED_MESSAGE_ID;
import static com.useceleris.client.live.LiveSupport.await;
import static com.useceleris.client.live.LiveSupport.collect;
import static com.useceleris.client.live.LiveSupport.settle;
import static com.useceleris.client.live.LiveSupport.uniqueChannelReference;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.useceleris.client.Channel;
import com.useceleris.client.PayloadCodec;
import com.useceleris.client.Payloads;
import com.useceleris.client.live.LiveSupport.Delivery;
import com.useceleris.client.live.LiveSupport.Recorder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Payloads are opaque bytes to the SDK and the server: whatever a caller encodes is what the peer
 * decodes. Each format is decoded on arrival, the JSON ones by the other library too, so a payload
 * proves itself rather than matching a copy of itself.
 */
@Tag("live")
@Timeout(120)
final class PayloadFormatsLiveTest {
  private static final ObjectMapper JACKSON = JsonMapper.builder().build();

  private static final Gson GSON = new Gson();

  private static final String TEXT = "héllo 안녕 😀\r\n";

  private static final Greeting GREETING = new Greeting(7, TEXT, true);

  private static final PayloadCodec<Greeting> JACKSON_CODEC =
      PayloadCodec.of(
          JACKSON::writeValueAsBytes, payload -> JACKSON.readValue(payload, Greeting.class));

  private static final PayloadCodec<Greeting> GSON_CODEC =
      PayloadCodec.of(
          value -> GSON.toJson(value).getBytes(UTF_8),
          payload -> GSON.fromJson(new String(payload, UTF_8), Greeting.class));

  @RegisterExtension final LiveSupport live = new LiveSupport();

  record Greeting(int id, String text, boolean ok) {}

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"text", "Jackson JSON", "Gson JSON", "raw bytes"})
  void roundTripsPayloadsByteIdentically(String format) throws Exception {
    byte[] vector =
        switch (format) {
          case "text" -> Payloads.text(TEXT);
          case "Jackson JSON" -> JACKSON_CODEC.encodePayload(GREETING);
          case "Gson JSON" -> GSON_CODEC.encodePayload(GREETING);
          case "raw bytes" -> everyByteValue();
          default -> throw new IllegalArgumentException("Unknown format.");
        };

    String reference = uniqueChannelReference("fmt");
    Channel publisher = live.connectedChannel(reference);
    Channel receiver = live.connectedChannel(reference);
    Recorder<Delivery> received = collect(receiver.segment("formats"));
    receiver.segment("formats").subscribe();
    settle();

    await(publisher.segment("formats").publish(vector), "publish");

    Delivery delivery =
        received.await(any -> true, "the " + format + " delivery", DELIVERY_TIMEOUT);
    assertArrayEquals(vector, delivery.payload(), "the payload changed in transit");
    assertTrue(GENERATED_MESSAGE_ID.matcher(delivery.metadata().messageId()).matches());

    switch (format) {
      case "text" -> assertEquals(TEXT, Payloads.readText(delivery.payload()));
      case "Jackson JSON" -> {
        assertEquals(GREETING, JACKSON_CODEC.readPayload(delivery.payload()));
        assertEquals(GREETING, GSON_CODEC.readPayload(delivery.payload()));
      }
      case "Gson JSON" -> {
        assertEquals(GREETING, GSON_CODEC.readPayload(delivery.payload()));
        assertEquals(GREETING, JACKSON_CODEC.readPayload(delivery.payload()));
      }
      default -> {
        for (int value = 0; value < 256; value++) {
          assertEquals((byte) value, delivery.payload()[value]);
        }
      }
    }
  } // end method roundTripsPayloadsByteIdentically

  private static byte[] everyByteValue() {
    byte[] bytes = new byte[256];

    for (int value = 0; value < bytes.length; value++) {
      bytes[value] = (byte) value;
    }

    return bytes;
  } // end method everyByteValue
} // end class PayloadFormatsLiveTest

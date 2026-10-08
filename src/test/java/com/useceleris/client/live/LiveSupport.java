package com.useceleris.client.live;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.fail;

import com.useceleris.client.CelerisClient;
import com.useceleris.client.CelerisException;
import com.useceleris.client.Channel;
import com.useceleris.client.ClientOptions;
import com.useceleris.client.CredentialProvider;
import com.useceleris.client.Credentials;
import com.useceleris.client.MessageMetadata;
import com.useceleris.client.Segment;
import com.useceleris.client.ServerErrorException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Support for the live suites, which run against the realtime stack named by CELERIS_WS_URL with
 * the application credentials CELERIS_CLIENT_ID and CELERIS_SIGNING_SECRET. Real environment
 * variables take precedence over the repository's gitignored .env. The optional CELERIS_WS_URL_PEER
 * names a gateway that routes to a different node, for the cross-node suite's second connection;
 * without it that suite skips.
 *
 * <p>Each suite registers one instance per test with {@code @RegisterExtension}. Before each test
 * it fails the test, never skips it, when the stack is unconfigured or unreachable; after each test
 * it closes the channels and resources the test opened.
 */
final class LiveSupport implements BeforeEachCallback, AfterEachCallback {
  /**
   * The SDK gives every publish its own id, which the server delivers as is (RESEND-01): 16 random
   * bytes, hex-encoded.
   */
  static final Pattern GENERATED_MESSAGE_ID = Pattern.compile("[0-9a-f]{32}");

  static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(15);

  static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(20);

  /** Lets a subscription reach every node before something is published to it. */
  static final Duration SUBSCRIPTION_SETTLE = Duration.ofMillis(1500);

  private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(15);

  private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

  private static final int PROBE_TIMEOUT_MILLISECONDS = 5_000;

  private static final List<String> REQUIRED_VARIABLES =
      List.of("CELERIS_WS_URL", "CELERIS_CLIENT_ID", "CELERIS_SIGNING_SECRET");

  private static final Map<String, String> DOT_ENVIRONMENT =
      readEnvironmentFile(Path.of(System.getProperty("basedir", ""), ".env").toAbsolutePath());

  private static final AtomicLong CHANNEL_COUNTER = new AtomicLong();

  // Empty once the stack is known to be usable; the reason it is not, otherwise.
  private static String stackProblem;

  private final List<Channel> channels = Collections.synchronizedList(new ArrayList<>());
  private final List<AutoCloseable> resources = Collections.synchronizedList(new ArrayList<>());

  // ---- Configuration ----

  /** Reads KEY=VALUE lines; the values only fill in what the real environment leaves unset. */
  private static Map<String, String> readEnvironmentFile(Path file) {
    Map<String, String> values = new HashMap<>();

    if (!Files.isRegularFile(file)) {
      return values;
    }

    List<String> lines;

    try {
      lines = Files.readAllLines(file, UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException("The repository's .env cannot be read.", failure);
    }

    for (String rawLine : lines) {
      String line = rawLine.strip();
      int separator = line.indexOf('=');

      if (line.startsWith("#") || separator < 0) {
        continue;
      }

      String value = line.substring(separator + 1).strip();

      if (value.length() >= 2
          && (value.startsWith("\"") && value.endsWith("\"")
              || value.startsWith("'") && value.endsWith("'"))) {
        value = value.substring(1, value.length() - 1);
      }

      values.put(line.substring(0, separator).strip(), value);
    }

    return values;
  } // end method readEnvironmentFile

  private static String variable(String name) {
    String value = System.getenv(name);

    return value != null ? value : DOT_ENVIRONMENT.getOrDefault(name, "");
  } // end method variable

  static String websocketUrl() {
    return variable("CELERIS_WS_URL");
  } // end method websocketUrl

  /**
   * Whether CELERIS_WS_URL_PEER is set. Two connections through one gateway can share a node, so
   * the cross-node suite runs only with it.
   */
  static boolean hasPeerWebsocketUrl() {
    return !peerWebsocketUrl().isEmpty();
  } // end method hasPeerWebsocketUrl

  /** The optional CELERIS_WS_URL_PEER, a gateway that routes to a different node. */
  static String peerWebsocketUrl() {
    return variable("CELERIS_WS_URL_PEER");
  } // end method peerWebsocketUrl

  static String clientId() {
    return variable("CELERIS_CLIENT_ID");
  } // end method clientId

  static String signingSecret() {
    return variable("CELERIS_SIGNING_SECRET");
  } // end method signingSecret

  /** Fails at once when the stack cannot be used, instead of letting a test wait out a deadline. */
  static synchronized void requireStack() {
    if (stackProblem == null) {
      stackProblem = findStackProblem();
    }

    if (!stackProblem.isEmpty()) {
      fail(stackProblem);
    }
  } // end method requireStack

  private static String findStackProblem() {
    for (String name : REQUIRED_VARIABLES) {
      if (variable(name).isEmpty()) {
        return name
            + " is not set. Put CELERIS_WS_URL, CELERIS_CLIENT_ID and CELERIS_SIGNING_SECRET in"
            + " the repository's .env (gitignored) or the environment.";
      }
    }

    URI target;

    try {
      target = new URI(websocketUrl());
    } catch (URISyntaxException failure) {
      return "CELERIS_WS_URL is not a URL.";
    }

    if (target.getHost() == null) {
      return "CELERIS_WS_URL names no host.";
    }

    int port = target.getPort();

    if (port == -1) {
      port = "wss".equals(target.getScheme()) ? 443 : 80;
    }

    try (Socket probe = new Socket()) {
      probe.connect(new InetSocketAddress(target.getHost(), port), PROBE_TIMEOUT_MILLISECONDS);
    } catch (IOException failure) {
      return "The realtime service at "
          + target.getHost()
          + ":"
          + port
          + " is not reachable. Start the stack, or point CELERIS_WS_URL elsewhere.";
    }

    return "";
  } // end method findStackProblem

  @Override
  public void beforeEach(ExtensionContext context) {
    requireStack();
  } // end method beforeEach

  // ---- Signing ----

  /**
   * Token claims, written in the wire's order: timestamp, reference, channel_references,
   * token_permission, replay, allow_echo. Claims never set are omitted; the timestamp defaults to
   * the signing time.
   */
  static final class Claims {
    private static final List<String> WIRE_ORDER =
        List.of("reference", "channel_references", "token_permission", "replay", "allow_echo");

    private static final Claims NONE = new Claims(Optional.empty(), Map.of());

    private final Optional<Long> timestamp;
    private final Map<String, String> encodedClaims;

    private Claims(Optional<Long> timestamp, Map<String, String> encodedClaims) {
      this.timestamp = timestamp;
      this.encodedClaims = encodedClaims;
    } // end constructor Claims

    Claims withTimestamp(long unixMilliseconds) {
      return new Claims(Optional.of(unixMilliseconds), encodedClaims);
    } // end method withTimestamp

    Claims withReference(String tokenReference) {
      return with("reference", jsonString(tokenReference));
    } // end method withReference

    Claims withChannelReferences(String... channelReferences) {
      List<String> encoded = new ArrayList<>();

      for (String channelReference : channelReferences) {
        encoded.add(jsonString(channelReference));
      }

      return with("channel_references", "[" + String.join(",", encoded) + "]");
    } // end method withChannelReferences

    Claims withPermission(boolean read, boolean write) {
      return with("token_permission", "{\"read\":" + read + ",\"write\":" + write + "}");
    } // end method withPermission

    /** Grants each listed segment its own access, and every other segment none. */
    Claims withPermissions(SegmentPermission... permissions) {
      List<String> encoded = new ArrayList<>();

      for (SegmentPermission permission : permissions) {
        encoded.add(
            "{\"segment_id\":"
                + jsonString(permission.segmentId())
                + ",\"read\":"
                + permission.read()
                + ",\"write\":"
                + permission.write()
                + "}");
      }

      return with("token_permission", "[" + String.join(",", encoded) + "]");
    } // end method withPermissions

    /** Asks for replay without naming a lookback. */
    Claims withReplay() {
      return with("replay", "true");
    } // end method withReplay

    Claims withReplay(long lookbackMilliseconds) {
      return with("replay", Long.toString(lookbackMilliseconds));
    } // end method withReplay

    Claims withAllowEcho() {
      return with("allow_echo", "true");
    } // end method withAllowEcho

    private Claims with(String key, String encodedValue) {
      Map<String, String> updated = new LinkedHashMap<>(encodedClaims);
      updated.put(key, encodedValue);

      return new Claims(timestamp, Map.copyOf(updated));
    } // end method with

    String json(long signingTime) {
      StringBuilder json =
          new StringBuilder("{\"timestamp\":").append(timestamp.orElse(signingTime));

      for (String key : WIRE_ORDER) {
        String encodedValue = encodedClaims.get(key);

        if (encodedValue != null) {
          json.append(",\"").append(key).append("\":").append(encodedValue);
        }
      }

      return json.append('}').toString();
    } // end method json

    private static String jsonString(String text) {
      StringBuilder json = new StringBuilder("\"");

      for (char character : text.toCharArray()) {
        if (character == '"' || character == '\\') {
          json.append('\\').append(character);
        } else if (character < 0x20) {
          json.append(String.format("\\u%04x", (int) character));
        } else {
          json.append(character);
        }
      }

      return json.append('"').toString();
    } // end method jsonString
  } // end class Claims

  /** One entry of a per-segment token_permission claim. */
  record SegmentPermission(String segmentId, boolean read, boolean write) {}

  static Claims claims() {
    return Claims.NONE;
  } // end method claims

  /**
   * Signs the claims as a trusted server would. Written by hand from the protocol, independent of
   * the server artifact: client tests never depend on server code.
   */
  static Credentials sign(String clientId, String signingSecret, Claims claims) {
    return signPayloadText(clientId, signingSecret, claims.json(System.currentTimeMillis()));
  } // end method sign

  static Credentials sign(Claims claims) {
    return sign(clientId(), signingSecret(), claims);
  } // end method sign

  /**
   * Signs any payload text as it is, for claims {@link Claims} cannot express: malformed JSON,
   * missing or wrongly typed fields.
   */
  static Credentials signRawPayload(String payloadText) {
    return signPayloadText(clientId(), signingSecret(), payloadText);
  } // end method signRawPayload

  private static Credentials signPayloadText(
      String clientId, String signingSecret, String payloadText) {
    String payload = Base64.getEncoder().encodeToString(payloadText.getBytes(UTF_8));
    String digest;

    try {
      Mac hmac = Mac.getInstance("HmacSHA512");
      hmac.init(new SecretKeySpec(signingSecret.getBytes(UTF_8), "HmacSHA512"));
      digest = HexFormat.of().formatHex(hmac.doFinal(payload.getBytes(UTF_8)));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("HMAC-SHA512 is unavailable", failure);
    }

    String signature =
        Base64.getEncoder().encodeToString((clientId + ":" + digest).getBytes(UTF_8));

    return new Credentials(payload, signature);
  } // end method signPayloadText

  /** Signs the claims afresh for every connection attempt. */
  static CredentialProvider signing(Claims claims) {
    return request -> CompletableFuture.completedFuture(sign(claims));
  } // end method signing

  // ---- Clients and channels ----

  static CelerisClient client(String baseUrl, CredentialProvider credentialProvider) {
    return CelerisClient.create(
        ClientOptions.builder(credentialProvider)
            .baseUrl(baseUrl)
            .allowInsecureLoopback(true)
            .build());
  } // end method client

  static CelerisClient client(Claims claims) {
    return client(websocketUrl(), signing(claims));
  } // end method client

  static String uniqueChannelReference(String label) {
    return "javaqual-"
        + label
        + "-"
        + System.currentTimeMillis()
        + "-"
        + CHANNEL_COUNTER.incrementAndGet();
  } // end method uniqueChannelReference

  /** Returns a channel that is closed after the test. */
  Channel channel(CelerisClient client, String reference) {
    Channel channel = client.channel(reference);
    channels.add(channel);

    return channel;
  } // end method channel

  Channel connectedChannel(String reference) throws InterruptedException {
    return connectedChannel(reference, claims());
  } // end method connectedChannel

  /** Returns a channel of its own client, connected with the claims and closed after the test. */
  Channel connectedChannel(String reference, Claims claims) throws InterruptedException {
    return connectedChannel(reference, claims, websocketUrl());
  } // end method connectedChannel

  /** As {@link #connectedChannel(String, Claims)}, through the given gateway. */
  Channel connectedChannel(String reference, Claims claims, String baseUrl)
      throws InterruptedException {
    Channel channel = channel(client(baseUrl, signing(claims)), reference);
    await(channel.connect(), "connecting " + reference);

    return channel;
  } // end method connectedChannel

  /** Closes the resource after the test, once every channel is closed. */
  <T extends AutoCloseable> T closeAfterTest(T resource) {
    resources.add(resource);

    return resource;
  } // end method closeAfterTest

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    List<CompletableFuture<Void>> closing;

    synchronized (channels) {
      closing = channels.stream().map(Channel::closeAsync).collect(Collectors.toList());
    }

    try {
      CompletableFuture.allOf(closing.toArray(new CompletableFuture<?>[0]))
          .get(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } finally {
      List<AutoCloseable> opened;

      synchronized (resources) {
        opened = new ArrayList<>(resources);
      }

      Collections.reverse(opened);

      for (AutoCloseable resource : opened) {
        resource.close();
      }
    }
  } // end method afterEach

  // ---- Waiting ----

  static void pause(Duration duration) throws InterruptedException {
    Thread.sleep(duration.toMillis());
  } // end method pause

  static void settle() throws InterruptedException {
    pause(SUBSCRIPTION_SETTLE);
  } // end method settle

  /** Polls the condition until it holds, failing the test after the timeout. */
  static void waitUntil(BooleanSupplier condition, String description, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();

    while (!condition.getAsBoolean()) {
      if (System.nanoTime() - deadline >= 0) {
        fail("Timed out after " + timeout.toMillis() + " ms waiting for " + description + ".");
      }

      pause(POLL_INTERVAL);
    }
  } // end method waitUntil

  /** Waits for the future to succeed, failing the test if it fails or runs past the timeout. */
  static <T> T await(CompletableFuture<T> future, String description, Duration timeout)
      throws InterruptedException {
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException failure) {
      throw new AssertionError(description + " failed: " + failure.getCause(), failure.getCause());
    } catch (TimeoutException failure) {
      throw new AssertionError(description + " did not complete within " + timeout + ".", failure);
    }
  } // end method await

  static <T> T await(CompletableFuture<T> future, String description) throws InterruptedException {
    return await(future, description, OPERATION_TIMEOUT);
  } // end method await

  /** Waits for the future to fail and returns why, failing the test if it succeeds instead. */
  static Throwable failureOf(CompletableFuture<?> future, String description)
      throws InterruptedException {
    try {
      future.get(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (ExecutionException failure) {
      return failure.getCause();
    } catch (TimeoutException failure) {
      throw new AssertionError(description + " did not complete within " + OPERATION_TIMEOUT + ".");
    }

    throw new AssertionError(description + " succeeded; a failure was expected.");
  } // end method failureOf

  /** Waits for the future, returning the SDK's failure if it failed with one. */
  static Optional<CelerisException> sdkFailureOf(CompletableFuture<?> future, String description)
      throws InterruptedException {
    try {
      future.get(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

      return Optional.empty();
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof CelerisException sdkFailure) {
        return Optional.of(sdkFailure);
      }

      throw new AssertionError(description + " failed: " + failure.getCause(), failure.getCause());
    } catch (TimeoutException failure) {
      throw new AssertionError(description + " did not complete within " + OPERATION_TIMEOUT + ".");
    }
  } // end method sdkFailureOf

  // ---- Recording ----

  /** Records what a listener receives, in arrival order, for any thread to inspect. */
  static final class Recorder<T> implements Consumer<T> {
    private final List<T> received = new ArrayList<>();

    @Override
    public synchronized void accept(T value) {
      received.add(value);
    } // end method accept

    synchronized List<T> all() {
      return List.copyOf(received);
    } // end method all

    synchronized int count() {
      return received.size();
    } // end method count

    synchronized boolean any(Predicate<? super T> predicate) {
      return received.stream().anyMatch(predicate);
    } // end method any

    /** Waits for the first value the predicate accepts, recorded before or after the call. */
    T await(Predicate<? super T> predicate, String description, Duration timeout)
        throws InterruptedException {
      waitUntil(() -> any(predicate), description, timeout);

      synchronized (this) {
        return received.stream().filter(predicate).findFirst().orElseThrow();
      }
    } // end method await
  } // end class Recorder

  /** One delivery: the listener's copy of the payload, and its metadata. */
  record Delivery(byte[] payload, MessageMetadata metadata) {
    String text() {
      return new String(payload, UTF_8);
    } // end method text
  } // end record Delivery

  /** Records every delivery on the segment from now on. */
  static Recorder<Delivery> collect(Segment segment) {
    Recorder<Delivery> deliveries = new Recorder<>();
    segment.onMessage((payload, metadata) -> deliveries.accept(new Delivery(payload, metadata)));

    return deliveries;
  } // end method collect

  /** Records every failure the channel reports through its error listeners from now on. */
  static Recorder<RuntimeException> collectErrors(Channel channel) {
    Recorder<RuntimeException> errors = new Recorder<>();
    channel.events().onError(errors);

    return errors;
  } // end method collectErrors

  /** The texts of the deliveries recorded so far, in arrival order. */
  static List<String> texts(Recorder<Delivery> deliveries) {
    return deliveries.all().stream().map(Delivery::text).toList();
  } // end method texts

  static Predicate<Delivery> withText(String text) {
    return delivery -> delivery.text().equals(text);
  } // end method withText

  static Predicate<RuntimeException> serverErrorOfType(String errorType) {
    return failure ->
        failure instanceof ServerErrorException serverError && serverError.type().equals(errorType);
  } // end method serverErrorOfType

  static byte[] patterned(int length) {
    byte[] data = new byte[length];

    for (int index = 0; index < length; index++) {
      data[index] = (byte) (index % 251);
    }

    return data;
  } // end method patterned
} // end class LiveSupport

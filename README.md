# Celeris Java client

[![Maven Central](https://img.shields.io/maven-central/v/com.useceleris/celeris-client.svg)](https://central.sonatype.com/artifact/com.useceleris/celeris-client)
[![Javadoc](https://javadoc.io/badge2/com.useceleris/celeris-client/javadoc.svg)](https://javadoc.io/doc/com.useceleris/celeris-client)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Realtime client for Celeris channels: segment messaging, presence, and automatic recovery.

A `Channel` is one WebSocket connection; another `Channel`, even for the same reference, opens another socket. Every segment of a channel is multiplexed over that one connection, and connecting joins the `"default"` segment. `Segment` handles are free: any number of handles for one segment share its subscriptions and listeners.

## Install

```xml
<dependency>
  <groupId>com.useceleris</groupId>
  <artifactId>celeris-client</artifactId>
  <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("com.useceleris:celeris-client:0.1.0")
```

Java 17 or newer, module `com.useceleris.client`. The WebSocket is the JDK's own `java.net.http` client, so there are no runtime dependencies, and Android, which lacks it, is not supported.

## Quickstart

```java
CelerisClient client = CelerisClient.create(ClientOptions.builder(credentialProvider).build());

try (Channel channel = client.channel("room-42")) {
  Segment chat = channel.segment("chat");

  chat.onMessage(
      (payload, metadata) ->
          System.out.println(metadata.tokenReference() + ": " + Payloads.readText(payload)));

  chat.subscribe();

  channel.connect().join();
  chat.publish(Payloads.text("hello")).join();
}
```

`credentialProvider` fetches signed credentials from your server; see [Credentials](#credentials). The SDK's threads are daemon threads, so a program that only listens keeps its main thread alive itself. [examples/Quickstart](examples/com/useceleris/examples/Quickstart.java) is a complete program, with presence.

## Credentials

The client never signs. Your own authenticated endpoint signs credentials with [sdk-java-server](https://github.com/useceleris/sdk-java-server), and a `CredentialProvider` fetches them. Never ship a signing secret in an application.

```java
HttpClient http = HttpClient.newHttpClient();
String sessionToken = "the signed-in user's session";

CredentialProvider credentialProvider =
    request -> {
      String body =
          mapper.writeValueAsString(
              Map.of(
                  "channelReference", request.channelReference(),
                  "replayLookbackMs",
                      request.replayLookback().map(Duration::toMillis).orElse(0L)));
      HttpRequest httpRequest =
          HttpRequest.newBuilder(URI.create("https://your-app.example/api/realtime-credentials"))
              .header("Authorization", "Bearer " + sessionToken)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();

      return http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
          .thenApply(
              response -> {
                if (response.statusCode() != 200) {
                  throw new IllegalStateException("credential endpoint refused");
                }

                return mapper.readValue(response.body(), Credentials.class);
              });
    };
```

The provider runs on the SDK's threads, once per connection attempt, reconnects included. On a reconnect, `request.reconnect()` is true and `replayLookback()` is the outage so far plus five seconds; your endpoint decides whether to grant it. When an attempt is abandoned, by its deadline, `close()` or cancelling `connect()`, the SDK cancels the stage the provider returned and discards a late result.

A provider that throws or fails is reported as `TRANSPORT`, and its text is never passed on. Credentials with a missing, empty or ill-formed value are `CONFIGURATION`, which on a reconnect ends recovery. `Credentials` reads and writes as `{"payload": "...", "signature": "..."}` with Jackson or Gson, and its `toString` never shows either value.

## Connecting and closing

```java
channel
    .events()
    .onStateChange(
        state -> {
          switch (state) {
            case RECONNECTING -> System.out.println("connection lost; recovering");
            case FAILED -> System.out.println("gave up; call connect() to try again");
            default -> {}
          }
        });

try {
  channel.connect().join();
} catch (CompletionException failure) {
  System.out.println("not connected: " + failure.getCause().getMessage());
}
```

States run `idle` → `connecting` → `connected`; then `reconnecting` and back to `connected` on recovery, `failed` when connecting or recovery gives up, and `closing` → `closed` on `close()`.

`connect()` completes once the socket is up and held subscriptions are queued to be restored. A failed first connect is not retried: the channel becomes `failed`, and you may call `connect()` again. Only a connection that was up recovers automatically.

`close()` is terminal and idempotent. It flushes publishes already handed to the socket, fails waiting publishes and presence queries with `CANCELLED`, and returns within about five seconds even when the server does not answer; `closeAsync()` does the same without blocking.

A channel reference is 1 to 255 ASCII letters, digits, `-` or `_`. Segment ids and message ids are non-empty text without CR, LF or unpaired surrogates.

## Messages

```java
Registration listener =
    chat.onMessage(
        (payload, metadata) ->
            System.out.println(
                metadata.tokenReference()
                    + " at "
                    + Instant.ofEpochMilli(metadata.timestamp())
                    + ": "
                    + Payloads.readText(payload)));

Subscription subscription = chat.subscribe();

chat.publish(Payloads.text("Order 1042 shipped"), "order-1042-shipped").join();

// When done:
subscription.cancel();
listener.close();
```

`subscribe()` registers interest: the first interest subscribes the segment, the last `cancel()` unsubscribes it, and subscriptions are restored after every reconnect. The protocol has no acknowledgement, so a denial arrives later through `onError`. The default segment is joined on connect and never needs a subscription. Each listener receives its own copy of the payload, and each message id is delivered once within the deduplication window.

The server membership of the connection controls what it receives on a segment. A listener does not control it:

- When you hold `subscribe()`, the segment sends messages to you. A listener alone receives nothing, unless the connection published to the segment.
- A publish joins the connection to the segment. With read and write access, the connection then receives messages. With write access only, it receives nothing. With read access only, the server does not accept the publish, so you must call `subscribe()`.
- The last `cancel()` stops the membership, also a membership from a publish. A presence subscription does not join a segment and does not keep a segment.
- `"default"` always sends messages to you.
- After a reconnect, the SDK subscribes again only to the segments that you hold a subscription for. A join from a publish does not continue. Call `subscribe()` for a segment that must continue to send messages to you.
- Listeners and subscriptions operate independently. When you remove one, the other does not change.

To receive all messages from all segments, add a listener to the channel. The segment listeners get each message first, then the channel listeners. Each listener gets a different copy of the payload:

```java
Registration channelListener =
    channel
        .events()
        .onMessage(
            (payload, metadata) ->
                System.out.println(metadata.segmentId() + ": " + Payloads.readText(payload)));
```

`channelListener.close()` removes only this channel listener. The other channel listeners and the segment listeners continue to receive messages. Your subscriptions do not change, and the SDK does not send a message to the server. When you call `close()` again, it has no effect.

`publish(payload)` generates a message id; `publish(payload, messageId)` uses yours. The payload is copied before `publish` returns. The future completes once the local socket accepted the bytes: there is no receipt. While the channel reconnects, a publish waits in the queue and is sent after the restored subscriptions, as does a publish still waiting when the connection drops; waiting publishes fail with the error `onError` reports if recovery fails, and with `CANCELLED` on `close()`. Before the first connect completes, and once the channel is failed or closed, `publish` fails with `NOT_CONNECTED`. Publishing joins the segment server-side. A publish over your plan's payload cap still completes, and is refused afterwards with a `MessageSizeLimitError` through `onError`.

Cancelling the future (`cancel(true)`) withdraws a publish the socket has not started writing. Once it is being written, the future completes with `DELIVERY_UNKNOWN` instead and `cancel` returns false.

A `RateLimitError` never names the command it dropped, so the client pauses, then resends what it sent in the last two seconds: subscriptions as they stand now, then publishes, each at most once and with its original id, so receivers drop a copy that already arrived. Resends count toward usage. Eight limits in a row are treated as a used-up quota: resending stops, and dropped subscriptions are retried on a slow probe. Subscription changes go out ahead of waiting publishes, except publishes to the same segment queued before them.

## Payloads

Payloads are opaque bytes. `Payloads.text` encodes UTF-8, replacing an unpaired surrogate with U+FFFD, and `Payloads.readText` throws `CONFIGURATION` for bytes that are not UTF-8. The SDK bundles no JSON library, so JSON, or any other format, goes through a `PayloadCodec` built from the serializer you already use:

```java
record Typing(boolean active) {}

PayloadCodec<Typing> typing =
    PayloadCodec.of(
        mapper::writeValueAsBytes, payload -> mapper.readValue(payload, Typing.class));

chat.onMessage(
    (payload, metadata) -> System.out.println("typing: " + typing.readPayload(payload).active()));

chat.publish(typing.encodePayload(new Typing(true)));
```

With Gson:

```java
record Typing(boolean active) {}

PayloadCodec<Typing> typing =
    PayloadCodec.of(
        value -> gson.toJson(value).getBytes(StandardCharsets.UTF_8),
        payload -> gson.fromJson(new String(payload, StandardCharsets.UTF_8), Typing.class));
```

A codec's own exceptions propagate unchanged. Decoding asserts a type; it does not validate, so check payloads from peers you do not control.

## Presence

```java
chat.onPresence(
    event ->
        System.out.println(
            event.tokenReference()
                + (event.joined() ? " joined on " : " left from ")
                + event.connectionId()));

Subscription presence = chat.subscribePresence();
```

Presence events arrive only while a presence subscription is held. Watching presence is not membership: it neither joins nor holds the segment for messages.

`presenceList` reads one page of who is present, up to 100 connections per page:

```java
for (int page = 1; ; page++) {
  PresencePage result = chat.presenceList(page, 100).join();

  result
      .connections()
      .forEach(
          connection ->
              System.out.println(connection.tokenReference() + " " + connection.connectionId()));

  if (result.connections().isEmpty() || result.to() >= result.total()) {
    break;
  }
}
```

One query may be in flight per channel; another fails with `OPERATION_IN_PROGRESS`. A server refusal fails the future with a `ServerErrorException` (`subType()` `"PRES_LIST"`) instead of reaching `onError`. A timeout frees the slot and leaves the connection up; a connection loss fails the query with `TRANSPORT`. The reply is routed only once listeners return, so a listener that waits for the result always times out: compose on the future instead.

## Errors

The SDK's own failures are `CelerisException`s, matched by `code()`. Their messages name what failed and the rule it broke, never your input, credentials or server bytes, and they never carry a cause.

```java
chat.publish(Payloads.text("Order 1042 shipped"))
    .whenComplete(
        (ignored, failure) -> {
          Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;

          if (cause instanceof CelerisException sdkFailure) {
            switch (sdkFailure.code()) {
              case BACKPRESSURE -> System.out.println("too many publishes waiting; retry later");
              case DELIVERY_UNKNOWN ->
                  System.out.println("may or may not have been sent; do not resend blindly");
              default -> System.out.println("not sent: " + sdkFailure.getMessage());
            }
          }
        });
```

| Code                    | Meaning                                                                                  |
| ----------------------- | ---------------------------------------------------------------------------------------- |
| `CONFIGURATION`         | Invalid options, identifiers or payloads, or invalid credentials from the provider       |
| `TIMEOUT`               | A connect attempt or presence query ran past its deadline                                |
| `CANCELLED`             | Cancelled by `close()`, or a connection attempt abandoned                                |
| `TRANSPORT`             | The provider failed, the handshake was refused, the socket broke, or a listener threw    |
| `NOT_CONNECTED`         | The operation needs a connection the channel does not have                               |
| `BACKPRESSURE`          | `publishQueueSize` publishes (64 by default) are waiting; for a presence query, the writer is full or paused |
| `OPERATION_IN_PROGRESS` | A second `connect()`, or a second presence query, while the first runs                   |
| `DELIVERY_UNKNOWN`      | A publish that may or may not have left the socket; never resent, so do not resend blindly |
| `PROTOCOL`              | A server message could not be decoded; it is dropped and the connection stays up         |

A refused handshake is `TRANSPORT`: the SDK never reports an authentication failure. Methods returning a future never throw, except `NullPointerException` for a null argument; `join()` wraps a failure in a `CompletionException`.

Failures no caller is waiting for reach `onError`. Errors the server sends are `ServerErrorException`s, with `type()` (`PermissionDeniedError`, `RateLimitError`, `MessageSizeLimitError`, `ParserError`, `SendError`, `InternalError`, or a type a newer server adds), `subType()` (the command it answers) and `resource()`; the connection stays up.

```java
channel
    .events()
    .onError(
        error -> {
          if (error instanceof ServerErrorException server) {
            System.out.println(
                server.type() + " " + server.subType().orElse("-") + " " + server.resource());
          } else {
            System.out.println("sdk: " + error.getMessage());
          }
        });
```

`onNotice` delivers the server's raw notices, such as greetings, as prose. Never branch on their text.

## Reconnection

```java
channel
    .events()
    .onRecovery(
        event -> {
          // Replay is a bounded window: reload authoritative state from your own API.
          System.out.println("recovered on retry " + event.retryIndex());
        });
```

A connection that drops is retried with full-jitter backoff, each attempt with fresh credentials and a replay lookback covering the outage. Recovery restores every subscription, then sends the publishes that were waiting; a publish the socket had already taken is never sent again. It reports possible gaps and duplicates every time: replayed messages already seen are dropped within the deduplication window, and duplicates beyond it reach your listeners. When `maximumReconnectAttempts` attempts (10 by default) have failed, `onError` reports why and the channel becomes `failed`.

The client pings when the server has been quiet, and a ping left unanswered means the path is dead and the channel recovers. Only reading time counts, so a slow listener holding the read side never costs the connection.

The JDK's WebSocket client can miss a connection closed without a close frame right after it delivers a message. So when the server stays quiet for 1 s after a delivered message, the client sends one ping, and if nothing arrives within 5 s, the channel recovers. An idle connection gets no extra pings.

## Limits and defaults

| What             | Value                                                                                       |
| ---------------- | ------------------------------------------------------------------------------------------- |
| Outbound command | 2 MiB encoded, refused with `CONFIGURATION` before any write                                |
| Plan payload cap | 64 KiB free, 128 KiB standard, 512 KiB pro, 1024 KiB prime; enforced by the server          |
| Writer bounds    | 64 commands / 2 MiB not yet written; `publishQueueSize` more waiting, 64 by default         |
| Rate limits      | pause 1 s plus jitter, at most 31 s; resend the last 2 s, at most 64 publishes, each once   |
| Quota probe      | after 8 limits in a row: dropped subscriptions retried after 1 min, doubling to 1 h        |
| Connect deadline | `connectTimeout`, default 15 s, covering credentials and the handshake                      |
| Reconnect deadline | `reconnectTimeout` per reconnect attempt; unset, it follows `connectTimeout`              |
| Presence query   | one in flight per channel; `presenceQueryTimeout`, default 10 s                            |
| Timeouts         | 1 ms to 15 min                                                                              |
| Reconnect        | `maximumReconnectAttempts` failed attempts, 10 by default, 1 to 100; full jitter up to 30 s; the budget resets at a drop after 60 s connected |
| Heartbeat        | a ping after 15 s of silence; dead after 15 s of reading time without an answer            |
| Delivery probe   | a ping 1 s after a delivered message when nothing followed; dead after 5 s without an answer |
| Dedup window     | `deduplicationWindowSize` message ids per channel, 1024 by default                          |
| Close            | about 5 s at most, even when the server does not answer                                     |

Received messages are never size-checked: they are already in memory when they arrive. A publish the server rejects for your plan's cap still counts toward usage.

## Threads and futures

A client, its channels and their segments are safe for concurrent use. Create one client per application; its daemon threads start when first needed, never when it is created.

Listeners run one at a time on the client's threads, in the order events happened, never under an SDK lock: a slow listener slows delivery rather than growing a queue. A listener may call `publish`, `subscribe`, `close` or `connect`; the events those calls cause follow once it returns.

Futures complete on the client's worker threads, each as its own task, independently of event delivery and of one another. A listener may wait for `connect` or `publish`, never for `presenceList`; a stage you attach may wait for any of the channel's futures. Cancel the future the SDK returned: cancelling a stage derived from it does not reach the operation, while an `orTimeout` deadline on it abandons the operation as a cancel does.

## Local development

The production endpoint is built in. Set `baseUrl` only for a local stack or another deployment; `ws://` is accepted only for a loopback host, and only when you opt in:

```java
CelerisClient client =
    CelerisClient.create(
        ClientOptions.builder(credentialProvider)
            .baseUrl("ws://localhost:19002")
            .allowInsecureLoopback(true)
            .build());
```

TLS is always verified against the JVM's default trust store. [SECURITY.md](SECURITY.md) lists the JVM-wide properties that can weaken or log any `java.net.http` connection.

## More documentation

- [Javadoc](https://javadoc.io/doc/com.useceleris/celeris-client)
- [Java guide](https://useceleris.com/docs/sdks/java) and [Java client API reference](https://useceleris.com/docs/api-reference/java-client)
- [sdk-java-server](https://github.com/useceleris/sdk-java-server), for signing credentials on your server

Releases follow semantic versioning; before 1.0.0, a minor release may change the API. To contribute, read [CONVENTIONS.md](CONVENTIONS.md); to report a vulnerability, [SECURITY.md](SECURITY.md).

## License

[Apache 2.0](LICENSE).

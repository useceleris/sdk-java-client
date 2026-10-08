package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The published surface, and what the package must never do. */
class PackageTest {
  private static final Path MAIN_SOURCES = Path.of("src/main/java/com/useceleris/client");
  private static final Path MAIN_CLASSES = Path.of("target/classes/com/useceleris/client");

  /** Every public type and member, as one sorted list. Changing it is a surface change. */
  private static final List<String> SURFACE =
      List.of(
          "class CelerisClient",
          "class CelerisClient: CelerisClient create(ClientOptions)",
          "class CelerisClient: Channel channel(String)",
          "class CelerisException",
          "class CelerisException: CelerisException(ErrorCode,String)",
          "class CelerisException: ErrorCode code()",
          "class CelerisException: Optional field()",
          "class CelerisException: OptionalInt offset()",
          "class Channel",
          "class Channel: ChannelEventHandler events()",
          "class Channel: ChannelState state()",
          "class Channel: CompletableFuture closeAsync()",
          "class Channel: CompletableFuture connect()",
          "class Channel: Segment defaultSegment()",
          "class Channel: Segment segment(String)",
          "class Channel: void close()",
          "class ChannelEventHandler",
          "class ChannelEventHandler: Registration onError(Consumer)",
          "class ChannelEventHandler: Registration onMessage(MessageListener)",
          "class ChannelEventHandler: Registration onNotice(Consumer)",
          "class ChannelEventHandler: Registration onRecovery(Consumer)",
          "class ChannelEventHandler: Registration onStateChange(Consumer)",
          "class ClientOptions",
          "class ClientOptions$Builder",
          "class ClientOptions$Builder: ClientOptions build()",
          "class ClientOptions$Builder: ClientOptions$Builder allowInsecureLoopback(boolean)",
          "class ClientOptions$Builder: ClientOptions$Builder baseUrl(String)",
          "class ClientOptions$Builder: ClientOptions$Builder connectTimeout(Duration)",
          "class ClientOptions$Builder: ClientOptions$Builder deduplicationWindowSize(int)",
          "class ClientOptions$Builder: ClientOptions$Builder maximumReconnectAttempts(int)",
          "class ClientOptions$Builder: ClientOptions$Builder presenceQueryTimeout(Duration)",
          "class ClientOptions$Builder: ClientOptions$Builder publishQueueSize(int)",
          "class ClientOptions$Builder: ClientOptions$Builder reconnectTimeout(Duration)",
          "class ClientOptions: ClientOptions$Builder builder(CredentialProvider)",
          "class PayloadCodec",
          "class PayloadCodec: Object readPayload(byte[])",
          "class PayloadCodec: PayloadCodec of(Function,Function)",
          "class PayloadCodec: byte[] encodePayload(Object)",
          "class Payloads",
          "class Payloads: String readText(byte[])",
          "class Payloads: byte[] text(String)",
          "class Registration",
          "class Registration: void close()",
          "class Segment",
          "class Segment: CompletableFuture presenceList(int,int)",
          "class Segment: CompletableFuture publish(byte[])",
          "class Segment: CompletableFuture publish(byte[],String)",
          "class Segment: Registration onMessage(MessageListener)",
          "class Segment: Registration onPresence(Consumer)",
          "class Segment: String segmentId()",
          "class Segment: Subscription subscribe()",
          "class Segment: Subscription subscribePresence()",
          "class ServerErrorException",
          "class ServerErrorException: Optional resource()",
          "class ServerErrorException: Optional subType()",
          "class ServerErrorException: String INTERNAL_ERROR",
          "class ServerErrorException: String MESSAGE_SIZE_LIMIT_ERROR",
          "class ServerErrorException: String PARSER_ERROR",
          "class ServerErrorException: String PERMISSION_DENIED_ERROR",
          "class ServerErrorException: String RATE_LIMIT_ERROR",
          "class ServerErrorException: String SEND_ERROR",
          "class ServerErrorException: String type()",
          "class Subscription",
          "class Subscription: void cancel()",
          "class Subscription: void close()",
          "enum ChannelState",
          "enum ChannelState: ChannelState CLOSED",
          "enum ChannelState: ChannelState CLOSING",
          "enum ChannelState: ChannelState CONNECTED",
          "enum ChannelState: ChannelState CONNECTING",
          "enum ChannelState: ChannelState FAILED",
          "enum ChannelState: ChannelState IDLE",
          "enum ChannelState: ChannelState RECONNECTING",
          "enum ChannelState: ChannelState valueOf(String)",
          "enum ChannelState: ChannelState[] values()",
          "enum ChannelState: String toString()",
          "enum ErrorCode",
          "enum ErrorCode: ErrorCode BACKPRESSURE",
          "enum ErrorCode: ErrorCode CANCELLED",
          "enum ErrorCode: ErrorCode CONFIGURATION",
          "enum ErrorCode: ErrorCode DELIVERY_UNKNOWN",
          "enum ErrorCode: ErrorCode NOT_CONNECTED",
          "enum ErrorCode: ErrorCode OPERATION_IN_PROGRESS",
          "enum ErrorCode: ErrorCode PROTOCOL",
          "enum ErrorCode: ErrorCode TIMEOUT",
          "enum ErrorCode: ErrorCode TRANSPORT",
          "enum ErrorCode: ErrorCode valueOf(String)",
          "enum ErrorCode: ErrorCode[] values()",
          "enum ErrorCode: String code()",
          "interface CredentialProvider",
          "interface CredentialProvider: CompletionStage provide(CredentialRequest)",
          "interface MessageListener",
          "interface MessageListener: void onMessage(byte[],MessageMetadata)",
          "record CredentialRequest",
          "record CredentialRequest: CredentialRequest(String,boolean,Optional,Optional)",
          "record CredentialRequest: Optional disconnectedAt()",
          "record CredentialRequest: Optional replayLookback()",
          "record CredentialRequest: String channelReference()",
          "record CredentialRequest: String toString()",
          "record CredentialRequest: boolean equals(Object)",
          "record CredentialRequest: boolean reconnect()",
          "record CredentialRequest: int hashCode()",
          "record Credentials",
          "record Credentials: Credentials(String,String)",
          "record Credentials: String payload()",
          "record Credentials: String signature()",
          "record Credentials: String toString()",
          "record Credentials: boolean equals(Object)",
          "record Credentials: int hashCode()",
          "record MessageMetadata",
          "record MessageMetadata: MessageMetadata(String,String,String,long)",
          "record MessageMetadata: String messageId()",
          "record MessageMetadata: String segmentId()",
          "record MessageMetadata: String toString()",
          "record MessageMetadata: String tokenReference()",
          "record MessageMetadata: boolean equals(Object)",
          "record MessageMetadata: int hashCode()",
          "record MessageMetadata: long timestamp()",
          "record PresenceConnection",
          "record PresenceConnection: PresenceConnection(String,String,long)",
          "record PresenceConnection: String connectionId()",
          "record PresenceConnection: String toString()",
          "record PresenceConnection: String tokenReference()",
          "record PresenceConnection: boolean equals(Object)",
          "record PresenceConnection: int hashCode()",
          "record PresenceConnection: long timestamp()",
          "record PresenceEvent",
          "record PresenceEvent: PresenceEvent(String,String,String,boolean,long)",
          "record PresenceEvent: String connectionId()",
          "record PresenceEvent: String segmentId()",
          "record PresenceEvent: String toString()",
          "record PresenceEvent: String tokenReference()",
          "record PresenceEvent: boolean equals(Object)",
          "record PresenceEvent: boolean joined()",
          "record PresenceEvent: int hashCode()",
          "record PresenceEvent: long timestamp()",
          "record PresencePage",
          "record PresencePage: List connections()",
          "record PresencePage: PresencePage(String,int,int,int,int,int,List)",
          "record PresencePage: String segmentId()",
          "record PresencePage: String toString()",
          "record PresencePage: boolean equals(Object)",
          "record PresencePage: int currentPage()",
          "record PresencePage: int from()",
          "record PresencePage: int hashCode()",
          "record PresencePage: int perPage()",
          "record PresencePage: int to()",
          "record PresencePage: int total()",
          "record RecoveryEvent",
          "record RecoveryEvent: RecoveryEvent(int,boolean,boolean)",
          "record RecoveryEvent: String toString()",
          "record RecoveryEvent: boolean equals(Object)",
          "record RecoveryEvent: boolean possibleDuplicates()",
          "record RecoveryEvent: boolean possibleGaps()",
          "record RecoveryEvent: int hashCode()",
          "record RecoveryEvent: int retryIndex()",
          "record ServerNotice",
          "record ServerNotice: ServerNotice(long,byte[])",
          "record ServerNotice: String toString()",
          "record ServerNotice: boolean equals(Object)",
          "record ServerNotice: byte[] payload()",
          "record ServerNotice: int hashCode()",
          "record ServerNotice: long timestamp()");

  @Test
  void theSurfaceIsPinned() throws Exception {
    List<String> actual = surface();

    if (!SURFACE.equals(actual)) {
      Files.writeString(Path.of("target/actual-surface.txt"), String.join("\n", actual));
    }

    assertEquals(SURFACE, actual);
  } // end method theSurfaceIsPinned

  @Test
  void nothingPrintsOrLogs() throws IOException {
    for (Path source : mainSources()) {
      String text = Files.readString(source, StandardCharsets.UTF_8);

      for (String forbidden :
          List.of(
              "System.out",
              "System.err",
              "printStackTrace",
              "java.util.logging",
              "System.Logger",
              "System.getLogger")) {
        assertFalse(text.contains(forbidden), source + " uses " + forbidden);
      }
    }
  } // end method nothingPrintsOrLogs

  @Test
  void noSigningFacilityIsReachable() throws IOException {
    for (Path source : mainSources()) {
      String text = Files.readString(source, StandardCharsets.UTF_8);

      for (String forbidden :
          List.of(
              "javax.crypto", "MessageDigest", "Hmac", "com.useceleris.server", "signingSecret")) {
        assertFalse(text.contains(forbidden), source + " mentions " + forbidden);
      }
    }
  } // end method noSigningFacilityIsReachable

  @Test
  void loadingEveryClassStartsNoThread() throws Exception {
    List<String> before = threadNames();

    for (Path compiled : compiledClasses()) {
      String name = compiled.getFileName().toString().replace(".class", "");
      Class.forName("com.useceleris.client." + name, true, PackageTest.class.getClassLoader());
    }

    CelerisClient.create(
            ClientOptions.builder(request -> new java.util.concurrent.CompletableFuture<>())
                .build())
        .channel("room-1")
        .segment("chat");

    List<String> started = threadNames();
    started.removeAll(before);
    assertTrue(
        started.stream().noneMatch(name -> name.startsWith("celeris-")), "started " + started);
  } // end method loadingEveryClassStartsNoThread

  private static List<String> threadNames() {
    return Thread.getAllStackTraces().keySet().stream()
        .map(Thread::getName)
        .collect(Collectors.toCollection(ArrayList::new));
  } // end method threadNames

  private static List<Path> mainSources() throws IOException {
    try (Stream<Path> files = Files.list(MAIN_SOURCES)) {
      return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
    }
  } // end method mainSources

  private static List<Path> compiledClasses() throws IOException {
    try (Stream<Path> files = Files.list(MAIN_CLASSES)) {
      return files.filter(path -> path.toString().endsWith(".class")).sorted().toList();
    }
  } // end method compiledClasses

  private static List<String> surface() throws Exception {
    List<String> entries = new ArrayList<>();

    for (Path compiled : compiledClasses()) {
      String name = compiled.getFileName().toString().replace(".class", "");
      Class<?> type = Class.forName("com.useceleris.client." + name);

      if (!isExported(type) || type.isAnnotation()) {
        continue;
      }

      String kind =
          type.isRecord()
              ? "record"
              : type.isEnum() ? "enum" : type.isInterface() ? "interface" : "class";
      String label = kind + " " + type.getName().replace("com.useceleris.client.", "");
      entries.add(label);

      for (Constructor<?> constructor : type.getConstructors()) {
        entries.add(
            label
                + ": "
                + simpleName(type)
                + "("
                + parameters(constructor.getParameterTypes())
                + ")");
      }

      for (Method method : type.getDeclaredMethods()) {
        if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
          entries.add(
              label
                  + ": "
                  + simpleName(method.getReturnType())
                  + " "
                  + method.getName()
                  + "("
                  + parameters(method.getParameterTypes())
                  + ")");
        }
      }

      for (Field field : type.getDeclaredFields()) {
        if (Modifier.isPublic(field.getModifiers())) {
          entries.add(label + ": " + simpleName(field.getType()) + " " + field.getName());
        }
      }
    }

    entries.sort(null);

    return entries;
  } // end method surface

  /** A type is reachable only when it and every type enclosing it are public. */
  private static boolean isExported(Class<?> type) {
    for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
      if (!Modifier.isPublic(current.getModifiers())) {
        return false;
      }
    }

    return true;
  } // end method isExported

  private static String parameters(Class<?>[] types) {
    return Stream.of(types).map(PackageTest::simpleName).collect(Collectors.joining(","));
  } // end method parameters

  private static String simpleName(Class<?> type) {
    if (type.isArray()) {
      return simpleName(type.getComponentType()) + "[]";
    }

    return type.getName().replaceAll("^.*\\.", "");
  } // end method simpleName
} // end class PackageTest

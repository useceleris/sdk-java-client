package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ErrorsTest {
  @Test
  void codesCarryTheNameEveryCelerisSdkUses() {
    // Hand-copied from the reference's errors.ts.
    List<String> names =
        List.of(
            "Configuration",
            "Timeout",
            "Cancelled",
            "Transport",
            "NotConnected",
            "Backpressure",
            "OperationInProgress",
            "DeliveryUnknown",
            "ProtocolError");

    assertEquals(names, Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList());
  } // end method codesCarryTheNameEveryCelerisSdkUses

  @Test
  void protocolErrorsLocateTheirField() {
    CelerisException error = CelerisException.protocol("Invalid UTF-8 text.", "tokenReference", 5);

    assertEquals(ErrorCode.PROTOCOL, error.code());
    assertEquals("Invalid UTF-8 text. Field: tokenReference, byte offset 5.", error.getMessage());
    assertEquals(Optional.of("tokenReference"), error.field());
    assertEquals(OptionalInt.of(5), error.offset());
  } // end method protocolErrorsLocateTheirField

  @Test
  void protocolErrorsReportOffsetZero() {
    CelerisException error = CelerisException.protocol("Missing field marker.", "message", 0);

    assertEquals(OptionalInt.of(0), error.offset());
  } // end method protocolErrorsReportOffsetZero

  @Test
  void otherErrorsCarryNoFieldOrOffset() {
    for (ErrorCode code : ErrorCode.values()) {
      CelerisException error = new CelerisException(code, "Something failed.");

      assertEquals(code, error.code());
      assertEquals("Something failed.", error.getMessage());
      assertEquals(Optional.empty(), error.field());
      assertEquals(OptionalInt.empty(), error.offset());
    }
  } // end method otherErrorsCarryNoFieldOrOffset

  @Test
  void errorsNeverWrapACause() {
    CelerisException configuration = new CelerisException(ErrorCode.TRANSPORT, "Socket broke.");
    CelerisException protocol = CelerisException.protocol("Unterminated line.", "payload", 3);

    for (CelerisException error : List.of(configuration, protocol)) {
      assertNull(error.getCause());
      assertThrows(
          IllegalStateException.class, () -> error.initCause(new RuntimeException("secret")));
      assertNull(error.getCause());
    }
  } // end method errorsNeverWrapACause

  @Test
  void serverErrorsCarryEveryFieldExactlyAsSent() {
    List<Object> resource = List.of("room", 7, -5L);
    ServerErrorException error =
        new ServerErrorException(
            "FutureError", "PRES_LIST", "Error getting presence data", resource);

    assertEquals("FutureError", error.type());
    assertEquals(Optional.of("PRES_LIST"), error.subType());
    assertEquals("Error getting presence data", error.getMessage());
    assertEquals(Optional.of(resource), error.resource());
    assertNull(error.getCause());
  } // end method serverErrorsCarryEveryFieldExactlyAsSent

  @Test
  void serverErrorsReportMissingSubTypesAndResourcesAsEmpty() {
    ServerErrorException error =
        new ServerErrorException(ServerErrorException.RATE_LIMIT_ERROR, null, "slow", null);

    assertEquals("RateLimitError", error.type());
    assertEquals(Optional.empty(), error.subType());
    assertEquals(Optional.empty(), error.resource());
  } // end method serverErrorsReportMissingSubTypesAndResourcesAsEmpty

  @Test
  void serverErrorTypesAreTheServersNames() {
    assertEquals("ParserError", ServerErrorException.PARSER_ERROR);
    assertEquals("SendError", ServerErrorException.SEND_ERROR);
    assertEquals("PermissionDeniedError", ServerErrorException.PERMISSION_DENIED_ERROR);
    assertEquals("RateLimitError", ServerErrorException.RATE_LIMIT_ERROR);
    assertEquals("MessageSizeLimitError", ServerErrorException.MESSAGE_SIZE_LIMIT_ERROR);
    assertEquals("InternalError", ServerErrorException.INTERNAL_ERROR);
  } // end method serverErrorTypesAreTheServersNames

  @Test
  void serializationKeepsEverythingButTheResource() throws IOException, ClassNotFoundException {
    ServerErrorException error =
        new ServerErrorException("PermissionDeniedError", "SUB", "denied", "room");
    ServerErrorException copy = roundTrip(error, ServerErrorException.class);

    assertEquals("PermissionDeniedError", copy.type());
    assertEquals(Optional.of("SUB"), copy.subType());
    assertEquals("denied", copy.getMessage());
    assertEquals(Optional.empty(), copy.resource());

    CelerisException protocol = CelerisException.protocol("Unterminated line.", "payload", 15);
    CelerisException protocolCopy = roundTrip(protocol, CelerisException.class);

    assertEquals(ErrorCode.PROTOCOL, protocolCopy.code());
    assertEquals(protocol.getMessage(), protocolCopy.getMessage());
    assertEquals(Optional.of("payload"), protocolCopy.field());
    assertEquals(OptionalInt.of(15), protocolCopy.offset());
    assertNull(protocolCopy.getCause());
  } // end method serializationKeepsEverythingButTheResource

  private static <T> T roundTrip(T value, Class<T> type)
      throws IOException, ClassNotFoundException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
      output.writeObject(value);
    }

    try (ObjectInputStream input =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      Object copy = input.readObject();

      assertTrue(type.isInstance(copy));

      return type.cast(copy);
    }
  } // end method roundTrip
} // end class ErrorsTest

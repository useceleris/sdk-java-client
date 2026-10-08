package com.useceleris.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CredentialsTest {
  private static final Credentials CREDENTIALS =
      new Credentials("synthetic-payload", "synthetic-signature");

  @Test
  void credentialsNeverPrintTheirValues() {
    assertEquals("Credentials[redacted]", CREDENTIALS.toString());

    for (String printed :
        List.of(
            String.valueOf(CREDENTIALS),
            String.format("%s", CREDENTIALS),
            List.of(CREDENTIALS).toString(),
            Map.of("credentials", CREDENTIALS).toString())) {
      assertFalse(printed.contains("synthetic"), printed);
    }
  } // end method credentialsNeverPrintTheirValues

  @Test
  void credentialsEncodeAsTheShapeCredentialEndpointsReturn() {
    Credentials credentials = new Credentials("p", "s");
    JsonMapper jackson = JsonMapper.builder().build();
    Gson gson = new Gson();

    assertEquals(
        "{\"payload\":\"p\",\"signature\":\"s\"}", jackson.writeValueAsString(credentials));
    assertEquals("{\"payload\":\"p\",\"signature\":\"s\"}", gson.toJson(credentials));
    assertEquals(
        credentials,
        jackson.readValue("{\"signature\":\"s\",\"payload\":\"p\"}", Credentials.class));
    assertEquals(
        credentials, gson.fromJson("{\"signature\":\"s\",\"payload\":\"p\"}", Credentials.class));
  } // end method credentialsEncodeAsTheShapeCredentialEndpointsReturn
} // end class CredentialsTest

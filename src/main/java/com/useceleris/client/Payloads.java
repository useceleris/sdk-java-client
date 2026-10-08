package com.useceleris.client;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Text payload helpers. Payloads are opaque bytes on the wire: text is the encoding applications
 * reach for first, and every other format, JSON included, goes through a {@link PayloadCodec} built
 * from your own serializer (HELP-01). The JDK has no JSON library, and the SDK bundles none.
 */
public final class Payloads {
  private static final byte[] REPLACEMENT = {(byte) 0xEF, (byte) 0xBF, (byte) 0xBD};

  private Payloads() {}

  /**
   * Encodes text as UTF-8. An unpaired surrogate is replaced with U+FFFD, so the payload is always
   * valid text.
   *
   * @param text the text to encode
   * @return the UTF-8 bytes
   */
  public static byte[] text(String text) {
    CharsetEncoder encoder =
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith(REPLACEMENT);

    try {
      ByteBuffer encoded = encoder.encode(CharBuffer.wrap(text));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);

      return bytes;
    } catch (CharacterCodingException failure) {
      throw new IllegalStateException("UTF-8 encoding with replacement cannot fail", failure);
    }
  } // end method text

  /**
   * Decodes leniently, replacing malformed bytes: delivering a server error must never fail. Each
   * maximal invalid subpart becomes one U+FFFD, as the WHATWG decoder every browser uses does; the
   * JDK's own decoder folds an encoded surrogate such as ED A0 80 into one, where WHATWG gives
   * three.
   */
  static String lenient(byte[] bytes) {
    StringBuilder text = new StringBuilder(bytes.length);
    int codePoint = 0;
    int needed = 0;
    int seen = 0;
    int lower = 0x80;
    int upper = 0xBF;

    // A leading byte order mark is dropped, as TextDecoder drops it.
    boolean byteOrderMark =
        bytes.length >= 3
            && (bytes[0] & 0xFF) == 0xEF
            && (bytes[1] & 0xFF) == 0xBB
            && (bytes[2] & 0xFF) == 0xBF;
    int index = byteOrderMark ? 3 : 0;

    while (index < bytes.length) {
      int value = bytes[index] & 0xFF;

      if (needed == 0) {
        index++;

        if (value <= 0x7F) {
          text.append((char) value);
        } else if (value >= 0xC2 && value <= 0xDF) {
          needed = 1;
          codePoint = value & 0x1F;
        } else if (value >= 0xE0 && value <= 0xEF) {
          lower = value == 0xE0 ? 0xA0 : 0x80;
          upper = value == 0xED ? 0x9F : 0xBF;
          needed = 2;
          codePoint = value & 0x0F;
        } else if (value >= 0xF0 && value <= 0xF4) {
          lower = value == 0xF0 ? 0x90 : 0x80;
          upper = value == 0xF4 ? 0x8F : 0xBF;
          needed = 3;
          codePoint = value & 0x07;
        } else {
          text.append('�');
        }

        continue;
      }

      boolean continuation = value >= lower && value <= upper;
      lower = 0x80;
      upper = 0xBF;

      if (!continuation) {
        // The byte ends the invalid subpart and is read again as the start of the next sequence.
        text.append('�');
        needed = 0;
        seen = 0;

        continue;
      }

      index++;
      codePoint = (codePoint << 6) | (value & 0x3F);
      seen++;

      if (seen == needed) {
        text.appendCodePoint(codePoint);
        needed = 0;
        seen = 0;
      }
    }

    if (needed != 0) {
      text.append('�');
    }

    return text.toString();
  } // end method lenient

  /**
   * Decodes a UTF-8 payload.
   *
   * @param payload the bytes to decode
   * @return the text
   * @throws CelerisException with {@link ErrorCode#CONFIGURATION} when the payload is not valid
   *     UTF-8
   */
  public static String readText(byte[] payload) {
    try {
      return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString();
    } catch (CharacterCodingException failure) {
      throw new CelerisException(
          ErrorCode.CONFIGURATION, "Payload is not valid UTF-8, so it cannot be read as text.");
    }
  } // end method readText
} // end class Payloads

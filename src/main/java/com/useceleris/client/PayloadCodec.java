package com.useceleris.client;

import java.util.function.Function;

/**
 * Gives a serializer of your choice, such as Jackson, Gson, protobuf or MessagePack, one shape for
 * encoding and reading payloads, without the SDK depending on it (HELP-01).
 *
 * <pre>{@code
 * PayloadCodec<Typing> codec =
 *     PayloadCodec.of(
 *         value -> mapper.writeValueAsBytes(value),
 *         payload -> mapper.readValue(payload, Typing.class));
 * }</pre>
 *
 * <p>Failures from the supplied functions propagate unchanged: they are yours, not the SDK's.
 *
 * @param <T> the type the codec encodes and reads
 */
public final class PayloadCodec<T> {
  private final Function<? super T, byte[]> encode;
  private final Function<byte[], ? extends T> decode;

  private PayloadCodec(Function<? super T, byte[]> encode, Function<byte[], ? extends T> decode) {
    this.encode = encode;
    this.decode = decode;
  } // end constructor PayloadCodec

  /**
   * Wraps an encoder and a decoder.
   *
   * @param encode turns a value into payload bytes
   * @param decode turns payload bytes into a value
   * @param <T> the type the codec encodes and reads
   * @return the codec
   * @throws CelerisException with {@link ErrorCode#CONFIGURATION} when either function is null
   */
  public static <T> PayloadCodec<T> of(
      @Nullable Function<? super T, byte[]> encode,
      @Nullable Function<byte[], ? extends T> decode) {
    if (encode == null || decode == null) {
      throw new CelerisException(
          ErrorCode.CONFIGURATION, "Codec must provide encode and decode functions.");
    }

    return new PayloadCodec<>(encode, decode);
  } // end method of

  /**
   * Encodes a value with the codec's encoder.
   *
   * @param value the value to encode
   * @return the payload bytes
   */
  public byte[] encodePayload(T value) {
    return encode.apply(value);
  } // end method encodePayload

  /**
   * Reads a payload with the codec's decoder.
   *
   * @param payload the payload bytes
   * @return the decoded value
   */
  public T readPayload(byte[] payload) {
    return decode.apply(payload);
  } // end method readPayload
} // end class PayloadCodec

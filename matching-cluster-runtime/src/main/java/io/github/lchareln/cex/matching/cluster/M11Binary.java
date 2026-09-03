package io.github.lchareln.cex.matching.cluster;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class M11Binary {
  private M11Binary() {}

  static final class Writer {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final DataOutputStream output = new DataOutputStream(bytes);

    void putByte(int value) {
      write(() -> output.writeByte(value));
    }

    void putInt(int value) {
      write(() -> output.writeInt(value));
    }

    void putLong(long value) {
      write(() -> output.writeLong(value));
    }

    void putBytes(byte[] value) {
      write(() -> output.write(value));
    }

    void putByteArray(byte[] value) {
      putInt(value.length);
      putBytes(value);
    }

    void putString(String value) {
      putByteArray(value.getBytes(StandardCharsets.UTF_8));
    }

    byte[] toByteArray() {
      return bytes.toByteArray();
    }

    private void write(IoAction action) {
      try {
        action.run();
      } catch (IOException failure) {
        throw new IllegalStateException("in-memory encoding failed", failure);
      }
    }
  }

  static final class Reader {
    private final ByteBuffer buffer;

    Reader(byte[] encoded) {
      buffer = ByteBuffer.wrap(encoded).asReadOnlyBuffer();
    }

    int getUnsignedByte() throws M11ProtocolException {
      requireRemaining(Byte.BYTES);
      return Byte.toUnsignedInt(buffer.get());
    }

    int getInt() throws M11ProtocolException {
      requireRemaining(Integer.BYTES);
      return buffer.getInt();
    }

    long getLong() throws M11ProtocolException {
      requireRemaining(Long.BYTES);
      return buffer.getLong();
    }

    int getCount(int maximum) throws M11ProtocolException {
      int value = getInt();
      if (value < 0 || value > maximum) {
        throw failure(M11ProtocolException.Code.LENGTH_LIMIT, "count is outside its bound");
      }
      return value;
    }

    byte[] getBytes(int length) throws M11ProtocolException {
      if (length < 0) {
        throw failure(M11ProtocolException.Code.LENGTH_LIMIT, "negative byte length");
      }
      requireRemaining(length);
      byte[] value = new byte[length];
      buffer.get(value);
      return value;
    }

    byte[] getByteArray(int maximum) throws M11ProtocolException {
      int length = getInt();
      if (length < 0 || length > maximum) {
        throw failure(M11ProtocolException.Code.LENGTH_LIMIT, "byte array is outside its bound");
      }
      return getBytes(length);
    }

    String getString(int maximumBytes) throws M11ProtocolException {
      byte[] encoded = getByteArray(maximumBytes);
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString();
      } catch (CharacterCodingException failure) {
        throw new M11ProtocolException(
            M11ProtocolException.Code.INVALID_VALUE, "string is not strict UTF-8", failure);
      }
    }

    boolean hasRemaining() {
      return buffer.hasRemaining();
    }

    void requireExhausted() throws M11ProtocolException {
      if (hasRemaining()) {
        throw failure(M11ProtocolException.Code.NON_CANONICAL, "trailing bytes are forbidden");
      }
    }

    private void requireRemaining(int count) throws M11ProtocolException {
      if (buffer.remaining() < count) {
        throw failure(M11ProtocolException.Code.TRUNCATED, "input is truncated");
      }
    }
  }

  private static M11ProtocolException failure(M11ProtocolException.Code code, String message) {
    return new M11ProtocolException(code, message);
  }

  @FunctionalInterface
  private interface IoAction {
    void run() throws IOException;
  }
}

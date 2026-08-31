package io.github.lchareln.cex.matching.local;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class BinaryEncoding {
  private BinaryEncoding() {}

  static final class Writer {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final DataOutputStream data = new DataOutputStream(bytes);

    void putByte(int value) {
      try {
        data.writeByte(value);
      } catch (IOException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    void putInt(int value) {
      try {
        data.writeInt(value);
      } catch (IOException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    void putLong(long value) {
      try {
        data.writeLong(value);
      } catch (IOException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    void putBytes(byte[] value) {
      try {
        data.write(value);
      } catch (IOException impossible) {
        throw new IllegalStateException(impossible);
      }
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
  }

  static final class Reader {
    private final ByteBuffer bytes;

    Reader(byte[] value) {
      bytes = ByteBuffer.wrap(value).asReadOnlyBuffer();
    }

    int getUnsignedByte() throws StructuralRejectionException {
      require(1);
      return Byte.toUnsignedInt(bytes.get());
    }

    int getInt() throws StructuralRejectionException {
      require(Integer.BYTES);
      return bytes.getInt();
    }

    long getLong() throws StructuralRejectionException {
      require(Long.BYTES);
      return bytes.getLong();
    }

    byte[] getBytes(int length, int limit) throws StructuralRejectionException {
      if (length < 0 || length > limit) {
        throw malformed("declared byte length is outside the configured limit");
      }
      require(length);
      byte[] value = new byte[length];
      bytes.get(value);
      return value;
    }

    byte[] getByteArray(int limit) throws StructuralRejectionException {
      return getBytes(getInt(), limit);
    }

    String getString(int byteLimit) throws StructuralRejectionException {
      byte[] encoded = getByteArray(byteLimit);
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString();
      } catch (CharacterCodingException failure) {
        throw new StructuralRejectionException(
            StructuralRejectionCode.MALFORMED_ENVELOPE, "string is not canonical UTF-8", failure);
      }
    }

    boolean hasRemaining() {
      return bytes.hasRemaining();
    }

    private void require(int count) throws StructuralRejectionException {
      if (count < 0 || bytes.remaining() < count) {
        throw malformed("truncated canonical bytes");
      }
    }

    private static StructuralRejectionException malformed(String message) {
      return new StructuralRejectionException(StructuralRejectionCode.MALFORMED_ENVELOPE, message);
    }
  }
}

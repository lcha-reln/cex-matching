package io.github.lchareln.cex.matching.cluster;

import io.github.lchareln.cex.matching.local.CommandApplierState;
import io.github.lchareln.cex.matching.local.Slot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Canonical payload embedded by both M11S1 and M11S2 snapshots. */
public final class M11RuntimeStateCodec {
  public static final int MAX_STATE_BYTES = 256 * 1024 * 1024;

  private static final int MAX_STRING_BYTES = 1024 * 1024;
  static final int MAX_BINDINGS = 2_000_000;

  public byte[] encode(M11RuntimeState state) {
    Objects.requireNonNull(state, "state");
    M11Binary.Writer writer = new M11Binary.Writer();
    M11CommandStateCodec.putState(writer, state.commandState());
    putIdentityTable(writer, state.identityBindings());
    byte[] encoded = writer.toByteArray();
    if (encoded.length > MAX_STATE_BYTES) {
      throw new IllegalArgumentException("M11 runtime state exceeds the snapshot bound");
    }
    return encoded;
  }

  public M11RuntimeState decodeCanonical(byte[] encoded) throws M11ProtocolException {
    Objects.requireNonNull(encoded, "encoded");
    if (encoded.length == 0 || encoded.length > MAX_STATE_BYTES) {
      throw new M11ProtocolException(
          M11ProtocolException.Code.LENGTH_LIMIT, "M11 runtime state length is outside its bound");
    }
    M11Binary.Reader reader = new M11Binary.Reader(encoded);
    CommandApplierState commandState = M11CommandStateCodec.readState(reader);
    List<M11IdentityBinding> identities = readIdentityTable(reader);
    reader.requireExhausted();
    final M11RuntimeState state;
    try {
      state = new M11RuntimeState(commandState, identities);
    } catch (IllegalArgumentException failure) {
      throw new M11ProtocolException(
          M11ProtocolException.Code.INVALID_VALUE,
          "M11 runtime state is internally inconsistent",
          failure);
    }
    if (!Arrays.equals(encoded, encode(state))) {
      throw new M11ProtocolException(
          M11ProtocolException.Code.NON_CANONICAL, "M11 runtime state is not canonical");
    }
    return state;
  }

  public byte[] encodeIdentityTable(List<M11IdentityBinding> identities) {
    M11Binary.Writer writer = new M11Binary.Writer();
    putIdentityTable(writer, List.copyOf(identities));
    return writer.toByteArray();
  }

  public String identityTableDigest(List<M11IdentityBinding> identities) {
    return M11Digests.sha256Hex(encodeIdentityTable(identities));
  }

  private static void putIdentityTable(
      M11Binary.Writer writer, List<M11IdentityBinding> identities) {
    requireEncodableBindingCount(identities.size());
    writer.putInt(identities.size());
    for (M11IdentityBinding binding : identities) {
      writer.putLong(binding.commandId().getMostSignificantBits());
      writer.putLong(binding.commandId().getLeastSignificantBits());
      writer.putString(binding.slot().producerId());
      writer.putLong(binding.slot().producerEpoch());
      writer.putLong(binding.slot().shardId());
      writer.putLong(binding.slot().producerSequence());
      writer.putString(binding.payloadHash());
      M11FullResultCodec.putResult(writer, binding.result());
    }
  }

  static void requireEncodableBindingCount(int count) {
    M11EncodingBounds.requireAtMost(count, MAX_BINDINGS, "identity binding count");
  }

  private static List<M11IdentityBinding> readIdentityTable(M11Binary.Reader reader)
      throws M11ProtocolException {
    int count = reader.getCount(MAX_BINDINGS);
    List<M11IdentityBinding> identities = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      UUID commandId = new UUID(reader.getLong(), reader.getLong());
      Slot slot =
          construct(
              () ->
                  new Slot(
                      reader.getString(MAX_STRING_BYTES),
                      reader.getLong(),
                      reader.getLong(),
                      reader.getLong()),
              "identity slot");
      String payloadHash = reader.getString(MAX_STRING_BYTES);
      M11IdentityBinding binding =
          construct(
              () ->
                  new M11IdentityBinding(
                      commandId, slot, payloadHash, M11FullResultCodec.readResult(reader)),
              "identity binding");
      identities.add(binding);
    }
    return identities;
  }

  private static <T> T construct(Constructor<T> constructor, String field)
      throws M11ProtocolException {
    try {
      return constructor.create();
    } catch (IllegalArgumentException failure) {
      throw new M11ProtocolException(
          M11ProtocolException.Code.INVALID_VALUE, "invalid " + field, failure);
    }
  }

  @FunctionalInterface
  private interface Constructor<T> {
    T create() throws M11ProtocolException;
  }
}

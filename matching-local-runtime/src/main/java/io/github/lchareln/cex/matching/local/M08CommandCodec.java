package io.github.lchareln.cex.matching.local;

import io.github.lchareln.cex.matching.MarketMode;
import io.github.lchareln.cex.matching.MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.PriceTicks;
import io.github.lchareln.cex.matching.RuleSetIdentity;
import io.github.lchareln.cex.matching.RuleSetVersion;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/** Canonical M08C1 command-payload codec; this is an internal journal format, not a wire API. */
public final class M08CommandCodec {
  public static final int VERSION = 1;
  public static final int MAX_COMMAND_BYTES = 256 * 1024;

  private static final int MAX_STRING_BYTES = 16 * 1024;
  private static final int MAX_INTEGER_BYTES = 1024;

  private static final int PLACE = 1;
  private static final int CANCEL = 2;
  private static final int PREPARE_RULE_SET = 3;
  private static final int ACTIVATE_RULE_SET = 4;
  private static final int CHANGE_MARKET_MODE = 5;
  private static final int MASS_CANCEL = 6;

  public byte[] encode(M08Command command) {
    BinaryEncoding.Writer writer = new BinaryEncoding.Writer();
    writer.putInt(VERSION);
    switch (command) {
      case M08Command.Place place -> encodePlace(writer, place);
      case M08Command.Cancel cancel -> encodeCancel(writer, cancel);
      case M08Command.PrepareRuleSet prepare -> encodePrepare(writer, prepare);
      case M08Command.ActivateRuleSet activate -> encodeActivate(writer, activate);
      case M08Command.ChangeMarketMode change -> encodeChangeMode(writer, change);
      case M08Command.MassCancel massCancel -> encodeMassCancel(writer, massCancel);
    }
    byte[] encoded = writer.toByteArray();
    if (encoded.length > MAX_COMMAND_BYTES) {
      throw new IllegalArgumentException("canonical command exceeds M08C1 size limit");
    }
    return encoded;
  }

  public M08Command decodeCanonical(byte[] encoded) throws StructuralRejectionException {
    if (encoded.length == 0 || encoded.length > MAX_COMMAND_BYTES) {
      throw new StructuralRejectionException(
          StructuralRejectionCode.COMMAND_SIZE_LIMIT,
          "canonical command is empty or exceeds M08C1 size limit");
    }
    M08Command command = decode(encoded);
    if (!Arrays.equals(encoded, encode(command))) {
      throw new StructuralRejectionException(
          StructuralRejectionCode.NON_CANONICAL_ENVELOPE,
          "decoded command does not round-trip byte-for-byte");
    }
    return command;
  }

  private M08Command decode(byte[] encoded) throws StructuralRejectionException {
    BinaryEncoding.Reader reader = new BinaryEncoding.Reader(encoded);
    int version = reader.getInt();
    if (version != VERSION) {
      throw malformed("unsupported M08C1 command version");
    }
    int type = reader.getInt();
    final M08Command command;
    try {
      command =
          switch (type) {
            case PLACE -> decodePlace(reader);
            case CANCEL -> decodeCancel(reader);
            case PREPARE_RULE_SET -> decodePrepare(reader);
            case ACTIVATE_RULE_SET -> decodeActivate(reader);
            case CHANGE_MARKET_MODE -> decodeChangeMode(reader);
            case MASS_CANCEL -> decodeMassCancel(reader);
            default -> throw malformed("unknown M08C1 command type");
          };
    } catch (IllegalArgumentException failure) {
      throw new StructuralRejectionException(
          StructuralRejectionCode.MALFORMED_ENVELOPE,
          "command contains a value that core cannot construct",
          failure);
    }
    if (reader.hasRemaining()) {
      throw new StructuralRejectionException(
          StructuralRejectionCode.NON_CANONICAL_ENVELOPE,
          "canonical command contains trailing bytes");
    }
    return command;
  }

  private static void encodePlace(BinaryEncoding.Writer writer, M08Command.Place command) {
    writer.putInt(PLACE);
    putString(writer, command.instrumentId());
    putBigInteger(writer, command.orderId());
    putString(writer, command.side());
    putBigInteger(writer, command.priceTicks());
    putBigInteger(writer, command.quantityLots());
    putString(writer, command.executionPolicy());
    writer.putLong(command.participantGroupId());
    putString(writer, command.stpPolicy());
    writer.putByte(command.expectedActive().isPresent() ? 1 : 0);
    command.expectedActive().ifPresent(identity -> putRuleIdentity(writer, identity));
  }

  private static M08Command.Place decodePlace(BinaryEncoding.Reader reader)
      throws StructuralRejectionException {
    String instrument = reader.getString(MAX_STRING_BYTES);
    BigInteger orderId = getBigInteger(reader);
    String side = reader.getString(MAX_STRING_BYTES);
    BigInteger price = getBigInteger(reader);
    BigInteger quantity = getBigInteger(reader);
    String policy = reader.getString(MAX_STRING_BYTES);
    long group = reader.getLong();
    String stpPolicy = reader.getString(MAX_STRING_BYTES);
    int governed = reader.getUnsignedByte();
    if (governed != 0 && governed != 1) {
      throw malformed("governed flag is not canonical");
    }
    Optional<RuleSetIdentity> expected =
        governed == 1 ? Optional.of(getRuleIdentity(reader)) : Optional.empty();
    return new M08Command.Place(
        instrument, orderId, side, price, quantity, policy, group, stpPolicy, expected);
  }

  private static void encodeCancel(BinaryEncoding.Writer writer, M08Command.Cancel command) {
    writer.putInt(CANCEL);
    putString(writer, command.instrumentId());
    putBigInteger(writer, command.orderId());
  }

  private static M08Command.Cancel decodeCancel(BinaryEncoding.Reader reader)
      throws StructuralRejectionException {
    return new M08Command.Cancel(reader.getString(MAX_STRING_BYTES), getBigInteger(reader));
  }

  private static void encodePrepare(
      BinaryEncoding.Writer writer, M08Command.PrepareRuleSet command) {
    writer.putInt(PREPARE_RULE_SET);
    putRuleIdentity(writer, command.expectedActive());
    MarketRuleSetArtifact artifact = command.artifact();
    putString(writer, artifact.schemaVersion());
    putString(writer, artifact.instrumentId());
    writer.putLong(artifact.version().value());
    writer.putLong(artifact.lowerInclusive().value());
    writer.putLong(artifact.upperInclusive().value());
    putString(writer, artifact.contentHash());
  }

  private static M08Command.PrepareRuleSet decodePrepare(BinaryEncoding.Reader reader)
      throws StructuralRejectionException {
    RuleSetIdentity expected = getRuleIdentity(reader);
    MarketRuleSetArtifact artifact =
        new MarketRuleSetArtifact(
            reader.getString(MAX_STRING_BYTES),
            reader.getString(MAX_STRING_BYTES),
            new RuleSetVersion(reader.getLong()),
            new PriceTicks(reader.getLong()),
            new PriceTicks(reader.getLong()),
            reader.getString(MAX_STRING_BYTES));
    return new M08Command.PrepareRuleSet(expected, artifact);
  }

  private static void encodeActivate(
      BinaryEncoding.Writer writer, M08Command.ActivateRuleSet command) {
    writer.putInt(ACTIVATE_RULE_SET);
    writer.putLong(command.expectedApplicationSequence());
    putRuleIdentity(writer, command.expectedActive());
    putRuleIdentity(writer, command.target());
  }

  private static M08Command.ActivateRuleSet decodeActivate(BinaryEncoding.Reader reader)
      throws StructuralRejectionException {
    return new M08Command.ActivateRuleSet(
        reader.getLong(), getRuleIdentity(reader), getRuleIdentity(reader));
  }

  private static void encodeChangeMode(
      BinaryEncoding.Writer writer, M08Command.ChangeMarketMode command) {
    writer.putInt(CHANGE_MARKET_MODE);
    writer.putLong(command.expectedApplicationSequence());
    putString(writer, command.expectedMode().name());
    putString(writer, command.targetMode().name());
    putString(writer, command.operatorId());
  }

  private static M08Command.ChangeMarketMode decodeChangeMode(BinaryEncoding.Reader reader)
      throws StructuralRejectionException {
    return new M08Command.ChangeMarketMode(
        reader.getLong(),
        MarketMode.valueOf(reader.getString(MAX_STRING_BYTES)),
        MarketMode.valueOf(reader.getString(MAX_STRING_BYTES)),
        reader.getString(MAX_STRING_BYTES));
  }

  private static void encodeMassCancel(
      BinaryEncoding.Writer writer, M08Command.MassCancel command) {
    writer.putInt(MASS_CANCEL);
    writer.putLong(command.expectedApplicationSequence());
    putString(writer, command.expectedMode().name());
    putString(writer, command.operatorId());
  }

  private static M08Command.MassCancel decodeMassCancel(BinaryEncoding.Reader reader)
      throws StructuralRejectionException {
    return new M08Command.MassCancel(
        reader.getLong(),
        MarketMode.valueOf(reader.getString(MAX_STRING_BYTES)),
        reader.getString(MAX_STRING_BYTES));
  }

  private static void putRuleIdentity(BinaryEncoding.Writer writer, RuleSetIdentity identity) {
    writer.putLong(identity.version().value());
    putString(writer, identity.contentHash());
  }

  private static RuleSetIdentity getRuleIdentity(BinaryEncoding.Reader reader)
      throws StructuralRejectionException {
    return new RuleSetIdentity(reader.getLong(), reader.getString(MAX_STRING_BYTES));
  }

  private static void putBigInteger(BinaryEncoding.Writer writer, BigInteger value) {
    String canonical = value.toString();
    if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_INTEGER_BYTES) {
      throw new IllegalArgumentException("canonical integer exceeds M08C1 field limit");
    }
    writer.putString(canonical);
  }

  private static void putString(BinaryEncoding.Writer writer, String value) {
    if (value.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_BYTES) {
      throw new IllegalArgumentException("canonical string exceeds M08C1 field limit");
    }
    writer.putString(value);
  }

  private static BigInteger getBigInteger(BinaryEncoding.Reader reader)
      throws StructuralRejectionException {
    String raw = reader.getString(MAX_INTEGER_BYTES);
    try {
      BigInteger value = new BigInteger(raw);
      if (!value.toString().equals(raw)) {
        throw malformed("BigInteger is not in canonical decimal form");
      }
      return value;
    } catch (NumberFormatException failure) {
      throw new StructuralRejectionException(
          StructuralRejectionCode.MALFORMED_ENVELOPE,
          "BigInteger is not in canonical decimal form",
          failure);
    }
  }

  private static StructuralRejectionException malformed(String message) {
    return new StructuralRejectionException(StructuralRejectionCode.MALFORMED_ENVELOPE, message);
  }
}

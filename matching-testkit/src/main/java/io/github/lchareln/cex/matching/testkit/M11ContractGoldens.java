package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.M08Envelope;
import io.github.lchareln.cex.matching.local.M08EnvelopeCodec;
import io.github.lchareln.cex.matching.local.M11GoldenStateProducer;
import io.github.lchareln.cex.matching.local.StructuralRejectionException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.CRC32C;

/** Reproducible source for the six immutable M11 application-protocol goldens. */
public final class M11ContractGoldens {
  private static final int REQUEST_MAGIC = 0x4d313151; // M11Q
  private static final int RESPONSE_MAGIC = 0x4d313152; // M11R
  private static final int SNAPSHOT_MAGIC = 0x4d313153; // M11S
  private static final UUID CORRELATION = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final UUID COMMAND = UUID.fromString("11111111-2222-3333-4444-555555555555");

  private M11ContractGoldens() {}

  public static List<Fixture> fixtures() {
    byte[] envelopeBytes = envelope();
    M11GoldenStateProducer.GoldenState state =
        M11GoldenStateProducer.produce(decode(envelopeBytes));
    return List.of(
        new Fixture("REQUEST_V1", "REQUEST", 1, "request-v1.bin", request(1, envelopeBytes)),
        new Fixture("REQUEST_V2", "REQUEST", 2, "request-v2.bin", request(2, envelopeBytes)),
        new Fixture("RESPONSE_V1", "RESPONSE", 1, "response-v1.bin", response(1, state)),
        new Fixture("RESPONSE_V2", "RESPONSE", 2, "response-v2.bin", response(2, state)),
        new Fixture("SNAPSHOT_V1", "SNAPSHOT", 1, "snapshot-v1.bin", snapshot(1, state)),
        new Fixture("SNAPSHOT_V2", "SNAPSHOT", 2, "snapshot-v2.bin", snapshot(2, state)));
  }

  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("usage: M11ContractGoldens <output-directory>");
    }
    Path output = Path.of(arguments[0]).toAbsolutePath().normalize();
    fixtures()
        .forEach(fixture -> AtomicFiles.write(output.resolve(fixture.fileName()), fixture.bytes()));
  }

  private static byte[] envelope() {
    return new M08EnvelopeCodec()
        .encode(
            "m11-golden",
            1,
            1,
            1,
            COMMAND,
            new M08Command.Place(
                "BTC-USDT",
                BigInteger.valueOf(42),
                "BUY",
                BigInteger.valueOf(6_500_000),
                BigInteger.valueOf(3),
                "GTC",
                0,
                "NONE",
                Optional.empty()));
  }

  private static M08Envelope decode(byte[] envelope) {
    try {
      return new M08EnvelopeCodec().decodeCanonical(envelope, 1);
    } catch (StructuralRejectionException failure) {
      throw new IllegalStateException("generated M08C1 envelope is not canonical", failure);
    }
  }

  private static byte[] request(int version, byte[] envelope) {
    return frame(
        out -> {
          out.writeInt(REQUEST_MAGIC);
          out.writeInt(version);
          out.writeInt(1);
          writeUuid(out, CORRELATION);
          if (version == 2) {
            out.writeInt(2);
          }
          writeBytes(out, envelope);
        });
  }

  private static byte[] response(int version, M11GoldenStateProducer.GoldenState state) {
    return frame(
        out -> {
          out.writeInt(RESPONSE_MAGIC);
          out.writeInt(version);
          out.writeInt(2);
          writeUuid(out, CORRELATION);
          out.writeInt(1); // NEW_APPLIED
          out.writeLong(1);
          out.write(HexFormat.of().parseHex(state.resultDigest()));
          if (version == 2) {
            out.writeByte(1);
            writeUuid(out, COMMAND);
            out.write(HexFormat.of().parseHex(state.responseSemanticStateDigest()));
          }
        });
  }

  private static byte[] snapshot(int version, M11GoldenStateProducer.GoldenState state) {
    byte[] prefix =
        frame(
            out -> {
              out.writeInt(SNAPSHOT_MAGIC);
              out.writeInt(version);
              out.writeInt(3);
              if (version == 2) {
                out.writeInt(1); // minimum readable
                out.writeInt(2); // current writer
              }
              writeBytes(out, state.canonicalStateBytes());
              if (version == 2) {
                out.write(sha256(state.identityTableBytes()));
                out.write(HexFormat.of().parseHex(state.semanticStateDigest()));
              }
            });
    CRC32C crc = new CRC32C();
    crc.update(prefix, 0, prefix.length);
    byte[] withCrc =
        frame(
            out -> {
              out.write(prefix);
              out.writeInt((int) crc.getValue());
            });
    return frame(
        out -> {
          out.write(withCrc);
          out.write(sha256(withCrc));
        });
  }

  private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
    out.writeLong(value.getMostSignificantBits());
    out.writeLong(value.getLeastSignificantBits());
  }

  private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
    out.writeInt(value.length);
    out.write(value);
  }

  private static byte[] frame(Writer writer) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        writer.write(out);
      }
      return bytes.toByteArray();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot encode M11 golden", failure);
    }
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  @FunctionalInterface
  private interface Writer {
    void write(DataOutputStream output) throws IOException;
  }

  public record Fixture(String id, String kind, int schemaVersion, String fileName, byte[] bytes) {
    public Fixture {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}

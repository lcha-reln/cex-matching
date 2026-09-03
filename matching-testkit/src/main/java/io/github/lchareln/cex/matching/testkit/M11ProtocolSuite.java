package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.cluster.DirectM11MatchingRuntime;
import io.github.lchareln.cex.matching.cluster.M11ApplicationResult;
import io.github.lchareln.cex.matching.cluster.M11CommandRequest;
import io.github.lchareln.cex.matching.cluster.M11CommandResponse;
import io.github.lchareln.cex.matching.cluster.M11ProtocolException;
import io.github.lchareln.cex.matching.cluster.M11RequestCodec;
import io.github.lchareln.cex.matching.cluster.M11ResponseCodec;
import io.github.lchareln.cex.matching.cluster.M11ResponseStatus;
import io.github.lchareln.cex.matching.cluster.M11RuntimeStateCodec;
import io.github.lchareln.cex.matching.cluster.M11Snapshot;
import io.github.lchareln.cex.matching.cluster.M11SnapshotCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Executes every frozen request, response, and snapshot golden plus fail-closed probes. */
final class M11ProtocolSuite {
  Result run(Path repositoryRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    JsonNode workload = JsonSupport.parse(read(root.resolve(M11StartCheckRunner.WORKLOAD_PATH)));
    JsonSupport.validate(
        workload, readString(root.resolve(M11StartCheckRunner.WORKLOAD_SCHEMA_PATH)), false);
    M11RequestCodec requestCodec = new M11RequestCodec();
    M11ResponseCodec responseCodec = new M11ResponseCodec();
    M11SnapshotCodec snapshotCodec = new M11SnapshotCodec();
    ArrayNode fixtures = JsonSupport.MAPPER.createArrayNode();
    List<M11CommandRequest> requests = new ArrayList<>();
    List<M11CommandResponse> responses = new ArrayList<>();
    List<M11Snapshot> snapshots = new ArrayList<>();

    for (JsonNode binding : workload.path("goldenFixtures")) {
      Path path = root.resolve(binding.path("path").stringValue()).normalize();
      require(path.startsWith(root) && Files.isRegularFile(path), "M11 golden is missing or unsafe");
      byte[] bytes = read(path);
      require(bytes.length == binding.path("bytes").intValue(), "M11 golden byte count changed");
      require(
          Hashing.sha256Hex(bytes).equals(binding.path("sha256").stringValue()),
          "M11 golden SHA-256 changed");
      String kind = binding.path("kind").stringValue();
      int version = binding.path("schemaVersion").intValue();
      switch (kind) {
        case "REQUEST" -> {
          M11CommandRequest decoded = decodeRequest(requestCodec, bytes);
          require(decoded.protocolVersion() == version, "request golden version changed");
          require(Arrays.equals(bytes, requestCodec.encode(decoded)), "request golden is not byte-exact");
          requests.add(decoded);
        }
        case "RESPONSE" -> {
          M11CommandResponse decoded = decodeResponse(responseCodec, bytes);
          require(decoded.protocolVersion() == version, "response golden version changed");
          require(Arrays.equals(bytes, responseCodec.encode(decoded)), "response golden is not byte-exact");
          responses.add(decoded);
        }
        case "SNAPSHOT" -> {
          M11Snapshot decoded = decodeSnapshot(snapshotCodec, bytes);
          require(decoded.schemaVersion() == version, "snapshot golden version changed");
          require(Arrays.equals(bytes, snapshotCodec.encode(version, decoded.state())), "snapshot golden is not byte-exact");
          require(decoded.state().identityBindings().size() >= 2, "snapshot golden needs two real identity bindings");
          DirectM11MatchingRuntime.restore(decoded.state());
          snapshots.add(decoded);
        }
        default -> throw new M11SemanticFailure("unknown M11 golden kind " + kind);
      }
      ObjectNode entry = fixtures.addObject();
      entry.put("id", binding.path("id").stringValue());
      entry.put("kind", kind);
      entry.put("schemaVersion", version);
      entry.put("path", binding.path("path").stringValue());
      entry.put("bytes", bytes.length);
      entry.put("sha256", Hashing.sha256Hex(bytes));
      entry.put("decoded", true);
      entry.put("reencodedByteExact", true);
    }
    require(requests.size() == 2 && responses.size() == 2 && snapshots.size() == 2, "golden matrix changed");
    require(requests.getFirst().protocolVersion() == 1, "request v1 golden missing");
    require(requests.getFirst().requestedResponseVersion() == 1, "request v1 did not fix response v1");
    require(requests.getLast().protocolVersion() == 2, "request v2 golden missing");
    require(responses.getFirst().protocolVersion() == 1, "response v1 golden missing");
    require(responses.getLast().protocolVersion() == 2, "response v2 golden missing");
    require(snapshots.getFirst().schemaVersion() == 1, "snapshot S1 golden missing");
    require(snapshots.getLast().schemaVersion() == 2, "snapshot S2 golden missing");

    verifyS1Idempotency(requests.getFirst(), snapshots.getFirst());
    verifyInvalidRequestedResponse(requestCodec, requests.getLast());
    verifyMalformed(requestCodec, responseCodec, snapshotCodec, root, workload);
    verifyUnsupported(requestCodec, responseCodec, snapshotCodec, root, workload);

    ObjectNode report = JsonSupport.MAPPER.createObjectNode();
    report.put("schemaVersion", "matching.m11.protocol-goldens.v1");
    report.put("status", M11CheckRunner.PASS);
    report.put("goldens", 6);
    report.put("requestV1Readable", true);
    report.put("requestV1FixesResponseV1", true);
    report.put("requestV2Current", true);
    report.put("requestV2ResponseBounds", "ONE_OR_TWO_ONLY_PRE_APPLY");
    report.put("invalidRequestedResponseStateMutations", 0);
    report.put("fabricatedBusinessResults", 0);
    report.put("responseV1DownEncoded", true);
    report.put("responseV2Current", true);
    report.put("snapshotS1ReadableAndRestorable", true);
    report.put("snapshotS2Current", true);
    report.put("snapshotIdentityBindingsMinimum", 2);
    report.put("snapshotIdentityOrder", "ORIGINAL_APPLICATION_SEQUENCE_1_TO_N");
    report.put("snapshotProducerCursorContinuityValidated", true);
    report.put("nMinusOneIdempotencyPreserved", true);
    report.put("malformedFailsClosed", true);
    report.put("unsupportedFailsClosed", true);
    report.put("boundedResponse", true);
    report.put("fullEventStreamInResponse", false);
    report.set("fixtures", fixtures);
    return new Result(report, requests, responses, snapshots);
  }

  private static void verifyS1Idempotency(M11CommandRequest request, M11Snapshot snapshot) {
    DirectM11MatchingRuntime restored = DirectM11MatchingRuntime.restore(snapshot.state());
    long beforeSequence = restored.nextApplicationSequence();
    String beforeDigest = restored.semanticStateDigest();
    M11ApplicationResult duplicate = restored.submit(request.withCorrelationId(new java.util.UUID(7, 11)));
    require(duplicate.response().status() == M11ResponseStatus.DUPLICATE_REPLAYED, "S1 lost duplicate identity");
    require(duplicate.fullResult().isPresent(), "S1 lost original result");
    require(restored.nextApplicationSequence() == beforeSequence, "S1 duplicate advanced sequence");
    require(restored.semanticStateDigest().equals(beforeDigest), "S1 duplicate mutated state");
  }

  private static void verifyInvalidRequestedResponse(
      M11RequestCodec codec, M11CommandRequest requestV2) {
    byte[] invalid = codec.encode(requestV2);
    ByteBuffer.wrap(invalid).putInt(Integer.BYTES * 3 + Long.BYTES * 2, 3);
    DirectM11MatchingRuntime untouched = new DirectM11MatchingRuntime();
    long sequence = untouched.nextApplicationSequence();
    String digest = untouched.semanticStateDigest();
    expectProtocolFailure(() -> codec.decodeCanonical(invalid, 1), "request v2 accepted response v3");
    require(sequence == untouched.nextApplicationSequence(), "invalid response version advanced state");
    require(digest.equals(untouched.semanticStateDigest()), "invalid response version mutated state");
  }

  private static void verifyMalformed(
      M11RequestCodec requests,
      M11ResponseCodec responses,
      M11SnapshotCodec snapshots,
      Path root,
      JsonNode workload) {
    byte[] request = fixture(root, workload, "REQUEST_V2");
    byte[] response = fixture(root, workload, "RESPONSE_V2");
    byte[] snapshot = fixture(root, workload, "SNAPSHOT_V2");
    expectProtocolFailure(
        () -> requests.decodeCanonical(Arrays.copyOf(request, request.length - 1), 1),
        "truncated request was accepted");
    expectProtocolFailure(
        () -> responses.decodeCanonical(Arrays.copyOf(response, response.length - 1)),
        "truncated response was accepted");
    expectProtocolFailure(
        () -> snapshots.decodeCanonical(Arrays.copyOf(snapshot, snapshot.length - 1)),
        "truncated snapshot was accepted");
  }

  private static void verifyUnsupported(
      M11RequestCodec requests,
      M11ResponseCodec responses,
      M11SnapshotCodec snapshots,
      Path root,
      JsonNode workload) {
    byte[] request = fixture(root, workload, "REQUEST_V2");
    byte[] response = fixture(root, workload, "RESPONSE_V2");
    byte[] snapshot = fixture(root, workload, "SNAPSHOT_V2");
    ByteBuffer.wrap(request).putInt(Integer.BYTES, 3);
    ByteBuffer.wrap(response).putInt(Integer.BYTES, 3);
    byte[] unsupportedSnapshot = repairSnapshotVersion(snapshot, 3);
    expectProtocolFailure(() -> requests.decodeCanonical(request, 1), "request v3 was accepted");
    expectProtocolFailure(() -> responses.decodeCanonical(response), "response v3 was accepted");
    expectProtocolFailure(() -> snapshots.decodeCanonical(unsupportedSnapshot), "snapshot S3 was accepted");
  }

  private static byte[] repairSnapshotVersion(byte[] source, int version) {
    byte[] value = source.clone();
    ByteBuffer.wrap(value).putInt(Integer.BYTES, version);
    int digestOffset = value.length - 32;
    int crcOffset = digestOffset - Integer.BYTES;
    CRC32C crc = new CRC32C();
    crc.update(value, 0, crcOffset);
    ByteBuffer.wrap(value).putInt(crcOffset, (int) crc.getValue());
    byte[] digest = sha256(Arrays.copyOf(value, digestOffset));
    System.arraycopy(digest, 0, value, digestOffset, digest.length);
    return value;
  }

  private static byte[] fixture(Path root, JsonNode workload, String id) {
    for (JsonNode binding : workload.path("goldenFixtures")) {
      if (id.equals(binding.path("id").stringValue())) {
        return read(root.resolve(binding.path("path").stringValue()));
      }
    }
    throw new IllegalStateException("missing M11 golden " + id);
  }

  private static M11CommandRequest decodeRequest(M11RequestCodec codec, byte[] bytes) {
    try {
      return codec.decodeCanonical(bytes, 1);
    } catch (M11ProtocolException failure) {
      throw new M11SemanticFailure("request golden did not decode: " + failure.code());
    }
  }

  private static M11CommandResponse decodeResponse(M11ResponseCodec codec, byte[] bytes) {
    try {
      return codec.decodeCanonical(bytes);
    } catch (M11ProtocolException failure) {
      throw new M11SemanticFailure("response golden did not decode: " + failure.code());
    }
  }

  private static M11Snapshot decodeSnapshot(M11SnapshotCodec codec, byte[] bytes) {
    try {
      return codec.decodeCanonical(bytes);
    } catch (M11ProtocolException failure) {
      throw new M11SemanticFailure("snapshot golden did not decode: " + failure.code());
    }
  }

  private static void expectProtocolFailure(ThrowingAction action, String message) {
    try {
      action.run();
      throw new M11SemanticFailure(message);
    } catch (M11ProtocolException expected) {
      // Strict decoder rejected the invalid bytes.
    }
  }

  private static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static String readString(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 unavailable", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M11SemanticFailure(message);
    }
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws M11ProtocolException;
  }

  record Result(
      ObjectNode report,
      List<M11CommandRequest> requests,
      List<M11CommandResponse> responses,
      List<M11Snapshot> snapshots) {
    Result {
      requests = List.copyOf(requests);
      responses = List.copyOf(responses);
      snapshots = List.copyOf(snapshots);
    }
  }
}

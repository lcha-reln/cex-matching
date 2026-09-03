package io.github.lchareln.cex.matching.cluster;

import io.github.lchareln.cex.matching.local.CanonicalResult;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Bounded result commitment returned after the business result has been bound. */
public record M11CommandResponse(
    int protocolVersion,
    UUID correlationId,
    M11ResponseStatus status,
    OptionalLong applicationSequence,
    Optional<String> resultDigest,
    Optional<String> rejectionCode,
    Optional<UUID> commandId,
    Optional<String> semanticStateDigest) {
  public M11CommandResponse {
    if (protocolVersion < M11ResponseCodec.MIN_READABLE_VERSION
        || protocolVersion > M11ResponseCodec.CURRENT_VERSION) {
      throw new IllegalArgumentException("unsupported response protocol version");
    }
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(status, "status");
    applicationSequence = Objects.requireNonNull(applicationSequence, "applicationSequence");
    resultDigest = Objects.requireNonNull(resultDigest, "resultDigest");
    rejectionCode = Objects.requireNonNull(rejectionCode, "rejectionCode");
    commandId = Objects.requireNonNull(commandId, "commandId");
    semanticStateDigest = Objects.requireNonNull(semanticStateDigest, "semanticStateDigest");
    boolean successful = status != M11ResponseStatus.REJECTED;
    if (successful != applicationSequence.isPresent()
        || successful != resultDigest.isPresent()
        || successful == rejectionCode.isPresent()) {
      throw new IllegalArgumentException("response outcome fields are inconsistent");
    }
    if (applicationSequence.isPresent() && applicationSequence.orElseThrow() <= 0) {
      throw new IllegalArgumentException("applicationSequence must be positive");
    }
    rejectionCode.ifPresent(
        value -> {
          if (value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("rejectionCode must be stable and bounded");
          }
        });
    resultDigest.ifPresent(value -> requireDigest(value, "resultDigest"));
    semanticStateDigest.ifPresent(value -> requireDigest(value, "semanticStateDigest"));
    if (protocolVersion == 1 && (commandId.isPresent() || semanticStateDigest.isPresent())) {
      throw new IllegalArgumentException("response v1 cannot contain v2 fields");
    }
    if (commandId.isPresent() != semanticStateDigest.isPresent()) {
      throw new IllegalArgumentException("v2 identity and semantic digest presence must agree");
    }
  }

  public static M11CommandResponse applied(M11CommandRequest request, CanonicalResult result) {
    return completed(request, M11ResponseStatus.NEW_APPLIED, result);
  }

  public static M11CommandResponse duplicate(M11CommandRequest request, CanonicalResult result) {
    return completed(request, M11ResponseStatus.DUPLICATE_REPLAYED, result);
  }

  public static M11CommandResponse rejected(
      M11CommandRequest request, String rejectionCode, String currentSemanticDigest) {
    int responseVersion = request.requestedResponseVersion();
    return new M11CommandResponse(
        responseVersion,
        request.correlationId(),
        M11ResponseStatus.REJECTED,
        OptionalLong.empty(),
        Optional.empty(),
        Optional.of(rejectionCode),
        responseVersion == 2 ? Optional.of(request.commandId()) : Optional.empty(),
        responseVersion == 2 ? Optional.of(currentSemanticDigest) : Optional.empty());
  }

  private static M11CommandResponse completed(
      M11CommandRequest request, M11ResponseStatus status, CanonicalResult result) {
    int responseVersion = request.requestedResponseVersion();
    return new M11CommandResponse(
        responseVersion,
        request.correlationId(),
        status,
        OptionalLong.of(result.applicationSequence()),
        Optional.of(result.resultDigest()),
        Optional.empty(),
        responseVersion == 2 ? Optional.of(request.commandId()) : Optional.empty(),
        responseVersion == 2 ? Optional.of(result.semanticStateDigest()) : Optional.empty());
  }

  private static void requireDigest(String value, String field) {
    if (!value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(field + " must be lowercase SHA-256");
    }
  }
}

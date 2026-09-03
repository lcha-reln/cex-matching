package io.github.lchareln.cex.matching.cluster;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Aeron-independent, read-only status sampled by the external M12 harness.
 *
 * <p>This record is diagnostics only. It is never read by the clustered service, never used as a
 * recovery source, and never participates in a business decision.
 */
public record M12MemberStatus(
    String schema,
    long statusSequence,
    long processId,
    long processStartedAtEpochMillis,
    long observedAtEpochMillis,
    int clusterId,
    int memberId,
    int memberCount,
    int quorumSize,
    int appointedLeaderId,
    boolean freshStart,
    String role,
    String electionState,
    long leadershipTermId,
    long commitPosition,
    long logPosition,
    long nextApplicationSequence,
    int identityResultCount,
    String semanticStateDigest,
    String identityResultDigest,
    int udpPortBlockBase,
    String rootDirectory,
    String aeronDirectory,
    String archiveDirectory,
    String clusterDirectory,
    List<String> componentErrors,
    List<String> diagnosticWarnings,
    long droppedDiagnosticWarnings) {
  public static final String SCHEMA = "matching.m12.member-status.v1";

  private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

  public M12MemberStatus {
    schema = requireText(schema, "schema");
    if (!SCHEMA.equals(schema)) {
      throw new IllegalArgumentException("unsupported M12 member status schema: " + schema);
    }
    if (statusSequence <= 0) {
      throw new IllegalArgumentException("statusSequence must be positive");
    }
    if (processId <= 0 || processStartedAtEpochMillis <= 0 || observedAtEpochMillis <= 0) {
      throw new IllegalArgumentException("process and observation timestamps must be positive");
    }
    if (clusterId < 0) {
      throw new IllegalArgumentException("clusterId must not be negative");
    }
    if (memberId < 0 || memberId >= memberCount) {
      throw new IllegalArgumentException("memberId is outside memberCount");
    }
    if (memberCount != M12ThreeMemberConfig.MEMBER_COUNT
        || quorumSize != M12ThreeMemberConfig.QUORUM_SIZE
        || appointedLeaderId != M12ThreeMemberConfig.APPOINTED_LEADER_ID) {
      throw new IllegalArgumentException("status is not the frozen M12 three-member topology");
    }
    role = requireText(role, "role");
    electionState = requireText(electionState, "electionState");
    if (leadershipTermId < -1 || commitPosition < -1 || logPosition < -1) {
      throw new IllegalArgumentException("cluster positions must use -1 or a non-negative value");
    }
    if (nextApplicationSequence <= 0) {
      throw new IllegalArgumentException("nextApplicationSequence must be positive");
    }
    if (identityResultCount < 0) {
      throw new IllegalArgumentException("identityResultCount must not be negative");
    }
    semanticStateDigest = requireDigest(semanticStateDigest, "semanticStateDigest");
    identityResultDigest = requireDigest(identityResultDigest, "identityResultDigest");
    if (udpPortBlockBase < 1_024 || udpPortBlockBase > 65_535) {
      throw new IllegalArgumentException("udpPortBlockBase is invalid");
    }
    rootDirectory = requireText(rootDirectory, "rootDirectory");
    aeronDirectory = requireText(aeronDirectory, "aeronDirectory");
    archiveDirectory = requireText(archiveDirectory, "archiveDirectory");
    clusterDirectory = requireText(clusterDirectory, "clusterDirectory");
    componentErrors = List.copyOf(Objects.requireNonNull(componentErrors, "componentErrors"));
    for (String error : componentErrors) {
      requireText(error, "component error");
    }
    diagnosticWarnings =
        List.copyOf(Objects.requireNonNull(diagnosticWarnings, "diagnosticWarnings"));
    for (String warning : diagnosticWarnings) {
      requireText(warning, "diagnostic warning");
    }
    if (droppedDiagnosticWarnings < 0) {
      throw new IllegalArgumentException("droppedDiagnosticWarnings must not be negative");
    }
  }

  public boolean healthy() {
    return componentErrors.isEmpty();
  }

  private static String requireDigest(String value, String field) {
    value = requireText(value, field);
    if (!SHA_256.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
    }
    return value;
  }

  private static String requireText(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}

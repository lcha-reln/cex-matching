package io.github.lchareln.cex.matching.benchmark;

import java.util.Objects;

/** Identity repeated on every raw record so shards cannot be silently mixed across runs. */
public record ArtifactContext(
    String runId,
    QualificationProfile.Id profileId,
    String resultScope,
    boolean eligibleForReleaseEvidence,
    String sourceCommit,
    String workloadSha256) {
  public ArtifactContext {
    require(runId, "runId");
    Objects.requireNonNull(profileId, "profileId");
    require(resultScope, "resultScope");
    require(sourceCommit, "sourceCommit");
    if (!sourceCommit.matches("[0-9a-f]{40}")) {
      throw new IllegalArgumentException("sourceCommit must be a full lowercase Git object id");
    }
    require(workloadSha256, "workloadSha256");
    if (!workloadSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("workloadSha256 must be lowercase SHA-256");
    }
    if (profileId == QualificationProfile.Id.CI_SMOKE
        && (eligibleForReleaseEvidence || !resultScope.equals("METHOD_SMOKE_ONLY"))) {
      throw new IllegalArgumentException("CI_SMOKE raw records cannot claim release eligibility");
    }
  }

  private static void require(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}

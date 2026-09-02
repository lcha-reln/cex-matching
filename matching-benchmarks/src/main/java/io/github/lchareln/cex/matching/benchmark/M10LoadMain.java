package io.github.lchareln.cex.matching.benchmark;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Command-line boundary for the complete M10 CI smoke or release qualification method. */
public final class M10LoadMain {
  private static final String WORKLOAD_SHA256 =
      "92300fe4580a99f7e8ece911bce2f68a41b945273c923ed484051a011be4fa9b";

  private M10LoadMain() {}

  public static void main(String[] args) throws Exception {
    Map<String, String> options = parse(args);
    QualificationProfile profile =
        switch (required(options, "profile")) {
          case "CI_SMOKE" -> QualificationProfile.CI_SMOKE;
          case "RELEASE_QUALIFICATION" -> QualificationProfile.RELEASE_QUALIFICATION;
          default -> throw new IllegalArgumentException("unsupported --profile");
        };
    String workloadHash = options.getOrDefault("workload-sha256", WORKLOAD_SHA256);
    if (!WORKLOAD_SHA256.equals(workloadHash)) {
      throw new IllegalArgumentException("--workload-sha256 must equal the frozen M10Q1 hash");
    }
    ArtifactContext context =
        new ArtifactContext(
            options.getOrDefault("run-id", "m10-" + UUID.randomUUID()),
            profile.id(),
            profile.resultScope(),
            profile.eligibleForReleaseEvidence(),
            required(options, "source-commit"),
            workloadHash);
    RuntimeProvenance provenance =
        RuntimeProvenance.capture(
            Path.of(required(options, "repository-root")),
            context.sourceCommit(),
            profile.eligibleForReleaseEvidence());
    M10QualificationRunner.Config config =
        new M10QualificationRunner.Config(
            profile,
            context,
            Path.of(required(options, "wal-root")),
            Path.of(required(options, "output")),
            required(options, "cpu-model"),
            required(options, "storage-device"),
            required(options, "filesystem"),
            required(options, "power-policy"),
            optionalPath(options, "diagnostic-jmh"),
            provenance);
    M10QualificationRunner.RunResult result = new M10QualificationRunner(config).run();
    System.out.println(result.qualificationJson());
  }

  private static Map<String, String> parse(String[] args) {
    Map<String, String> options = new LinkedHashMap<>();
    if (args.length == 0 || args.length % 2 != 0) {
      throw usage();
    }
    for (int index = 0; index < args.length; index += 2) {
      String key = args[index];
      String value = args[index + 1];
      if (!key.startsWith("--") || key.length() == 2 || value.isBlank()) {
        throw usage();
      }
      String previous = options.putIfAbsent(key.substring(2), value);
      if (previous != null) {
        throw new IllegalArgumentException("duplicate option: " + key);
      }
    }
    return Map.copyOf(options);
  }

  private static String required(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing --" + name + "\n" + usage().getMessage());
    }
    return value;
  }

  private static Optional<Path> optionalPath(Map<String, String> options, String name) {
    String value = options.get(name);
    return value == null ? Optional.empty() : Optional.of(Path.of(value));
  }

  private static IllegalArgumentException usage() {
    return new IllegalArgumentException(
        "usage: --profile CI_SMOKE|RELEASE_QUALIFICATION"
            + " --repository-root <git-root> --source-commit <40-lowercase-hex>"
            + " --wal-root <new-path> --output <new-path>"
            + " --cpu-model <text> --storage-device <text> --filesystem <text>"
            + " --power-policy <text> [--run-id <text>] [--workload-sha256 <64-hex>]"
            + " [--diagnostic-jmh <jmh-json>]");
  }
}

package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** M08 architecture inheritance plus the M09 production/testkit separation. */
final class M09ArchitectureGate {
  private static final Pattern MATCHING_PRODUCTION_TYPE =
      Pattern.compile(
          "io\\.github\\.lchareln\\.cex\\.matching(?:\\.local)?\\.([A-Z][A-Za-z0-9_]*)");
  private static final Set<String> LEDGER_ALLOWED_PRODUCTION_TYPES =
      Set.of("CheckpointResult", "SubmissionResult", "WalPosition");

  Report verify(Path repositoryRoot) {
    M08ArchitectureGate.Report inherited = new M08ArchitectureGate().verify(repositoryRoot);
    List<String> violations = new ArrayList<>(inherited.violations());
    Path local = repositoryRoot.resolve("matching-local-runtime/src/main/java");
    List<Path> localSources = sources(local);
    requireSource(localSources, "M09SnapshotCodec.java", violations);
    requireSource(localSources, "SnapshotStore.java", violations);
    requireSource(localSources, "RecoveryBudget.java", violations);
    requireSource(localSources, "CheckpointResult.java", violations);
    for (Path source : localSources) {
      String text = read(source);
      if (text.contains("M09RuntimeJudgeProbe") || text.contains("matching.testkit")) {
        violations.add("production runtime references M09 testkit bridge: " + source.getFileName());
      }
    }
    Path packageRoot = local.resolve("io/github/lchareln/cex/matching/local");
    Path snapshotStore = packageRoot.resolve("SnapshotStore.java");
    Path segmentedWal = packageRoot.resolve("SegmentedWal.java");
    Path localRuntime = packageRoot.resolve("LocalMatchingRuntime.java");
    Path storageOperations = packageRoot.resolve("StorageOperations.java");
    Path jdkStorageOperations = packageRoot.resolve("JdkStorageOperations.java");
    if (Files.isRegularFile(snapshotStore)
        && Files.isRegularFile(segmentedWal)
        && Files.isRegularFile(localRuntime)
        && Files.isRegularFile(storageOperations)
        && Files.isRegularFile(jdkStorageOperations)) {
      violations.addAll(
          storageOperationWiringViolations(
              read(snapshotStore),
              read(segmentedWal),
              read(localRuntime),
              read(storageOperations) + '\n' + read(jdkStorageOperations)));
    }
    Path probe =
        repositoryRoot.resolve(
            "matching-testkit/src/main/java/io/github/lchareln/cex/matching/local/M09RuntimeJudgeProbe.java");
    if (!Files.isRegularFile(probe)) {
      violations.add("M09 testkit mutation bridge is missing");
    }
    Path ledger =
        repositoryRoot.resolve(
            "matching-testkit/src/main/java/io/github/lchareln/cex/matching/testkit/M09StorageLedger.java");
    List<String> ledgerViolations =
        Files.isRegularFile(ledger)
            ? ledgerIndependenceViolations(read(ledger))
            : List.of("M09 independent storage ledger is missing");
    violations.addAll(ledgerViolations);
    violations.sort(String::compareTo);
    return new Report(
        inherited.coreSources(),
        inherited.localRuntimeSources(),
        Files.isRegularFile(probe),
        violations.stream().noneMatch(value -> value.startsWith("M09 storage operation wiring")),
        ledgerViolations.isEmpty(),
        List.copyOf(violations));
  }

  static List<String> ledgerIndependenceViolations(String ledgerSource) {
    List<String> violations = new ArrayList<>();
    Set<String> rejectedTypes = new HashSet<>();
    Matcher matcher = MATCHING_PRODUCTION_TYPE.matcher(ledgerSource);
    while (matcher.find()) {
      String type = matcher.group(1);
      if (!LEDGER_ALLOWED_PRODUCTION_TYPES.contains(type)) {
        rejectedTypes.add(type);
      }
    }
    rejectedTypes.stream()
        .sorted()
        .forEach(
            type ->
                violations.add(
                    "M09 independent storage ledger references forbidden production type " + type));
    if (ledgerSource.contains("io.github.lchareln.cex.matching.local.*")
        || ledgerSource.contains("io.github.lchareln.cex.matching.*")) {
      violations.add("M09 independent storage ledger uses a forbidden production wildcard");
    }
    if (ledgerSource.contains("java.nio.file")
        || ledgerSource.contains("java.nio.channels")
        || ledgerSource.contains("Files.")) {
      violations.add("M09 independent storage ledger performs production storage I/O");
    }
    return List.copyOf(violations);
  }

  static List<String> storageOperationWiringViolations(
      String snapshotStore, String segmentedWal, String localRuntime, String storageOperations) {
    List<String> violations = new ArrayList<>();
    requireContains(
        snapshotStore,
        "snapshot publication force",
        violations,
        "storageOperations.forceFile(temporary, channel);");
    requireContains(
        snapshotStore,
        "snapshot publication move",
        violations,
        "storageOperations.atomicMove(temporary, target);");
    requireContains(
        snapshotStore,
        "snapshot publication directory force",
        violations,
        "storageOperations.forceDirectory(directory);");
    requireContains(
        segmentedWal,
        "WAL retirement delete",
        violations,
        "storageOperations.delete(segment.path());");
    requireContains(
        segmentedWal,
        "WAL retirement directory force",
        violations,
        "storageOperations.forceDirectory(config.directory());");
    requireContains(
        localRuntime, "ordinary open JDK delegate", violations, "JdkStorageOperations.INSTANCE");
    requireContains(storageOperations, "JDK file force", violations, "channel.force(true);");
    requireContains(
        storageOperations,
        "JDK atomic move",
        violations,
        "Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);");
    requireContains(
        storageOperations,
        "JDK directory force",
        violations,
        "FileChannel.open(directory, StandardOpenOption.READ)");
    requireContains(storageOperations, "JDK delete", violations, "Files.delete(path);");
    return List.copyOf(violations);
  }

  private static void requireContains(
      String source, String boundary, List<String> violations, String token) {
    if (!source.contains(token)) {
      violations.add("M09 storage operation wiring missing " + boundary + " token: " + token);
    }
  }

  private static void requireSource(List<Path> sources, String filename, List<String> violations) {
    if (sources.stream().noneMatch(path -> path.getFileName().toString().equals(filename))) {
      violations.add("missing M09 production source " + filename);
    }
  }

  private static List<Path> sources(Path root) {
    try (var paths = Files.walk(root)) {
      return paths
          .filter(
              path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java"))
          .sorted(Comparator.comparing(Path::toString))
          .toList();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot enumerate M09 architecture sources", failure);
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read M09 architecture source " + path, failure);
    }
  }

  record Report(
      int coreSources,
      int localRuntimeSources,
      boolean testkitProbePresent,
      boolean storageOperationsProductionWiringVerified,
      boolean independentLedgerProductionParserFree,
      List<String> violations) {
    Report {
      violations = List.copyOf(violations);
    }

    boolean passed() {
      return violations.isEmpty();
    }
  }
}

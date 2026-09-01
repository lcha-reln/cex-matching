package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.MarketMode;
import io.github.lchareln.cex.matching.local.CheckpointResult;
import io.github.lchareln.cex.matching.local.FaultInjector;
import io.github.lchareln.cex.matching.local.FaultPoint;
import io.github.lchareln.cex.matching.local.LocalMatchingRuntime;
import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.M09RuntimeJudgeProbe;
import io.github.lchareln.cex.matching.local.RecoveryException;
import io.github.lchareln.cex.matching.local.RuntimeState;
import io.github.lchareln.cex.matching.local.SnapshotCorruptionException;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import io.github.lchareln.cex.matching.local.WalConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.node.ArrayNode;

/** Executes all twenty-two frozen M09 scenarios against real snapshot and WAL files. */
final class M09FixedSuite {
  private static final List<String> BUDGET_WITNESSES =
      List.of(
          "LIVE_RECORD_OVERRUN_REJECTED_PRE_WAL",
          "LIVE_BYTE_OVERRUN_REJECTED_PRE_WAL",
          "FRESH_RECOVERY_RECORD_OVERRUN_REJECTED_PRE_APPLY",
          "FRESH_RECOVERY_BYTE_OVERRUN_REJECTED_PRE_APPLY");
  private static final List<String> SNAPSHOT_IDENTITY_WITNESSES =
      List.of(
          "FILENAME_HEADER_GENERATION_MISMATCH_REJECTED",
          "VALID_INTEGRITY_WRONG_SHARD_REJECTED",
          "HEADER_STATE_WAL_CUT_MISMATCH_REJECTED");
  private static final List<String> PUBLICATION_WITNESSES =
      List.of(
          "FORCED_CANONICAL_TEMP_PRESENT_BEFORE_RENAME",
          "CANONICAL_FINAL_PRESENT_AFTER_RENAME_BEFORE_DIRECTORY_FORCE",
          "OLD_WAL_PRESENT_AFTER_DIRECTORY_FORCE_BEFORE_RETENTION");
  private final M09ScenarioSupport support = new M09ScenarioSupport();
  private final M09FileInventory inventory = new M09FileInventory();

  Result run(M09Corpus corpus, Path workingRoot) {
    Path root = workingRoot.toAbsolutePath().normalize();
    M09ScenarioSupport.deleteTree(root);
    provision(root);
    M09Coverage coverage = new M09Coverage(corpus.generator().obligations());
    ArrayNode results = JsonSupport.MAPPER.createArrayNode();
    StringBuilder canonical = new StringBuilder("M09F1\n");
    try {
      int index = 0;
      for (M09Corpus.Scenario scenario : corpus.scenarios()) {
        Path directory = root.resolve("scenario-%02d".formatted(index++));
        provision(directory);
        Observation observation = execute(scenario.id(), directory);
        if (!Set.copyOf(scenario.obligations()).equals(observation.proofs().keySet())) {
          throw new IllegalStateException(
              "M09 executable proofs disagree with frozen scenario " + scenario.id());
        }
        for (Map.Entry<String, String> proof : observation.proofs().entrySet()) {
          coverage.witnessed(proof.getKey(), scenario.id(), proof.getValue());
        }
        var node = results.addObject();
        node.put("scenarioId", scenario.id());
        node.put("status", "PASS");
        node.put("assertion", observation.assertion());
        node.set("declaredOperations", strings(scenario.operations()));
        node.set("proofObligations", strings(scenario.obligations()));
        observation.metrics().forEach(node::put);
        observation.facts().forEach(node::put);
        if ("RECOVERY_BUDGET_REJECTS_PRE_WAL".equals(scenario.id())) {
          node.set("budgetWitnesses", strings(BUDGET_WITNESSES));
        }
        if ("SNAPSHOT_IDENTITY_MISMATCH_FAILS_CLOSED".equals(scenario.id())) {
          node.set("snapshotIdentityWitnesses", strings(SNAPSHOT_IDENTITY_WITNESSES));
        }
        if ("SNAPSHOT_PUBLICATION_ORDER".equals(scenario.id())) {
          node.set("publicationWitnesses", strings(PUBLICATION_WITNESSES));
        }
        canonical
            .append(scenario.id())
            .append('|')
            .append(observation.assertion())
            .append('|')
            .append(
                observation.metrics().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + '=' + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(",")))
            .append('|')
            .append(
                observation.facts().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + '=' + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(",")))
            .append('\n');
      }
      coverage.requireComplete();
      byte[] bytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
      return new Result(results, coverage, bytes, Hashing.sha256Hex(bytes));
    } finally {
      M09ScenarioSupport.deleteTree(root);
    }
  }

  private Observation execute(String id, Path directory) {
    try {
      return switch (id) {
        case "FULL_CORE_STATE_ROUND_TRIP" -> fullCoreRoundTrip(directory);
        case "TERMINAL_ORDER_NON_RESURRECTION" -> terminalOrderRoundTrip(directory);
        case "DURABLE_IDENTITY_AND_ORIGINAL_RESULT_ROUND_TRIP" -> identityRoundTrip(directory);
        case "RULE_SET_AND_ACTIVATION_FENCE_ROUND_TRIP" -> ruleSetRoundTrip(directory);
        case "CANCEL_ONLY_MODE_ROUND_TRIP" -> cancelOnlyRoundTrip(directory);
        case "HALTED_MASS_CANCEL_FENCE_ROUND_TRIP" -> haltedMassCancelRoundTrip(directory);
        case "TRANSCRIPT_AND_DIGEST_ROUND_TRIP" -> transcriptRoundTrip(directory);
        case "SNAPSHOT_SUFFIX_EQUALS_GENESIS_REPLAY" -> suffixEqualsGenesis(directory);
        case "EMPTY_SUFFIX_RECOVERY" -> emptySuffix(directory);
        case "MULTI_SEGMENT_SUFFIX_RECOVERY" -> multiSegmentSuffix(directory);
        case "RECOVERY_BUDGET_REJECTS_PRE_WAL" -> budgetRejectsBeforeWal(directory);
        case "ORPHAN_TEMP_SNAPSHOT_IS_NOT_AUTHORITY" -> orphanTempIgnored(directory);
        case "SNAPSHOT_PUBLICATION_ORDER" -> publicationOrder(directory);
        case "NEWEST_PUBLISHED_GENERATION_WINS" -> newestGeneration(directory);
        case "UNKNOWN_SNAPSHOT_VERSION_FAILS_CLOSED" -> unknownVersion(directory);
        case "SNAPSHOT_CORRUPTION_FAILS_CLOSED" -> corruption(directory);
        case "SNAPSHOT_IDENTITY_MISMATCH_FAILS_CLOSED" -> identityMismatch(directory);
        case "RETIRE_ONLY_FULLY_COVERED_SEGMENTS" -> retireCoveredSegments(directory);
        case "RETIREMENT_REQUIRES_PUBLISHED_SNAPSHOT" -> retirementRequiresPublication(directory);
        case "RETIREMENT_DELETE_DIRECTORY_FORCE_ORDER" -> retirementOrder(directory);
        case "RETIREMENT_DELETE_CRASH_WINDOW" -> retirementCrashWindow(directory);
        case "MISSING_PREFIX_WITHOUT_VALID_SNAPSHOT_FAILS_CLOSED" ->
            missingPrefixFailsClosed(directory);
        default -> throw new IllegalStateException("missing M09 fixed executor " + id);
      };
    } catch (M09SemanticFailure failure) {
      throw failure;
    } catch (IOException failure) {
      throw new IllegalStateException("M09 fixed scenario I/O failed: " + id, failure);
    }
  }

  private Observation fullCoreRoundTrip(Path root) throws IOException {
    Path candidate = provision(root.resolve("candidate"));
    Path genesis = provision(root.resolve("genesis"));
    List<byte[]> history =
        support.encode(support.stream("full-state"), support.fullStateCommands());
    applyBoth(candidate, genesis, history, true);
    try (LocalMatchingRuntime restored = LocalMatchingRuntime.open(support.config(candidate));
        LocalMatchingRuntime baseline = LocalMatchingRuntime.open(support.unbounded(genesis))) {
      M09ScenarioSupport.requireEquivalent(restored, baseline);
      byte[] open =
          support.envelope(
              "full-state",
              1,
              12,
              uuidFor("full-state", 12),
              new M08Command.ChangeMarketMode(
                  12, MarketMode.CANCEL_ONLY, MarketMode.OPEN, "m09-reopen"));
      requireSame(restored.submit(open), baseline.submit(open), "post-snapshot mode continuation");
      byte[] stp =
          support.envelope(
              "full-state",
              1,
              13,
              uuidFor("full-state", 13),
              M09ScenarioSupport.place(8, "BUY", 99, 1, 77, "CANCEL_TAKER"));
      SubmissionResult restoredStp = restored.submit(stp);
      SubmissionResult baselineStp = baseline.submit(stp);
      requireSame(restoredStp, baselineStp, "restored STP state");
      String stpEvents = canonicalEvents(restoredStp);
      M09ScenarioSupport.require(
          stpEvents.contains("SelfTradePrevented") && stpEvents.contains("policy=CANCEL_TAKER"),
          "restored STP continuation did not trigger CANCEL_TAKER");
      byte[] fifo =
          support.envelope(
              "full-state",
              1,
              14,
              uuidFor("full-state", 14),
              M09ScenarioSupport.place(7, "BUY", 101, 7, 17, "CANCEL_TAKER"));
      SubmissionResult restoredFifo = restored.submit(fifo);
      SubmissionResult baselineFifo = baseline.submit(fifo);
      requireSame(restoredFifo, baselineFifo, "restored same-price FIFO");
      String fifoEvents = canonicalEvents(restoredFifo);
      int firstMaker = fifoEvents.indexOf("makerOrderId=OrderId[value=1]");
      int secondMaker = fifoEvents.indexOf("makerOrderId=OrderId[value=6]");
      M09ScenarioSupport.require(
          firstMaker >= 0 && secondMaker > firstMaker,
          "restored same-price makers did not execute in FIFO order");
    }
    return proved(
        "complete core image restored and continued equivalently",
        "QUIESCENT_COMMAND_BOUNDARY_CAPTURE",
        "FULL_ORDER_REGISTRY_ROUND_TRIP",
        "PRICE_TIME_FIFO_ROUND_TRIP",
        "STP_STATE_ROUND_TRIP",
        "SEQUENCE_CURSORS_ROUND_TRIP");
  }

  private Observation terminalOrderRoundTrip(Path root) throws IOException {
    Path candidate = provision(root.resolve("candidate"));
    Path genesis = provision(root.resolve("genesis"));
    List<byte[]> history = support.encode(support.stream("terminal"), support.fullStateCommands());
    applyBoth(candidate, genesis, history, true);
    byte[] reused =
        support.envelope(
            "terminal-reuse",
            1,
            1,
            new UUID(0x9091, 1),
            M09ScenarioSupport.place(2, "BUY", 99, 1, 99, "CANCEL_TAKER"));
    try (LocalMatchingRuntime restored = LocalMatchingRuntime.open(support.config(candidate));
        LocalMatchingRuntime baseline = LocalMatchingRuntime.open(support.unbounded(genesis))) {
      SubmissionResult actual = restored.submit(reused);
      SubmissionResult expected = baseline.submit(reused);
      M09ScenarioSupport.require(
          M09ScenarioSupport.signature(actual).equals(M09ScenarioSupport.signature(expected)),
          "terminal order identity resurrected after snapshot");
      M09ScenarioSupport.require(
          actual instanceof SubmissionResult.NewDurablyApplied,
          "terminal order reuse was not durably rejected by core");
    }
    return proved(
        "filled and canceled identities remained terminal", "TERMINAL_ORDER_NON_RESURRECTION");
  }

  private Observation identityRoundTrip(Path directory) throws IOException {
    M09ScenarioSupport.CommandStream stream = support.stream("identity");
    byte[] first = stream.next(M09ScenarioSupport.cancel(90_001));
    byte[] second = stream.next(M09ScenarioSupport.cancel(90_002));
    byte[] originalAudit;
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(support.config(directory))) {
      originalAudit =
          M09ScenarioSupport.requireNew(runtime.submit(first), "identity first")
              .result()
              .auditBytes();
      M09ScenarioSupport.requireNew(runtime.submit(second), "identity second");
      runtime.checkpoint();
    }
    try (LocalMatchingRuntime restored = LocalMatchingRuntime.open(support.config(directory))) {
      var duplicate =
          M09ScenarioSupport.requireDuplicate(restored.submit(first), "identity duplicate");
      M09ScenarioSupport.require(
          Arrays.equals(originalAudit, duplicate.originalResult().auditBytes()),
          "snapshot changed original duplicate result");
      byte[] conflict =
          support.envelope("identity", 1, 1, uuid(1), M09ScenarioSupport.cancel(90_099));
      M09ScenarioSupport.require(
          restored.submit(conflict) instanceof SubmissionResult.PreflightRejected,
          "snapshot lost identity conflict binding");
      M09ScenarioSupport.require(restored.nextWalSequence() == 3, "identity retry consumed WAL");
    }
    return proved(
        "durable identity and original result survived",
        "DURABLE_IDENTITY_INDEX_ROUND_TRIP",
        "ORIGINAL_RESULT_REPLAY_ROUND_TRIP");
  }

  private Observation ruleSetRoundTrip(Path root) throws IOException {
    equivalenceAfterFullState(root, "rule and activation fences restored", true);
    return proved(
        "active/prepared rule sets and activation fence restored",
        "ACTIVE_RULE_SET_ROUND_TRIP",
        "PREPARED_RULE_SET_ROUND_TRIP",
        "CONTROL_FENCES_ROUND_TRIP");
  }

  private Observation cancelOnlyRoundTrip(Path root) throws IOException {
    Path candidate = provision(root.resolve("candidate"));
    Path genesis = provision(root.resolve("genesis"));
    List<byte[]> history =
        support.encode(support.stream("cancel-only"), support.fullStateCommands());
    applyBoth(candidate, genesis, history, true);
    byte[] blocked =
        support.envelope(
            "cancel-only",
            1,
            12,
            uuidFor("cancel-only", 12),
            M09ScenarioSupport.place(81, "BUY", 101, 1, 8, "CANCEL_TAKER"));
    try (LocalMatchingRuntime restored = LocalMatchingRuntime.open(support.config(candidate));
        LocalMatchingRuntime baseline = LocalMatchingRuntime.open(support.unbounded(genesis))) {
      String actual = M09ScenarioSupport.signature(restored.submit(blocked));
      String expected = M09ScenarioSupport.signature(baseline.submit(blocked));
      M09ScenarioSupport.require(
          actual.equals(expected), "CANCEL_ONLY behavior changed after restore");
    }
    return proved(
        "CANCEL_ONLY mode and fence restored",
        "MARKET_MODE_ROUND_TRIP",
        "CONTROL_FENCES_ROUND_TRIP");
  }

  private Observation haltedMassCancelRoundTrip(Path root) throws IOException {
    Path candidate = provision(root.resolve("candidate"));
    Path genesis = provision(root.resolve("genesis"));
    List<M08Command> commands = new ArrayList<>(support.fullStateCommands());
    commands.add(
        new M08Command.ChangeMarketMode(12, MarketMode.CANCEL_ONLY, MarketMode.HALTED, "m09-halt"));
    commands.add(new M08Command.MassCancel(13, MarketMode.HALTED, "m09-mass-cancel"));
    List<byte[]> history = support.encode(support.stream("halted"), commands);
    applyBoth(candidate, genesis, history, true);
    try (LocalMatchingRuntime restored = LocalMatchingRuntime.open(support.config(candidate));
        LocalMatchingRuntime baseline = LocalMatchingRuntime.open(support.unbounded(genesis))) {
      M09ScenarioSupport.requireEquivalent(restored, baseline);
      String actual = M09ScenarioSupport.signature(restored.submit(history.getLast()));
      String expected = M09ScenarioSupport.signature(baseline.submit(history.getLast()));
      M09ScenarioSupport.require(actual.equals(expected), "Mass Cancel fence replay changed");
    }
    return proved(
        "HALTED mode and Mass Cancel fence restored",
        "MARKET_MODE_ROUND_TRIP",
        "MASS_CANCEL_FENCE_ROUND_TRIP");
  }

  private Observation transcriptRoundTrip(Path directory) throws IOException {
    List<byte[]> history =
        support.encode(support.stream("transcript"), support.fullStateCommands());
    String semantic;
    String firstSnapshot;
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(support.config(directory))) {
      history.forEach(
          value -> M09ScenarioSupport.requireNew(runtime.submit(value), "transcript apply"));
      semantic = runtime.semanticStateDigest();
      runtime.checkpoint();
      firstSnapshot = inventory.inspect(directory).snapshots().getLast().sha256();
      runtime.checkpoint();
      String secondSnapshot = inventory.inspect(directory).snapshots().getLast().sha256();
      M09ScenarioSupport.require(
          semantic.equals(runtime.semanticStateDigest()), "checkpoint changed semantic digest");
      M09ScenarioSupport.require(
          !firstSnapshot.equals(secondSnapshot),
          "serialization digest did not distinguish snapshot generations");
    }
    try (LocalMatchingRuntime restored = LocalMatchingRuntime.open(support.config(directory))) {
      M09ScenarioSupport.require(
          semantic.equals(restored.semanticStateDigest()), "transcript digest changed on restore");
    }
    return proved(
        "semantic and serialization digests remained separate",
        "TRANSCRIPT_DIGEST_ROUND_TRIP",
        "SEMANTIC_SERIALIZATION_DIGEST_SEPARATION");
  }

  private Observation suffixEqualsGenesis(Path root) throws IOException {
    Path candidate = provision(root.resolve("candidate"));
    Path genesis = provision(root.resolve("genesis"));
    M09ScenarioSupport.CommandStream stream = support.stream("suffix");
    List<byte[]> prefix = support.encode(stream, support.fullStateCommands().subList(0, 4));
    List<byte[]> suffix =
        List.of(
            stream.next(M09ScenarioSupport.cancel(1)),
            stream.next(M09ScenarioSupport.cancel(90_010)));
    apply(candidate, support.config(candidate), prefix, true, suffix);
    apply(genesis, support.unbounded(genesis), prefix, false, suffix);
    compareRecovered(candidate, genesis);
    return proved(
        "snapshot plus contiguous suffix equaled genesis replay",
        "SNAPSHOT_SUFFIX_GENESIS_EQUIVALENCE",
        "CUT_RECORD_EXACTLY_ONCE");
  }

  private Observation emptySuffix(Path directory) throws IOException {
    List<byte[]> prefix =
        support.encode(support.stream("empty-suffix"), support.fullStateCommands().subList(0, 4));
    String digest;
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(support.config(directory))) {
      prefix.forEach(
          value -> M09ScenarioSupport.requireNew(runtime.submit(value), "empty suffix prefix"));
      runtime.checkpoint();
      digest = runtime.semanticStateDigest();
    }
    try (LocalMatchingRuntime restored = LocalMatchingRuntime.open(support.config(directory))) {
      M09ScenarioSupport.require(restored.nextWalSequence() == 5, "empty suffix sequence changed");
      M09ScenarioSupport.require(
          digest.equals(restored.semanticStateDigest()), "empty suffix digest changed");
    }
    return proved("empty WAL suffix restored exactly", "EMPTY_SUFFIX_RECOVERY");
  }

  private Observation multiSegmentSuffix(Path root) throws IOException {
    Path candidate = provision(root.resolve("candidate"));
    Path genesis = provision(root.resolve("genesis"));
    WalConfig candidateConfig = support.smallWithBudget(candidate, 64, 4L * 1_048_576);
    WalConfig genesisConfig = support.smallWithBudget(genesis, 64, 4L * 1_048_576);
    M09ScenarioSupport.CommandStream stream = support.stream("multi-segment");
    byte[] prefix = stream.next(M09ScenarioSupport.cancel(1));
    List<byte[]> suffix = new ArrayList<>();
    for (int index = 0; index < 40; index++) {
      suffix.add(stream.next(M09ScenarioSupport.largeBusinessRejection(1_000 + index)));
    }
    apply(candidate, candidateConfig, List.of(prefix), true, suffix);
    apply(genesis, genesisConfig, List.of(prefix), false, suffix);
    M09ScenarioSupport.require(
        M09ScenarioSupport.segmentFiles(candidate).size() >= 2,
        "multi-segment suffix did not cross a segment boundary");
    try (LocalMatchingRuntime restored = LocalMatchingRuntime.open(candidateConfig);
        LocalMatchingRuntime baseline = LocalMatchingRuntime.open(genesisConfig)) {
      M09ScenarioSupport.requireEquivalent(restored, baseline);
    }
    return proved(
        "configured bounded suffix crossed segments and matched genesis",
        "MULTI_SEGMENT_SUFFIX_RECOVERY",
        "CUT_RECORD_EXACTLY_ONCE",
        "SNAPSHOT_SUFFIX_GENESIS_EQUIVALENCE");
  }

  private Observation budgetRejectsBeforeWal(Path directory) throws IOException {
    Path liveRecord = provision(directory.resolve("live-record"));
    M09ScenarioSupport.CommandStream recordStream = support.stream("budget-live-record");
    byte[] recordFirst = recordStream.next(M09ScenarioSupport.cancel(1));
    byte[] recordSecond = recordStream.next(M09ScenarioSupport.cancel(2));
    assertLiveBudgetPreWal(
        support.budget(liveRecord, 1, 1_048_576),
        liveRecord,
        recordFirst,
        recordSecond,
        1,
        "record");

    Path liveByte = provision(directory.resolve("live-byte"));
    M09ScenarioSupport.CommandStream byteStream = support.stream("budget-live-byte");
    byte[] byteFirst = byteStream.next(M09ScenarioSupport.cancel(1));
    byte[] byteSecond = byteStream.next(M09ScenarioSupport.largeBusinessRejection(2));
    long byteBudget = walRecordBytes(byteFirst) + walRecordBytes(byteSecond) - 1;
    assertLiveBudgetPreWal(
        support.budget(liveByte, 64, byteBudget), liveByte, byteFirst, byteSecond, 64, "byte");

    Path recoveryRecord = provision(directory.resolve("recovery-record"));
    M09ScenarioSupport.CommandStream recoveryRecordStream =
        support.stream("budget-recovery-record");
    List<byte[]> recordSuffix = new ArrayList<>();
    for (int index = 1; index <= 65; index++) {
      recordSuffix.add(recoveryRecordStream.next(M09ScenarioSupport.cancel(index)));
    }
    writeLegacySuffix(recoveryRecord, recordSuffix);
    String recordRecoveryFailure =
        assertRecoveryBudgetPreApply(recoveryRecord, "record", recordSuffix.size());

    Path recoveryByte = provision(directory.resolve("recovery-byte"));
    M09ScenarioSupport.CommandStream recoveryByteStream = support.stream("budget-recovery-byte");
    List<byte[]> byteSuffix = new ArrayList<>();
    long encodedBytes = 0;
    while (encodedBytes <= 1_048_576) {
      byte[] command =
          recoveryByteStream.next(
              M09ScenarioSupport.largeBusinessRejection(10_000 + byteSuffix.size()));
      byteSuffix.add(command);
      encodedBytes += walRecordBytes(command);
    }
    M09ScenarioSupport.require(byteSuffix.size() < 64, "byte recovery fixture hit record bound");
    writeLegacySuffix(recoveryByte, byteSuffix);
    String byteRecoveryFailure =
        assertRecoveryBudgetPreApply(recoveryByte, "byte", byteSuffix.size());
    return provedWithFactsAndMetrics(
        String.join(";", BUDGET_WITNESSES),
        Map.of(
            "recoveryRecordFirstRejectedWalSequence",
            recordSuffix.size(),
            "recoveryByteFirstRejectedWalSequence",
            byteSuffix.size()),
        Map.of(
            "recoveryRecordFailure", recordRecoveryFailure,
            "recoveryByteFailure", byteRecoveryFailure),
        "RECOVERY_BUDGET_ENFORCED_PRE_WAL");
  }

  private void assertLiveBudgetPreWal(
      WalConfig config,
      Path directory,
      byte[] first,
      byte[] second,
      long expectedMaxRecords,
      String dimension)
      throws IOException {
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config)) {
      M09ScenarioSupport.requireNew(runtime.submit(first), dimension + " budget first submit");
      M09ScenarioSupport.requireDuplicate(runtime.submit(first), dimension + " budget duplicate");
      M09FileInventory.Inventory before = inventory.inspect(directory);
      SubmissionResult result = runtime.submit(second);
      M09ScenarioSupport.require(
          result instanceof SubmissionResult.CheckpointRequired,
          dimension + " budget exhaustion did not request checkpoint");
      SubmissionResult.CheckpointRequired required = (SubmissionResult.CheckpointRequired) result;
      M09ScenarioSupport.require(
          required.maxSuffixRecords() == expectedMaxRecords,
          dimension + " budget exercised the wrong record bound");
      M09ScenarioSupport.require(
          required.suffixRecords() == 1 && required.suffixBytes() == walRecordBytes(first),
          dimension + " budget counters changed");
      M09ScenarioSupport.require(
          runtime.nextWalSequence() == 2, dimension + " budget rejection consumed WAL");
      M09FileInventory.Inventory after = inventory.inspect(directory);
      M09ScenarioSupport.require(
          before.entries().equals(after.entries()), dimension + " budget rejection changed files");
      runtime.checkpoint();
      M09ScenarioSupport.requireNew(
          runtime.submit(second), "post-checkpoint " + dimension + " submit");
    }
  }

  private void writeLegacySuffix(Path directory, List<byte[]> commands) throws IOException {
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(support.unbounded(directory))) {
      commands.forEach(
          command ->
              M09ScenarioSupport.requireNew(runtime.submit(command), "legacy suffix submit"));
    }
  }

  private String assertRecoveryBudgetPreApply(
      Path directory, String dimension, long firstRejectedWalSequence) throws IOException {
    RecoveryApplyCounter counter = new RecoveryApplyCounter();
    try {
      LocalMatchingRuntime.open(support.config(directory), counter).close();
      throw new M09SemanticFailure(dimension + " recovery budget overrun was accepted");
    } catch (RecoveryException expected) {
      requireBudgetRecoveryFailure(expected, firstRejectedWalSequence);
      M09ScenarioSupport.require(
          counter.applies() == 0, dimension + " recovery budget applied before fail closed");
      return expected.getMessage();
    }
  }

  static void requireBudgetRecoveryFailure(
      RecoveryException failure, long firstRejectedWalSequence) {
    String expected =
        "M09 recovery suffix exceeds the configured records-and-bytes budget before WAL "
            + firstRejectedWalSequence;
    if (!expected.equals(failure.getMessage()) || failure.getCause() != null) {
      throw new IllegalStateException("unexpected recovery failure instead of M09 budget", failure);
    }
  }

  private static long walRecordBytes(byte[] envelope) {
    return M09StorageLedger.WAL_RECORD_OVERHEAD + (long) envelope.length;
  }

  private Observation orphanTempIgnored(Path directory) throws IOException {
    Path orphan = directory.resolve("snapshot-00000000000000000001-00000000000000000001.m09s1.tmp");
    Files.write(orphan, new byte[] {1, 2, 3}, StandardOpenOption.CREATE_NEW);
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(support.config(directory))) {
      M09ScenarioSupport.require(runtime.nextWalSequence() == 1, "orphan temp became authority");
    }
    M09ScenarioSupport.require(!Files.exists(orphan), "orphan temp was not removed on open");
    return proved("orphan partial temp was ignored and removed", "ORPHAN_TEMP_IGNORED");
  }

  private Observation publicationOrder(Path directory) throws IOException {
    RecordingFault recorder = new RecordingFault(directory);
    try (LocalMatchingRuntime runtime =
        M09RuntimeJudgeProbe.openWithStorageTrace(
            support.config(directory), recorder, recorder::recordStorageOperation)) {
      M09ScenarioSupport.requireNew(
          runtime.submit(support.stream("publication").next(M09ScenarioSupport.cancel(1))),
          "publication setup");
      runtime.checkpoint();
    }
    recorder.requireOrder(
        FaultPoint.BEFORE_SNAPSHOT_TEMP_WRITE,
        FaultPoint.AFTER_PARTIAL_SNAPSHOT_TEMP_WRITE,
        FaultPoint.BEFORE_SNAPSHOT_FILE_FORCE,
        FaultPoint.BEFORE_SNAPSHOT_ATOMIC_RENAME,
        FaultPoint.BEFORE_SNAPSHOT_DIRECTORY_FORCE,
        FaultPoint.AFTER_SNAPSHOT_DIRECTORY_FORCE_BEFORE_RETENTION);
    recorder.requireNamespaceWitnesses();
    requirePublicationStorageOperations(recorder.storageOperations());
    M09ScenarioSupport.require(
        inventory.inspect(directory).count(M09FileInventory.Kind.SNAPSHOT_TEMP) == 0,
        "published checkpoint left a temp snapshot");
    return proved(
        String.join(";", PUBLICATION_WITNESSES),
        "SNAPSHOT_FORCE_BEFORE_RENAME",
        "SNAPSHOT_RENAME_BEFORE_DIRECTORY_FORCE",
        "SNAPSHOT_DIRECTORY_FORCE_BEFORE_RETIREMENT");
  }

  private Observation newestGeneration(Path directory) throws IOException {
    M09ScenarioSupport.CommandStream stream = support.stream("generation");
    String latestDigest;
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(support.config(directory))) {
      M09ScenarioSupport.requireNew(
          runtime.submit(stream.next(M09ScenarioSupport.cancel(1))), "generation one");
      runtime.checkpoint();
      M09ScenarioSupport.requireNew(
          runtime.submit(stream.next(M09ScenarioSupport.cancel(2))), "generation two");
      runtime.checkpoint();
      latestDigest = runtime.semanticStateDigest();
    }
    try (LocalMatchingRuntime restored = LocalMatchingRuntime.open(support.config(directory))) {
      M09ScenarioSupport.require(restored.nextWalSequence() == 3, "older generation was selected");
      M09ScenarioSupport.require(
          latestDigest.equals(restored.semanticStateDigest()), "latest generation digest changed");
    }
    M09RuntimeJudgeProbe.SnapshotSelection selected =
        M09RuntimeJudgeProbe.selectedSnapshot(support.config(directory));
    M09ScenarioSupport.require(
        selected.generation() == 2
            && selected.lastWalSequence() == 2
            && selected.lastApplicationSequence() == 2,
        "production discovery did not select the highest named generation/cut");
    return proved(
        "production selected snapshot anchor generation=2 cut=2",
        "NEWEST_PUBLISHED_GENERATION_SELECTED");
  }

  private Observation unknownVersion(Path directory) throws IOException {
    Path snapshot = publishOne(directory, "unknown-version");
    M09ScenarioSupport.rewriteVersionWithValidIntegrity(snapshot, 2);
    expectSnapshotFailure(directory, "unknown snapshot version was accepted");
    return proved(
        "unknown version with valid integrity failed closed",
        "UNKNOWN_SNAPSHOT_VERSION_FAIL_CLOSED");
  }

  private Observation corruption(Path directory) throws IOException {
    Path snapshot = publishOne(directory, "corruption");
    M09ScenarioSupport.corruptBody(snapshot);
    expectSnapshotFailure(directory, "corrupt snapshot was accepted");
    return proved("body corruption failed closed", "SNAPSHOT_CORRUPTION_FAIL_CLOSED");
  }

  private Observation identityMismatch(Path directory) throws IOException {
    Path generation = provision(directory.resolve("generation"));
    Path generationSnapshot = publishOne(generation, "identity-generation");
    M09ScenarioSupport.rewriteGenerationWithValidIntegrity(generationSnapshot, 2);
    expectSnapshotFailure(generation, "filename/header generation mismatch was accepted");

    Path shard = provision(directory.resolve("shard"));
    Path shardSnapshot = publishOne(shard, "identity-shard");
    M09ScenarioSupport.rewriteShardWithValidIntegrity(shardSnapshot, M09ScenarioSupport.SHARD + 1);
    expectSnapshotFailure(shard, "valid-integrity wrong shard was accepted");

    Path cut = provision(directory.resolve("cut"));
    Path cutSnapshot = publishOne(cut, "identity-cut");
    M09ScenarioSupport.rewriteWalCutWithValidIntegrity(cutSnapshot, 2);
    expectSnapshotFailure(cut, "header/state WAL cut mismatch was accepted");
    return proved(
        String.join(";", SNAPSHOT_IDENTITY_WITNESSES), "GENERATION_SHARD_CUT_MISMATCH_FAIL_CLOSED");
  }

  private Observation retireCoveredSegments(Path directory) throws IOException {
    Path eligibleDirectory = provision(directory.resolve("eligible"));
    CheckpointResult third = null;
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(support.config(eligibleDirectory))) {
      M09ScenarioSupport.CommandStream stream = support.stream("retire-covered");
      for (int index = 1; index <= 3; index++) {
        M09ScenarioSupport.requireNew(
            runtime.submit(stream.next(M09ScenarioSupport.cancel(index))), "retire setup");
        CheckpointResult result = runtime.checkpoint();
        if (index == 3) {
          third = result;
        }
      }
    }
    M09ScenarioSupport.require(third != null, "third checkpoint did not execute");
    List<Path> segments = M09ScenarioSupport.segmentFiles(eligibleDirectory);
    M09ScenarioSupport.require(
        segments.size() == 2, "retirement did not retain protected and active suffix segments");
    M09ScenarioSupport.require(
        third.prunedThroughWalSequence() == 2, "retirement crossed protected cut");
    try (LocalMatchingRuntime restored =
        LocalMatchingRuntime.open(support.config(eligibleDirectory))) {
      M09ScenarioSupport.require(
          restored.nextWalSequence() == 4, "retirement changed recovery state");
    }

    Path crossingDirectory = provision(directory.resolve("crossing"));
    M09ScenarioSupport.CommandStream crossingStream = support.stream("crossing");
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(support.config(crossingDirectory))) {
      M09ScenarioSupport.requireNew(
          runtime.submit(crossingStream.next(M09ScenarioSupport.cancel(91))), "crossing prefix");
      runtime.checkpoint();
      M09ScenarioSupport.requireNew(
          runtime.submit(crossingStream.next(M09ScenarioSupport.cancel(92))), "crossing suffix");
    }
    M09RuntimeJudgeProbe.CrossingFixture fixture =
        M09RuntimeJudgeProbe.createCrossingSegmentFixture(
            crossingDirectory, M09ScenarioSupport.SHARD);
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(support.config(crossingDirectory))) {
      M09ScenarioSupport.require(runtime.nextWalSequence() == 3, "crossing fixture lost suffix");
      CheckpointResult result = runtime.checkpoint();
      M09ScenarioSupport.require(
          result.prunedThroughWalSequence() == 0,
          "retirement deleted a segment crossing the protected cut");
    }
    M09ScenarioSupport.require(
        M09ScenarioSupport.segmentFiles(crossingDirectory).stream()
            .anyMatch(path -> path.getFileName().toString().equals(fixture.crossingSegment())),
        "crossing segment was not retained");
    return proved(
        "only fully covered closed segments retired",
        "RETIRE_ONLY_FULLY_COVERED_CLOSED_SEGMENTS",
        "ACTIVE_OR_CROSSING_SEGMENT_RETAINED");
  }

  private Observation retirementRequiresPublication(Path directory) throws IOException {
    ArmedFailure fault = new ArmedFailure(FaultPoint.BEFORE_SNAPSHOT_DIRECTORY_FORCE);
    M09ScenarioSupport.CommandStream stream = support.stream("retirement-publication");
    byte[] first = stream.next(M09ScenarioSupport.cancel(1));
    byte[] second = stream.next(M09ScenarioSupport.cancel(2));
    Path firstSegment;
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(support.config(directory), fault)) {
      M09ScenarioSupport.requireNew(runtime.submit(first), "retirement publication setup");
      runtime.checkpoint();
      firstSegment = M09ScenarioSupport.segmentFiles(directory).getFirst();
      M09ScenarioSupport.requireNew(runtime.submit(second), "retirement publication target");
      fault.arm();
      try {
        runtime.checkpoint();
        throw new IllegalStateException("generation two checkpoint fault was not injected");
      } catch (IOException expected) {
        M09ScenarioSupport.requireSystemBoundary(
            fault.hit(), "snapshot directory-force seam was not hit");
        M09ScenarioSupport.requireExactInjectedIOException(
            expected, fault.injected(), "snapshot directory-force seam");
        M09ScenarioSupport.require(
            runtime.state() == RuntimeState.FAILED_CLOSED, "checkpoint did not fail closed");
      }
    }
    M09ScenarioSupport.require(
        Files.exists(firstSegment),
        "gen1/cut1 segment retired using unpublished generation two authority");
    try (LocalMatchingRuntime restored = LocalMatchingRuntime.open(support.config(directory))) {
      M09ScenarioSupport.require(
          restored.nextWalSequence() == 3,
          "recovery after generation two publication fault changed");
      M09ScenarioSupport.requireDuplicate(
          restored.submit(first), "first duplicate after generation two publication fault");
      M09ScenarioSupport.requireDuplicate(
          restored.submit(second), "second duplicate after generation two publication fault");
    }
    return proved(
        "gen1 was published; failed gen2 directory force did not retire eligible gen1/cut1 WAL",
        "SNAPSHOT_DIRECTORY_FORCE_BEFORE_RETIREMENT");
  }

  private Observation retirementOrder(Path directory) throws IOException {
    RecordingFault recorder = new RecordingFault(directory, false);
    try (LocalMatchingRuntime runtime =
        M09RuntimeJudgeProbe.openWithStorageTrace(
            support.config(directory), recorder, recorder::recordStorageOperation)) {
      M09ScenarioSupport.CommandStream stream = support.stream("retirement-order");
      for (int index = 1; index <= 3; index++) {
        M09ScenarioSupport.requireNew(
            runtime.submit(stream.next(M09ScenarioSupport.cancel(index))),
            "retirement order setup");
        runtime.checkpoint();
      }
    }
    recorder.requireOrder(
        FaultPoint.AFTER_SNAPSHOT_DIRECTORY_FORCE_BEFORE_RETENTION,
        FaultPoint.BEFORE_RETENTION_DELETE,
        FaultPoint.AFTER_FIRST_RETENTION_SEGMENT_DELETE,
        FaultPoint.BEFORE_RETENTION_DIRECTORY_FORCE,
        FaultPoint.AFTER_RETENTION_DIRECTORY_FORCE_BEFORE_RETURN);
    requireRetirementStorageOperations(recorder.storageOperations());
    return proved(
        "delete-directory-force-return order observed", "RETIREMENT_DELETE_DIRECTORY_FORCE");
  }

  private Observation retirementCrashWindow(Path directory) throws IOException {
    M09ScenarioSupport.CommandStream stream = support.stream("retirement-crash");
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(support.config(directory))) {
      M09ScenarioSupport.requireNew(
          runtime.submit(stream.next(M09ScenarioSupport.cancel(1))), "retirement crash setup");
      runtime.checkpoint();
    }
    OneShotFailure fault = new OneShotFailure(FaultPoint.AFTER_FIRST_RETENTION_SEGMENT_DELETE);
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(support.config(directory), fault)) {
      M09ScenarioSupport.requireNew(
          runtime.submit(stream.next(M09ScenarioSupport.cancel(2))), "retirement crash target");
      try {
        runtime.checkpoint();
        throw new IllegalStateException("retirement delete crash fault was not injected");
      } catch (IOException expected) {
        M09ScenarioSupport.requireSystemBoundary(
            fault.hit(), "first segment delete seam was not hit");
        M09ScenarioSupport.requireExactInjectedIOException(
            expected, fault.injected(), "first segment delete seam");
      }
    }
    try (LocalMatchingRuntime restored = LocalMatchingRuntime.open(support.config(directory))) {
      M09ScenarioSupport.require(
          restored.nextWalSequence() == 3, "delete crash window changed state");
    }
    for (Path snapshot : M09ScenarioSupport.snapshotFiles(directory)) {
      Files.delete(snapshot);
    }
    try {
      LocalMatchingRuntime.open(support.config(directory)).close();
      throw new M09SemanticFailure(
          "delete crash window fell back after its required snapshot was removed");
    } catch (SnapshotCorruptionException
        | io.github.lchareln.cex.matching.local.WalCorruptionException expected) {
      // Missing prefix plus no valid authority must fail closed.
    }
    return proved(
        "first-delete interruption reopened from published authority",
        "RETIREMENT_DELETE_DIRECTORY_FORCE",
        "MISSING_PREFIX_WITHOUT_VALID_SNAPSHOT_FAIL_CLOSED");
  }

  private Observation missingPrefixFailsClosed(Path directory) throws IOException {
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(support.config(directory))) {
      M09ScenarioSupport.CommandStream stream = support.stream("missing-prefix");
      for (int index = 1; index <= 3; index++) {
        M09ScenarioSupport.requireNew(
            runtime.submit(stream.next(M09ScenarioSupport.cancel(index))), "missing prefix setup");
        runtime.checkpoint();
      }
    }
    M09ScenarioSupport.require(
        M09ScenarioSupport.segmentFiles(directory).stream()
            .noneMatch(path -> path.getFileName().toString().contains("00000000000000000001")),
        "test setup did not retire genesis prefix");
    for (Path snapshot : M09ScenarioSupport.snapshotFiles(directory)) {
      Files.delete(snapshot);
    }
    try {
      LocalMatchingRuntime.open(support.config(directory)).close();
      throw new M09SemanticFailure("missing prefix silently fell back to genesis");
    } catch (SnapshotCorruptionException
        | io.github.lchareln.cex.matching.local.WalCorruptionException expected) {
      // Required fail-closed authority boundary.
    }
    return proved(
        "missing prefix without snapshot failed closed",
        "MISSING_PREFIX_WITHOUT_VALID_SNAPSHOT_FAIL_CLOSED");
  }

  private Observation equivalenceAfterFullState(Path root, String assertion, boolean checkpoint)
      throws IOException {
    Path candidate = provision(root.resolve("candidate"));
    Path genesis = provision(root.resolve("genesis"));
    List<byte[]> history =
        support.encode(support.stream("equivalence"), support.fullStateCommands());
    applyBoth(candidate, genesis, history, checkpoint);
    compareRecovered(candidate, genesis);
    return proved(assertion);
  }

  private void applyBoth(Path candidate, Path genesis, List<byte[]> history, boolean checkpoint)
      throws IOException {
    apply(candidate, support.config(candidate), history, checkpoint, List.of());
    apply(genesis, support.unbounded(genesis), history, false, List.of());
  }

  private static void apply(
      Path directory,
      WalConfig config,
      List<byte[]> prefix,
      boolean checkpoint,
      List<byte[]> suffix)
      throws IOException {
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config)) {
      prefix.forEach(
          value -> M09ScenarioSupport.requireNew(runtime.submit(value), "prefix submit"));
      if (checkpoint) {
        runtime.checkpoint();
      }
      suffix.forEach(
          value -> M09ScenarioSupport.requireNew(runtime.submit(value), "suffix submit"));
    }
  }

  private void compareRecovered(Path candidate, Path genesis) throws IOException {
    try (LocalMatchingRuntime restored = LocalMatchingRuntime.open(support.config(candidate));
        LocalMatchingRuntime baseline = LocalMatchingRuntime.open(support.unbounded(genesis))) {
      M09ScenarioSupport.requireEquivalent(restored, baseline);
    }
  }

  private Path publishOne(Path directory, String producer) throws IOException {
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(support.config(directory))) {
      M09ScenarioSupport.requireNew(
          runtime.submit(support.stream(producer).next(M09ScenarioSupport.cancel(1))),
          "snapshot corruption setup");
      runtime.checkpoint();
    }
    List<Path> snapshots = M09ScenarioSupport.snapshotFiles(directory);
    M09ScenarioSupport.require(snapshots.size() == 1, "expected one snapshot file");
    return snapshots.getFirst();
  }

  private void expectSnapshotFailure(Path directory, String message) {
    try {
      LocalMatchingRuntime.open(support.config(directory)).close();
      throw new M09SemanticFailure(message);
    } catch (SnapshotCorruptionException expected) {
      // Required fail-closed snapshot boundary.
    } catch (IOException failure) {
      throw new IllegalStateException("unexpected M09 snapshot-open failure", failure);
    }
  }

  private static UUID uuid(long sequence) {
    return uuidFor("identity", sequence);
  }

  static void requirePublicationStorageOperations(
      List<M09RuntimeJudgeProbe.StorageOperationObservation> operations) {
    int force = findStorageOperation(operations, 0, "FORCE_FILE", ".m09s1.tmp", "");
    int move = findStorageOperation(operations, force + 1, "ATOMIC_MOVE", ".m09s1.tmp", ".m09s1");
    findStorageOperation(operations, move + 1, "FORCE_DIRECTORY", "", "");
  }

  static void requireRetirementStorageOperations(
      List<M09RuntimeJudgeProbe.StorageOperationObservation> operations) {
    int walDelete = findStorageOperation(operations, 0, "DELETE", ".m08w1", "");
    int snapshotForce =
        findLastStorageOperation(operations, walDelete, "FORCE_FILE", ".m09s1.tmp", "");
    int snapshotMove =
        findStorageOperation(operations, snapshotForce + 1, "ATOMIC_MOVE", ".m09s1.tmp", ".m09s1");
    int publicationDirectoryForce =
        findStorageOperation(operations, snapshotMove + 1, "FORCE_DIRECTORY", "", "");
    M09ScenarioSupport.require(
        publicationDirectoryForce < walDelete,
        "WAL retirement preceded the published snapshot directory force");
    findStorageOperation(operations, walDelete + 1, "FORCE_DIRECTORY", "", "");
  }

  private static int findStorageOperation(
      List<M09RuntimeJudgeProbe.StorageOperationObservation> operations,
      int start,
      String kind,
      String pathSuffix,
      String targetSuffix) {
    for (int index = start; index < operations.size(); index++) {
      M09RuntimeJudgeProbe.StorageOperationObservation operation = operations.get(index);
      if (kind.equals(operation.kind())
          && operation.path().endsWith(pathSuffix)
          && operation.target().endsWith(targetSuffix)) {
        return index;
      }
    }
    throw new M09SemanticFailure(
        "missing real storage operation " + kind + '|' + pathSuffix + '|' + targetSuffix);
  }

  private static int findLastStorageOperation(
      List<M09RuntimeJudgeProbe.StorageOperationObservation> operations,
      int endExclusive,
      String kind,
      String pathSuffix,
      String targetSuffix) {
    for (int index = endExclusive - 1; index >= 0; index--) {
      M09RuntimeJudgeProbe.StorageOperationObservation operation = operations.get(index);
      if (kind.equals(operation.kind())
          && operation.path().endsWith(pathSuffix)
          && operation.target().endsWith(targetSuffix)) {
        return index;
      }
    }
    throw new M09SemanticFailure(
        "missing prior real storage operation " + kind + '|' + pathSuffix + '|' + targetSuffix);
  }

  private static UUID uuidFor(String producer, long sequence) {
    return new UUID(0x0909000000000000L ^ producer.hashCode(), sequence);
  }

  private static void requireSame(
      SubmissionResult actual, SubmissionResult expected, String boundary) {
    M09ScenarioSupport.require(
        M09ScenarioSupport.signature(actual).equals(M09ScenarioSupport.signature(expected)),
        boundary + " diverged from genesis replay");
  }

  private static String canonicalEvents(SubmissionResult result) {
    if (!(result instanceof SubmissionResult.NewDurablyApplied applied)) {
      throw new M09SemanticFailure("expected durable continuation result: " + result);
    }
    return String.join("\n", applied.result().events());
  }

  private static Path provision(Path directory) {
    try {
      return Files.createDirectories(directory);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot provision M09 directory", failure);
    }
  }

  private static ArrayNode strings(List<String> values) {
    ArrayNode result = JsonSupport.MAPPER.createArrayNode();
    values.forEach(result::add);
    return result;
  }

  private static final class RecordingFault implements FaultInjector {
    private final List<FaultPoint> points = new ArrayList<>();
    private final Path directory;
    private final boolean capturePublicationNamespace;
    private final M09FileInventory inventory = new M09FileInventory();
    private final List<M09RuntimeJudgeProbe.StorageOperationObservation> storageOperations =
        new ArrayList<>();
    private boolean forcedCanonicalTempBeforeRename;
    private boolean canonicalFinalAfterRenameBeforeDirectoryForce;
    private boolean oldWalAfterDirectoryForceBeforeRetention;

    private RecordingFault(Path directory) {
      this(directory, true);
    }

    private RecordingFault(Path directory, boolean capturePublicationNamespace) {
      this.directory = directory;
      this.capturePublicationNamespace = capturePublicationNamespace;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      points.add(point);
      if (!capturePublicationNamespace) {
        return;
      }
      M09FileInventory.Inventory observed = inventory.inspect(directory);
      if (point == FaultPoint.BEFORE_SNAPSHOT_READ
          && observed.count(M09FileInventory.Kind.SNAPSHOT_TEMP) == 1
          && observed.count(M09FileInventory.Kind.SNAPSHOT) == 0) {
        Path temporary = M09ScenarioSupport.tempSnapshots(directory).getFirst();
        requireCanonicalSnapshot(
            temporary, "forced snapshot temp was not complete before atomic rename");
        forcedCanonicalTempBeforeRename = true;
      } else if (point == FaultPoint.BEFORE_SNAPSHOT_DIRECTORY_FORCE) {
        M09ScenarioSupport.require(
            observed.count(M09FileInventory.Kind.SNAPSHOT_TEMP) == 0
                && observed.count(M09FileInventory.Kind.SNAPSHOT) == 1,
            "snapshot namespace did not switch temp to final before directory force");
        requireCanonicalSnapshot(
            M09ScenarioSupport.snapshotFiles(directory).getFirst(),
            "renamed snapshot final was not complete before directory force");
        canonicalFinalAfterRenameBeforeDirectoryForce = true;
      } else if (point == FaultPoint.AFTER_SNAPSHOT_DIRECTORY_FORCE_BEFORE_RETENTION) {
        M09ScenarioSupport.require(
            observed.count(M09FileInventory.Kind.SNAPSHOT_TEMP) == 0
                && observed.count(M09FileInventory.Kind.SNAPSHOT) == 1
                && observed.count(M09FileInventory.Kind.WAL_SEGMENT) >= 1,
            "published snapshot did not retain the old WAL before retention");
        requireCanonicalSnapshot(
            M09ScenarioSupport.snapshotFiles(directory).getFirst(),
            "directory-forced snapshot final was not complete before retention");
        oldWalAfterDirectoryForceBeforeRetention = true;
      }
    }

    private void requireOrder(FaultPoint... required) {
      int previous = -1;
      for (FaultPoint point : required) {
        int index = points.subList(previous + 1, points.size()).indexOf(point);
        M09ScenarioSupport.require(index >= 0, "required M09 hook not observed: " + point);
        previous += index + 1;
      }
    }

    private void requireNamespaceWitnesses() {
      M09ScenarioSupport.require(
          forcedCanonicalTempBeforeRename,
          "forced canonical temp namespace witness was not observed");
      M09ScenarioSupport.require(
          canonicalFinalAfterRenameBeforeDirectoryForce,
          "rename-before-directory-force namespace witness was not observed");
      M09ScenarioSupport.require(
          oldWalAfterDirectoryForceBeforeRetention,
          "directory-force-before-retention WAL witness was not observed");
    }

    private void recordStorageOperation(
        M09RuntimeJudgeProbe.StorageOperationObservation operation) {
      storageOperations.add(operation);
    }

    private List<M09RuntimeJudgeProbe.StorageOperationObservation> storageOperations() {
      return List.copyOf(storageOperations);
    }

    private static void requireCanonicalSnapshot(Path snapshot, String message) throws IOException {
      try {
        M09RuntimeJudgeProbe.requireCanonicalSnapshot(snapshot);
      } catch (SnapshotCorruptionException failure) {
        throw new M09SemanticFailure(message);
      }
    }
  }

  private static final class OneShotFailure implements FaultInjector {
    private final FaultPoint target;
    private boolean hit;
    private IOException injected;

    private OneShotFailure(FaultPoint target) {
      this.target = target;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (!hit && point == target) {
        hit = true;
        injected = new IOException("injected M09 fixed failure " + point);
        throw injected;
      }
    }

    private boolean hit() {
      return hit;
    }

    private IOException injected() {
      return injected;
    }
  }

  private static final class ArmedFailure implements FaultInjector {
    private final FaultPoint target;
    private boolean armed;
    private boolean hit;
    private IOException injected;

    private ArmedFailure(FaultPoint target) {
      this.target = target;
    }

    private void arm() {
      armed = true;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (armed && !hit && point == target) {
        hit = true;
        injected = new IOException("injected armed M09 fixed failure " + point);
        throw injected;
      }
    }

    private boolean hit() {
      return hit;
    }

    private IOException injected() {
      return injected;
    }
  }

  private static final class RecoveryApplyCounter implements FaultInjector {
    private int applies;

    @Override
    public void hit(FaultPoint point) {
      if (point == FaultPoint.BEFORE_RECOVERY_APPLY) {
        applies++;
      }
    }

    private int applies() {
      return applies;
    }
  }

  private static Observation proved(String assertion, String... obligations) {
    return provedWithFactsAndMetrics(assertion, Map.of(), Map.of(), obligations);
  }

  private static Observation provedWithFactsAndMetrics(
      String assertion,
      Map<String, Integer> metrics,
      Map<String, String> facts,
      String... obligations) {
    Map<String, String> proofs = new LinkedHashMap<>();
    for (String obligation : obligations) {
      proofs.put(obligation, assertion + ":" + obligation);
    }
    return new Observation(assertion, Map.copyOf(metrics), Map.copyOf(facts), Map.copyOf(proofs));
  }

  private record Observation(
      String assertion,
      Map<String, Integer> metrics,
      Map<String, String> facts,
      Map<String, String> proofs) {
    private Observation {
      metrics = Map.copyOf(metrics);
      facts = Map.copyOf(facts);
      proofs = Map.copyOf(proofs);
    }
  }

  record Result(ArrayNode scenarios, M09Coverage coverage, byte[] canonicalBytes, String digest) {
    Result {
      scenarios = scenarios.deepCopy();
      canonicalBytes = canonicalBytes.clone();
    }

    @Override
    public ArrayNode scenarios() {
      return scenarios.deepCopy();
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }
}

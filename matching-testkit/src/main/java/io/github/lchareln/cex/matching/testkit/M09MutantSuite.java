package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.local.FaultInjector;
import io.github.lchareln.cex.matching.local.FaultPoint;
import io.github.lchareln.cex.matching.local.LocalMatchingRuntime;
import io.github.lchareln.cex.matching.local.M09RuntimeJudgeProbe;
import io.github.lchareln.cex.matching.local.RecoveryException;
import io.github.lchareln.cex.matching.local.SnapshotCorruptionException;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import io.github.lchareln.cex.matching.local.WalCorruptionException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Nine storage/state mutants plus three invalid-latest candidates with strict replay. */
final class M09MutantSuite {
  static final String COUNTEREXAMPLE_SCHEMA = "schemas/matching.m09.counterexamples.v1.schema.json";
  private final M09ScenarioSupport support = new M09ScenarioSupport();

  Result run(M09Corpus corpus, Path repositoryRoot) {
    Map<String, Definition> definitions = definitions();
    ArrayNode persisted = JsonSupport.MAPPER.createArrayNode();
    ArrayNode summaries = JsonSupport.MAPPER.createArrayNode();
    StringBuilder canonical = new StringBuilder("M09X1\n");
    int killed = 0;
    int rawOperations = 0;
    int minimalOperations = 0;
    int shrinkTrials = 0;
    int mutationActions = 0;
    int singleDeletePasses = 0;
    int singleDeleteInvalidHistories = 0;
    int singleDeleteDifferentStudentFailures = 0;
    int singleDeleteSameFingerprintStudentFailures = 0;
    for (String id : corpus.generator().requiredMutants()) {
      Definition definition = definitions.get(id);
      systemRequire(definition != null, "missing executable M09 mutant " + id);
      List<String> raw = new ArrayList<>();
      raw.add("NOOP_PREFIX");
      raw.addAll(definition.requiredGrammar());
      raw.add("NOOP_SUFFIX");
      Outcome production = execute(definition, raw, false);
      if (production.classification() == Classification.SYSTEM_ERROR) {
        throw new IllegalStateException(
            "M09 production mutant control SYSTEM_ERROR " + id + ": " + production.detail());
      }
      if (production.classification() != Classification.PASS) {
        throw new M09SemanticFailure(
            "M09 production control violates " + id + ": " + production.fingerprint());
      }
      Outcome mutant = execute(definition, raw, true);
      if (mutant.classification() == Classification.SYSTEM_ERROR) {
        throw new IllegalStateException(
            "M09 executable mutant SYSTEM_ERROR " + id + ": " + mutant.detail());
      }
      if (mutant.classification() != Classification.STUDENT_FAILURE) {
        throw new M09SemanticFailure("required M09 mutant survived: " + id);
      }
      systemRequire(mutant.actions() > 0, "M09 mutant had no executable mutation action: " + id);
      Shrink shrink = shrink(definition, raw);
      Outcome first = execute(definition, shrink.operations(), true);
      Outcome replay = execute(definition, shrink.operations(), true);
      systemRequire(
          first.equals(replay)
              && first.classification() == Classification.STUDENT_FAILURE
              && first.fingerprint().equals(definition.fingerprint())
              && first.actions() > 0
              && first.interpretedOperations() == shrink.operations().size(),
          "M09 strict mutant replay changed: " + id);
      DeletionAudit deletionAudit = deletionAudit(definition, shrink.operations());
      systemRequire(
          deletionAudit.sameFingerprintStudentFailures() == 0,
          "M09 counterexample is not one-minimal: " + id);

      ObjectNode counterexample = persisted.addObject();
      counterexample.put("mutantId", id);
      counterexample.put("classification", "STUDENT_FAILURE");
      counterexample.put("propertyFingerprint", first.fingerprint());
      counterexample.put("rawOperations", raw.size());
      counterexample.put("minimalOperations", shrink.operations().size());
      counterexample.put("shrinkTrials", shrink.trials());
      counterexample.put("freshRuntime", true);
      counterexample.put("realSnapshotOrWalFiles", true);
      counterexample.put("strictReplay", true);
      counterexample.put("operationInterpreter", true);
      counterexample.put("interpretedOperations", first.interpretedOperations());
      counterexample.put("oneMinimal", true);
      counterexample.put("singleDeletePasses", deletionAudit.passes());
      counterexample.put("singleDeleteInvalidHistories", deletionAudit.invalidHistories());
      counterexample.put(
          "singleDeleteDifferentStudentFailures", deletionAudit.differentStudentFailures());
      counterexample.put(
          "singleDeleteSameFingerprintStudentFailures",
          deletionAudit.sameFingerprintStudentFailures());
      counterexample.put("systemErrorCountedAsKill", false);
      counterexample.put("actualMutationActions", first.actions());
      ArrayNode operations = counterexample.putArray("operations");
      shrink.operations().forEach(operations::add);

      ObjectNode summary = summaries.addObject();
      summary.put("id", id);
      summary.put("classification", "STUDENT_FAILURE");
      summary.put("killed", true);
      summary.put("fingerprint", first.fingerprint());
      summary.put("actualMutationActions", first.actions());
      summary.put("systemErrorCountedAsKill", false);
      canonical
          .append(id)
          .append('|')
          .append(first.fingerprint())
          .append('|')
          .append(first.actions())
          .append('|')
          .append(first.interpretedOperations())
          .append('|')
          .append(deletionAudit.passes())
          .append('/')
          .append(deletionAudit.invalidHistories())
          .append('/')
          .append(deletionAudit.differentStudentFailures())
          .append('|')
          .append(String.join(",", shrink.operations()))
          .append('\n');
      killed++;
      rawOperations += raw.size();
      minimalOperations += shrink.operations().size();
      shrinkTrials += shrink.trials();
      mutationActions += first.actions();
      singleDeletePasses += deletionAudit.passes();
      singleDeleteInvalidHistories += deletionAudit.invalidHistories();
      singleDeleteDifferentStudentFailures += deletionAudit.differentStudentFailures();
      singleDeleteSameFingerprintStudentFailures += deletionAudit.sameFingerprintStudentFailures();
    }

    Definition throwing =
        new Definition(
            "THROWING-CONTROL",
            "THROWING_CONTROL",
            List.of("OPEN", "THROW", "CLOSE"),
            (directory, mutated, counter, program) -> {
              stage(program, "OPEN", true);
              if (stage(program, "THROW", true)) {
                throw new IllegalStateException("deterministic M09 throwing control");
              }
              stage(program, "CLOSE", true);
            });
    Outcome throwingOutcome = execute(throwing, throwing.requiredGrammar(), true);
    systemRequire(
        throwingOutcome.classification() == Classification.SYSTEM_ERROR,
        "M09 throwing control was not SYSTEM_ERROR");

    ObjectNode counterexamples = JsonSupport.MAPPER.createObjectNode();
    counterexamples.put("schemaVersion", "matching.m09.counterexamples.v1");
    counterexamples.put("unit", "M09");
    counterexamples.set("counterexamples", persisted);
    JsonNode parsed = JsonSupport.parse(JsonSupport.prettyBytes(counterexamples));
    JsonSupport.validate(parsed, readString(repositoryRoot.resolve(COUNTEREXAMPLE_SCHEMA)), false);
    byte[] canonicalBytes = canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return new Result(
        counterexamples,
        summaries,
        killed,
        rawOperations,
        minimalOperations,
        shrinkTrials,
        mutationActions,
        singleDeletePasses,
        singleDeleteInvalidHistories,
        singleDeleteDifferentStudentFailures,
        singleDeleteSameFingerprintStudentFailures,
        throwingOutcome.classification().name(),
        canonicalBytes,
        Hashing.sha256Hex(canonicalBytes));
  }

  private Map<String, Definition> definitions() {
    Map<String, Definition> values = new LinkedHashMap<>();
    stateDefinition(
        values,
        "M09-SNAPSHOT-DROPS-RESTING-ORDER",
        "SNAPSHOT_RESTING_ORDER_STATE",
        M09RuntimeJudgeProbe.StateMutation.DROP_RESTING_ORDER);
    stateDefinition(
        values,
        "M09-SNAPSHOT-RESETS-MARKET-MODE",
        "SNAPSHOT_MARKET_MODE_STATE",
        M09RuntimeJudgeProbe.StateMutation.RESET_MARKET_MODE);
    stateDefinition(
        values,
        "M09-SNAPSHOT-DROPS-PREPARED-RULE-SET",
        "SNAPSHOT_PREPARED_RULE_STATE",
        M09RuntimeJudgeProbe.StateMutation.DROP_PREPARED_RULE_SET);
    add(
        values,
        "M09-SNAPSHOT-DROPS-DURABLE-IDENTITY-RESULT",
        "SNAPSHOT_DURABLE_IDENTITY_RESULT",
        this::identityState,
        "OPEN",
        "SUBMIT_IDENTITIES",
        "CHECKPOINT",
        "CLOSE",
        "MUTATE_SNAPSHOT",
        "RESTART",
        "RETRY_ORIGINAL");
    add(
        values,
        "M09-SUFFIX-REPLAYS-CUT-RECORD",
        "SNAPSHOT_CUT_EXACTLY_ONCE",
        this::replayCut,
        "OPEN",
        "SUBMIT_CUT",
        "CHECKPOINT",
        "CLOSE",
        "MUTATE_SUFFIX",
        "RESTART");
    add(
        values,
        "M09-SUFFIX-SKIPS-FIRST-RECORD",
        "SNAPSHOT_FIRST_SUFFIX_PRESENT",
        this::skipSuffix,
        "OPEN",
        "SUBMIT_PREFIX",
        "CHECKPOINT",
        "SUBMIT_SUFFIX",
        "CLOSE",
        "MUTATE_SUFFIX",
        "RESTART");
    add(
        values,
        "M09-UNKNOWN-VERSION-ACCEPTED",
        "UNKNOWN_SNAPSHOT_VERSION_FAIL_CLOSED",
        this::unknownVersionAccepted,
        "PUBLISH",
        "CLOSE",
        "WRITE_UNKNOWN_VERSION",
        "RESTART");
    add(
        values,
        "M09-CORRUPT-SNAPSHOT-ACCEPTED",
        "CORRUPT_SNAPSHOT_FAIL_CLOSED",
        this::corruptionAccepted,
        "PUBLISH",
        "CLOSE",
        "CORRUPT_BODY",
        "RESTART");
    add(
        values,
        "M09-SNAPSHOT-IDENTITY-MISMATCH-ACCEPTED",
        "SNAPSHOT_IDENTITY_FAIL_CLOSED",
        this::identityMismatchAccepted,
        "PUBLISH",
        "CLOSE",
        "MISMATCH_HEADER",
        "RESTART");
    add(
        values,
        "M09-RETIREMENT-BEFORE-SNAPSHOT-DIRECTORY-FORCE",
        "RETIREMENT_AFTER_SNAPSHOT_DIRECTORY_FORCE",
        this::retirementBeforeForce,
        "PUBLISH_GENERATION_ONE",
        "SUBMIT_MORE",
        "FAIL_BEFORE_DIRECTORY_FORCE",
        "ASSERT_PREFIX_PRESENT");
    add(
        values,
        "M09-RETIREMENT-DELETES-CROSSING-SEGMENT",
        "RETIREMENT_RETAINS_CROSSING_SEGMENT",
        this::deleteCrossing,
        "CREATE_CROSSING_SEGMENT",
        "RESTART",
        "CHECKPOINT",
        "ASSERT_CROSSING_PRESENT");
    add(
        values,
        "M09-GENESIS-FALLBACK-WITH-MISSING-PREFIX",
        "MISSING_PREFIX_NO_GENESIS_FALLBACK",
        this::genesisFallback,
        "RETIRE_PREFIX",
        "REMOVE_SNAPSHOTS",
        "RESTART");
    return Map.copyOf(values);
  }

  private void stateDefinition(
      Map<String, Definition> values,
      String id,
      String fingerprint,
      M09RuntimeJudgeProbe.StateMutation mutation) {
    add(
        values,
        id,
        fingerprint,
        (directory, mutated, counter, program) ->
            stateMutation(directory, mutated, counter, mutation, fingerprint, program),
        "OPEN",
        "SUBMIT_FULL_STATE",
        "CHECKPOINT",
        "CLOSE",
        "MUTATE_SNAPSHOT",
        "RESTART",
        "ASSERT_DIGEST");
  }

  private static void add(
      Map<String, Definition> values,
      String id,
      String fingerprint,
      Scenario scenario,
      String... grammar) {
    values.put(id, new Definition(id, fingerprint, List.of(grammar), scenario));
  }

  private void stateMutation(
      Path directory,
      boolean mutated,
      Counter counter,
      M09RuntimeJudgeProbe.StateMutation mutation,
      String fingerprint,
      Program program)
      throws IOException {
    LocalMatchingRuntime runtime = null;
    LocalMatchingRuntime restored = null;
    String expected = null;
    try {
      if (stage(program, "OPEN", true)) {
        runtime = LocalMatchingRuntime.open(support.config(directory));
      }
      if (stage(program, "SUBMIT_FULL_STATE", runtime != null)) {
        for (byte[] command :
            support.encode(support.stream("mutant-state"), support.fullStateCommands())) {
          M09ScenarioSupport.requireNew(runtime.submit(command), "mutant state setup");
        }
        expected = runtime.semanticStateDigest();
      }
      if (stage(program, "CHECKPOINT", runtime != null && expected != null)) {
        runtime.checkpoint();
      }
      if (stage(program, "CLOSE", runtime != null)) {
        runtime.close();
        runtime = null;
      }
      if (stage(
              program,
              "MUTATE_SNAPSHOT",
              runtime == null && !M09ScenarioSupport.snapshotFiles(directory).isEmpty())
          && mutated) {
        counter.add(M09RuntimeJudgeProbe.mutateLatestSnapshot(directory, mutation));
      }
      if (stage(program, "RESTART", runtime == null)) {
        restored = LocalMatchingRuntime.open(support.config(directory));
      }
      if (stage(program, "ASSERT_DIGEST", restored != null && expected != null)
          && !expected.equals(restored.semanticStateDigest())) {
        difference(fingerprint);
      }
    } finally {
      closeCleanup(restored);
      closeCleanup(runtime);
    }
  }

  private void identityState(Path directory, boolean mutated, Counter counter, Program program)
      throws IOException {
    M09ScenarioSupport.CommandStream stream = support.stream("mutant-identity");
    byte[] first = stream.next(M09ScenarioSupport.cancel(1));
    LocalMatchingRuntime runtime = null;
    LocalMatchingRuntime restored = null;
    RecoveryException recoveryFailure = null;
    try {
      if (stage(program, "OPEN", true)) {
        runtime = LocalMatchingRuntime.open(support.config(directory));
      }
      if (stage(program, "SUBMIT_IDENTITIES", runtime != null)) {
        M09ScenarioSupport.requireNew(runtime.submit(first), "identity mutant first");
        M09ScenarioSupport.requireNew(
            runtime.submit(stream.next(M09ScenarioSupport.cancel(2))), "identity mutant second");
      }
      if (stage(program, "CHECKPOINT", runtime != null && runtime.nextWalSequence() == 3)) {
        runtime.checkpoint();
      }
      if (stage(program, "CLOSE", runtime != null)) {
        runtime.close();
        runtime = null;
      }
      if (stage(
              program,
              "MUTATE_SNAPSHOT",
              runtime == null && !M09ScenarioSupport.snapshotFiles(directory).isEmpty())
          && mutated) {
        counter.add(
            M09RuntimeJudgeProbe.mutateLatestSnapshot(
                directory, M09RuntimeJudgeProbe.StateMutation.DROP_DURABLE_IDENTITY_RESULT));
      }
      if (stage(program, "RESTART", runtime == null)) {
        try {
          restored = LocalMatchingRuntime.open(support.config(directory));
        } catch (RecoveryException failure) {
          recoveryFailure = failure;
        }
      }
      if (stage(program, "RETRY_ORIGINAL", restored != null || recoveryFailure != null)) {
        if (recoveryFailure != null) {
          if (!mutated) {
            throw recoveryFailure;
          }
          requireIdentityDropRecovery(recoveryFailure);
          difference("SNAPSHOT_DURABLE_IDENTITY_RESULT");
        }
        if (!(restored.submit(first) instanceof SubmissionResult.DuplicateReplayed)) {
          difference("SNAPSHOT_DURABLE_IDENTITY_RESULT");
        }
      }
    } finally {
      closeCleanup(restored);
      closeCleanup(runtime);
    }
  }

  private void replayCut(Path directory, boolean mutated, Counter counter, Program program)
      throws IOException {
    LocalMatchingRuntime runtime = null;
    LocalMatchingRuntime restored = null;
    try {
      if (stage(program, "OPEN", true)) {
        runtime = LocalMatchingRuntime.open(support.config(directory));
      }
      if (stage(program, "SUBMIT_CUT", runtime != null)) {
        M09ScenarioSupport.requireNew(
            runtime.submit(support.stream("mutant-cut").next(M09ScenarioSupport.cancel(1))),
            "cut mutant setup");
      }
      if (stage(program, "CHECKPOINT", runtime != null && runtime.nextWalSequence() == 2)) {
        runtime.checkpoint();
      }
      if (stage(program, "CLOSE", runtime != null)) {
        runtime.close();
        runtime = null;
      }
      if (stage(
              program,
              "MUTATE_SUFFIX",
              runtime == null && !M09ScenarioSupport.snapshotFiles(directory).isEmpty())
          && mutated) {
        counter.add(
            M09RuntimeJudgeProbe.replayCutRecordAsFirstSuffix(directory, M09ScenarioSupport.SHARD));
      }
      if (stage(program, "RESTART", runtime == null)) {
        try {
          restored = LocalMatchingRuntime.open(support.config(directory));
        } catch (RecoveryException failure) {
          if (!mutated) {
            throw failure;
          }
          requireCutReplayRecovery(failure);
          difference("SNAPSHOT_CUT_EXACTLY_ONCE");
        }
        if (restored != null && restored.nextWalSequence() != 2) {
          difference("SNAPSHOT_CUT_EXACTLY_ONCE");
        }
      }
    } finally {
      closeCleanup(restored);
      closeCleanup(runtime);
    }
  }

  private void skipSuffix(Path directory, boolean mutated, Counter counter, Program program)
      throws IOException {
    M09ScenarioSupport.CommandStream stream = support.stream("mutant-suffix");
    LocalMatchingRuntime runtime = null;
    LocalMatchingRuntime restored = null;
    String expected = null;
    try {
      if (stage(program, "OPEN", true)) {
        runtime = LocalMatchingRuntime.open(support.config(directory));
      }
      if (stage(program, "SUBMIT_PREFIX", runtime != null)) {
        M09ScenarioSupport.requireNew(
            runtime.submit(stream.next(M09ScenarioSupport.cancel(1))), "suffix prefix");
      }
      if (stage(program, "CHECKPOINT", runtime != null && runtime.nextWalSequence() == 2)) {
        runtime.checkpoint();
      }
      if (stage(program, "SUBMIT_SUFFIX", runtime != null)) {
        M09ScenarioSupport.requireNew(
            runtime.submit(stream.next(M09ScenarioSupport.cancel(2))), "suffix command");
        expected = runtime.semanticStateDigest();
      }
      if (stage(program, "CLOSE", runtime != null)) {
        runtime.close();
        runtime = null;
      }
      if (stage(
              program,
              "MUTATE_SUFFIX",
              runtime == null
                  && expected != null
                  && !M09ScenarioSupport.snapshotFiles(directory).isEmpty())
          && mutated) {
        counter.add(M09RuntimeJudgeProbe.dropFirstSuffixRecord(directory));
      }
      if (stage(program, "RESTART", runtime == null && expected != null)) {
        restored = LocalMatchingRuntime.open(support.config(directory));
        if (restored.nextWalSequence() != 3 || !expected.equals(restored.semanticStateDigest())) {
          difference("SNAPSHOT_FIRST_SUFFIX_PRESENT");
        }
      }
    } finally {
      closeCleanup(restored);
      closeCleanup(runtime);
    }
  }

  private void unknownVersionAccepted(
      Path directory, boolean mutated, Counter counter, Program program) throws IOException {
    LocalMatchingRuntime runtime = null;
    Path snapshot = null;
    try {
      if (stage(program, "PUBLISH", true)) {
        runtime = publishOpen(directory, "mutant-version");
        snapshot = M09ScenarioSupport.snapshotFiles(directory).getFirst();
      }
      if (stage(program, "CLOSE", runtime != null)) {
        runtime.close();
        runtime = null;
      }
      if (stage(program, "WRITE_UNKNOWN_VERSION", runtime == null && snapshot != null)) {
        M09ScenarioSupport.rewriteVersionWithValidIntegrity(snapshot, 2);
        if (mutated) {
          M09ScenarioSupport.rewriteVersionWithValidIntegrity(snapshot, 1);
          counter.add(1);
        }
      }
      if (stage(program, "RESTART", runtime == null && snapshot != null)) {
        rejectedOrAccepted(directory, mutated, "UNKNOWN_SNAPSHOT_VERSION_FAIL_CLOSED");
      }
    } finally {
      closeCleanup(runtime);
    }
  }

  private void corruptionAccepted(Path directory, boolean mutated, Counter counter, Program program)
      throws IOException {
    LocalMatchingRuntime runtime = null;
    Path snapshot = null;
    try {
      if (stage(program, "PUBLISH", true)) {
        runtime = publishOpen(directory, "mutant-corrupt");
        snapshot = M09ScenarioSupport.snapshotFiles(directory).getFirst();
      }
      if (stage(program, "CLOSE", runtime != null)) {
        runtime.close();
        runtime = null;
      }
      if (stage(program, "CORRUPT_BODY", runtime == null && snapshot != null)) {
        byte[] valid = Files.readAllBytes(snapshot);
        M09ScenarioSupport.corruptBody(snapshot);
        if (mutated) {
          forceWrite(snapshot, valid);
          counter.add(1);
        }
      }
      if (stage(program, "RESTART", runtime == null && snapshot != null)) {
        rejectedOrAccepted(directory, mutated, "CORRUPT_SNAPSHOT_FAIL_CLOSED");
      }
    } finally {
      closeCleanup(runtime);
    }
  }

  private void identityMismatchAccepted(
      Path directory, boolean mutated, Counter counter, Program program) throws IOException {
    LocalMatchingRuntime runtime = null;
    Path snapshot = null;
    try {
      if (stage(program, "PUBLISH", true)) {
        runtime = publishOpen(directory, "mutant-identity-mismatch");
        snapshot = M09ScenarioSupport.snapshotFiles(directory).getFirst();
      }
      if (stage(program, "CLOSE", runtime != null)) {
        runtime.close();
        runtime = null;
      }
      if (stage(program, "MISMATCH_HEADER", runtime == null && snapshot != null)) {
        M09ScenarioSupport.rewriteShardWithValidIntegrity(snapshot, M09ScenarioSupport.SHARD + 1);
        if (mutated) {
          M09ScenarioSupport.rewriteShardWithValidIntegrity(snapshot, M09ScenarioSupport.SHARD);
          counter.add(1);
        }
      }
      if (stage(program, "RESTART", runtime == null && snapshot != null)) {
        rejectedOrAccepted(directory, mutated, "SNAPSHOT_IDENTITY_FAIL_CLOSED");
      }
    } finally {
      closeCleanup(runtime);
    }
  }

  private void rejectedOrAccepted(Path directory, boolean mutated, String fingerprint)
      throws IOException {
    try (LocalMatchingRuntime restored = LocalMatchingRuntime.open(support.config(directory))) {
      if (mutated && restored.nextWalSequence() == 2) {
        difference(fingerprint);
      }
      throw new M09SemanticFailure("invalid M09 snapshot unexpectedly opened");
    } catch (SnapshotCorruptionException expected) {
      if (mutated) {
        throw expected;
      }
    }
  }

  private void retirementBeforeForce(
      Path directory, boolean mutated, Counter counter, Program program) throws IOException {
    M09ScenarioSupport.CommandStream stream = support.stream("mutant-retirement-order");
    LocalMatchingRuntime runtime = null;
    Path firstSegment = null;
    DeleteBeforeForce fault = null;
    try {
      if (stage(program, "PUBLISH_GENERATION_ONE", true)) {
        runtime = LocalMatchingRuntime.open(support.config(directory));
        M09ScenarioSupport.requireNew(
            runtime.submit(stream.next(M09ScenarioSupport.cancel(1))), "retirement mutant first");
        runtime.checkpoint();
        runtime.close();
        runtime = null;
        firstSegment = M09ScenarioSupport.segmentFiles(directory).getFirst();
        fault = new DeleteBeforeForce(firstSegment, mutated, counter);
      }
      if (stage(program, "SUBMIT_MORE", runtime == null && fault != null)) {
        runtime = LocalMatchingRuntime.open(support.config(directory), fault);
        M09ScenarioSupport.requireNew(
            runtime.submit(stream.next(M09ScenarioSupport.cancel(2))), "retirement mutant second");
      }
      if (stage(program, "FAIL_BEFORE_DIRECTORY_FORCE", runtime != null && fault != null)) {
        try {
          runtime.checkpoint();
          throw new IllegalStateException("retirement order mutant fault was not injected");
        } catch (IOException expected) {
          systemRequire(fault.hit(), "retirement order seam was not reached");
          M09ScenarioSupport.requireExactInjectedIOException(
              expected, fault.injected(), "retirement order mutant seam");
        }
        runtime.close();
        runtime = null;
      }
      if (stage(program, "ASSERT_PREFIX_PRESENT", runtime == null && firstSegment != null)
          && !Files.exists(firstSegment)) {
        difference("RETIREMENT_AFTER_SNAPSHOT_DIRECTORY_FORCE");
      }
    } finally {
      closeCleanup(runtime);
    }
  }

  private void deleteCrossing(Path directory, boolean mutated, Counter counter, Program program)
      throws IOException {
    M09ScenarioSupport.CommandStream stream = support.stream("mutant-crossing");
    LocalMatchingRuntime runtime = null;
    Path crossing = null;
    try {
      if (stage(program, "CREATE_CROSSING_SEGMENT", true)) {
        runtime = LocalMatchingRuntime.open(support.config(directory));
        M09ScenarioSupport.requireNew(
            runtime.submit(stream.next(M09ScenarioSupport.cancel(1))), "crossing mutant prefix");
        runtime.checkpoint();
        M09ScenarioSupport.requireNew(
            runtime.submit(stream.next(M09ScenarioSupport.cancel(2))), "crossing mutant suffix");
        runtime.close();
        runtime = null;
        M09RuntimeJudgeProbe.CrossingFixture fixture =
            M09RuntimeJudgeProbe.createCrossingSegmentFixture(directory, M09ScenarioSupport.SHARD);
        crossing = directory.resolve(fixture.crossingSegment());
      }
      if (stage(program, "RESTART", runtime == null && crossing != null)) {
        runtime = LocalMatchingRuntime.open(support.config(directory));
      }
      if (stage(program, "CHECKPOINT", runtime != null && crossing != null)) {
        if (mutated) {
          Files.delete(crossing);
          counter.add(1);
        }
        runtime.checkpoint();
        runtime.close();
        runtime = null;
      }
      if (stage(program, "ASSERT_CROSSING_PRESENT", runtime == null && crossing != null)
          && !Files.exists(crossing)) {
        difference("RETIREMENT_RETAINS_CROSSING_SEGMENT");
      }
    } finally {
      closeCleanup(runtime);
    }
  }

  private void genesisFallback(Path directory, boolean mutated, Counter counter, Program program)
      throws IOException {
    LocalMatchingRuntime runtime = null;
    boolean retiredPrefix = false;
    boolean removedSnapshots = false;
    try {
      if (stage(program, "RETIRE_PREFIX", true)) {
        runtime = LocalMatchingRuntime.open(support.config(directory));
        M09ScenarioSupport.CommandStream stream = support.stream("mutant-genesis");
        for (int index = 1; index <= 3; index++) {
          M09ScenarioSupport.requireNew(
              runtime.submit(stream.next(M09ScenarioSupport.cancel(index))),
              "genesis mutant setup");
          runtime.checkpoint();
        }
        runtime.close();
        runtime = null;
        retiredPrefix = true;
      }
      if (stage(program, "REMOVE_SNAPSHOTS", runtime == null && retiredPrefix)) {
        for (Path snapshot : M09ScenarioSupport.snapshotFiles(directory)) {
          Files.delete(snapshot);
        }
        removedSnapshots = true;
      }
      if (stage(program, "RESTART", runtime == null && removedSnapshots)) {
        if (mutated) {
          for (Path segment : M09ScenarioSupport.segmentFiles(directory)) {
            Files.delete(segment);
            counter.add(1);
          }
        }
        try {
          runtime = LocalMatchingRuntime.open(support.config(directory));
          if (mutated && runtime.nextWalSequence() == 1) {
            difference("MISSING_PREFIX_NO_GENESIS_FALLBACK");
          }
          throw new M09SemanticFailure("missing M09 prefix unexpectedly opened");
        } catch (WalCorruptionException expected) {
          if (mutated) {
            throw expected;
          }
        }
      }
    } finally {
      closeCleanup(runtime);
    }
  }

  private LocalMatchingRuntime publishOpen(Path directory, String producer) throws IOException {
    LocalMatchingRuntime runtime = LocalMatchingRuntime.open(support.config(directory));
    try {
      M09ScenarioSupport.requireNew(
          runtime.submit(support.stream(producer).next(M09ScenarioSupport.cancel(1))),
          "mutant snapshot publish");
      runtime.checkpoint();
      return runtime;
    } catch (IOException | RuntimeException failure) {
      closeCleanup(runtime);
      throw failure;
    }
  }

  private static void forceWrite(Path path, byte[] bytes) throws IOException {
    Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING);
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  private Outcome execute(Definition definition, List<String> operations, boolean mutated) {
    Path directory = null;
    Counter counter = new Counter();
    Program program = new Program(operations);
    try {
      directory = Files.createTempDirectory("m09-mutant-");
      definition.scenario().run(directory, mutated, counter, program);
      program.finish();
      if (program.invalid()) {
        return new Outcome(
            Classification.INVALID_HISTORY,
            "",
            program.invalidDetail(),
            counter.actions(),
            program.interpretedOperations());
      }
      return new Outcome(
          Classification.PASS, "", "", counter.actions(), program.interpretedOperations());
    } catch (Difference difference) {
      program.finish();
      if (program.invalid()) {
        return new Outcome(
            Classification.INVALID_HISTORY,
            "",
            program.invalidDetail(),
            counter.actions(),
            program.interpretedOperations());
      }
      return new Outcome(
          Classification.STUDENT_FAILURE,
          difference.fingerprint(),
          difference.getMessage(),
          counter.actions(),
          program.interpretedOperations());
    } catch (RuntimeException | IOException failure) {
      return new Outcome(
          Classification.SYSTEM_ERROR,
          "",
          failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()),
          counter.actions(),
          program.interpretedOperations());
    } finally {
      if (directory != null) {
        M09ScenarioSupport.deleteTree(directory);
      }
    }
  }

  private Shrink shrink(Definition definition, List<String> raw) {
    List<String> current = new ArrayList<>(raw);
    int trials = 0;
    boolean changed;
    do {
      changed = false;
      for (int index = 0; index < current.size(); index++) {
        List<String> trial = new ArrayList<>(current);
        trial.remove(index);
        trials++;
        Outcome outcome = execute(definition, trial, true);
        if (outcome.classification() == Classification.SYSTEM_ERROR) {
          throw new IllegalStateException("M09 shrink trial SYSTEM_ERROR " + definition.id());
        }
        if (outcome.classification() == Classification.STUDENT_FAILURE
            && outcome.fingerprint().equals(definition.fingerprint())
            && outcome.actions() > 0) {
          current = trial;
          changed = true;
          break;
        }
      }
    } while (changed);
    return new Shrink(List.copyOf(current), trials);
  }

  private DeletionAudit deletionAudit(Definition definition, List<String> operations) {
    int passes = 0;
    int invalidHistories = 0;
    int differentStudentFailures = 0;
    int sameFingerprintStudentFailures = 0;
    for (int index = 0; index < operations.size(); index++) {
      List<String> trial = new ArrayList<>(operations);
      trial.remove(index);
      Outcome outcome = execute(definition, trial, true);
      if (outcome.classification() == Classification.SYSTEM_ERROR) {
        throw new IllegalStateException("M09 one-minimal trial SYSTEM_ERROR " + definition.id());
      }
      if (outcome.classification() == Classification.PASS) {
        passes++;
      } else if (outcome.classification() == Classification.INVALID_HISTORY) {
        invalidHistories++;
      } else if (outcome.fingerprint().equals(definition.fingerprint())) {
        sameFingerprintStudentFailures++;
      } else {
        differentStudentFailures++;
      }
    }
    return new DeletionAudit(
        passes, invalidHistories, differentStudentFailures, sameFingerprintStudentFailures);
  }

  private static boolean stage(Program program, String token, boolean dependenciesReady) {
    boolean present = program.consume(token);
    if (present && !dependenciesReady) {
      program.invalidate("operation " + token + " has unsatisfied runtime dependencies");
    }
    return present && dependenciesReady;
  }

  private static void difference(String fingerprint) {
    throw new Difference(fingerprint);
  }

  static void requireIdentityDropRecovery(RecoveryException failure) {
    Throwable cause = failure.getCause();
    if (!"M09S1 state restore failed".equals(failure.getMessage())
        || !(cause instanceof IllegalArgumentException)
        || !"identity bindings are not a contiguous durable history".equals(cause.getMessage())) {
      throw new IllegalStateException(
          "unexpected recovery failure instead of dropped durable identity", failure);
    }
  }

  static void requireCutReplayRecovery(RecoveryException failure) {
    if (!"durable M08C1 identities are not a new contiguous stream".equals(failure.getMessage())
        || failure.getCause() != null) {
      throw new IllegalStateException(
          "unexpected recovery failure instead of replayed snapshot cut", failure);
    }
  }

  List<String> requiredGrammarForAudit(String id) {
    Definition definition = definitions().get(id);
    systemRequire(definition != null, "unknown M09 mutant audit id " + id);
    return definition.requiredGrammar();
  }

  AuditOutcome executeForAudit(String id, List<String> operations, boolean mutated) {
    Definition definition = definitions().get(id);
    systemRequire(definition != null, "unknown M09 mutant audit id " + id);
    Outcome outcome = execute(definition, operations, mutated);
    return new AuditOutcome(
        outcome.classification().name(),
        outcome.fingerprint(),
        outcome.actions(),
        outcome.interpretedOperations());
  }

  private static String readString(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read M09 counterexample schema", failure);
    }
  }

  private static void systemRequire(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private static void closeCleanup(LocalMatchingRuntime runtime) throws IOException {
    if (runtime == null) {
      return;
    }
    runtime.close();
  }

  private static final class DeleteBeforeForce implements FaultInjector {
    private final Path segment;
    private final boolean mutated;
    private final Counter counter;
    private boolean hit;
    private IOException injected;

    private DeleteBeforeForce(Path segment, boolean mutated, Counter counter) {
      this.segment = segment;
      this.mutated = mutated;
      this.counter = counter;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (!hit && point == FaultPoint.BEFORE_SNAPSHOT_DIRECTORY_FORCE) {
        hit = true;
        if (mutated) {
          Files.delete(segment);
          counter.add(1);
        }
        injected = new IOException("injected M09 pre-directory-force stop");
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

  private static final class Counter {
    private int actions;

    private void add(int value) {
      actions = Math.addExact(actions, value);
    }

    private int actions() {
      return actions;
    }
  }

  private static final class Program {
    private final List<String> operations;
    private final List<String> invalidDetails = new ArrayList<>();
    private int cursor;
    private int interpretedOperations;

    private Program(List<String> operations) {
      this.operations = List.copyOf(operations);
    }

    private boolean consume(String expected) {
      while (cursor < operations.size() && operations.get(cursor).startsWith("NOOP_")) {
        cursor++;
      }
      if (cursor >= operations.size() || !expected.equals(operations.get(cursor))) {
        invalidate("missing or out-of-order operation " + expected);
        return false;
      }
      cursor++;
      interpretedOperations++;
      return true;
    }

    private void invalidate(String detail) {
      invalidDetails.add(detail);
    }

    private void finish() {
      while (cursor < operations.size()) {
        String operation = operations.get(cursor++);
        if (!operation.startsWith("NOOP_")) {
          interpretedOperations++;
          invalidate("unconsumed operation " + operation);
        }
      }
    }

    private boolean invalid() {
      return !invalidDetails.isEmpty();
    }

    private String invalidDetail() {
      return String.join(";", invalidDetails);
    }

    private int interpretedOperations() {
      return interpretedOperations;
    }
  }

  private static final class Difference extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String fingerprint;

    private Difference(String fingerprint) {
      super(fingerprint);
      this.fingerprint = fingerprint;
    }

    private String fingerprint() {
      return fingerprint;
    }
  }

  private enum Classification {
    PASS,
    INVALID_HISTORY,
    STUDENT_FAILURE,
    SYSTEM_ERROR
  }

  @FunctionalInterface
  private interface Scenario {
    void run(Path directory, boolean mutated, Counter counter, Program program) throws IOException;
  }

  private record Definition(
      String id, String fingerprint, List<String> requiredGrammar, Scenario scenario) {
    private Definition {
      requiredGrammar = List.copyOf(requiredGrammar);
    }
  }

  private record Outcome(
      Classification classification,
      String fingerprint,
      String detail,
      int actions,
      int interpretedOperations) {}

  record AuditOutcome(
      String classification, String fingerprint, int actions, int interpretedOperations) {}

  private record Shrink(List<String> operations, int trials) {
    private Shrink {
      operations = List.copyOf(operations);
    }
  }

  private record DeletionAudit(
      int passes,
      int invalidHistories,
      int differentStudentFailures,
      int sameFingerprintStudentFailures) {}

  record Result(
      ObjectNode counterexamples,
      ArrayNode mutants,
      int killed,
      int rawOperations,
      int minimalOperations,
      int shrinkTrials,
      int actualMutationActions,
      int singleDeletePasses,
      int singleDeleteInvalidHistories,
      int singleDeleteDifferentStudentFailures,
      int singleDeleteSameFingerprintStudentFailures,
      String throwingControl,
      byte[] canonicalBytes,
      String digest) {
    Result {
      counterexamples = counterexamples.deepCopy();
      mutants = mutants.deepCopy();
      canonicalBytes = canonicalBytes.clone();
    }

    @Override
    public ObjectNode counterexamples() {
      return counterexamples.deepCopy();
    }

    @Override
    public ArrayNode mutants() {
      return mutants.deepCopy();
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }
}

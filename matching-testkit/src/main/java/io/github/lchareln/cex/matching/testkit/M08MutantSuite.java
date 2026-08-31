package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.local.CanonicalResult;
import io.github.lchareln.cex.matching.local.FaultInjector;
import io.github.lchareln.cex.matching.local.FaultPoint;
import io.github.lchareln.cex.matching.local.LocalMatchingRuntime;
import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.M08Envelope;
import io.github.lchareln.cex.matching.local.M08EnvelopeCodec;
import io.github.lchareln.cex.matching.local.PreflightRejectionCode;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import io.github.lchareln.cex.matching.local.WalConfig;
import io.github.lchareln.cex.matching.local.WalCorruptionException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Executable runtime/file mutants judged by one observer and complete restart histories. */
final class M08MutantSuite {
  static final String COUNTEREXAMPLE_SCHEMA = "schemas/matching.m08.counterexamples.v1.schema.json";
  private static final long SHARD = 8_098;
  private static final int MIN_MAX_RECORD_BYTES = 1_048_608;
  private static final long ROLLOVER_SEGMENT_BYTES = 1_100_000;
  private static final String LARGE_FIELD = "m".repeat(12_000);

  Result run(Path repositoryRoot) {
    Map<String, Definition> definitions = definitions();
    ArrayNode persisted = JsonSupport.MAPPER.createArrayNode();
    ArrayNode summaries = JsonSupport.MAPPER.createArrayNode();
    StringBuilder canonical = new StringBuilder("M08X2\n");
    int killed = 0;
    int rawTotal = 0;
    int minimalTotal = 0;
    int trialTotal = 0;
    int actionTotal = 0;

    for (String id : M08StartCheckRunner.REQUIRED_MUTANTS) {
      Definition definition = definitions.get(id);
      systemRequire(definition != null, "missing executable mutant " + id);
      List<String> raw = new ArrayList<>();
      raw.add("NOOP_PREFIX");
      raw.addAll(definition.history());
      raw.add("NOOP_SUFFIX");

      Outcome production = execute(definition, raw, Kind.NONE);
      if (production.classification() == Classification.SYSTEM_ERROR) {
        throw new IllegalStateException(
            "production control SYSTEM_ERROR " + id + ": " + production.detail());
      }
      if (production.classification() == Classification.STUDENT_FAILURE) {
        throw new M08SemanticFailure(
            "production candidate violates " + id + ": " + production.fingerprint());
      }

      Outcome mutant = execute(definition, raw, definition.kind());
      if (mutant.classification() == Classification.SYSTEM_ERROR) {
        throw new IllegalStateException(
            "executable mutant SYSTEM_ERROR " + id + ": " + mutant.detail());
      }
      if (mutant.classification() != Classification.STUDENT_FAILURE) {
        throw new M08SemanticFailure("required executable mutant survived: " + id);
      }
      systemRequire(mutant.actions() > 0, "mutant diverged without an executable action " + id);

      Shrink shrink = shrink(definition, raw);
      Outcome first = execute(definition, shrink.operations(), definition.kind());
      Outcome replay = execute(definition, shrink.operations(), definition.kind());
      systemRequire(
          first.classification() == Classification.STUDENT_FAILURE
              && first.equals(replay)
              && first.fingerprint().equals(definition.fingerprint())
              && first.actions() > 0,
          "strict executable replay changed " + id);
      systemRequire(
          containsInOrder(shrink.operations(), definition.history()),
          "counterexample lost full restart grammar " + id);
      systemRequire(oneMinimal(definition, shrink.operations()), "not one-minimal " + id);

      ObjectNode counterexample = persisted.addObject();
      counterexample.put("mutantId", id);
      counterexample.put("classification", "STUDENT_FAILURE");
      counterexample.put("propertyFingerprint", first.fingerprint());
      counterexample.put("rawOperations", raw.size());
      counterexample.put("minimalOperations", shrink.operations().size());
      counterexample.put("shrinkTrials", shrink.trials());
      counterexample.put("freshRuntime", true);
      counterexample.put("realWalFiles", true);
      counterexample.put("strictReplay", true);
      counterexample.put("oneMinimal", true);
      counterexample.put("systemErrorCountedAsKill", false);
      counterexample.put("candidateDriver", "EXECUTABLE_MUTATED_RUNTIME");
      counterexample.put("actualMutationActions", first.actions());
      counterexample.put("fullRestartGrammar", true);
      ArrayNode operations = counterexample.putArray("operations");
      shrink.operations().forEach(operations::add);

      ObjectNode summary = summaries.addObject();
      summary.put("id", id);
      summary.put("classification", "STUDENT_FAILURE");
      summary.put("killed", true);
      summary.put("fingerprint", first.fingerprint());
      summary.put("candidateDriver", "EXECUTABLE_MUTATED_RUNTIME");
      summary.put("actualMutationActions", first.actions());
      summary.put("freshRuntimeFileReplay", true);
      summary.put("fullRestartGrammar", true);
      summary.put("systemErrorCountedAsKill", false);
      canonical
          .append(id)
          .append('|')
          .append(first.fingerprint())
          .append('|')
          .append(first.actions())
          .append('|')
          .append(String.join(",", shrink.operations()))
          .append('\n');
      killed++;
      rawTotal += raw.size();
      minimalTotal += shrink.operations().size();
      trialTotal += shrink.trials();
      actionTotal += first.actions();
    }

    Definition throwing =
        new Definition(
            "THROWING-CONTROL",
            "THROWING_CONTROL",
            Kind.THROWING,
            List.of("OPEN", "SUBMIT", "CLOSE", "RESTART", "RETRY"),
            (operations, candidate, required) -> {
              throw new IllegalStateException("deterministic executable candidate failure");
            });
    Outcome systemControl = execute(throwing, throwing.history(), throwing.kind());
    systemRequire(
        systemControl.classification() == Classification.SYSTEM_ERROR,
        "throwing candidate was not SYSTEM_ERROR");

    ObjectNode counterexamples = JsonSupport.MAPPER.createObjectNode();
    counterexamples.put("schemaVersion", "matching.m08.counterexamples.v1");
    counterexamples.put("unit", "M08");
    counterexamples.set("counterexamples", persisted);
    JsonNode parsed = JsonSupport.parse(JsonSupport.prettyBytes(counterexamples));
    JsonSupport.validate(parsed, readString(repositoryRoot.resolve(COUNTEREXAMPLE_SCHEMA)), false);
    strictReplayPersisted(parsed, definitions);

    byte[] canonicalBytes = canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return new Result(
        counterexamples,
        summaries,
        killed,
        rawTotal,
        minimalTotal,
        trialTotal,
        actionTotal,
        systemControl.classification().name(),
        canonicalBytes,
        Hashing.sha256Hex(canonicalBytes));
  }

  private static Map<String, Definition> definitions() {
    Map<String, Definition> values = new LinkedHashMap<>();
    add(
        values,
        "M08-ACK-BEFORE-RECORD-FORCE",
        "ACK_ORDER_RECORD_FORCE",
        Kind.ACK_RECORD_FORCE,
        M08MutantSuite::ackRecordForce,
        "OPEN_FAULT",
        "SUBMIT",
        "CLOSE",
        "RESTART",
        "RETRY");
    add(
        values,
        "M08-ACK-BEFORE-DIRECTORY-FORCE",
        "ACK_ORDER_DIRECTORY_FORCE",
        Kind.ACK_DIRECTORY_FORCE,
        M08MutantSuite::ackDirectoryForce,
        "OPEN",
        "FILL_TO_DIRECTORY_FORCE",
        "CLOSE",
        "RESTART",
        "RETRY");
    add(
        values,
        "M08-DUPLICATE-REAPPLIES",
        "DUPLICATE_APPLY_COUNT",
        Kind.DUPLICATE_REAPPLIES,
        M08MutantSuite::duplicateReapplies,
        "OPEN",
        "SUBMIT_NEW",
        "CLOSE",
        "RESTART",
        "SUBMIT_EXACT_DUPLICATE");
    add(
        values,
        "M08-COMMAND-ID-PAYLOAD-CONFLICT-ACCEPTED",
        "COMMAND_ID_PAYLOAD_BINDING",
        Kind.COMMAND_CONFLICT,
        (operations, candidate, required) ->
            identityConflict(
                operations,
                candidate,
                required,
                true,
                PreflightRejectionCode.COMMAND_ID_PAYLOAD_CONFLICT,
                "COMMAND_ID_PAYLOAD_BINDING"),
        "OPEN",
        "SUBMIT_FIRST",
        "CLOSE",
        "RESTART",
        "SUBMIT_CONFLICT",
        "RETRY_CONFLICT");
    add(
        values,
        "M08-SLOT-IDENTITY-CONFLICT-ACCEPTED",
        "SLOT_IDENTITY_BINDING",
        Kind.SLOT_CONFLICT,
        (operations, candidate, required) ->
            identityConflict(
                operations,
                candidate,
                required,
                false,
                PreflightRejectionCode.SLOT_IDENTITY_CONFLICT,
                "SLOT_IDENTITY_BINDING"),
        "OPEN",
        "SUBMIT_FIRST",
        "CLOSE",
        "RESTART",
        "SUBMIT_CONFLICT",
        "RETRY_CONFLICT");
    add(
        values,
        "M08-GAP-ADVANCES-PRODUCER",
        "PRODUCER_GAP_CONTINUITY",
        Kind.GAP_ADVANCES,
        M08MutantSuite::gapAdvances,
        "OPEN",
        "SUBMIT_SEQUENCE_1",
        "SUBMIT_SEQUENCE_3",
        "CLOSE",
        "RESTART",
        "RETRY_SEQUENCE_3");
    add(
        values,
        "M08-FENCED-EPOCH-ACCEPTED",
        "PRODUCER_EPOCH_FENCE",
        Kind.FENCED_ACCEPTED,
        M08MutantSuite::fencedAccepted,
        "OPEN",
        "SUBMIT_EPOCH_1",
        "ACTIVATE_EPOCH_2",
        "SUBMIT_FENCED",
        "CLOSE",
        "RESTART",
        "RETRY_FENCED");
    add(
        values,
        "M08-BUSINESS-REJECTION-NOT-JOURNALED",
        "BUSINESS_REJECTION_DURABILITY",
        Kind.BUSINESS_NOT_JOURNALED,
        M08MutantSuite::businessNotJournaled,
        "OPEN",
        "SUBMIT_BUSINESS_INVALID",
        "CLOSE",
        "RESTART",
        "RETRY_EXACT");
    add(
        values,
        "M08-TORN-TAIL-REPLAYED",
        "FINAL_TORN_TAIL_RECOVERY",
        Kind.TORN_REPLAYED,
        M08MutantSuite::tornReplayed,
        "OPEN_FAULT",
        "WRITE_LENGTH_ONLY",
        "CLOSE",
        "RESTART",
        "RETRY_EXACT");
    add(
        values,
        "M08-CORRUPTION-SKIPPED",
        "COMPLETE_CORRUPTION_FAIL_CLOSED",
        Kind.CORRUPTION_SKIPPED,
        M08MutantSuite::corruptionSkipped,
        "OPEN",
        "WRITE_COMPLETE_RECORD",
        "CLOSE",
        "FLIP_CRC",
        "RESTART",
        "RETRY_EXACT");
    return Map.copyOf(values);
  }

  private static void add(
      Map<String, Definition> values,
      String id,
      String fingerprint,
      Kind kind,
      Scenario scenario,
      String... history) {
    values.put(id, new Definition(id, fingerprint, kind, List.of(history), scenario));
  }

  private static void ackRecordForce(
      List<String> operations, Candidate candidate, List<String> required) throws IOException {
    byte[] envelope = candidate.envelope("record-force", 1, 1, uuid(1), cancel(1));
    Observed first = Observed.none();
    Observed retry = Observed.none();
    Restart restart = Restart.NOT_RUN;
    for (String operation : operations) {
      switch (operation) {
        case "OPEN_FAULT" -> candidate.open(new OneShotFault(FaultPoint.BEFORE_RECORD_FORCE));
        case "SUBMIT" -> first = candidate.submit(envelope);
        case "CLOSE" -> candidate.close();
        case "RESTART" -> restart = candidate.restart();
        case "RETRY" -> retry = candidate.submit(envelope);
        default -> {}
      }
    }
    Snapshot snapshot = candidate.snapshot();
    judge(
        operations,
        required,
        first.kind() == ResultKind.UNKNOWN
            && restart == Restart.OPEN
            && retry.kind() == ResultKind.DUPLICATE
            && retry.applicationSequence() == 1
            && snapshot.nextWalSequence() == 2,
        "ACK_ORDER_RECORD_FORCE");
  }

  private static void ackDirectoryForce(
      List<String> operations, Candidate candidate, List<String> required) throws IOException {
    Observed pending = Observed.none();
    Observed retry = Observed.none();
    Restart restart = Restart.NOT_RUN;
    byte[] pendingEnvelope = null;
    long sequence = 1;
    for (String operation : operations) {
      switch (operation) {
        case "OPEN" -> candidate.open(new NthFault(FaultPoint.BEFORE_DIRECTORY_FORCE, 2));
        case "FILL_TO_DIRECTORY_FORCE" -> {
          while (candidate.isOpen()) {
            pendingEnvelope =
                candidate.envelope(
                    "directory-force", 1, sequence, uuid(sequence), largePlace(sequence));
            Observed result = candidate.submit(pendingEnvelope);
            if (result.kind() == ResultKind.NEW) {
              sequence++;
            } else {
              pending = result;
              break;
            }
            systemRequire(sequence < 200, "rollover mutant did not reach directory force");
          }
        }
        case "CLOSE" -> candidate.close();
        case "RESTART" -> restart = candidate.restart();
        case "RETRY" -> {
          if (pendingEnvelope != null) retry = candidate.submit(pendingEnvelope);
        }
        default -> {}
      }
    }
    judge(
        operations,
        required,
        pending.kind() == ResultKind.UNKNOWN
            && restart == Restart.OPEN
            && retry.kind() == ResultKind.NEW,
        "ACK_ORDER_DIRECTORY_FORCE");
  }

  private static void duplicateReapplies(
      List<String> operations, Candidate candidate, List<String> required) throws IOException {
    byte[] envelope = candidate.envelope("duplicate", 1, 1, uuid(1), cancel(1));
    Observed first = Observed.none();
    Observed duplicate = Observed.none();
    String firstDigest = "";
    Restart restart = Restart.NOT_RUN;
    for (String operation : operations) {
      switch (operation) {
        case "OPEN" -> candidate.open(FaultInjector.NONE);
        case "SUBMIT_NEW" -> {
          first = candidate.submit(envelope);
          firstDigest = candidate.snapshot().semanticDigest();
        }
        case "CLOSE" -> candidate.close();
        case "RESTART" -> restart = candidate.restart();
        case "SUBMIT_EXACT_DUPLICATE" -> duplicate = candidate.submit(envelope);
        default -> {}
      }
    }
    Snapshot snapshot = candidate.snapshot();
    judge(
        operations,
        required,
        first.kind() == ResultKind.NEW
            && restart == Restart.OPEN
            && duplicate.kind() == ResultKind.DUPLICATE
            && duplicate.applicationSequence() == 1
            && snapshot.maxApplicationSequence() == 1
            && snapshot.nextWalSequence() == 2
            && snapshot.semanticDigest().equals(firstDigest),
        "DUPLICATE_APPLY_COUNT");
  }

  private static void identityConflict(
      List<String> operations,
      Candidate candidate,
      List<String> required,
      boolean sameId,
      PreflightRejectionCode code,
      String fingerprint)
      throws IOException {
    UUID firstId = uuid(1);
    byte[] first = candidate.envelope("conflict", 1, 1, firstId, cancel(1));
    byte[] conflict = candidate.envelope("conflict", 1, 1, sameId ? firstId : uuid(2), cancel(2));
    Observed accepted = Observed.none();
    Observed rejected = Observed.none();
    Observed retry = Observed.none();
    Restart restart = Restart.NOT_RUN;
    for (String operation : operations) {
      switch (operation) {
        case "OPEN" -> candidate.open(FaultInjector.NONE);
        case "SUBMIT_FIRST" -> accepted = candidate.submit(first);
        case "CLOSE" -> candidate.close();
        case "RESTART" -> restart = candidate.restart();
        case "SUBMIT_CONFLICT" -> rejected = candidate.submit(conflict);
        case "RETRY_CONFLICT" -> retry = candidate.submit(conflict);
        default -> {}
      }
    }
    judge(
        operations,
        required,
        accepted.kind() == ResultKind.NEW
            && restart == Restart.OPEN
            && rejected.isPreflight(code)
            && retry.isPreflight(code)
            && candidate.snapshot().nextWalSequence() == 2,
        fingerprint);
  }

  private static void gapAdvances(
      List<String> operations, Candidate candidate, List<String> required) throws IOException {
    byte[] first = candidate.envelope("gap", 1, 1, uuid(1), cancel(1));
    byte[] gap = candidate.envelope("gap", 1, 3, uuid(3), cancel(3));
    Observed one = Observed.none();
    Observed rejected = Observed.none();
    Observed retry = Observed.none();
    Restart restart = Restart.NOT_RUN;
    for (String operation : operations) {
      switch (operation) {
        case "OPEN" -> candidate.open(FaultInjector.NONE);
        case "SUBMIT_SEQUENCE_1" -> one = candidate.submit(first);
        case "SUBMIT_SEQUENCE_3" -> rejected = candidate.submit(gap);
        case "CLOSE" -> candidate.close();
        case "RESTART" -> restart = candidate.restart();
        case "RETRY_SEQUENCE_3" -> retry = candidate.submit(gap);
        default -> {}
      }
    }
    judge(
        operations,
        required,
        one.kind() == ResultKind.NEW
            && rejected.isPreflight(PreflightRejectionCode.PRODUCER_SEQUENCE_GAP)
            && restart == Restart.OPEN
            && retry.isPreflight(PreflightRejectionCode.PRODUCER_SEQUENCE_GAP)
            && candidate.snapshot().nextWalSequence() == 2,
        "PRODUCER_GAP_CONTINUITY");
  }

  private static void fencedAccepted(
      List<String> operations, Candidate candidate, List<String> required) throws IOException {
    byte[] first = candidate.envelope("epoch", 1, 1, uuid(1), cancel(1));
    byte[] second = candidate.envelope("epoch", 2, 1, uuid(2), cancel(2));
    byte[] fenced = candidate.envelope("epoch", 1, 2, uuid(3), cancel(3));
    Observed one = Observed.none();
    Observed two = Observed.none();
    Observed rejected = Observed.none();
    Observed retry = Observed.none();
    Restart restart = Restart.NOT_RUN;
    for (String operation : operations) {
      switch (operation) {
        case "OPEN" -> candidate.open(FaultInjector.NONE);
        case "SUBMIT_EPOCH_1" -> one = candidate.submit(first);
        case "ACTIVATE_EPOCH_2" -> two = candidate.submit(second);
        case "SUBMIT_FENCED" -> rejected = candidate.submit(fenced);
        case "CLOSE" -> candidate.close();
        case "RESTART" -> restart = candidate.restart();
        case "RETRY_FENCED" -> retry = candidate.submit(fenced);
        default -> {}
      }
    }
    judge(
        operations,
        required,
        one.kind() == ResultKind.NEW
            && two.kind() == ResultKind.NEW
            && rejected.isPreflight(PreflightRejectionCode.PRODUCER_EPOCH_FENCED)
            && restart == Restart.OPEN
            && retry.isPreflight(PreflightRejectionCode.PRODUCER_EPOCH_FENCED)
            && candidate.snapshot().nextWalSequence() == 3,
        "PRODUCER_EPOCH_FENCE");
  }

  private static void businessNotJournaled(
      List<String> operations, Candidate candidate, List<String> required) throws IOException {
    byte[] invalid =
        candidate.envelope(
            "business", 1, 1, uuid(1), new M08Command.Cancel("", BigInteger.valueOf(-1)));
    Observed first = Observed.none();
    Observed retry = Observed.none();
    Restart restart = Restart.NOT_RUN;
    for (String operation : operations) {
      switch (operation) {
        case "OPEN" -> candidate.open(FaultInjector.NONE);
        case "SUBMIT_BUSINESS_INVALID" -> first = candidate.submit(invalid);
        case "CLOSE" -> candidate.close();
        case "RESTART" -> restart = candidate.restart();
        case "RETRY_EXACT" -> retry = candidate.submit(invalid);
        default -> {}
      }
    }
    judge(
        operations,
        required,
        first.kind() == ResultKind.NEW
            && !first.synthetic()
            && first.events().stream().anyMatch(event -> event.contains("Rejected"))
            && restart == Restart.OPEN
            && retry.kind() == ResultKind.DUPLICATE
            && retry.resultDigest().equals(first.resultDigest())
            && candidate.snapshot().nextWalSequence() == 2,
        "BUSINESS_REJECTION_DURABILITY");
  }

  private static void tornReplayed(
      List<String> operations, Candidate candidate, List<String> required) throws IOException {
    byte[] envelope = candidate.envelope("torn", 1, 1, uuid(1), cancel(1));
    Observed first = Observed.none();
    Observed retry = Observed.none();
    Restart restart = Restart.NOT_RUN;
    for (String operation : operations) {
      switch (operation) {
        case "OPEN_FAULT" -> candidate.open(new OneShotFault(FaultPoint.AFTER_RECORD_LENGTH_WRITE));
        case "WRITE_LENGTH_ONLY" -> first = candidate.submit(envelope);
        case "CLOSE" -> candidate.close();
        case "RESTART" -> restart = candidate.restart();
        case "RETRY_EXACT" -> retry = candidate.submit(envelope);
        default -> {}
      }
    }
    judge(
        operations,
        required,
        first.kind() == ResultKind.UNKNOWN
            && restart == Restart.OPEN
            && retry.kind() == ResultKind.NEW
            && candidate.snapshot().nextWalSequence() == 2,
        "FINAL_TORN_TAIL_RECOVERY");
  }

  private static void corruptionSkipped(
      List<String> operations, Candidate candidate, List<String> required) throws IOException {
    byte[] envelope = candidate.envelope("corruption", 1, 1, uuid(1), cancel(1));
    Observed first = Observed.none();
    Observed retry = Observed.none();
    boolean corrupted = false;
    Restart restart = Restart.NOT_RUN;
    for (String operation : operations) {
      switch (operation) {
        case "OPEN" -> candidate.open(FaultInjector.NONE);
        case "WRITE_COMPLETE_RECORD" -> first = candidate.submit(envelope);
        case "CLOSE" -> candidate.close();
        case "FLIP_CRC" -> corrupted = candidate.corruptFinalRecord();
        case "RESTART" -> restart = candidate.restart();
        case "RETRY_EXACT" -> retry = candidate.submit(envelope);
        default -> {}
      }
    }
    judge(
        operations,
        required,
        first.kind() == ResultKind.NEW
            && corrupted
            && restart == Restart.CORRUPTION_BLOCKED
            && retry.kind() == ResultKind.NOT_RUN,
        "COMPLETE_CORRUPTION_FAIL_CLOSED");
  }

  private static void judge(
      List<String> operations, List<String> required, boolean invariant, String fingerprint) {
    if (containsInOrder(operations, required) && !invariant) {
      throw new SemanticDifference(fingerprint);
    }
  }

  private static boolean containsInOrder(List<String> operations, List<String> required) {
    int cursor = 0;
    for (String operation : operations) {
      if (cursor < required.size() && required.get(cursor).equals(operation)) cursor++;
    }
    return cursor == required.size();
  }

  private static Shrink shrink(Definition definition, List<String> raw) {
    List<String> current = new ArrayList<>(raw);
    int trials = 0;
    boolean changed;
    do {
      changed = false;
      for (int index = 0; index < current.size(); index++) {
        List<String> candidate = new ArrayList<>(current);
        candidate.remove(index);
        trials++;
        Outcome outcome = execute(definition, candidate, definition.kind());
        systemRequire(
            outcome.classification() != Classification.SYSTEM_ERROR,
            "shrinker SYSTEM_ERROR " + definition.id() + ": " + outcome.detail());
        if (outcome.classification() == Classification.STUDENT_FAILURE
            && outcome.fingerprint().equals(definition.fingerprint())) {
          current = candidate;
          changed = true;
          break;
        }
      }
    } while (changed);
    return new Shrink(List.copyOf(current), trials);
  }

  private static boolean oneMinimal(Definition definition, List<String> operations) {
    for (int index = 0; index < operations.size(); index++) {
      List<String> candidate = new ArrayList<>(operations);
      candidate.remove(index);
      Outcome outcome = execute(definition, candidate, definition.kind());
      systemRequire(
          outcome.classification() != Classification.SYSTEM_ERROR,
          "one-minimal SYSTEM_ERROR " + definition.id() + ": " + outcome.detail());
      if (outcome.classification() == Classification.STUDENT_FAILURE
          && outcome.fingerprint().equals(definition.fingerprint())) return false;
    }
    return true;
  }

  private static void strictReplayPersisted(JsonNode parsed, Map<String, Definition> definitions) {
    systemRequire(parsed.path("counterexamples").size() == 10, "counterexample count changed");
    for (JsonNode value : parsed.path("counterexamples")) {
      Definition definition = definitions.get(value.path("mutantId").stringValue());
      systemRequire(definition != null, "persisted mutant id is unknown");
      List<String> operations = new ArrayList<>();
      value.path("operations").forEach(operation -> operations.add(operation.stringValue()));
      Outcome replay = execute(definition, operations, definition.kind());
      systemRequire(
          replay.classification() == Classification.STUDENT_FAILURE
              && replay.fingerprint().equals(value.path("propertyFingerprint").stringValue())
              && replay.actions() == value.path("actualMutationActions").intValue()
              && containsInOrder(operations, definition.history()),
          "persisted executable counterexample did not replay " + definition.id());
    }
  }

  private static Outcome execute(Definition definition, List<String> operations, Kind kind) {
    Path root = null;
    Candidate candidate = null;
    Classification classification = Classification.PASS;
    String fingerprint = "";
    String detail = "";
    try {
      root = Files.createTempDirectory("m08-mutant-");
      Path directory = root.resolve("wal");
      Files.createDirectories(directory);
      candidate = new Candidate(directory, kind, definition.kind());
      definition.scenario().run(List.copyOf(operations), candidate, definition.history());
    } catch (SemanticDifference failure) {
      classification = Classification.STUDENT_FAILURE;
      fingerprint = failure.fingerprint;
    } catch (Throwable failure) {
      classification = Classification.SYSTEM_ERROR;
      detail =
          failure.getClass().getSimpleName()
              + ":"
              + (failure.getMessage() == null ? "" : failure.getMessage());
    } finally {
      if (candidate != null) {
        try {
          candidate.close();
        } catch (Throwable failure) {
          classification = Classification.SYSTEM_ERROR;
          fingerprint = "";
          detail = "close:" + failure.getClass().getSimpleName();
        }
      }
      if (root != null) {
        try {
          deleteTree(root);
        } catch (RuntimeException failure) {
          classification = Classification.SYSTEM_ERROR;
          fingerprint = "";
          detail = "cleanup:" + failure.getClass().getSimpleName();
        }
      }
    }
    int actions = candidate == null ? 0 : candidate.actions();
    if (classification == Classification.STUDENT_FAILURE && kind != Kind.NONE && actions == 0) {
      return new Outcome(
          Classification.SYSTEM_ERROR,
          "",
          "candidate divergence had no executable mutation action",
          0);
    }
    return new Outcome(classification, fingerprint, detail, actions);
  }

  private static M08Command.Cancel cancel(long orderId) {
    return new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(orderId));
  }

  private static M08Command.Place largePlace(long orderId) {
    return new M08Command.Place(
        LARGE_FIELD,
        BigInteger.valueOf(orderId),
        LARGE_FIELD,
        BigInteger.ONE,
        BigInteger.ONE,
        LARGE_FIELD,
        0,
        "NONE",
        Optional.empty());
  }

  private static UUID uuid(long value) {
    return new UUID(0x0898000000000000L, value);
  }

  private static Optional<Path> finalSegment(Path directory) {
    try (var paths = Files.list(directory)) {
      return paths
          .filter(path -> path.getFileName().toString().matches("segment-[0-9]{20}\\.m08w1"))
          .max(Comparator.comparing(Path::toString));
    } catch (IOException failure) {
      throw new IllegalStateException("cannot locate mutant WAL segment", failure);
    }
  }

  private static String readString(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static void deleteTree(Path path) {
    if (!Files.exists(path)) return;
    try (var paths = Files.walk(path)) {
      for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(current);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot clear mutant path", failure);
    }
  }

  private static void systemRequire(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  @FunctionalInterface
  private interface Scenario {
    void run(List<String> operations, Candidate candidate, List<String> required) throws Exception;
  }

  private record Definition(
      String id, String fingerprint, Kind kind, List<String> history, Scenario scenario) {
    private Definition {
      history = List.copyOf(history);
    }
  }

  private enum Kind {
    NONE,
    ACK_RECORD_FORCE,
    ACK_DIRECTORY_FORCE,
    DUPLICATE_REAPPLIES,
    COMMAND_CONFLICT,
    SLOT_CONFLICT,
    GAP_ADVANCES,
    FENCED_ACCEPTED,
    BUSINESS_NOT_JOURNALED,
    TORN_REPLAYED,
    CORRUPTION_SKIPPED,
    THROWING
  }

  private enum Classification {
    PASS,
    STUDENT_FAILURE,
    SYSTEM_ERROR
  }

  private enum ResultKind {
    NEW,
    DUPLICATE,
    PREFLIGHT,
    STRUCTURAL,
    UNKNOWN,
    FAILED,
    NOT_RUN
  }

  private enum Restart {
    OPEN,
    CORRUPTION_BLOCKED,
    CORRUPTION_SKIPPED_OPEN,
    NOT_RUN
  }

  private record Outcome(
      Classification classification, String fingerprint, String detail, int actions) {}

  private record Shrink(List<String> operations, int trials) {
    private Shrink {
      operations = List.copyOf(operations);
    }
  }

  private record Snapshot(
      boolean open, long nextWalSequence, long maxApplicationSequence, String semanticDigest) {
    private static Snapshot closed(long maxApplicationSequence) {
      return new Snapshot(false, -1, maxApplicationSequence, "CLOSED");
    }
  }

  private record Observed(
      ResultKind kind,
      String code,
      long walSequence,
      long applicationSequence,
      String resultDigest,
      String semanticDigest,
      List<String> events,
      boolean synthetic) {
    private Observed {
      events = List.copyOf(events);
    }

    private static Observed none() {
      return new Observed(ResultKind.NOT_RUN, "", -1, -1, "", "", List.of(), false);
    }

    private static Observed syntheticNew() {
      return new Observed(ResultKind.NEW, "", -1, -1, "", "", List.of(), true);
    }

    private boolean isPreflight(PreflightRejectionCode expected) {
      return kind == ResultKind.PREFLIGHT && code.equals(expected.name());
    }
  }

  private static final class SemanticDifference extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String fingerprint;

    private SemanticDifference(String fingerprint) {
      super(fingerprint);
      this.fingerprint = fingerprint;
    }
  }

  /** Mutation boundary: every non-control branch changes output, WAL/core state, or recovery. */
  private static final class Candidate implements AutoCloseable {
    private final Path directory;
    private final Path shadowDirectory;
    private final Kind kind;
    private final WalConfig config;
    private final M08EnvelopeCodec codec = new M08EnvelopeCodec();
    private LocalMatchingRuntime runtime;
    private LocalMatchingRuntime shadowRuntime;
    private byte[] tornPending;
    private long maxApplicationSequence;
    private long remapSequence;
    private int actions;

    private Candidate(Path directory, Kind kind, Kind targetKind) throws IOException {
      this.directory = directory;
      this.shadowDirectory = directory.resolveSibling("shadow-wal");
      this.kind = kind;
      this.config =
          targetKind == Kind.ACK_DIRECTORY_FORCE
              ? new WalConfig(directory, SHARD, ROLLOVER_SEGMENT_BYTES, MIN_MAX_RECORD_BYTES)
              : WalConfig.defaults(directory, SHARD);
      Files.createDirectories(shadowDirectory);
    }

    private void open(FaultInjector injector) throws IOException {
      close();
      runtime = LocalMatchingRuntime.open(config, injector);
    }

    private Restart restart() throws IOException {
      close();
      try {
        runtime = LocalMatchingRuntime.open(config, FaultInjector.NONE);
        if (kind == Kind.TORN_REPLAYED && tornPending != null) {
          SubmissionResult injected = runtime.submit(tornPending);
          systemRequire(
              injected instanceof SubmissionResult.NewDurablyApplied,
              "torn mutant could not persist wrong replay");
          observeRaw(injected);
          actions++;
        }
        return Restart.OPEN;
      } catch (WalCorruptionException corruption) {
        runtime = null;
        if (kind != Kind.CORRUPTION_SKIPPED) return Restart.CORRUPTION_BLOCKED;
        Path segment =
            finalSegment(directory)
                .orElseThrow(() -> new IllegalStateException("corruption mutant has no segment"));
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE)) {
          channel.truncate(36);
          channel.force(true);
        }
        runtime = LocalMatchingRuntime.open(config, FaultInjector.NONE);
        actions++;
        return Restart.CORRUPTION_SKIPPED_OPEN;
      }
    }

    private Observed submit(byte[] envelope) {
      if (runtime == null) return Observed.none();
      if (kind == Kind.BUSINESS_NOT_JOURNALED) {
        if (shadowRuntime == null) {
          try {
            shadowRuntime = LocalMatchingRuntime.open(WalConfig.defaults(shadowDirectory, SHARD));
          } catch (IOException failure) {
            throw new IllegalStateException("cannot open non-authoritative shadow core", failure);
          }
        }
        SubmissionResult shadowResult = shadowRuntime.submit(envelope);
        systemRequire(
            shadowResult instanceof SubmissionResult.NewDurablyApplied
                || shadowResult instanceof SubmissionResult.DuplicateReplayed,
            "shadow core did not produce a real business result");
        actions++;
        return observeRaw(shadowResult);
      }
      SubmissionResult raw = runtime.submit(envelope);
      if ((kind == Kind.ACK_RECORD_FORCE || kind == Kind.ACK_DIRECTORY_FORCE)
          && raw instanceof SubmissionResult.DurabilityUnknown) {
        actions++;
        return Observed.syntheticNew();
      }
      if (kind == Kind.TORN_REPLAYED && raw instanceof SubmissionResult.DurabilityUnknown) {
        tornPending = envelope.clone();
      }
      if (kind == Kind.DUPLICATE_REAPPLIES && raw instanceof SubmissionResult.DuplicateReplayed) {
        long sequence = ++remapSequence;
        M08Envelope decoded = decode(envelope);
        SubmissionResult reapplied =
            runtime.submit(
                envelope(
                    "mutant-duplicate-apply",
                    1,
                    sequence,
                    new UUID(0xD008000000000000L, sequence),
                    decoded.command()));
        systemRequire(
            reapplied instanceof SubmissionResult.NewDurablyApplied,
            "duplicate mutant did not perform real extra apply");
        observeRaw(reapplied);
        actions++;
      }
      if (raw instanceof SubmissionResult.PreflightRejected rejected) {
        raw = mutateRejected(envelope, raw, rejected.code());
      }
      return observeRaw(raw);
    }

    private SubmissionResult mutateRejected(
        byte[] envelope, SubmissionResult original, PreflightRejectionCode code) {
      boolean command =
          kind == Kind.COMMAND_CONFLICT
              && code == PreflightRejectionCode.COMMAND_ID_PAYLOAD_CONFLICT;
      boolean slot =
          kind == Kind.SLOT_CONFLICT && code == PreflightRejectionCode.SLOT_IDENTITY_CONFLICT;
      boolean gap =
          kind == Kind.GAP_ADVANCES && code == PreflightRejectionCode.PRODUCER_SEQUENCE_GAP;
      boolean fenced =
          kind == Kind.FENCED_ACCEPTED && code == PreflightRejectionCode.PRODUCER_EPOCH_FENCED;
      if (!command && !slot && !gap && !fenced) return original;
      M08Envelope decoded = decode(envelope);
      SubmissionResult accepted;
      if (gap) {
        SubmissionResult filler =
            runtime.submit(
                codec.encode(
                    decoded.slot().producerId(),
                    decoded.slot().producerEpoch(),
                    SHARD,
                    decoded.slot().producerSequence() - 1,
                    new UUID(0xD008100000000000L, decoded.slot().producerSequence()),
                    cancel(80_002)));
        systemRequire(
            filler instanceof SubmissionResult.NewDurablyApplied,
            "gap mutant did not advance real producer cursor");
        observeRaw(filler);
        accepted = runtime.submit(envelope);
      } else if (fenced) {
        accepted =
            runtime.submit(
                codec.encode(
                    decoded.slot().producerId(),
                    2,
                    SHARD,
                    2,
                    decoded.commandId(),
                    decoded.command()));
      } else {
        long sequence = ++remapSequence;
        accepted =
            runtime.submit(
                codec.encode(
                    "mutant-conflict-remap-" + kind.name(),
                    1,
                    SHARD,
                    sequence,
                    new UUID(0xD008200000000000L + kind.ordinal(), sequence),
                    decoded.command()));
      }
      systemRequire(
          accepted instanceof SubmissionResult.NewDurablyApplied
              || accepted instanceof SubmissionResult.DuplicateReplayed,
          "conflict/gap/fence mutant did not create accepted real state");
      actions++;
      return accepted;
    }

    private Observed observeRaw(SubmissionResult result) {
      if (result instanceof SubmissionResult.NewDurablyApplied applied) {
        return observeCanonical(ResultKind.NEW, applied.position().walSequence(), applied.result());
      }
      if (result instanceof SubmissionResult.DuplicateReplayed duplicate) {
        return observeCanonical(
            ResultKind.DUPLICATE,
            duplicate.originalPosition().walSequence(),
            duplicate.originalResult());
      }
      if (result instanceof SubmissionResult.PreflightRejected rejected) {
        return simple(ResultKind.PREFLIGHT, rejected.code().name());
      }
      if (result instanceof SubmissionResult.StructuralRejected rejected) {
        return simple(ResultKind.STRUCTURAL, rejected.code().name());
      }
      if (result instanceof SubmissionResult.DurabilityUnknown unknown) {
        return new Observed(
            ResultKind.UNKNOWN,
            unknown.stage(),
            unknown.attemptedPosition().map(position -> position.walSequence()).orElse(-1L),
            unknown.attemptedPosition().map(position -> position.applicationSequence()).orElse(-1L),
            "",
            snapshot().semanticDigest(),
            List.of(),
            false);
      }
      return simple(ResultKind.FAILED, ((SubmissionResult.FailedClosed) result).detail());
    }

    private Observed observeCanonical(
        ResultKind resultKind, long walSequence, CanonicalResult result) {
      maxApplicationSequence = Math.max(maxApplicationSequence, result.applicationSequence());
      return new Observed(
          resultKind,
          "",
          walSequence,
          result.applicationSequence(),
          result.resultDigest(),
          result.semanticStateDigest(),
          result.events(),
          false);
    }

    private Observed simple(ResultKind resultKind, String code) {
      return new Observed(
          resultKind, code, -1, -1, "", snapshot().semanticDigest(), List.of(), false);
    }

    private byte[] envelope(
        String producer, long epoch, long sequence, UUID id, M08Command command) {
      return codec.encode(producer, epoch, SHARD, sequence, id, command);
    }

    private M08Envelope decode(byte[] envelope) {
      try {
        return codec.decodeCanonical(envelope, SHARD);
      } catch (io.github.lchareln.cex.matching.local.StructuralRejectionException failure) {
        throw new IllegalStateException("mutant driver supplied a non-canonical envelope", failure);
      }
    }

    private Snapshot snapshot() {
      return runtime == null
          ? Snapshot.closed(maxApplicationSequence)
          : new Snapshot(
              runtime.state().name().equals("OPEN"),
              runtime.nextWalSequence(),
              maxApplicationSequence,
              runtime.semanticStateDigest());
    }

    private boolean isOpen() {
      return snapshot().open();
    }

    private boolean corruptFinalRecord() throws IOException {
      Optional<Path> segment = finalSegment(directory);
      if (segment.isEmpty()) return false;
      byte[] bytes = Files.readAllBytes(segment.orElseThrow());
      if (bytes.length <= 36) return false;
      bytes[bytes.length - 1] ^= 1;
      Files.write(
          segment.orElseThrow(),
          bytes,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING);
      try (FileChannel channel =
          FileChannel.open(segment.orElseThrow(), StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      return true;
    }

    private int actions() {
      return actions;
    }

    @Override
    public void close() throws IOException {
      IOException failure = null;
      if (runtime != null) {
        try {
          runtime.close();
        } catch (IOException closeFailure) {
          failure = closeFailure;
        }
        runtime = null;
      }
      if (shadowRuntime != null) {
        try {
          shadowRuntime.close();
        } catch (IOException closeFailure) {
          if (failure == null) failure = closeFailure;
          else failure.addSuppressed(closeFailure);
        }
        shadowRuntime = null;
      }
      if (failure != null) throw failure;
    }
  }

  private static final class OneShotFault implements FaultInjector {
    private final FaultPoint target;
    private boolean thrown;

    private OneShotFault(FaultPoint target) {
      this.target = target;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (!thrown && point == target) {
        thrown = true;
        throw new IOException("injected executable mutant fault " + point);
      }
    }
  }

  private static final class NthFault implements FaultInjector {
    private final FaultPoint target;
    private final int occurrence;
    private int hits;

    private NthFault(FaultPoint target, int occurrence) {
      this.target = target;
      this.occurrence = occurrence;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (point == target && ++hits == occurrence) {
        throw new IOException("injected executable mutant fault " + point + " " + occurrence);
      }
    }
  }

  record Result(
      ObjectNode counterexamples,
      ArrayNode mutants,
      int killed,
      int rawOperations,
      int minimalOperations,
      int shrinkTrials,
      int actualMutationActions,
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

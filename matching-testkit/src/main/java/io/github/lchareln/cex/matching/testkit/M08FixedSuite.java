package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.MarketMode;
import io.github.lchareln.cex.matching.MarketRuleSetArtifact;
import io.github.lchareln.cex.matching.PriceTicks;
import io.github.lchareln.cex.matching.RuleSetVersion;
import io.github.lchareln.cex.matching.local.DirectoryLockException;
import io.github.lchareln.cex.matching.local.FaultInjector;
import io.github.lchareln.cex.matching.local.FaultPoint;
import io.github.lchareln.cex.matching.local.LocalMatchingRuntime;
import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.M08EnvelopeCodec;
import io.github.lchareln.cex.matching.local.M08RuntimeJudgeProbe;
import io.github.lchareln.cex.matching.local.PreflightRejectionCode;
import io.github.lchareln.cex.matching.local.StructuralRejectionCode;
import io.github.lchareln.cex.matching.local.SubmissionResult;
import io.github.lchareln.cex.matching.local.WalConfig;
import io.github.lchareln.cex.matching.local.WalCorruptionException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Executes the twenty frozen M08 local-runtime scenarios against real files and injected seams. */
final class M08FixedSuite {
  private static final long SHARD = 808;
  private static final int WAL_HEADER_BYTES = 36;
  private static final int MIN_MAX_RECORD_BYTES = 1_048_608;
  private static final long ROLLOVER_SEGMENT_BYTES = 1_100_000;
  private static final String LARGE_FIELD = "r".repeat(12_000);
  private final M08EnvelopeCodec codec = new M08EnvelopeCodec();

  Result run(Path repositoryRoot, Path workingRoot) {
    JsonNode fixture =
        JsonSupport.parse(readBytes(repositoryRoot.resolve(M08StartCheckRunner.FIXED_CORPUS_PATH)));
    Path root = workingRoot.toAbsolutePath().normalize();
    deleteTree(root);
    try {
      Files.createDirectories(root);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create M08 fixed working root", failure);
    }
    ArrayNode scenarios = JsonSupport.MAPPER.createArrayNode();
    StringBuilder canonical = new StringBuilder("M08F1\n");
    Set<String> covered = new LinkedHashSet<>();
    int injectedFaults = 0;
    try {
      for (int index = 0; index < fixture.path("scenarios").size(); index++) {
        JsonNode declared = fixture.path("scenarios").get(index);
        String id = declared.path("scenarioId").stringValue();
        ScenarioObservation observation =
            execute(index, root.resolve("scenario-%02d".formatted(index)));
        List<String> obligations = strings(declared.path("proofObligations"));
        covered.addAll(obligations);
        injectedFaults += observation.injectedFaults();
        ObjectNode report = scenarios.addObject();
        report.put("scenarioId", id);
        report.put("status", "PASS");
        report.put("summary", observation.summary());
        report.put("injectedFaults", observation.injectedFaults());
        ArrayNode proof = report.putArray("proofObligations");
        obligations.forEach(proof::add);
        canonical
            .append(index)
            .append('|')
            .append(id)
            .append('|')
            .append(observation.summary())
            .append('|')
            .append(String.join(",", obligations))
            .append('\n');
      }
      require(scenarios.size() == 20, "M08 fixed scenario count changed");
      require(
          covered.equals(new LinkedHashSet<>(M08StartCheckRunner.COVERAGE_IDS)),
          "M08 fixed coverage set changed");
      byte[] bytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
      return new Result(
          scenarios, List.copyOf(covered), injectedFaults, bytes, Hashing.sha256Hex(bytes));
    } finally {
      deleteTree(root);
    }
  }

  private ScenarioObservation execute(int index, Path directory) {
    provision(directory);
    return switch (index) {
      case 0 -> allCommandsAndBusinessRejection(directory);
      case 1 -> structuralRejections(directory);
      case 2 -> liveDuplicate(directory);
      case 3 -> restartDuplicate(directory);
      case 4 -> commandPayloadConflict(directory);
      case 5 -> commandSlotConflict(directory);
      case 6 -> slotIdentityConflict(directory);
      case 7 -> gapAndBoundStale(directory);
      case 8 -> epochFence(directory);
      case 9 -> higherEpochStart(directory);
      case 10 -> tornLength(directory);
      case 11 -> bodyBeforeForce(directory);
      case 12 -> forcedBeforeApply(directory);
      case 13 -> appliedBeforeAck(directory);
      case 14 -> rolloverDirectoryForce(directory);
      case 15 -> orphanTemp(directory);
      case 16 -> finalTornTail(directory);
      case 17 -> completeFinalCorruption(directory);
      case 18 -> middleCorruption(directory);
      case 19 -> lockAndApplyFailure(directory);
      default -> throw new IllegalStateException("unknown M08 scenario index " + index);
    };
  }

  private ScenarioObservation allCommandsAndBusinessRejection(Path directory) {
    WalConfig config = WalConfig.defaults(directory, SHARD);
    List<byte[]> envelopes = new ArrayList<>();
    String governedDigest;
    String invalidStpDigest;
    String semanticDigest;
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config)) {
      envelopes.add(envelope("all", 1, commandId(0, 1), place(1, 99, 2, 0, "NONE")));
      assertNew(runtime.submit(envelopes.getLast()));
      envelopes.add(
          envelope("all", 2, commandId(0, 2), new M08Command.Cancel("BTC-USDT", BigInteger.ONE)));
      assertNew(runtime.submit(envelopes.getLast()));
      MarketRuleSetArtifact artifact = artifact(1, 90, 110);
      envelopes.add(
          envelope(
              "all",
              3,
              commandId(0, 3),
              new M08Command.PrepareRuleSet(MarketRuleSetArtifact.bootstrapIdentity(), artifact)));
      assertNew(runtime.submit(envelopes.getLast()));
      envelopes.add(
          envelope(
              "all",
              4,
              commandId(0, 4),
              new M08Command.ActivateRuleSet(
                  4, MarketRuleSetArtifact.bootstrapIdentity(), artifact.identity())));
      assertNew(runtime.submit(envelopes.getLast()));
      envelopes.add(
          envelope(
              "all",
              5,
              commandId(0, 5),
              stpPlace(2, "SELL", 101, 1, 7, "CANCEL_MAKER", artifact.identity())));
      SubmissionResult.NewDurablyApplied governed = assertNew(runtime.submit(envelopes.getLast()));
      governedDigest = governed.result().resultDigest();
      envelopes.add(
          envelope("all", 6, commandId(0, 6), stpPlace(3, "BUY", 102, 1, 7, "CANCEL_TAKER")));
      SubmissionResult.NewDurablyApplied stp = assertNew(runtime.submit(envelopes.getLast()));
      require(
          stp.result().events().stream().anyMatch(value -> value.contains("SelfTradePrevented")),
          "valid nonzero STP maker/taker sequence did not produce STP attribution");
      envelopes.add(envelope("all", 7, commandId(0, 7), stpPlace(4, "BUY", 99, 1, 7, "NONE")));
      SubmissionResult.NewDurablyApplied rawRejected =
          assertNew(runtime.submit(envelopes.getLast()));
      require(
          rawRejected.result().events().stream().anyMatch(value -> value.contains("Rejected")),
          "raw invalid STP fields were normalized before the core rejection");
      require(
          rawRejected.result().events().stream()
              .anyMatch(value -> value.contains("INVALID_STP_INSTRUCTION")),
          "raw invalid STP fields did not reach the core INVALID_STP_INSTRUCTION rejection");
      invalidStpDigest = rawRejected.result().resultDigest();
      envelopes.add(
          envelope(
              "all",
              8,
              commandId(0, 8),
              new M08Command.ChangeMarketMode(8, MarketMode.OPEN, MarketMode.HALTED, "ops-m08")));
      assertNew(runtime.submit(envelopes.getLast()));
      envelopes.add(
          envelope(
              "all",
              9,
              commandId(0, 9),
              new M08Command.MassCancel(9, MarketMode.HALTED, "ops-m08")));
      assertNew(runtime.submit(envelopes.getLast()));
      envelopes.add(
          envelope("all", 10, commandId(0, 10), new M08Command.Cancel("", BigInteger.valueOf(-1))));
      SubmissionResult.NewDurablyApplied rejected = assertNew(runtime.submit(envelopes.getLast()));
      require(
          rejected.result().events().stream().anyMatch(value -> value.contains("Rejected")),
          "business-invalid command was not represented as a durable core rejection");
      require(runtime.nextWalSequence() == 11, "not every M08 command variant was journaled");
      semanticDigest = runtime.semanticStateDigest();
    } catch (IOException failure) {
      throw new IllegalStateException("all-command scenario failed", failure);
    }
    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config)) {
      require(recovered.nextWalSequence() == 11, "genesis recovery lost a command variant");
      assertDuplicate(recovered.submit(envelopes.getLast()));
      SubmissionResult.DuplicateReplayed invalidDuplicate =
          assertDuplicate(recovered.submit(envelopes.get(6)));
      SubmissionResult.DuplicateReplayed governedDuplicate =
          assertDuplicate(recovered.submit(envelopes.get(4)));
      require(
          invalidStpDigest.equals(invalidDuplicate.originalResult().resultDigest()),
          "raw invalid STP rejection changed after restart");
      require(
          governedDigest.equals(governedDuplicate.originalResult().resultDigest()),
          "governed nonzero STP result changed after restart");
      require(
          semanticDigest.equals(recovered.semanticStateDigest()),
          "governed/ungoverned STP semantic state changed after genesis recovery");
    } catch (IOException failure) {
      throw new IllegalStateException("all-command recovery failed", failure);
    }
    return observation(
        "all six command variants, valid STP state, and raw invalid STP rejection recovered", 0);
  }

  private ScenarioObservation structuralRejections(Path directory) {
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
      byte[] wrongShard =
          codec.encode(
              "structural",
              1,
              SHARD + 1,
              1,
              commandId(1, 1),
              new M08Command.Cancel("BTC-USDT", BigInteger.ONE));
      assertStructural(runtime.submit(wrongShard), StructuralRejectionCode.WRONG_SHARD);
      byte[] hashMismatch =
          envelope(
              "structural", 1, commandId(1, 2), new M08Command.Cancel("BTC-USDT", BigInteger.TWO));
      int hashOffset = 8 + 4 + "structural".getBytes(StandardCharsets.UTF_8).length + 24 + 16;
      hashMismatch[hashOffset] ^= 1;
      assertStructural(runtime.submit(hashMismatch), StructuralRejectionCode.PAYLOAD_HASH_MISMATCH);
      require(runtime.nextWalSequence() == 1, "structural rejection reached WAL");
    } catch (IOException failure) {
      throw new IllegalStateException("structural scenario failed", failure);
    }
    return observation("wrong shard and payload hash rejected before WAL", 0);
  }

  private ScenarioObservation liveDuplicate(Path directory) {
    byte[] value = envelope("live-dup", 1, commandId(2, 1), cancel(1));
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
      SubmissionResult.NewDurablyApplied first = assertNew(runtime.submit(value));
      SubmissionResult.DuplicateReplayed duplicate = assertDuplicate(runtime.submit(value));
      require(
          first.position().equals(duplicate.originalPosition()), "live duplicate position changed");
      require(
          first.result().resultDigest().equals(duplicate.originalResult().resultDigest()),
          "live duplicate result changed");
      require(runtime.nextWalSequence() == 2, "live duplicate appended again");
    } catch (IOException failure) {
      throw new IllegalStateException("live duplicate scenario failed", failure);
    }
    return observation("exact live identity replayed original result without append", 0);
  }

  private ScenarioObservation restartDuplicate(Path directory) {
    WalConfig config = WalConfig.defaults(directory, SHARD);
    byte[] value = envelope("restart-dup", 1, commandId(3, 1), cancel(1));
    String digest;
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config)) {
      digest = assertNew(runtime.submit(value)).result().resultDigest();
    } catch (IOException failure) {
      throw new IllegalStateException("restart duplicate write failed", failure);
    }
    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config)) {
      SubmissionResult.DuplicateReplayed duplicate = assertDuplicate(recovered.submit(value));
      require(
          digest.equals(duplicate.originalResult().resultDigest()), "recovered duplicate changed");
    } catch (IOException failure) {
      throw new IllegalStateException("restart duplicate recovery failed", failure);
    }
    return observation("exact restart identity replayed original durable result", 0);
  }

  private ScenarioObservation commandPayloadConflict(Path directory) {
    UUID id = commandId(4, 1);
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
      assertNew(runtime.submit(envelope("payload", 1, id, cancel(1))));
      assertPreflight(
          runtime.submit(envelope("payload", 1, id, cancel(2))),
          PreflightRejectionCode.COMMAND_ID_PAYLOAD_CONFLICT);
      require(runtime.nextWalSequence() == 2, "payload conflict appended");
    } catch (IOException failure) {
      throw new IllegalStateException("payload conflict scenario failed", failure);
    }
    return observation("commandId to payload binding remained immutable", 0);
  }

  private ScenarioObservation commandSlotConflict(Path directory) {
    UUID id = commandId(5, 1);
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
      assertNew(runtime.submit(envelope("id-slot", 1, id, cancel(1))));
      assertPreflight(
          runtime.submit(envelope("id-slot", 2, id, cancel(1))),
          PreflightRejectionCode.COMMAND_ID_SLOT_CONFLICT);
      require(runtime.nextWalSequence() == 2, "commandId slot conflict appended");
    } catch (IOException failure) {
      throw new IllegalStateException("commandId slot conflict scenario failed", failure);
    }
    return observation("commandId could not be rebound to a second producer slot", 0);
  }

  private ScenarioObservation slotIdentityConflict(Path directory) {
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
      assertNew(runtime.submit(envelope("slot", 1, commandId(6, 1), cancel(1))));
      assertPreflight(
          runtime.submit(envelope("slot", 1, commandId(6, 2), cancel(1))),
          PreflightRejectionCode.SLOT_IDENTITY_CONFLICT);
      require(runtime.nextWalSequence() == 2, "slot identity conflict appended");
    } catch (IOException failure) {
      throw new IllegalStateException("slot identity conflict scenario failed", failure);
    }
    return observation("producer slot could not be rebound to a second commandId", 0);
  }

  private ScenarioObservation gapAndBoundStale(Path directory) {
    UUID firstId = commandId(7, 1);
    byte[] first = envelope("sequence", 1, firstId, cancel(1));
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
      assertNew(runtime.submit(first));
      assertPreflight(
          runtime.submit(envelope("sequence", 3, commandId(7, 3), cancel(3))),
          PreflightRejectionCode.PRODUCER_SEQUENCE_GAP);
      assertNew(runtime.submit(envelope("sequence", 2, commandId(7, 2), cancel(2))));
      assertDuplicate(runtime.submit(first));
      assertPreflight(
          runtime.submit(envelope("sequence", 1, commandId(7, 4), cancel(1))),
          PreflightRejectionCode.SLOT_IDENTITY_CONFLICT);
      require(runtime.nextWalSequence() == 3, "gap or bound stale attempt advanced WAL");
    } catch (IOException failure) {
      throw new IllegalStateException("sequence scenario failed", failure);
    }
    return observation(
        "gap rejected; continuous no-eviction makes prior slots resolve as duplicate or slot conflict",
        0);
  }

  private ScenarioObservation epochFence(Path directory) {
    byte[] epochOne = envelope("epoch", 1, 1, commandId(8, 1), cancel(1));
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
      assertNew(runtime.submit(epochOne));
      assertNew(runtime.submit(envelope("epoch", 2, 1, commandId(8, 2), cancel(2))));
      assertDuplicate(runtime.submit(epochOne));
      assertPreflight(
          runtime.submit(envelope("epoch", 1, 2, commandId(8, 3), cancel(3))),
          PreflightRejectionCode.PRODUCER_EPOCH_FENCED);
    } catch (IOException failure) {
      throw new IllegalStateException("epoch fence scenario failed", failure);
    }
    return observation("known old-epoch exact replayed; unseen old epoch was fenced", 0);
  }

  private ScenarioObservation higherEpochStart(Path directory) {
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(WalConfig.defaults(directory, SHARD))) {
      assertNew(runtime.submit(envelope("epoch-start", 1, 1, commandId(9, 1), cancel(1))));
      assertPreflight(
          runtime.submit(envelope("epoch-start", 2, 2, commandId(9, 2), cancel(2))),
          PreflightRejectionCode.PRODUCER_EPOCH_MUST_START_AT_ONE);
      assertNew(runtime.submit(envelope("epoch-start", 2, 1, commandId(9, 3), cancel(3))));
    } catch (IOException failure) {
      throw new IllegalStateException("higher epoch start scenario failed", failure);
    }
    return observation("higher epoch activated only at sequence one", 0);
  }

  private ScenarioObservation tornLength(Path directory) {
    FaultOutcome outcome = faultAndRecover(directory, FaultPoint.AFTER_RECORD_LENGTH_WRITE, false);
    require(outcome.retryNew(), "torn length was replayed instead of truncated");
    return observation("length-only final tail returned UNKNOWN then truncated on restart", 1);
  }

  private ScenarioObservation bodyBeforeForce(Path directory) {
    FaultOutcome outcome = faultAndRecover(directory, FaultPoint.AFTER_RECORD_BODY_WRITE, true);
    require(!outcome.retryNew(), "complete body disappeared during same-host restart smoke");
    return observation("complete unforced frame returned UNKNOWN and was resolved by restart", 1);
  }

  private ScenarioObservation forcedBeforeApply(Path directory) {
    FaultOutcome outcome = faultAndRecover(directory, FaultPoint.AFTER_RECORD_FORCE, true);
    require(!outcome.retryNew(), "forced frame was not recovered");
    return observation("record force completed before apply hook; no ACK escaped", 1);
  }

  private ScenarioObservation appliedBeforeAck(Path directory) {
    FaultOutcome outcome = faultAndRecover(directory, FaultPoint.AFTER_LIVE_APPLY_BEFORE_ACK, true);
    require(!outcome.retryNew(), "applied durable command was not recovered");
    return observation("apply completed before ACK hook; exact retry replayed after restart", 1);
  }

  private ScenarioObservation rolloverDirectoryForce(Path directory) {
    WalConfig config =
        new WalConfig(directory, SHARD, ROLLOVER_SEGMENT_BYTES, MIN_MAX_RECORD_BYTES);
    CountingFault fault = new CountingFault(FaultPoint.AFTER_DIRECTORY_FORCE, 2);
    long sequence = 1;
    byte[] pending = null;
    int acknowledged = 0;
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config, fault)) {
      while (sequence < 100) {
        pending = envelope("rollover", sequence, commandId(14, sequence), largePlace(sequence));
        SubmissionResult result = runtime.submit(pending);
        if (result instanceof SubmissionResult.NewDurablyApplied) {
          acknowledged++;
          sequence++;
          continue;
        }
        require(
            result instanceof SubmissionResult.DurabilityUnknown, "rollover fault returned ACK");
        require(
            ((SubmissionResult.DurabilityUnknown) result).attemptedPosition().isEmpty(),
            "directory-force failure claimed a record position");
        break;
      }
    } catch (IOException failure) {
      throw new IllegalStateException("rollover directory-force scenario failed", failure);
    }
    require(pending != null && acknowledged > 0, "rollover fault was not reached");
    require(countFinalSegments(directory) >= 2, "rollover final header was not renamed");
    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config)) {
      require(
          recovered.nextWalSequence() == acknowledged + 1L,
          "header-only final segment changed WAL continuity");
      assertNew(recovered.submit(pending));
    } catch (IOException failure) {
      throw new IllegalStateException("rollover recovery failed", failure);
    }
    return observation("new segment directory force preceded its first record and ACK", 1);
  }

  private ScenarioObservation orphanTemp(Path directory) {
    WalConfig config = WalConfig.defaults(directory, SHARD);
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config)) {
      assertNew(runtime.submit(envelope("orphan", 1, commandId(15, 1), cancel(1))));
    } catch (IOException failure) {
      throw new IllegalStateException("orphan seed failed", failure);
    }
    Path orphan = directory.resolve("segment-00000000000000000002.m08w1.tmp");
    try {
      Files.write(orphan, new byte[] {1, 2, 3}, StandardOpenOption.CREATE_NEW);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot create orphan temp", failure);
    }
    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config)) {
      require(!Files.exists(orphan), "orphan temp survived recovery");
      require(recovered.nextWalSequence() == 2, "orphan temp became authoritative");
    } catch (IOException failure) {
      throw new IllegalStateException("orphan recovery failed", failure);
    }
    return observation("orphan temp removed without entering recovered history", 0);
  }

  private ScenarioObservation finalTornTail(Path directory) {
    WalConfig config = WalConfig.defaults(directory, SHARD);
    long headerSize;
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config)) {
      require(runtime.nextWalSequence() == 1, "fresh torn-tail WAL sequence changed");
      headerSize = size(finalSegment(directory));
    } catch (IOException failure) {
      throw new IllegalStateException("torn-tail seed failed", failure);
    }
    byte[] value = envelope("tail", 1, commandId(16, 1), cancel(1));
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(config, new OneShotFault(FaultPoint.AFTER_RECORD_LENGTH_WRITE))) {
      require(
          runtime.submit(value) instanceof SubmissionResult.DurabilityUnknown, "tail fault ACKed");
    } catch (IOException failure) {
      throw new IllegalStateException("tail injection failed", failure);
    }
    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config)) {
      require(size(finalSegment(directory)) == headerSize, "final torn tail was not truncated");
      assertNew(recovered.submit(value));
    } catch (IOException failure) {
      throw new IllegalStateException("tail repair failed", failure);
    }
    return observation("only incomplete final frame was truncated and forced", 1);
  }

  private ScenarioObservation completeFinalCorruption(Path directory) {
    WalConfig config = WalConfig.defaults(directory, SHARD);
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config)) {
      assertNew(runtime.submit(envelope("corrupt-final", 1, commandId(17, 1), cancel(1))));
    } catch (IOException failure) {
      throw new IllegalStateException("final corruption seed failed", failure);
    }
    flipByte(finalSegment(directory), Math.toIntExact(size(finalSegment(directory)) - 1));
    assertCorruptOpen(config);
    return observation("complete final frame CRC corruption failed closed", 0);
  }

  private ScenarioObservation middleCorruption(Path directory) {
    WalConfig config = WalConfig.defaults(directory, SHARD);
    try (LocalMatchingRuntime runtime = LocalMatchingRuntime.open(config)) {
      for (long sequence = 1; sequence <= 3; sequence++) {
        assertNew(
            runtime.submit(
                envelope("corrupt-middle", sequence, commandId(18, sequence), cancel(sequence))));
      }
    } catch (IOException failure) {
      throw new IllegalStateException("middle corruption seed failed", failure);
    }
    Path segment = finalSegment(directory);
    int firstLength = readInt(segment, WAL_HEADER_BYTES);
    require(firstLength > 32, "first record framing is unexpectedly small");
    flipByte(segment, WAL_HEADER_BYTES + firstLength - 1);
    assertCorruptOpen(config);
    return observation("complete middle-frame corruption was not skipped or repaired", 0);
  }

  private ScenarioObservation lockAndApplyFailure(Path directory) {
    WalConfig config = WalConfig.defaults(directory, SHARD);
    try (LocalMatchingRuntime first = LocalMatchingRuntime.open(config)) {
      require(first.nextWalSequence() == 1, "first writer did not own a fresh WAL");
      try (LocalMatchingRuntime ignored = LocalMatchingRuntime.open(config)) {
        throw new M08SemanticFailure(
            "second M08 writer acquired the directory in state " + ignored.state());
      } catch (DirectoryLockException expected) {
        // Expected single-writer rejection.
      }
    } catch (IOException failure) {
      throw new IllegalStateException("single-writer scenario failed", failure);
    }
    Path poison = directory.resolveSibling(directory.getFileName() + "-poison");
    provision(poison);
    byte[] value = envelope("poison", 1, commandId(19, 1), cancel(1));
    M08RuntimeJudgeProbe.Result result =
        M08RuntimeJudgeProbe.exercisePoisonRecovery(poison, SHARD, value);
    require("DurabilityUnknown".equals(result.firstResult()), "poison result changed");
    require(result.poisonBlockedRecovery(), "poison did not block unchanged recovery");
    require(result.repairedApplyCount() == 1, "repaired recovery applied poison more than once");
    require("DuplicateReplayed".equals(result.exactRetryResult()), "poison identity was lost");
    deleteTree(poison);
    return observation("directory lock and deterministic apply poison both failed closed", 1);
  }

  private FaultOutcome faultAndRecover(Path directory, FaultPoint point, boolean expectDurable) {
    WalConfig config = WalConfig.defaults(directory, SHARD);
    byte[] value = envelope("fault-" + point, 1, commandId(100 + point.ordinal(), 1), cancel(1));
    try (LocalMatchingRuntime runtime =
        LocalMatchingRuntime.open(config, new OneShotFault(point))) {
      SubmissionResult.DurabilityUnknown unknown =
          requireType(
              runtime.submit(value),
              SubmissionResult.DurabilityUnknown.class,
              "fault window returned ACK");
      require(unknown.attemptedPosition().isPresent(), "record fault lost attempted position");
      require(
          runtime.submit(value) instanceof SubmissionResult.FailedClosed,
          "faulted runtime accepted another command");
    } catch (IOException failure) {
      throw new IllegalStateException("fault window failed", failure);
    }
    try (LocalMatchingRuntime recovered = LocalMatchingRuntime.open(config)) {
      SubmissionResult retry = recovered.submit(value);
      boolean retryNew = retry instanceof SubmissionResult.NewDurablyApplied;
      require(
          expectDurable ? retry instanceof SubmissionResult.DuplicateReplayed : retryNew,
          "restart classified fault window incorrectly: " + point + " -> " + retry);
      return new FaultOutcome(retryNew);
    } catch (IOException failure) {
      throw new IllegalStateException("fault recovery failed", failure);
    }
  }

  private byte[] envelope(String producer, long sequence, UUID id, M08Command command) {
    return envelope(producer, 1, sequence, id, command);
  }

  private byte[] envelope(String producer, long epoch, long sequence, UUID id, M08Command command) {
    return codec.encode(producer, epoch, SHARD, sequence, id, command);
  }

  private static M08Command.Place place(
      long orderId, long price, long quantity, long group, String policy) {
    return stpPlace(orderId, "BUY", price, quantity, group, policy);
  }

  private static M08Command.Place stpPlace(
      long orderId, String side, long price, long quantity, long group, String policy) {
    return stpPlace(orderId, side, price, quantity, group, policy, null);
  }

  private static M08Command.Place stpPlace(
      long orderId,
      String side,
      long price,
      long quantity,
      long group,
      String policy,
      io.github.lchareln.cex.matching.RuleSetIdentity expectedActive) {
    return new M08Command.Place(
        "BTC-USDT",
        BigInteger.valueOf(orderId),
        side,
        BigInteger.valueOf(price),
        BigInteger.valueOf(quantity),
        "GTC",
        group,
        policy,
        Optional.ofNullable(expectedActive));
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

  private static M08Command.Cancel cancel(long orderId) {
    return new M08Command.Cancel("BTC-USDT", BigInteger.valueOf(orderId));
  }

  private static MarketRuleSetArtifact artifact(long version, long lower, long upper) {
    MarketRuleSetArtifact unhashed =
        new MarketRuleSetArtifact(
            MarketRuleSetArtifact.SCHEMA_VERSION,
            "BTC-USDT",
            new RuleSetVersion(version),
            new PriceTicks(lower),
            new PriceTicks(upper),
            "sha256:" + "0".repeat(64));
    return new MarketRuleSetArtifact(
        unhashed.schemaVersion(),
        unhashed.instrumentId(),
        unhashed.version(),
        unhashed.lowerInclusive(),
        unhashed.upperInclusive(),
        unhashed.computedContentHash());
  }

  private static UUID commandId(long scenario, long operation) {
    return new UUID(0x0800000000000000L | scenario, operation);
  }

  private static SubmissionResult.NewDurablyApplied assertNew(SubmissionResult result) {
    return requireType(
        result, SubmissionResult.NewDurablyApplied.class, "expected NEW_DURABLY_APPLIED");
  }

  private static SubmissionResult.DuplicateReplayed assertDuplicate(SubmissionResult result) {
    return requireType(
        result, SubmissionResult.DuplicateReplayed.class, "expected DUPLICATE_REPLAYED");
  }

  private static void assertPreflight(SubmissionResult result, PreflightRejectionCode code) {
    SubmissionResult.PreflightRejected rejection =
        requireType(result, SubmissionResult.PreflightRejected.class, "expected preflight reject");
    require(rejection.code() == code, "unexpected preflight code " + rejection.code());
  }

  private static void assertStructural(SubmissionResult result, StructuralRejectionCode code) {
    SubmissionResult.StructuralRejected rejection =
        requireType(
            result, SubmissionResult.StructuralRejected.class, "expected structural reject");
    require(rejection.code() == code, "unexpected structural code " + rejection.code());
  }

  private static void assertCorruptOpen(WalConfig config) {
    try (LocalMatchingRuntime ignored = LocalMatchingRuntime.open(config)) {
      throw new M08SemanticFailure(
          "corrupt M08 WAL opened successfully in state " + ignored.state());
    } catch (WalCorruptionException expected) {
      // Expected fail-closed recovery.
    } catch (IOException failure) {
      throw new IllegalStateException("unexpected corruption I/O classification", failure);
    }
  }

  private static Path finalSegment(Path directory) {
    try (var paths = Files.list(directory)) {
      return paths
          .filter(path -> path.getFileName().toString().matches("segment-[0-9]{20}\\.m08w1"))
          .max(Comparator.comparing(Path::toString))
          .orElseThrow();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot locate M08 final segment", failure);
    }
  }

  private static int countFinalSegments(Path directory) {
    try (var paths = Files.list(directory)) {
      return Math.toIntExact(
          paths
              .filter(path -> path.getFileName().toString().matches("segment-[0-9]{20}\\.m08w1"))
              .count());
    } catch (IOException failure) {
      throw new IllegalStateException("cannot count M08 final segments", failure);
    }
  }

  private static long size(Path path) {
    try {
      return Files.size(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot size " + path, failure);
    }
  }

  private static int readInt(Path path, int offset) {
    try {
      byte[] bytes = Files.readAllBytes(path);
      return ByteBuffer.wrap(bytes, offset, Integer.BYTES).getInt();
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read record length", failure);
    }
  }

  private static void flipByte(Path path, int offset) {
    try {
      byte[] bytes = Files.readAllBytes(path);
      bytes[offset] ^= 1;
      Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot corrupt M08 frame", failure);
    }
  }

  private static List<String> strings(JsonNode values) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.stringValue()));
    return List.copyOf(result);
  }

  private static byte[] readBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static <T> T requireType(Object value, Class<T> type, String message) {
    require(type.isInstance(value), message + ": " + value);
    return type.cast(value);
  }

  private static ScenarioObservation observation(String summary, int injectedFaults) {
    return new ScenarioObservation(summary, injectedFaults);
  }

  private static void deleteTree(Path path) {
    if (!Files.exists(path)) {
      return;
    }
    try (var paths = Files.walk(path)) {
      for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(current);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot clear M08 fixed path", failure);
    }
  }

  private static void provision(Path directory) {
    try {
      Files.createDirectories(directory);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot provision M08 WAL directory " + directory, failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M08SemanticFailure(message);
    }
  }

  private record ScenarioObservation(String summary, int injectedFaults) {}

  private record FaultOutcome(boolean retryNew) {}

  private static class OneShotFault implements FaultInjector {
    private final FaultPoint target;
    private boolean thrown;

    private OneShotFault(FaultPoint target) {
      this.target = target;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (!thrown && point == target) {
        thrown = true;
        throw new IOException("injected " + point);
      }
    }
  }

  private static final class CountingFault extends OneShotFault {
    private final FaultPoint target;
    private final int throwOn;
    private int hits;

    private CountingFault(FaultPoint target, int throwOn) {
      super(target);
      this.target = target;
      this.throwOn = throwOn;
    }

    @Override
    public void hit(FaultPoint point) throws IOException {
      if (point == target && ++hits == throwOn) {
        throw new IOException("injected occurrence " + throwOn + " at " + point);
      }
    }
  }

  record Result(
      ArrayNode scenarios,
      List<String> coverage,
      int injectedFaults,
      byte[] canonicalBytes,
      String digest) {
    Result {
      scenarios = scenarios.deepCopy();
      coverage = List.copyOf(coverage);
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

package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.cluster.DirectM11MatchingRuntime;
import io.github.lchareln.cex.matching.cluster.M11ResponseStatus;
import io.github.lchareln.cex.matching.cluster.M11RuntimeStateCodec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;

/** Strict independent readers for the M12 report projections that carry release claims. */
final class M12StrictReports {
  private static final Pattern EXPECTED_AERON_FAIL_STOP_WARNING =
      Pattern.compile(
          "^io\\.aeron\\.cluster\\.client\\.ClusterEvent: WARN - "
              + "(?:leader heartbeat timeout|inactive follower quorum|quorum position went backwards: "
              + "leaderCommitPosition=[0-9]+ quorumPosition=[0-9]+)$");
  private static final long ARCHIVE_MARK_FILE_LIVENESS_TIMEOUT_MILLIS = 10_000;
  private static final String RESTART_SAFETY_PREDICATE =
      "ARCHIVE_MARK_FILE_ACTIVITY_AGE_GT_LIVENESS_TIMEOUT";
  private static final String AERON_VERSION = "1.52.2";
  private static final List<String> STABLE_STATUS_FIELDS =
      List.of(
          "role",
          "electionState",
          "leadershipTermId",
          "commitPosition",
          "logPosition",
          "nextApplicationSequence",
          "identityCount",
          "semanticStateDigest",
          "identityResultDigest",
          "componentErrorCount",
          "diagnosticWarningCount",
          "droppedDiagnosticWarnings",
          "diagnosticWarnings");

  static final String INHERITED_SCHEMA_PATH = "schemas/matching.m12.inherited-m11.v1.schema.json";
  static final String HISTORY_SCHEMA_PATH = "schemas/matching.m12.command-history.v1.schema.json";
  static final String TOPOLOGY_SCHEMA_PATH = "schemas/matching.m12.topology.v1.schema.json";
  static final String LEADERSHIP_SCHEMA_PATH = "schemas/matching.m12.leadership.v1.schema.json";
  static final String QUORUM_SCHEMA_PATH = "schemas/matching.m12.quorum.v1.schema.json";
  static final String CATCHUP_SCHEMA_PATH = "schemas/matching.m12.catchup.v1.schema.json";
  static final String STATE_SCHEMA_PATH = "schemas/matching.m12.state-equivalence.v1.schema.json";
  static final String ARCHITECTURE_SCHEMA_PATH = "schemas/matching.m12.architecture.v1.schema.json";
  static final String ENVIRONMENT_SCHEMA_PATH = "schemas/matching.m12.environment.v1.schema.json";

  static final List<String> SCHEMA_PATHS =
      List.of(
          INHERITED_SCHEMA_PATH,
          HISTORY_SCHEMA_PATH,
          TOPOLOGY_SCHEMA_PATH,
          LEADERSHIP_SCHEMA_PATH,
          QUORUM_SCHEMA_PATH,
          CATCHUP_SCHEMA_PATH,
          STATE_SCHEMA_PATH,
          ARCHITECTURE_SCHEMA_PATH,
          ENVIRONMENT_SCHEMA_PATH);

  private static final Map<String, String> REPORT_SCHEMAS =
      Map.ofEntries(
          Map.entry("inherited-m11.json", INHERITED_SCHEMA_PATH),
          Map.entry("m12-command-history.json", HISTORY_SCHEMA_PATH),
          Map.entry("topology.json", TOPOLOGY_SCHEMA_PATH),
          Map.entry("leadership.json", LEADERSHIP_SCHEMA_PATH),
          Map.entry("quorum.json", QUORUM_SCHEMA_PATH),
          Map.entry("catchup.json", CATCHUP_SCHEMA_PATH),
          Map.entry("state-equivalence.json", STATE_SCHEMA_PATH),
          Map.entry("architecture.json", ARCHITECTURE_SCHEMA_PATH),
          Map.entry("environment.json", ENVIRONMENT_SCHEMA_PATH));

  private M12StrictReports() {}

  static void validateDocuments(Path schemaRoot, Map<String, ? extends JsonNode> candidates) {
    require(
        candidates.keySet().equals(REPORT_SCHEMAS.keySet()),
        "strict M12 in-memory report set changed");
    Map<String, JsonNode> documents = new LinkedHashMap<>();
    REPORT_SCHEMAS.forEach(
        (name, schemaPath) -> {
          JsonNode document = parse(name, JsonSupport.prettyBytes(candidates.get(name)));
          JsonSupport.validate(document, readString(schemaRoot.resolve(schemaPath)), false);
          documents.put(name, document);
        });
    verifyStructuralSemantics(documents);
  }

  static Map<String, JsonNode> validateAll(Path schemaRoot, Path reports) {
    return validateAll(schemaRoot, reports, null);
  }

  static Map<String, JsonNode> validateAll(Path schemaRoot, Path reports, JsonNode check) {
    Map<String, JsonNode> documents = new LinkedHashMap<>();
    REPORT_SCHEMAS.forEach(
        (name, schemaPath) -> {
          Path report = reports.resolve(name);
          require(
              Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS),
              "missing strict M12 report: " + name);
          JsonNode document = parse(name, read(report));
          JsonSupport.validate(document, readString(schemaRoot.resolve(schemaPath)), false);
          documents.put(name, document);
        });
    IntegrityContext context = IntegrityContext.load(schemaRoot, reports);
    verifySemantics(documents, context, check);
    return Map.copyOf(documents);
  }

  private static JsonNode parse(String name, byte[] bytes) {
    return "m12-command-history.json".equals(name)
        ? M12InfrastructurePreconditions.parseHistory(bytes)
        : JsonSupport.parse(bytes);
  }

  private static void verifyStructuralSemantics(Map<String, JsonNode> documents) {
    verifyHistoryShape(documents.get("m12-command-history.json"));
    verifyTopology(documents.get("topology.json"));
    verifyLeadership(documents.get("leadership.json"));
    verifyQuorum(documents.get("quorum.json"), documents.get("m12-command-history.json"));
    verifyCatchup(documents.get("catchup.json"), documents.get("topology.json"));
    verifyStateEquivalence(documents.get("state-equivalence.json"), null);
    verifyInherited(documents.get("inherited-m11.json"), documents.get("architecture.json"));
    verifyArchitecture(documents.get("architecture.json"));
    verifyEnvironment(documents.get("environment.json"));
    verifyCrossDocuments(documents, null, null);
  }

  private static void verifySemantics(
      Map<String, JsonNode> documents, IntegrityContext context, JsonNode check) {
    verifyCorpus(context);
    verifyHistory(documents.get("m12-command-history.json"), context);
    verifyTopology(documents.get("topology.json"));
    verifyLeadership(documents.get("leadership.json"));
    verifyQuorum(documents.get("quorum.json"), documents.get("m12-command-history.json"));
    verifyCatchup(documents.get("catchup.json"), documents.get("topology.json"));
    verifyStateEquivalence(documents.get("state-equivalence.json"), context.oracle());
    verifyInherited(documents.get("inherited-m11.json"), documents.get("architecture.json"));
    verifyArchitecture(documents.get("architecture.json"));
    verifyEnvironment(documents.get("environment.json"));
    verifyCoverage(context.coverage(), context.workload(), context.oracle());
    verifyCrossDocuments(documents, context.oracle(), context.corpus());
    if (check != null) {
      verifyJudgeInspection(check, documents, context);
    }
  }

  private static void verifyCorpus(IntegrityContext context) {
    JsonNode report = context.corpusReport();
    M12WorkloadLoader.Workload workload = context.workload();
    M12DeterministicCorpus.Corpus corpus = context.corpus();
    DirectOracle oracle = context.oracle();
    require(
        "matching.m12.corpus.v1".equals(requiredText(report, "schemaVersion"))
            && "PASS".equals(requiredText(report, "status")),
        "M12 corpus report is not strict PASS");
    require(
        "splitmix64-v1".equals(requiredText(report, "algorithm"))
            && Long.toString(M12DeterministicCorpus.SEED).equals(requiredText(report, "seed")),
        "M12 corpus generator changed");
    require(
        workload.sha256().equals(requiredText(report, "workloadSha256")),
        "M12 corpus workload binding changed");
    require(
        report.path("fixedScenarios").intValue() == workload.scenarios().size()
            && report.path("coverageObligations").intValue()
                == workload.coverageRequirements().size()
            && report.path("requiredMutants").intValue() == workload.requiredMutants().size()
            && report.path("systemErrorControls").intValue()
                == workload.systemErrorControls().size(),
        "M12 corpus workload cardinalities changed");
    require(
        report.path("distinctBusinessCommands").intValue() == 66
            && report.path("invocations").intValue() == 85
            && report.path("acceptedIngressAttempts").intValue() == 84
            && report.path("expectedFinalNextApplicationSequence").intValue() == 67,
        "M12 corpus command cardinalities changed");
    require(
        strings(report.path("phaseOrder")).equals(workload.phaseOrder()),
        "M12 corpus phase order changed");
    require(
        requiredSha256(report, "corpusSha256").equals(recomputeFrozenCorpusDigest(corpus)),
        "M12 corpus digest does not bind the frozen corpus");
    require(
        requiredSha256(report, "expectedFinalSemanticDigest").equals(oracle.semanticStateDigest()),
        "M12 corpus semantic digest differs from the direct runtime oracle");

    JsonNode identities = report.path("identities");
    require(identities.size() == corpus.identities().size(), "M12 corpus identity count changed");
    for (int index = 0; index < identities.size(); index++) {
      JsonNode actual = identities.get(index);
      M12DeterministicCorpus.DurableIdentity expected = corpus.identities().get(index);
      require(
          actual.path("index").intValue() == expected.index()
              && expected.commandId().equals(requiredUuid(actual, "commandId")),
          "M12 corpus durable command identity changed");
      require(
          expected.producerId().equals(requiredText(actual, "producerId"))
              && actual.path("producerEpoch").longValue() == expected.producerEpoch()
              && actual.path("shardId").longValue() == expected.shardId()
              && actual.path("producerSequence").longValue() == expected.producerSequence(),
          "M12 corpus producer slot changed");
      require(
          expected.payloadSha256().equals(requiredSha256(actual, "payloadSha256"))
              && expected
                  .canonicalSha256()
                  .equals(requiredSha256(actual, "canonicalEnvelopeSha256"))
              && actual.path("canonicalEnvelopeBytes").intValue()
                  == expected.canonicalBytes().length,
          "M12 corpus canonical identity bytes changed");
    }
    require(
        Arrays.equals(context.canonicalCommands(), canonicalCommandBytes(corpus)),
        "M12 canonical command artifact differs from the frozen identities");
  }

  private static void verifyHistoryShape(JsonNode history) {
    JsonNode attempts = history.path("attempts");
    long ingressAccepted = 0;
    long acknowledged = 0;
    long unknown = 0;
    long notSubmitted = 0;
    Set<UUID> correlations = new LinkedHashSet<>();
    for (int index = 0; index < attempts.size(); index++) {
      JsonNode attempt = attempts.get(index);
      require(
          attempt.path("ordinal").intValue() == index + 1,
          "M12 history attempt ordinals are not contiguous");
      require(
          correlations.add(requiredUuid(attempt, "correlationId")),
          "M12 invocation correlation was reused");
      if (attempt.path("ingressAccepted").booleanValue()) {
        ingressAccepted++;
      }
      switch (requiredText(attempt, "outcome")) {
        case "ACKNOWLEDGED" -> acknowledged++;
        case "UNKNOWN" -> unknown++;
        case "NOT_SUBMITTED" -> notSubmitted++;
        default -> throw new IllegalStateException("unknown M12 attempt outcome");
      }
    }
    require(
        ingressAccepted == history.path("ingressAccepted").longValue()
            && acknowledged == history.path("acknowledged").longValue()
            && unknown == history.path("unknown").longValue()
            && notSubmitted == history.path("notSubmitted").longValue(),
        "M12 history aggregate counts do not match attempts");

    Set<Integer> identities = new LinkedHashSet<>();
    JsonNode bindings = history.path("bindings");
    for (int index = 0; index < bindings.size(); index++) {
      JsonNode binding = bindings.get(index);
      require(
          binding.path("applicationSequence").intValue() == index + 1,
          "M12 binding application sequences are not contiguous");
      require(
          identities.add(binding.path("identityIndex").intValue()),
          "M12 binding identity is duplicated");
      require(
          binding.path("businessEffectCount").intValue() == 1,
          "M12 binding business effect count is not exactly one");
    }
    require(
        identities.equals(new LinkedHashSet<>(IntStream.rangeClosed(1, 66).boxed().toList())),
        "M12 binding identity set is incomplete");
    requireExactMemberSet(
        history.path("appliedUnknownMembersBeforeLeaderKill"),
        "M12 applied-UNKNOWN replication member set");
  }

  private static void verifyHistory(JsonNode history, IntegrityContext context) {
    verifyHistoryShape(history);
    M12DeterministicCorpus.Corpus corpus = context.corpus();
    DirectOracle oracle = context.oracle();
    require(
        requiredSha256(history, "corpusSha256").equals(recomputeFrozenCorpusDigest(corpus)),
        "M12 history corpus digest changed");
    require(
        history.path("identityCount").intValue() == 66
            && history.path("invocationCount").intValue() == 85
            && history.path("memberProcessStarts").intValue() == 6
            && history.path("externalForceStops").intValue() == 3
            && history.path("finalNextApplicationSequence").intValue() == 67,
        "M12 history cardinality projection changed");

    Map<Integer, JsonNode> bindingByIdentity = new LinkedHashMap<>();
    for (JsonNode binding : history.path("bindings")) {
      int identityIndex = binding.path("identityIndex").intValue();
      DirectBinding expected = oracle.bindings().get(identityIndex);
      require(expected != null, "M12 history binding is outside the direct oracle");
      require(
          requiredSha256(binding, "canonicalEnvelopeSha256")
                  .equals(expected.canonicalEnvelopeSha256())
              && binding.path("applicationSequence").longValue() == expected.applicationSequence()
              && requiredSha256(binding, "resultDigest").equals(expected.resultDigest())
              && binding.path("businessEffectCount").intValue() == 1,
          "M12 history binding differs from the direct runtime oracle");
      require(
          bindingByIdentity.put(identityIndex, binding) == null,
          "M12 history binding is duplicated");
    }

    Map<Integer, JsonNode> attemptsByOrdinal = new LinkedHashMap<>();
    Map<Integer, Integer> effectsByIdentity = new HashMap<>();
    for (int index = 0; index < corpus.attempts().size(); index++) {
      M12DeterministicCorpus.Attempt template = corpus.attempts().get(index);
      JsonNode attempt = history.path("attempts").get(index);
      require(
          attempt.path("ordinal").intValue() == template.ordinal()
              && template.phase().equals(requiredText(attempt, "phase"))
              && attempt.path("identityIndex").intValue() == template.identity().index(),
          "M12 history attempt ordinal, phase, or identity differs from the frozen corpus");
      require(
          template
                  .identity()
                  .canonicalSha256()
                  .equals(requiredSha256(attempt, "canonicalEnvelopeSha256"))
              && template.identity().commandId().equals(requiredUuid(attempt, "commandId"))
              && template.correlationId().equals(requiredUuid(attempt, "correlationId")),
          "M12 history attempt canonical identity differs from the frozen corpus");
      require(
          attempt.path("ingressAccepted").booleanValue() == template.ingressAccepted()
              && template.outcome().name().equals(requiredText(attempt, "outcome"))
              && attempt.path("noQuorumWindow").booleanValue() == template.noQuorumWindow()
              && Objects.equals(
                  nullableInt(attempt.path("retryOfAttemptOrdinal")),
                  template.retryOfAttemptOrdinal()),
          "M12 history invocation boundary differs from the frozen corpus");
      require(
          !attempt.path("timeoutClassifiedAsBusinessRejection").booleanValue(),
          "M12 timeout was classified as a business rejection");

      String outcome = requiredText(attempt, "outcome");
      boolean trusted = attempt.path("trustedResponseObserved").booleanValue();
      boolean currentAuthority =
          attempt.path("responseAcceptedUnderCurrentClientAuthority").booleanValue();
      boolean hasSequence = !attempt.path("applicationSequence").isNull();
      boolean hasDigest = !attempt.path("resultDigest").isNull();
      boolean effect = attempt.path("businessEffectApplied").booleanValue();
      if ("ACKNOWLEDGED".equals(outcome)) {
        require(
            trusted
                && currentAuthority
                && requiredUuid(attempt, "correlationId")
                    .equals(requiredUuid(attempt, "responseCorrelationId"))
                && !attempt.path("responseStatus").isNull()
                && hasSequence
                && hasDigest,
            "M12 ACK is not bound to a trusted correlated response");
        require(
            effect == "NEW_APPLIED".equals(requiredText(attempt, "responseStatus")),
            "M12 ACK business-effect flag differs from its response status");
      } else if ("UNKNOWN".equals(outcome)) {
        require(
            !trusted
                && !currentAuthority
                && attempt.path("responseCorrelationId").isNull()
                && attempt.path("responseStatus").isNull()
                && hasSequence == hasDigest
                && effect == hasSequence,
            "M12 UNKNOWN crossed its allowed evidence boundary");
      } else {
        require(
            !attempt.path("ingressAccepted").booleanValue()
                && !trusted
                && !currentAuthority
                && attempt.path("responseCorrelationId").isNull()
                && attempt.path("responseStatus").isNull()
                && !hasSequence
                && !hasDigest
                && !effect,
            "M12 NOT_SUBMITTED contains submitted or response state");
      }

      JsonNode binding = bindingByIdentity.get(attempt.path("identityIndex").intValue());
      if (hasSequence) {
        require(
            binding != null
                && attempt.path("applicationSequence").longValue()
                    == binding.path("applicationSequence").longValue()
                && requiredSha256(attempt, "resultDigest")
                    .equals(requiredSha256(binding, "resultDigest")),
            "M12 attempt result differs from its identity binding");
      }
      if (effect) {
        effectsByIdentity.merge(attempt.path("identityIndex").intValue(), 1, Integer::sum);
        if (template.ordinal() != 84) {
          require(
              attempt.path("authorityTerm").longValue()
                  == binding.path("observedResponseAuthorityTerm").longValue(),
              "M12 binding authority term differs from its observed effect response");
        }
      }
      if (template.retryOfAttemptOrdinal() != null) {
        JsonNode original = attemptsByOrdinal.get(template.retryOfAttemptOrdinal());
        require(
            original != null
                && original.path("identityIndex").intValue()
                    == attempt.path("identityIndex").intValue()
                && requiredSha256(original, "canonicalEnvelopeSha256")
                    .equals(requiredSha256(attempt, "canonicalEnvelopeSha256"))
                && !requiredText(original, "correlationId")
                    .equals(requiredText(attempt, "correlationId")),
            "M12 retry changed durable identity or reused correlation");
      }
      attemptsByOrdinal.put(template.ordinal(), attempt);
    }
    require(
        effectsByIdentity.keySet().equals(bindingByIdentity.keySet())
            && effectsByIdentity.values().stream().allMatch(count -> count == 1),
        "M12 attempt history does not contain exactly one effect per binding");

    JsonNode attempt42 = attemptsByOrdinal.get(42);
    JsonNode attempt84 = attemptsByOrdinal.get(84);
    JsonNode attempt85 = attemptsByOrdinal.get(85);
    JsonNode noQuorumBinding = bindingByIdentity.get(attempt85.path("identityIndex").intValue());
    require(
        attempt42.path("applicationSequence").longValue() == 33
            && attempt42.path("businessEffectApplied").booleanValue(),
        "M12 applied UNKNOWN attempt 42 is not bound to effect 33");
    require(
        noQuorumBinding != null
            && attempt85.path("authorityTerm").longValue()
                == noQuorumBinding.path("observedResponseAuthorityTerm").longValue()
            && attempt84.path("identityIndex").intValue()
                == attempt85.path("identityIndex").intValue()
            && ((attempt84.path("applicationSequence").isNull()
                    && "NEW_APPLIED".equals(requiredText(attempt85, "responseStatus")))
                || (attempt84.path("applicationSequence").longValue() == 66
                    && "DUPLICATE_REPLAYED".equals(requiredText(attempt85, "responseStatus")))),
        "M12 no-quorum UNKNOWN/retry pair is inconsistent");
    verifyAppliedUnknown(
        history.path("appliedUnknownMembersBeforeLeaderKill"), DirectOracle.replay(corpus, 33));
  }

  private static void verifyTopology(JsonNode topology) {
    JsonNode processStarts = topology.path("processStarts");
    Set<Long> processIds = new LinkedHashSet<>();
    Set<String> allDirectories = new LinkedHashSet<>();
    Map<Integer, List<JsonNode>> startsByMember = new LinkedHashMap<>();
    Map<Integer, Ownership> memberOwnership = new LinkedHashMap<>();
    int freshStarts = 0;
    for (JsonNode process : processStarts) {
      require(
          processIds.add(process.path("processId").longValue()),
          "M12 process-start PID is not unique");
      require(!process.path("aliveAfterTeardown").booleanValue(), "M12 child survived teardown");
      int memberId = process.path("memberId").intValue();
      startsByMember.computeIfAbsent(memberId, ignored -> new ArrayList<>()).add(process);
      if (process.path("freshStart").booleanValue()) {
        freshStarts++;
      }
      int portBase = process.path("portBlockBase").intValue();
      List<Integer> ports = integers(process.path("udpPorts"));
      require(
          ports.equals(
              List.of(portBase + 1, portBase + 2, portBase + 3, portBase + 4, portBase + 5)),
          "M12 process UDP ports do not match its owned port block");
      Path root = normalizedAbsolutePath(process, "rootDirectory");
      Path aeron = normalizedAbsolutePath(process, "aeronDirectory");
      Path archive = normalizedAbsolutePath(process, "archiveDirectory");
      Path cluster = normalizedAbsolutePath(process, "clusterDirectory");
      require(
          aeron.equals(root.resolve("aeron"))
              && archive.equals(root.resolve("archive"))
              && cluster.equals(root.resolve("cluster")),
          "M12 member directories do not belong to their declared root");
      Ownership ownership =
          new Ownership(
              portBase,
              ports,
              root.toString(),
              aeron.toString(),
              archive.toString(),
              cluster.toString());
      Ownership prior = memberOwnership.putIfAbsent(memberId, ownership);
      require(
          prior == null || prior.equals(ownership), "M12 member ownership changed across restart");
      if (prior == null) {
        require(
            allDirectories.add(root.toString())
                && allDirectories.add(aeron.toString())
                && allDirectories.add(archive.toString())
                && allDirectories.add(cluster.toString()),
            "M12 directory ownership overlaps across categories");
      }
    }
    require(
        processIds.size() == 6 && freshStarts == 3 && processStarts.size() - freshStarts == 3,
        "M12 process-start/freshStart cardinality changed");
    require(
        IntStream.range(0, 3)
                .allMatch(index -> processStarts.get(index).path("freshStart").booleanValue())
            && IntStream.range(3, 6)
                .noneMatch(index -> processStarts.get(index).path("freshStart").booleanValue())
            && IntStream.range(0, 3)
                .map(index -> processStarts.get(index).path("memberId").intValue())
                .boxed()
                .collect(java.util.stream.Collectors.toSet())
                .equals(Set.of(0, 1, 2)),
        "M12 process-start order is not three initial members followed by three recoveries");
    require(memberOwnership.keySet().equals(Set.of(0, 1, 2)), "M12 member ownership set changed");
    startsByMember
        .values()
        .forEach(
            starts ->
                require(
                    starts.getFirst().path("freshStart").booleanValue()
                        && starts.stream()
                            .skip(1)
                            .noneMatch(value -> value.path("freshStart").booleanValue()),
                    "M12 member restart freshness order changed"));
    Set<Integer> allPorts = new LinkedHashSet<>();
    memberOwnership.values().forEach(ownership -> allPorts.addAll(ownership.ports()));
    require(allPorts.size() == 15, "M12 member UDP ownership is not disjoint");
    require(
        topology.path("teardownComplete").booleanValue()
            && topology.path("childProcessesAliveAfterTeardown").intValue() == 0,
        "M12 teardown observation is incomplete");
    require(
        !processIds.contains(topology.path("ownerProcessId").longValue()),
        "M12 fault-controller PID aliases a child process");

    JsonNode witnesses = topology.path("stableSnapshotWitnesses");
    require(
        witnesses.size() == topology.path("stableSnapshotWitnessCount").intValue(),
        "M12 stable witness count changed");
    for (int index = 0; index < witnesses.size(); index++) {
      verifyStableWitness(witnesses.get(index), index + 1);
    }

    requireExactMemberSet(topology.path("initialMembers"), "M12 initial member set");
    requireExactMemberSet(topology.path("convergedMembers"), "M12 converged member set");
    verifyObservedOwnership(topology.path("initialMembers"), memberOwnership);
    verifyObservedOwnership(topology.path("convergedMembers"), memberOwnership);
    for (JsonNode member : topology.path("initialMembers")) {
      JsonNode first = startsByMember.get(member.path("memberId").intValue()).getFirst();
      require(
          member.path("processId").longValue() == first.path("processId").longValue(),
          "M12 initial member PID does not match its fresh process start");
    }
    Set<Long> convergedPids = new LinkedHashSet<>();
    for (JsonNode member : topology.path("convergedMembers")) {
      List<JsonNode> starts = startsByMember.get(member.path("memberId").intValue());
      long expectedPid = starts.getLast().path("processId").longValue();
      require(
          member.path("processId").longValue() == expectedPid,
          "M12 converged member PID is not its latest process start");
      convergedPids.add(expectedPid);
    }

    Set<Long> stoppedPids = new LinkedHashSet<>();
    for (JsonNode stop : topology.path("forcedStops")) {
      long pid = stop.path("processId").longValue();
      int memberId = stop.path("memberId").intValue();
      require(
          stoppedPids.add(pid)
              && processIds.contains(pid)
              && startsByMember.get(memberId).stream()
                  .anyMatch(start -> start.path("processId").longValue() == pid),
          "M12 forced-stop PID/member does not identify a unique child start");
      require(!convergedPids.contains(pid), "M12 forced-stop PID appears in the final topology");
      verifyDiagnostics(stop, "M12 forced-stop member");
    }
    verifyRestartSafety(topology, processStarts, memberOwnership);
    Set<Long> classifiedPids = new LinkedHashSet<>(stoppedPids);
    classifiedPids.addAll(convergedPids);
    require(
        classifiedPids.equals(processIds),
        "M12 child PIDs are not partitioned into forced stops and final members");

    requireExactMemberSet(
        topology.path("appliedUnknownMembersBeforeLeaderKill"),
        "M12 topology applied-UNKNOWN member set");
    for (JsonNode status : topology.path("appliedUnknownMembersBeforeLeaderKill")) {
      verifyDiagnostics(status, "M12 applied-UNKNOWN member");
    }
    require(
        snapshotMatches(topology.path("initialMembers"), witnesses)
            && snapshotMatches(topology.path("convergedMembers"), witnesses),
        "M12 topology projections are not bound to stable status witnesses");
  }

  private static void verifyRestartSafety(
      JsonNode topology, JsonNode processStarts, Map<Integer, Ownership> memberOwnership) {
    require(
        topology.path("archiveMarkFileLivenessTimeoutMillis").longValue()
                == ARCHIVE_MARK_FILE_LIVENESS_TIMEOUT_MILLIS
            && RESTART_SAFETY_PREDICATE.equals(requiredText(topology, "restartSafetyPredicate")),
        "M12 restart-safety contract is not pinned to the Archive mark-file predicate");
    JsonNode witnesses = topology.path("restartSafetyWitnesses");
    JsonNode stops = topology.path("forcedStops");
    require(
        witnesses.size() == 3
            && witnesses.size() == topology.path("restartSafetyWitnessCount").intValue()
            && stops.size() == witnesses.size(),
        "M12 restart-safety witness cardinality changed");
    for (int index = 0; index < witnesses.size(); index++) {
      JsonNode witness = witnesses.get(index);
      JsonNode stop = stops.get(index);
      JsonNode recoveryStart = processStarts.get(index + 3);
      int memberId = stop.path("memberId").intValue();
      long stoppedProcessId = stop.path("processId").longValue();
      verifyRestartSafetyWitness(
          witness, index + 1, memberId, stoppedProcessId, memberOwnership.get(memberId));
      require(
          !recoveryStart.path("freshStart").booleanValue()
              && recoveryStart.path("memberId").intValue() == memberId
              && recoveryStart.path("processId").longValue() != stoppedProcessId,
          "M12 restart-safety witness is not followed by a new recovery process for its stopped member");
    }
  }

  private static void verifyRestartSafetyWitness(
      JsonNode witness,
      int expectedOrdinal,
      int expectedMemberId,
      long expectedStoppedProcessId,
      Ownership ownership) {
    long lastActivity = witness.path("lastActivityTimestampMillis").longValue();
    long observedAt = witness.path("observedAtMillis").longValue();
    long age = witness.path("ageMillis").longValue();
    long timeout = witness.path("livenessTimeoutMillis").longValue();
    require(
        witness.path("ordinal").intValue() == expectedOrdinal
            && witness.path("memberId").intValue() == expectedMemberId
            && witness.path("stoppedProcessId").longValue() == expectedStoppedProcessId,
        "M12 restart-safety witness is not positionally bound to its forced stop");
    require(
        ownership != null
            && normalizedAbsolutePath(witness, "archiveMarkFile")
                .equals(Path.of(ownership.archiveDirectory()).resolve("archive-mark.dat")),
        "M12 restart-safety witness does not observe its member-owned Archive mark file");
    require(
        lastActivity > 0
            && observedAt >= lastActivity
            && age == observedAt - lastActivity
            && timeout == ARCHIVE_MARK_FILE_LIVENESS_TIMEOUT_MILLIS
            && age > timeout
            && witness.path("probeCount").longValue() > 0
            && witness.path("waitElapsedNanos").longValue() >= 0,
        "M12 restart-safety witness does not prove mark-file liveness expiry");
    require(
        AERON_VERSION.equals(requiredText(witness, "aeronVersion"))
            && RESTART_SAFETY_PREDICATE.equals(requiredText(witness, "predicate"))
            && witness.path("activityTimestampPositive").booleanValue()
            && witness.path("ageStrictlyExceedsLivenessTimeout").booleanValue(),
        "M12 restart-safety witness claims differ from the pinned dependency contract");
  }

  private static void verifyStableWitness(JsonNode witness, int expectedOrdinal) {
    require(
        witness.path("ordinal").intValue() == expectedOrdinal,
        "M12 stable witness ordinals are not contiguous");
    long publishMillis = witness.path("statusPublishIntervalMillis").longValue();
    long freshnessMillis = witness.path("freshnessBoundMillis").longValue();
    long elapsedNanos = witness.path("elapsedNanos").longValue();
    require(
        publishMillis > 0
            && freshnessMillis >= publishMillis
            && elapsedNanos >= 0
            && elapsedNanos <= Math.multiplyExact(freshnessMillis, 1_000_000L),
        "M12 stable witness exceeded its monotonic freshness bound");
    Map<Integer, JsonNode> first = membersById(witness.path("firstSnapshot"));
    Map<Integer, JsonNode> second = membersById(witness.path("secondSnapshot"));
    require(
        first.keySet().equals(second.keySet()) && !first.isEmpty(),
        "M12 stable witness member set changed");
    first.forEach(
        (memberId, before) -> {
          JsonNode after = second.get(memberId);
          require(
              before.path("processId").longValue() == after.path("processId").longValue()
                  && after.path("statusSequence").longValue()
                      > before.path("statusSequence").longValue()
                  && after.path("observedAtEpochMillis").longValue()
                      >= before.path("observedAtEpochMillis").longValue(),
              "M12 stable witness PID or status sequence did not advance");
          for (String field : STABLE_STATUS_FIELDS) {
            require(
                before.path(field).equals(after.path(field)),
                "M12 stable witness changed " + field);
          }
          verifyDiagnostics(before, "M12 first stable status");
          verifyDiagnostics(after, "M12 second stable status");
        });
    require(
        second.values().stream()
            .allMatch(status -> "CLOSED".equals(requiredText(status, "electionState"))),
        "M12 stable witness accepted a non-CLOSED election");
    require(
        second.values().stream()
                    .filter(status -> "LEADER".equals(requiredText(status, "role")))
                    .count()
                == 1
            && second.values().stream()
                    .filter(status -> "FOLLOWER".equals(requiredText(status, "role")))
                    .count()
                == second.size() - 1
            && second.values().stream()
                    .map(status -> status.path("leadershipTermId").longValue())
                    .distinct()
                    .count()
                == 1,
        "M12 stable witness is not a single-Leader same-term topology");
  }

  private static void verifyLeadership(JsonNode leadership) {
    int initial = leadership.path("initialLeaderId").intValue();
    long initialTerm = leadership.path("initialLeadershipTermId").longValue();
    int faultTarget = leadership.path("faultTargetLeaderId").intValue();
    long faultTargetTerm = leadership.path("faultTargetLeadershipTermId").longValue();
    int replacement = leadership.path("replacementLeaderId").intValue();
    long replacementTerm = leadership.path("replacementLeadershipTermId").longValue();
    require(
        faultTarget == initial && faultTargetTerm >= initialTerm,
        "M12 pre-kill authority does not extend the observed initial leadership");
    require(
        initial != replacement && replacementTerm > faultTargetTerm,
        "M12 replacement leadership did not advance");
    JsonNode generations = leadership.path("clientGenerations");
    for (int index = 0; index < generations.size(); index++) {
      JsonNode generation = generations.get(index);
      require(
          generation.path("clientGeneration").intValue() == index + 1,
          "M12 client generations are not contiguous");
      if (index == 0) {
        require(
            generation.path("leaderMemberId").intValue() == initial
                && generation.path("leadershipTermId").longValue() >= initialTerm,
            "M12 generation one is not bound to the initial Leader");
      } else {
        require(
            generation.path("leaderMemberId").intValue() == replacement
                && generation.path("leadershipTermId").longValue() >= replacementTerm
                && generation.path("leadershipTermId").longValue() > faultTargetTerm,
            "M12 post-failure client authority is not bound to the replacement Leader");
      }
    }
  }

  private static void verifyQuorum(JsonNode quorum, JsonNode history) {
    JsonNode minority =
        history.path("attempts").get(quorum.path("minorityAttemptOrdinal").intValue() - 1);
    JsonNode retry =
        history.path("attempts").get(quorum.path("retryAttemptOrdinal").intValue() - 1);
    require(
        minority.path("ingressAccepted").booleanValue()
                == quorum.path("minorityIngressAccepted").booleanValue()
            && requiredText(minority, "outcome").equals(requiredText(quorum, "minorityOutcome"))
            && minority.path("noQuorumWindow").booleanValue()
            && quorum.path("minorityAcknowledgements").intValue() == 0,
        "M12 quorum report differs from the minority invocation");
    require(
        requiredText(retry, "outcome").equals(requiredText(quorum, "retryOutcome"))
            && requiredText(retry, "responseStatus")
                .equals(requiredText(quorum, "retryResponseStatus"))
            && retry.path("retryOfAttemptOrdinal").intValue() == minority.path("ordinal").intValue()
            && retry.path("identityIndex").intValue() == minority.path("identityIndex").intValue(),
        "M12 quorum retry differs from the command history");
  }

  private static void verifyCatchup(JsonNode catchup, JsonNode topology) {
    int former = catchup.path("formerLeaderId").intValue();
    require(
        !catchup.path("freshStartOnReturn").booleanValue()
            && "FOLLOWER".equals(requiredText(catchup, "roleAfterReturn")),
        "M12 former Leader did not return through recovery as a Follower");
    JsonNode finalMember = membersById(topology.path("convergedMembers")).get(former);
    require(
        finalMember != null && "FOLLOWER".equals(requiredText(finalMember, "role")),
        "M12 catch-up former Leader differs from the final topology");
    long recoveredStarts =
        topology
            .path("processStarts")
            .valueStream()
            .filter(
                start ->
                    start.path("memberId").intValue() == former
                        && !start.path("freshStart").booleanValue())
            .count();
    require(recoveredStarts >= 1, "M12 catch-up member has no recovered process start");
    require(
        catchup.path("archiveMarkFileLivenessTimeoutMillis").longValue()
                == topology.path("archiveMarkFileLivenessTimeoutMillis").longValue()
            && requiredText(catchup, "restartSafetyPredicate")
                .equals(requiredText(topology, "restartSafetyPredicate")),
        "M12 catch-up restart-safety contract differs from topology");
    int witnessOrdinal = catchup.path("firstReturnRestartSafetyWitnessOrdinal").intValue();
    JsonNode topologyWitnesses = topology.path("restartSafetyWitnesses");
    require(
        witnessOrdinal >= 1
            && witnessOrdinal <= topologyWitnesses.size()
            && catchup
                .path("firstReturnRestartSafetyWitness")
                .equals(topologyWitnesses.get(witnessOrdinal - 1))
            && catchup.path("firstReturnRestartSafetyWitness").path("ordinal").intValue()
                == witnessOrdinal
            && catchup.path("firstReturnRestartSafetyWitness").path("memberId").intValue()
                == former,
        "M12 catch-up restart-safety witness differs from the former Leader return");
    require(
        hasCatchupStableProjection(catchup, topology.path("stableSnapshotWitnesses")),
        "M12 catch-up state is not bound to a stable topology witness");
  }

  private static boolean hasCatchupStableProjection(JsonNode catchup, JsonNode witnesses) {
    int former = catchup.path("formerLeaderId").intValue();
    for (JsonNode witness : witnesses) {
      JsonNode status = membersById(witness.path("secondSnapshot")).get(former);
      if (status != null
          && "FOLLOWER".equals(requiredText(status, "role"))
          && status.path("commitPosition").longValue()
              == catchup.path("catchupCommitPosition").longValue()
          && status.path("logPosition").longValue()
              == catchup.path("catchupLogPosition").longValue()
          && status.path("nextApplicationSequence").longValue()
              == catchup.path("catchupNextApplicationSequence").longValue()) {
        return true;
      }
    }
    return false;
  }

  private static void verifyStateEquivalence(JsonNode state, DirectOracle oracle) {
    String semantic = requiredSha256(state, "expectedSemanticStateDigest");
    String identity = requiredSha256(state, "expectedIdentityResultDigest");
    if (oracle != null) {
      require(
          semantic.equals(oracle.semanticStateDigest())
              && identity.equals(oracle.identityResultDigest()),
          "M12 expected final state differs from an independent direct runtime replay");
    }
    require(
        semantic.equals(requiredSha256(state, "semanticStateDigest"))
            && identity.equals(requiredSha256(state, "identityResultDigest")),
        "M12 aggregate state differs from the direct oracle");
    JsonNode members = state.path("members");
    requireExactMemberSet(members, "M12 final member set");
    long commit = state.path("commitPosition").longValue();
    long log = state.path("logPosition").longValue();
    long term = members.get(0).path("leadershipTermId").longValue();
    int warningCount = 0;
    long dropped = 0;
    for (JsonNode member : members) {
      require(
          "CLOSED".equals(requiredText(member, "electionState")),
          "M12 final member election is not CLOSED");
      require(
          semantic.equals(requiredSha256(member, "semanticStateDigest"))
              && identity.equals(requiredSha256(member, "identityResultDigest"))
              && member.path("commitPosition").longValue() == commit
              && member.path("logPosition").longValue() == log
              && member.path("leadershipTermId").longValue() == term,
          "M12 final member state is not equivalent");
      verifyDiagnostics(member, "M12 final member");
      warningCount += member.path("diagnosticWarnings").size();
      dropped += member.path("droppedDiagnosticWarnings").longValue();
    }
    require(
        members
                    .valueStream()
                    .filter(member -> "LEADER".equals(requiredText(member, "role")))
                    .count()
                == 1
            && members
                    .valueStream()
                    .filter(member -> "FOLLOWER".equals(requiredText(member, "role")))
                    .count()
                == 2,
        "M12 final state is not one Leader and two Followers");
    require(
        warningCount == state.path("diagnosticWarningCount").intValue()
            && dropped == state.path("droppedDiagnosticWarnings").longValue(),
        "M12 aggregate diagnostics do not match member diagnostics");
    require(dropped == 0, "M12 final state dropped diagnostic warnings");
  }

  private static void verifyAppliedUnknown(JsonNode members, DirectOracle first33) {
    requireExactMemberSet(members, "M12 applied-UNKNOWN member set");
    for (JsonNode member : members) {
      require(
          "CLOSED".equals(requiredText(member, "electionState"))
              && member.path("nextApplicationSequence").intValue() == 34
              && member.path("identityCount").intValue() == 33
              && requiredSha256(member, "semanticStateDigest").equals(first33.semanticStateDigest())
              && requiredSha256(member, "identityResultDigest")
                  .equals(first33.identityResultDigest()),
          "M12 applied UNKNOWN is not replicated through the direct 33-command state");
      require(
          member.path("diagnosticWarnings").isEmpty(),
          "M12 applied UNKNOWN pre-fault observation contains a warning");
      verifyDiagnostics(member, "M12 applied-UNKNOWN member");
    }
  }

  private static void verifyCoverage(
      JsonNode coverage, M12WorkloadLoader.Workload workload, DirectOracle oracle) {
    require(
        requiredSha256(coverage, "semanticDigest").equals(oracle.semanticStateDigest()),
        "M12 coverage semantic digest differs from the direct oracle");
    JsonNode facts = coverage.path("factLedger");
    JsonNode witnesses = coverage.path("witnesses");
    require(
        facts.size() == workload.coverageRequirements().size() && witnesses.size() == facts.size(),
        "M12 coverage cardinality changed");
    List<M12CoverageLedger.Fact> decoded = new ArrayList<>();
    Set<String> witnessDigests = new LinkedHashSet<>();
    for (int index = 0; index < facts.size(); index++) {
      M12CoverageLedger.Fact fact = M12CoverageLedger.Fact.read(facts.get(index));
      JsonNode witness = witnesses.get(index);
      String obligation = workload.coverageRequirements().get(index);
      M12WorkloadLoader.Scenario scenario = workload.scenariosById().get(fact.scenarioId());
      require(
          obligation.equals(fact.obligation())
              && scenario != null
              && scenario.proofObligations().contains(obligation),
          "M12 coverage fact is outside the frozen workload");
      String expectedAssertion = "M12." + fact.scenarioId() + '.' + obligation + ".V1";
      boolean systemControl = "SYSTEM_ERROR_NEVER_SEMANTIC".equals(obligation);
      require(
          expectedAssertion.equals(fact.assertionId())
              && (systemControl
                  ? "m12-system-controls.json".equals(fact.sourceArtifact())
                      && "M12CoverageLedger#assertSystemControls(SYSTEM_ERROR_NEVER_SEMANTIC)"
                          .equals(fact.producer())
                  : "m12-command-history.json".equals(fact.sourceArtifact())
                      && ("M12HistoryJudge#assertObligation(" + obligation + ')')
                          .equals(fact.producer()))
              && witnessDigests.add(fact.witnessSha256()),
          "M12 coverage fact origin binding changed");
      require(
          fact.obligation().equals(requiredText(witness, "obligation"))
              && fact.scenarioId().equals(requiredText(witness, "scenarioId"))
              && fact.assertionId().equals(requiredText(witness, "assertionId"))
              && fact.witnessSha256().equals(requiredSha256(witness, "witnessSha256")),
          "M12 coverage witness differs from its fact");
      decoded.add(fact);
    }
    require(
        requiredSha256(coverage, "ledgerSha256").equals(M12CoverageLedger.ledgerSha256(decoded)),
        "M12 coverage ledger digest does not bind its facts");
  }

  private static void verifyJudgeInspection(
      JsonNode check, Map<String, JsonNode> documents, IntegrityContext context) {
    JsonNode judge = check.path("judgeInspection");
    JsonNode history = documents.get("m12-command-history.json");
    JsonNode coverage = context.coverage();
    require(
        judge.path("realAeronChildProcesses").booleanValue()
            && judge.path("assertions").intValue() == coverage.path("factLedger").size() - 1,
        "M12 judge inspection assertion count changed");
    require(
        requiredSha256(judge, "semanticDigest").equals(context.oracle().semanticStateDigest())
            && requiredSha256(judge, "semanticDigest")
                .equals(requiredSha256(coverage, "semanticDigest")),
        "M12 judge digest is not bound to coverage and the direct oracle");
    require(
        judge.path("acknowledged").intValue() == history.path("acknowledged").intValue()
            && judge.path("unknown").intValue() == history.path("unknown").intValue()
            && judge.path("notSubmitted").intValue() == history.path("notSubmitted").intValue(),
        "M12 judge invocation counts differ from history");
    long retries =
        history
            .path("attempts")
            .valueStream()
            .filter(attempt -> !attempt.path("retryOfAttemptOrdinal").isNull())
            .count();
    long duplicates =
        history
            .path("attempts")
            .valueStream()
            .filter(
                attempt ->
                    "DUPLICATE_REPLAYED".equals(attempt.path("responseStatus").stringValue()))
            .count();
    require(
        judge.path("sameIdentityRetries").longValue() == retries
            && judge.path("duplicateReplays").longValue() == duplicates
            && requiredText(judge, "noQuorumRetryStatus")
                .equals(requiredText(history.path("attempts").get(84), "responseStatus")),
        "M12 judge retry projection differs from history");
  }

  private static void verifyCrossDocuments(
      Map<String, JsonNode> documents, DirectOracle oracle, M12DeterministicCorpus.Corpus corpus) {
    JsonNode inherited = documents.get("inherited-m11.json");
    JsonNode history = documents.get("m12-command-history.json");
    JsonNode topology = documents.get("topology.json");
    JsonNode leadership = documents.get("leadership.json");
    JsonNode quorum = documents.get("quorum.json");
    JsonNode catchup = documents.get("catchup.json");
    JsonNode state = documents.get("state-equivalence.json");
    require(
        history
            .path("appliedUnknownMembersBeforeLeaderKill")
            .equals(topology.path("appliedUnknownMembersBeforeLeaderKill")),
        "M12 applied-UNKNOWN projections differ between history and topology");
    require(
        history.path("memberProcessStarts").intValue()
                == topology.path("memberProcessStarts").intValue()
            && history.path("externalForceStops").intValue()
                == topology.path("externalForceStops").intValue(),
        "M12 process/fault counts differ between history and topology");
    require(
        topology.path("contractCorrection").path("initialLeaderId").intValue()
            == leadership.path("initialLeaderId").intValue(),
        "M12 contract correction initial Leader differs from leadership");
    require(
        leadership.path("initialLeaderId").intValue()
            == onlyLeader(topology.path("initialMembers")),
        "M12 initial topology differs from leadership");
    int replacement = leadership.path("replacementLeaderId").intValue();
    require(
        replacement == onlyLeader(topology.path("convergedMembers"))
            && replacement == onlyLeader(state.path("members")),
        "M12 final Leader differs across topology, leadership, and state");
    require(
        topology
            .path("forcedStops")
            .valueStream()
            .noneMatch(stop -> stop.path("memberId").intValue() == replacement),
        "M12 replacement Leader appears among the forced-stop targets");
    require(
        catchup.path("formerLeaderId").intValue() == leadership.path("initialLeaderId").intValue(),
        "M12 catch-up former Leader differs from leadership");
    require(
        quorum.path("restoredLeaderId").intValue() == replacement
            && quorum.path("restoredLeaderId").intValue()
                == leadership.path("clientGenerations").get(2).path("leaderMemberId").intValue()
            && quorum.path("restoredLeadershipTermId").longValue()
                == leadership.path("clientGenerations").get(2).path("leadershipTermId").longValue(),
        "M12 quorum-restored authority differs from client generation three");
    int initial = leadership.path("initialLeaderId").intValue();
    long initialTerm = leadership.path("faultTargetLeadershipTermId").longValue();
    long replacementTerm = leadership.path("replacementLeadershipTermId").longValue();
    for (JsonNode attempt : history.path("attempts")) {
      boolean beforeKill = attempt.path("ordinal").intValue() <= 42;
      require(
          attempt.path("authorityLeaderId").intValue() == (beforeKill ? initial : replacement)
              && (beforeKill
                  ? attempt.path("authorityTerm").longValue() == initialTerm
                  : attempt.path("authorityTerm").longValue() >= replacementTerm),
          "M12 invocation authority differs from the observed Leader epoch");
    }
    Map<Integer, JsonNode> applied =
        membersById(history.path("appliedUnknownMembersBeforeLeaderKill"));
    require(
        onlyLeader(history.path("appliedUnknownMembersBeforeLeaderKill")) == initial
            && applied.values().stream()
                .allMatch(member -> member.path("leadershipTermId").longValue() == initialTerm),
        "M12 applied-UNKNOWN snapshot differs from the pre-kill authority");

    Map<Integer, JsonNode> converged = membersById(topology.path("convergedMembers"));
    Map<Integer, JsonNode> finalState = membersById(state.path("members"));
    JsonNode stableWitnesses = topology.path("stableSnapshotWitnesses");
    Map<Integer, JsonNode> finalStable =
        membersById(stableWitnesses.get(stableWitnesses.size() - 1).path("secondSnapshot"));
    require(
        finalStable.keySet().equals(Set.of(0, 1, 2)),
        "M12 final stable witness does not contain all three members");
    for (int memberId : converged.keySet()) {
      JsonNode left = converged.get(memberId);
      JsonNode right = finalState.get(memberId);
      for (String field :
          List.of(
              "processId",
              "role",
              "leadershipTermId",
              "nextApplicationSequence",
              "identityCount",
              "semanticStateDigest",
              "identityResultDigest",
              "componentErrorCount")) {
        require(
            left.path(field).equals(right.path(field)),
            "M12 final topology/state mismatch at " + field);
      }
      JsonNode stable = finalStable.get(memberId);
      for (String field :
          List.of(
              "processId",
              "role",
              "electionState",
              "leadershipTermId",
              "commitPosition",
              "logPosition",
              "nextApplicationSequence",
              "identityCount",
              "semanticStateDigest",
              "identityResultDigest",
              "componentErrorCount",
              "diagnosticWarningCount",
              "droppedDiagnosticWarnings",
              "diagnosticWarnings")) {
        require(
            right.path(field).equals(stable.path(field)),
            "M12 final state/stable-witness mismatch at " + field);
      }
    }
    long finalTerm = finalState.values().iterator().next().path("leadershipTermId").longValue();
    require(
        finalTerm
                == leadership.path("clientGenerations").get(2).path("leadershipTermId").longValue()
            && finalTerm >= leadership.path("replacementLeadershipTermId").longValue(),
        "M12 final leadership term differs from client authority");
    JsonNode firstStop = topology.path("forcedStops").get(0);
    require(
        firstStop.path("memberId").intValue() == leadership.path("faultTargetLeaderId").intValue()
            && firstStop.path("processId").longValue()
                == membersById(topology.path("initialMembers"))
                    .get(leadership.path("initialLeaderId").intValue())
                    .path("processId")
                    .longValue()
            && "LEADER".equals(requiredText(firstStop, "roleBeforeStop"))
            && firstStop.path("termBeforeStop").longValue()
                == leadership.path("faultTargetLeadershipTermId").longValue(),
        "M12 first forced stop is not the observed current Leader");
    for (int index = 1; index < topology.path("forcedStops").size(); index++) {
      JsonNode stop = topology.path("forcedStops").get(index);
      require(
          "FOLLOWER".equals(requiredText(stop, "roleBeforeStop"))
              && stop.path("termBeforeStop").longValue()
                  >= leadership.path("replacementLeadershipTermId").longValue(),
          "M12 minority fault target was not a current replacement-term Follower");
    }
    require(
        requiredText(inherited, "matchingCoreTree")
            .equals(requiredText(documents.get("architecture.json"), "m11CoreTree")),
        "M12 inherited/core architecture tree binding changed");
    if (oracle != null && corpus != null) {
      verifyAppliedUnknown(
          history.path("appliedUnknownMembersBeforeLeaderKill"), DirectOracle.replay(corpus, 33));
    }
  }

  private static void verifyInherited(JsonNode inherited, JsonNode architecture) {
    require(
        requiredText(inherited, "baselineCommit")
                .equals(requiredText(architecture, "m11BaselineCommit"))
            && requiredText(inherited, "matchingCoreTree")
                .equals(requiredText(architecture, "m11CoreTree"))
            && requiredText(inherited, "matchingCoreTree")
                .equals(requiredText(architecture, "headCoreTree"))
            && requiredText(inherited, "goldensTree")
                .equals(requiredText(architecture, "m11GoldensTree"))
            && requiredText(inherited, "goldensTree")
                .equals(requiredText(architecture, "headGoldensTree")),
        "M12 inherited M11 projection differs from architecture");
  }

  private static void verifyDiagnostics(JsonNode observation, String label) {
    JsonNode warnings = observation.path("diagnosticWarnings");
    require(
        warnings.isArray()
            && warnings.size() == observation.path("diagnosticWarningCount").intValue()
            && warnings.size() <= 3,
        label + " warning count changed");
    require(
        observation.path("droppedDiagnosticWarnings").longValue() == 0,
        label + " dropped diagnostic warnings");
    Set<String> families = new LinkedHashSet<>();
    for (JsonNode warning : warnings) {
      require(
          warning.isString()
              && EXPECTED_AERON_FAIL_STOP_WARNING.matcher(warning.stringValue()).matches(),
          label + " contains an unexpected diagnostic warning");
      String value = warning.stringValue();
      String family =
          value.contains("leader heartbeat timeout")
              ? "leader-heartbeat"
              : value.contains("inactive follower quorum")
                  ? "inactive-follower"
                  : "quorum-position";
      require(families.add(family), label + " repeats an allowlisted diagnostic warning family");
    }
  }

  private static void verifyArchitecture(JsonNode architecture) {
    require(
        requiredText(architecture, "m11CoreTree").equals(requiredText(architecture, "headCoreTree"))
            && requiredText(architecture, "m11GoldensTree")
                .equals(requiredText(architecture, "headGoldensTree")),
        "M12 architecture immutable trees differ");
    require(
        !requiredText(architecture, "m11ClusteredServiceAdapterBaselineSha256")
            .equals(requiredText(architecture, "m12ClusteredServiceAdapterSha256")),
        "M12 transport adapter correction is not observable");
  }

  private static void verifyEnvironment(JsonNode environment) {
    Instant started = Instant.parse(requiredText(environment, "runStartedAt"));
    Instant finished = Instant.parse(requiredText(environment, "runFinishedAt"));
    require(!finished.isBefore(started), "M12 environment interval is inverted");
    require(
        environment.path("walFileStoreUsableSpaceBytes").longValue()
                <= environment.path("walFileStoreTotalSpaceBytes").longValue()
            && environment.path("walFileStoreUnallocatedSpaceBytes").longValue()
                <= environment.path("walFileStoreTotalSpaceBytes").longValue(),
        "M12 environment storage metrics are inconsistent");
  }

  private static boolean snapshotMatches(JsonNode projection, JsonNode witnesses) {
    for (JsonNode witness : witnesses) {
      JsonNode snapshot = witness.path("secondSnapshot");
      if (snapshot.size() != projection.size()) {
        continue;
      }
      Map<Integer, JsonNode> byId = membersById(snapshot);
      boolean matches = true;
      for (JsonNode member : projection) {
        JsonNode status = byId.get(member.path("memberId").intValue());
        if (status == null) {
          matches = false;
          break;
        }
        for (String field :
            List.of(
                "processId",
                "role",
                "leadershipTermId",
                "nextApplicationSequence",
                "identityCount",
                "semanticStateDigest",
                "identityResultDigest",
                "componentErrorCount")) {
          if (!member.path(field).equals(status.path(field))) {
            matches = false;
          }
        }
      }
      if (matches) {
        return true;
      }
    }
    return false;
  }

  private static Map<Integer, JsonNode> membersById(JsonNode members) {
    Map<Integer, JsonNode> result = new LinkedHashMap<>();
    for (JsonNode member : members) {
      require(
          result.put(member.path("memberId").intValue(), member) == null,
          "M12 member appears more than once");
    }
    return result;
  }

  private static void verifyObservedOwnership(JsonNode members, Map<Integer, Ownership> ownership) {
    for (JsonNode member : members) {
      Ownership expected = ownership.get(member.path("memberId").intValue());
      require(expected != null, "M12 observed member has no process ownership");
      require(
          member.path("portBlockBase").intValue() == expected.portBase()
              && integers(member.path("udpPorts")).equals(expected.ports())
              && normalizedAbsolutePath(member, "aeronDirectory")
                  .toString()
                  .equals(expected.aeronDirectory())
              && normalizedAbsolutePath(member, "archiveDirectory")
                  .toString()
                  .equals(expected.archiveDirectory())
              && normalizedAbsolutePath(member, "clusterDirectory")
                  .toString()
                  .equals(expected.clusterDirectory()),
          "M12 observed member ownership differs from its process configuration");
    }
  }

  private static void requireExactMemberSet(JsonNode members, String label) {
    require(
        membersById(members).keySet().equals(Set.of(0, 1, 2)),
        label + " must contain members 0, 1, and 2 exactly once");
  }

  private static int onlyLeader(JsonNode members) {
    List<Integer> leaders =
        members
            .valueStream()
            .filter(member -> "LEADER".equals(requiredText(member, "role")))
            .map(member -> member.path("memberId").intValue())
            .toList();
    require(leaders.size() == 1, "M12 member projection lacks exactly one Leader");
    return leaders.getFirst();
  }

  private static List<Integer> integers(JsonNode values) {
    List<Integer> result = new ArrayList<>();
    values.forEach(value -> result.add(value.intValue()));
    return List.copyOf(result);
  }

  private static List<String> strings(JsonNode values) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.stringValue()));
    return List.copyOf(result);
  }

  private static Integer nullableInt(JsonNode value) {
    return value.isNull() ? null : value.intValue();
  }

  private static Path normalizedAbsolutePath(JsonNode node, String field) {
    Path path = Path.of(requiredText(node, field));
    require(
        path.isAbsolute() && path.equals(path.normalize()),
        "M12 path is not absolute and normalized: " + field);
    return path;
  }

  private static String requiredText(JsonNode node, String field) {
    String value = node.path(field).stringValue();
    require(value != null && !value.isBlank(), "M12 strict report lacks " + field);
    return value;
  }

  private static String requiredSha256(JsonNode node, String field) {
    String value = requiredText(node, field);
    require(value.matches("[a-f0-9]{64}"), "M12 strict report has invalid SHA-256 " + field);
    return value;
  }

  private static UUID requiredUuid(JsonNode node, String field) {
    String value = requiredText(node, field);
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException failure) {
      throw new IllegalStateException("M12 strict report has invalid UUID " + field, failure);
    }
    require(parsed.toString().equals(value), "M12 strict report has non-canonical UUID " + field);
    return parsed;
  }

  private static byte[] canonicalCommandBytes(M12DeterministicCorpus.Corpus corpus) {
    List<byte[]> envelopes =
        corpus.identities().stream()
            .sorted(Comparator.comparingInt(M12DeterministicCorpus.DurableIdentity::index))
            .map(M12DeterministicCorpus.DurableIdentity::canonicalBytes)
            .toList();
    int size =
        envelopes.stream()
            .mapToInt(bytes -> Math.addExact(Integer.BYTES, bytes.length))
            .reduce(0, Math::addExact);
    ByteBuffer result = ByteBuffer.allocate(size);
    envelopes.forEach(bytes -> result.putInt(bytes.length).put(bytes));
    return result.array();
  }

  private static String recomputeFrozenCorpusDigest(M12DeterministicCorpus.Corpus corpus) {
    StringBuilder canonical = new StringBuilder("M12-DETERMINISTIC-CORPUS-V1\n");
    corpus
        .identities()
        .forEach(identity -> append(canonical, "identity", identity.canonicalSha256()));
    corpus
        .attempts()
        .forEach(
            attempt -> {
              append(canonical, "attempt", Integer.toString(attempt.ordinal()));
              append(canonical, "phase", attempt.phase());
              append(canonical, "identity", attempt.identity().canonicalSha256());
              append(canonical, "correlation", attempt.correlationId().toString());
              append(canonical, "outcome", attempt.outcome().name());
              append(canonical, "status", Objects.toString(attempt.responseStatus(), ""));
            });
    append(canonical, "semantic", recomputeBindingDigest(corpus.bindings()));
    return Hashing.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static String recomputeBindingDigest(List<M12DeterministicCorpus.Binding> bindings) {
    StringBuilder canonical = new StringBuilder("M12-SEMANTIC-STATE-V1\n");
    bindings.stream()
        .sorted(Comparator.comparingLong(M12DeterministicCorpus.Binding::applicationSequence))
        .forEach(
            binding -> {
              append(canonical, "identity", binding.identity().canonicalSha256());
              append(canonical, "sequence", Long.toString(binding.applicationSequence()));
              append(canonical, "result", binding.resultDigest());
              append(canonical, "effects", Integer.toString(binding.businessEffectCount()));
            });
    return Hashing.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void append(StringBuilder target, String name, String value) {
    target
        .append(name)
        .append(':')
        .append(value.getBytes(StandardCharsets.UTF_8).length)
        .append(':')
        .append(value)
        .append('\n');
  }

  private static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read strict M12 report " + path, failure);
    }
  }

  private static String readString(Path path) {
    return new String(read(path), StandardCharsets.UTF_8);
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private record Ownership(
      int portBase,
      List<Integer> ports,
      String rootDirectory,
      String aeronDirectory,
      String archiveDirectory,
      String clusterDirectory) {
    private Ownership {
      ports = List.copyOf(ports);
    }
  }

  private record DirectBinding(
      int identityIndex,
      String canonicalEnvelopeSha256,
      long applicationSequence,
      String resultDigest) {}

  private record DirectOracle(
      Map<Integer, DirectBinding> bindings,
      String semanticStateDigest,
      String identityResultDigest) {
    private DirectOracle {
      bindings = Map.copyOf(bindings);
    }

    static DirectOracle replay(M12DeterministicCorpus.Corpus corpus, int identities) {
      DirectM11MatchingRuntime runtime = new DirectM11MatchingRuntime();
      Map<Integer, DirectBinding> bindings = new LinkedHashMap<>();
      for (int index = 0; index < identities; index++) {
        M12DeterministicCorpus.DurableIdentity identity = corpus.identities().get(index);
        var response =
            runtime
                .submit(
                    M12DeterministicCorpus.requestFor(
                        identity, new UUID(0x4d31322d53545249L, index + 1L)))
                .response();
        require(
            response.status() == M11ResponseStatus.NEW_APPLIED
                && response.applicationSequence().isPresent()
                && response.resultDigest().isPresent(),
            "M12 direct oracle did not apply a frozen identity");
        bindings.put(
            identity.index(),
            new DirectBinding(
                identity.index(),
                identity.canonicalSha256(),
                response.applicationSequence().orElseThrow(),
                response.resultDigest().orElseThrow()));
      }
      String identityDigest =
          new M11RuntimeStateCodec().identityTableDigest(runtime.stateImage().identityBindings());
      return new DirectOracle(bindings, runtime.semanticStateDigest(), identityDigest);
    }
  }

  private record IntegrityContext(
      M12WorkloadLoader.Workload workload,
      M12DeterministicCorpus.Corpus corpus,
      DirectOracle oracle,
      JsonNode corpusReport,
      JsonNode coverage,
      byte[] canonicalCommands) {
    static IntegrityContext load(Path schemaRoot, Path reports) {
      M12WorkloadLoader.Workload workload =
          Files.isRegularFile(
                  schemaRoot.resolve(M12StartCheckRunner.WORKLOAD_PATH), LinkOption.NOFOLLOW_LINKS)
              ? M12WorkloadLoader.load(schemaRoot)
              : M12WorkloadLoader.loadEvidenceRoot(schemaRoot);
      M12DeterministicCorpus.Corpus corpus = M12DeterministicCorpus.generate(workload);
      return new IntegrityContext(
          workload,
          corpus,
          DirectOracle.replay(corpus, 66),
          JsonSupport.parse(read(reports.resolve("corpus.json"))),
          JsonSupport.parse(read(reports.resolve("coverage.json"))),
          read(reports.resolve("commands.canonical.bin")));
    }

    private IntegrityContext {
      corpusReport = corpusReport.deepCopy();
      coverage = coverage.deepCopy();
      canonicalCommands = canonicalCommands.clone();
    }

    @Override
    public JsonNode corpusReport() {
      return corpusReport.deepCopy();
    }

    @Override
    public JsonNode coverage() {
      return coverage.deepCopy();
    }

    @Override
    public byte[] canonicalCommands() {
      return canonicalCommands.clone();
    }
  }
}

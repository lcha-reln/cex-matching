package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Strict loader for the content-addressed M12 workload contract. */
final class M12WorkloadLoader {
  M12WorkloadLoader() {}

  static Workload load(Path repositoryRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    return load(
        root.resolve(M12StartCheckRunner.WORKLOAD_PATH),
        root.resolve(M12StartCheckRunner.WORKLOAD_SCHEMA_PATH));
  }

  static Workload loadEvidenceRoot(Path evidenceRoot) {
    Path root = evidenceRoot.toAbsolutePath().normalize();
    return load(
        root.resolve("inputs/workload-v1.json"),
        root.resolve("schemas")
            .resolve(Path.of(M12StartCheckRunner.WORKLOAD_SCHEMA_PATH).getFileName()));
  }

  private static Workload load(Path workloadPath, Path schemaPath) {
    byte[] bytes = read(workloadPath);
    String digest = Hashing.sha256Hex(bytes);
    require(
        M12StartCheckRunner.WORKLOAD_SHA256.equals(digest),
        "M12 workload bytes do not match the frozen SHA-256");

    JsonNode document = JsonSupport.parse(bytes);
    JsonSupport.validate(document, new String(read(schemaPath), StandardCharsets.UTF_8), false);

    require("matching.m12.workload.v1".equals(text(document, "schemaVersion")), "schema version");
    require("M12".equals(text(document, "unit")), "unit");
    require("0.15".equals(text(document, "contractPlanVersion")), "plan version");

    JsonNode baseline = document.path("dependencyBaseline");
    require("course/m11-complete".equals(text(baseline, "sourceRef")), "baseline ref");
    require(
        M12StartCheckRunner.BASELINE_COMMIT.equals(text(baseline, "sourceCommit")),
        "baseline commit");
    require("1.52.2".equals(text(baseline, "aeronVersion")), "Aeron version");
    require("2.5.0".equals(text(baseline, "agronaVersion")), "Agrona version");
    require(integer(baseline, "javaRelease") == 25, "Java release");
    require(integer(baseline, "m11GoldenCount") == 6, "M11 Golden count");

    JsonNode outcome = document.path("clientOutcomeModel");
    require("NOT_SUBMITTED".equals(text(outcome, "beforeIngressAcceptance")), "pre-offer outcome");
    require(
        "UNKNOWN".equals(text(outcome, "afterIngressAcceptanceWithoutTrustedResponse")),
        "unknown outcome");
    require("ACKNOWLEDGED".equals(text(outcome, "correlatedCurrentLeaderResponse")), "ack outcome");
    require(
        "SAME_DURABLE_COMMAND_IDENTITY_FRESH_CORRELATION".equals(text(outcome, "unknownRecovery")),
        "unknown retry rule");
    require(
        strings(outcome.path("allowedConvergedResponseStatuses"))
            .equals(List.of("NEW_APPLIED", "DUPLICATE_REPLAYED")),
        "converged response statuses");
    require(!bool(outcome, "timeoutIsBusinessRejection"), "timeout cannot be a rejection");

    JsonNode cluster = document.path("realClusterProfile");
    require(integer(cluster, "memberCount") == 3, "member count");
    require(integers(cluster.path("staticMemberIds")).equals(List.of(0, 1, 2)), "member IDs");
    require(integer(cluster, "appointedInitialLeaderId") == 0, "appointed leader");
    require(integer(cluster, "clusterId") == 12, "cluster ID");
    require("LOCALHOST_REAL_AERON_CHILD_PROCESSES".equals(text(cluster, "transport")), "transport");
    require(bool(cluster, "processIsolationClaim"), "process isolation claim");
    require(
        "OUTSIDE_CLUSTERED_SERVICE".equals(text(cluster, "faultController")), "fault controller");
    require(!bool(cluster, "externalServices"), "external services");
    require(!bool(cluster, "dockerRequired"), "Docker requirement");
    require(integer(cluster, "portsPerMember") == 5, "ports per member");
    require(integer(cluster, "maxWorkers") == 1, "max workers");

    JsonNode schedule = document.path("faultSchedule");
    require("splitmix64-v1".equals(text(schedule, "algorithm")), "generator algorithm");
    require("6120".equals(text(schedule, "seed")), "generator seed");
    require(
        strings(schedule.path("phaseOrder")).equals(M12StartCheckRunner.PHASE_ORDER),
        "phase order");
    require(integer(schedule, "preFailoverNew") == 32, "pre-failover count");
    require(integer(schedule, "acknowledgedDuplicateRetries") == 8, "pre retry count");
    require(integer(schedule, "appliedResponseUnobservedUnknown") == 1, "unknown count");
    require(integer(schedule, "postFailoverNew") == 32, "post-failover count");
    require(integer(schedule, "postFailoverDuplicateRetries") == 8, "post retry count");
    require(integer(schedule, "noQuorumUnknown") == 1, "no-quorum count");
    require(integer(schedule, "distinctBusinessCommands") == 66, "identity count");
    require(integer(schedule, "minimumIngressAttempts") == 84, "ingress attempt count");
    require(integer(schedule, "expectedFinalNextApplicationSequence") == 67, "next sequence");

    List<Scenario> scenarios = new ArrayList<>();
    Set<String> witnessed = new LinkedHashSet<>();
    for (JsonNode scenario : document.path("fixedScenarios")) {
      Scenario parsed =
          new Scenario(text(scenario, "id"), strings(scenario.path("proofObligations")));
      scenarios.add(parsed);
      for (String obligation : parsed.proofObligations()) {
        require(
            M12StartCheckRunner.COVERAGE_IDS.contains(obligation),
            "scenario has unknown obligation " + obligation);
        witnessed.add(obligation);
      }
    }
    require(
        scenarios.stream().map(Scenario::id).toList().equals(M12StartCheckRunner.SCENARIO_IDS),
        "scenario order");
    require(
        witnessed.equals(new LinkedHashSet<>(M12StartCheckRunner.COVERAGE_IDS)),
        "scenario obligation union");
    require(
        strings(document.path("coverageRequirements")).equals(M12StartCheckRunner.COVERAGE_IDS),
        "coverage order");
    require(
        strings(document.path("requiredMutants")).equals(M12StartCheckRunner.MUTANT_IDS),
        "mutant order");
    require(
        strings(document.path("systemErrorControls")).equals(M12StartCheckRunner.SYSTEM_ERROR_IDS),
        "system-control order");
    require(
        strings(document.path("tutorialPermalinks"))
            .equals(M12StartCheckRunner.TUTORIAL_PERMALINKS),
        "tutorial permalink order");
    require(document.path("exclusions").size() == 9, "exclusion count");
    require(
        "course/m12-complete".equals(text(document.path("releaseTarget"), "unitTag")),
        "completion tag");
    require(
        "matching-0.8.0".equals(text(document.path("releaseTarget"), "productRelease")),
        "product tag");

    Map<String, Scenario> byId = new LinkedHashMap<>();
    scenarios.forEach(
        scenario -> require(byId.put(scenario.id(), scenario) == null, "duplicate scenario"));
    return new Workload(
        digest,
        document.deepCopy(),
        List.copyOf(scenarios),
        Map.copyOf(byId),
        M12StartCheckRunner.PHASE_ORDER,
        M12StartCheckRunner.COVERAGE_IDS,
        M12StartCheckRunner.MUTANT_IDS,
        M12StartCheckRunner.SYSTEM_ERROR_IDS);
  }

  private static byte[] read(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(node -> values.add(node.stringValue()));
    return List.copyOf(values);
  }

  private static List<Integer> integers(JsonNode array) {
    List<Integer> values = new ArrayList<>();
    array.forEach(node -> values.add(node.intValue()));
    return List.copyOf(values);
  }

  private static String text(JsonNode object, String field) {
    JsonNode value = object.path(field);
    require(value.isString() && !value.stringValue().isBlank(), "missing string " + field);
    return value.stringValue();
  }

  private static int integer(JsonNode object, String field) {
    JsonNode value = object.path(field);
    require(value.isIntegralNumber(), "missing integer " + field);
    return value.intValue();
  }

  private static boolean bool(JsonNode object, String field) {
    JsonNode value = object.path(field);
    require(value.isBoolean(), "missing boolean " + field);
    return value.booleanValue();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException("invalid frozen M12 workload: " + message);
    }
  }

  record Scenario(String id, List<String> proofObligations) {
    Scenario {
      proofObligations = List.copyOf(proofObligations);
    }
  }

  record Workload(
      String sha256,
      JsonNode document,
      List<Scenario> scenarios,
      Map<String, Scenario> scenariosById,
      List<String> phaseOrder,
      List<String> coverageRequirements,
      List<String> requiredMutants,
      List<String> systemErrorControls) {
    Workload {
      document = document.deepCopy();
      scenarios = List.copyOf(scenarios);
      scenariosById = Map.copyOf(scenariosById);
      phaseOrder = List.copyOf(phaseOrder);
      coverageRequirements = List.copyOf(coverageRequirements);
      requiredMutants = List.copyOf(requiredMutants);
      systemErrorControls = List.copyOf(systemErrorControls);
    }

    @Override
    public JsonNode document() {
      return document.deepCopy();
    }
  }
}

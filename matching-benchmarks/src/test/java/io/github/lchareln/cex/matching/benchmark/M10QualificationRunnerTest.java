package io.github.lchareln.cex.matching.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class M10QualificationRunnerTest {
  @Test
  void freezesTheRawAdmissionAndCompletionTimeContract() {
    ObjectNode contract = JsonMapper.builder().build().createObjectNode();

    M10QualificationRunner.writeRawTimeContract(contract);

    assertEquals(4, contract.size());
    assertEquals("admissionDecisionNanos", contract.path("admissionTimestamp").stringValue());
    assertEquals(
        "ADMISSION_GATE_DECISION", contract.path("admissionObservationKind").stringValue());
    assertEquals("ownerCompletedNanos", contract.path("completionTimestamp").stringValue());
    assertEquals("OWNER_COMPLETED_UNDER_GATE", contract.path("completionTimeOrigin").stringValue());
  }
}

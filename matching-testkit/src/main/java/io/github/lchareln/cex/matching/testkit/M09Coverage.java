package io.github.lchareln.cex.matching.testkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.node.ArrayNode;

/** Issues one witness only after an executable assertion for a frozen obligation passes. */
final class M09Coverage {
  private final List<String> required;
  private final Map<String, Witness> witnesses = new LinkedHashMap<>();

  M09Coverage(List<String> required) {
    this.required = List.copyOf(required);
  }

  void witnessed(String obligation, String scenario, String assertion) {
    systemRequire(required.contains(obligation), "unknown M09 obligation " + obligation);
    systemRequire(!scenario.isBlank() && !assertion.isBlank(), "blank M09 obligation witness");
    witnesses.putIfAbsent(obligation, new Witness(scenario, assertion));
  }

  void requireComplete() {
    List<String> missing = required.stream().filter(id -> !witnesses.containsKey(id)).toList();
    if (!missing.isEmpty()) {
      throw new M09SemanticFailure("M09 coverage obligations not witnessed: " + missing);
    }
  }

  int observed() {
    return witnesses.size();
  }

  ArrayNode report() {
    ArrayNode result = JsonSupport.MAPPER.createArrayNode();
    for (String id : required) {
      Witness witness = witnesses.get(id);
      var node = result.addObject();
      node.put("id", id);
      node.put("hit", witness != null);
      if (witness != null) {
        node.put("scenario", witness.scenario());
        node.put("assertion", witness.assertion());
      }
    }
    return result;
  }

  private static void systemRequire(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private record Witness(String scenario, String assertion) {}
}

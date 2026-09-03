package io.github.lchareln.cex.matching.testkit;

import java.nio.file.Path;
import java.util.Arrays;
import tools.jackson.databind.node.ObjectNode;

/** Executes the inherited M10 semantic and method suites against current compiled classes. */
final class M11InheritedM10Regression {
  ObjectNode run(Path repositoryRoot, Path workingRoot) {
    M10MethodSuite.Result method = new M10MethodSuite().run();
    M10FixedSuite.Result fixed = new M10FixedSuite().run(workingRoot.resolve("fixed"), method);
    M10GeneratedSuite.Result generated = new M10GeneratedSuite().generate();
    M10GeneratedSuite.Result regenerated = new M10GeneratedSuite().generate();
    M10MutantSuite.Result mutants = new M10MutantSuite().run();
    require(fixed.passed() == 20, "inherited M10 fixed suite changed");
    require(generated.actions() == 16_384, "inherited M10 generated action count changed");
    require(
        Arrays.equals(generated.canonicalBytes(), regenerated.canonicalBytes()),
        "inherited M10 generation is not byte-exact");
    require(mutants.killed() == 12, "inherited M10 mutant suite changed");
    require(method.scheduledArrivals() > 0 && method.completionSamples() > 0, "M10 method smoke is empty");

    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    result.put("schemaVersion", "matching.m11.inherited-m10.v1");
    result.put("unit", "M10");
    result.put("completeRef", "course/m10-complete");
    result.put("productRelease", "matching-0.5.0");
    result.put("status", M11CheckRunner.PASS);
    result.put("fixedScenarios", fixed.passed());
    result.put("generatedActions", generated.actions());
    result.put("byteExactRegeneration", true);
    result.put("mutantsKilled", mutants.killed());
    result.put("methodSmoke", M11CheckRunner.PASS);
    result.put("currentCompiledClasses", true);
    result.put("baselineCommit", git(repositoryRoot, "rev-parse", "course/m10-complete^{}").strip());
    return result;
  }

  private static String git(Path root, String... arguments) {
    try {
      java.util.List<String> command = new java.util.ArrayList<>();
      command.add("git");
      command.addAll(java.util.List.of(arguments));
      Process process = new ProcessBuilder(command).directory(root.toFile()).start();
      String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      String error = new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      int exit = process.waitFor();
      if (exit != 0) {
        throw new IllegalStateException("git failed: " + error.strip());
      }
      return output;
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("cannot execute git", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git interrupted", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M11SemanticFailure(message);
    }
  }
}

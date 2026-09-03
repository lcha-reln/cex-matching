package io.github.lchareln.cex.matching.testkit;

import io.github.lchareln.cex.matching.cluster.DirectM11MatchingRuntime;
import io.github.lchareln.cex.matching.cluster.M11ApplicationResult;
import io.github.lchareln.cex.matching.cluster.M11ClusteredMatchingService;
import io.github.lchareln.cex.matching.cluster.M11CommandRequest;
import io.github.lchareln.cex.matching.cluster.M11RequestCodec;
import io.github.lchareln.cex.matching.cluster.M11RuntimeState;
import io.github.lchareln.cex.matching.cluster.M11ServiceObservation;
import io.github.lchareln.cex.matching.local.M08Command;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Source-graph and runtime-spy facts for the production ClusteredService callback boundary. */
final class M11ProductionArchitectureProbe {
  private static final String CLUSTER_MAIN = "matching-cluster-runtime/src/main/java/";
  private static final List<String> WAL_TOKENS =
      List.of(
          "LocalMatchingRuntime",
          "LocalMatchingService",
          "SegmentedWal",
          "SnapshotStore",
          "WalConfig");
  private static final List<String> EXTERNAL_IO_TOKENS =
      List.of(
          "java.net.http",
          "java.sql",
          "javax.sql",
          "Files.",
          "FileChannel",
          "Socket",
          "HttpClient");
  private static final List<String> STATE_OWNER_CLASSES =
      List.of(
          "DirectM11MatchingRuntime",
          "M11IdentityTable",
          "M11RuntimeState",
          "M11RuntimeStateCodec",
          "M11CommandStateCodec",
          "M11Digests");
  private static final List<String> EGRESS_METADATA_TOKENS =
      List.of(
          "ClientSession",
          "Publication",
          "clusterSessionId",
          "clusterTimestamp",
          "clusterLogPosition");

  Facts run(Path repositoryRoot) {
    Path root = repositoryRoot.toAbsolutePath().normalize();
    Map<String, SourceUnit> sources = productionSources(root);
    Set<String> reachable = callbackReachable(sources);
    require(reachable.contains("DirectM11MatchingRuntime"), "callback graph misses runtime");
    require(reachable.contains("M11AeronSnapshotTransport"), "callback graph misses snapshots");

    int walViolations = 0;
    int externalIoViolations = 0;
    int callbackModuleViolations = 0;
    for (String className : reachable) {
      SourceUnit unit = sources.get(className);
      walViolations += countTokens(unit.source(), WAL_TOKENS);
      externalIoViolations += countTokens(unit.source(), EXTERNAL_IO_TOKENS);
      callbackModuleViolations += disallowedProjectImports(unit.source()).size();
    }

    String serviceSource = sources.get("M11ClusteredMatchingService").source();
    String logCallback = methodBody(serviceSource, "public void onSessionMessage(");
    int totalBusinessApplyCalls = occurrences(serviceSource, "runtime.submit(");
    int logCallbackBusinessApplyCalls = occurrences(logCallback, "runtime.submit(");
    int nonLogCallbackBusinessApplyCalls = totalBusinessApplyCalls - logCallbackBusinessApplyCalls;

    int egressStateInputViolations = 0;
    for (String className : STATE_OWNER_CLASSES) {
      SourceUnit source = sources.get(className);
      require(source != null, "missing business-state owner " + className);
      egressStateInputViolations += countTokens(source.source(), EGRESS_METADATA_TOKENS);
    }

    CallbackFacts callbacks = callbackFacts();
    VersionConfiguration versions = versionConfiguration(root);
    RuntimeMetadataSpy metadata = runtimeMetadataSpy();
    return new Facts(
        List.copyOf(reachable),
        walViolations,
        externalIoViolations,
        callbackModuleViolations,
        totalBusinessApplyCalls,
        logCallbackBusinessApplyCalls,
        nonLogCallbackBusinessApplyCalls,
        egressStateInputViolations,
        callbacks.interfaceName(),
        callbacks.abstractCallbacks(),
        callbacks.implementedCallbacks(),
        versions.aeron(),
        versions.agrona(),
        versions.exact(),
        metadata.variants(),
        metadata.digestStable());
  }

  private static Map<String, SourceUnit> productionSources(Path root) {
    Path sourceRoot = root.resolve(CLUSTER_MAIN);
    Map<String, SourceUnit> result = new LinkedHashMap<>();
    try (var paths = Files.walk(sourceRoot)) {
      for (Path path :
          paths.filter(value -> value.toString().endsWith(".java")).sorted().toList()) {
        String file = path.getFileName().toString();
        String className = file.substring(0, file.length() - ".java".length());
        SourceUnit previous =
            result.put(
                className, new SourceUnit(portable(root.relativize(path)), Files.readString(path)));
        require(previous == null, "duplicate production class name " + className);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read Cluster production sources", failure);
    }
    return Map.copyOf(result);
  }

  private static Set<String> callbackReachable(Map<String, SourceUnit> sources) {
    Set<String> reached = new LinkedHashSet<>();
    ArrayDeque<String> pending = new ArrayDeque<>();
    pending.add("M11ClusteredMatchingService");
    while (!pending.isEmpty()) {
      String current = pending.removeFirst();
      if (!reached.add(current)) {
        continue;
      }
      SourceUnit unit = sources.get(current);
      require(unit != null, "missing callback-reachable source " + current);
      for (String candidate : sources.keySet()) {
        if (!reached.contains(candidate)
            && Pattern.compile("\\b" + Pattern.quote(candidate) + "\\b")
                .matcher(unit.source())
                .find()) {
          pending.add(candidate);
        }
      }
    }
    return new LinkedHashSet<>(reached.stream().sorted().toList());
  }

  private static List<String> disallowedProjectImports(String source) {
    List<String> violations = new ArrayList<>();
    for (String imported : imports(source)) {
      if (!imported.startsWith("io.github.lchareln.cex.matching.")) {
        continue;
      }
      String suffix = imported.substring("io.github.lchareln.cex.matching.".length());
      boolean topLevelCoreType = !suffix.contains(".");
      boolean allowed =
          topLevelCoreType || suffix.startsWith("cluster.") || suffix.startsWith("local.");
      if (!allowed) {
        violations.add(imported);
      }
    }
    return List.copyOf(violations);
  }

  private static CallbackFacts callbackFacts() {
    try {
      Class<?> callbackInterface = Class.forName("io.aeron.cluster.service.ClusteredService");
      int abstractCallbacks =
          (int)
              Arrays.stream(callbackInterface.getDeclaredMethods())
                  .filter(method -> java.lang.reflect.Modifier.isAbstract(method.getModifiers()))
                  .count();
      Set<String> implementedNames = new LinkedHashSet<>();
      Arrays.stream(M11ClusteredMatchingService.class.getDeclaredMethods())
          .map(java.lang.reflect.Method::getName)
          .forEach(implementedNames::add);
      int implementedCallbacks =
          (int)
              Arrays.stream(callbackInterface.getDeclaredMethods())
                  .filter(method -> java.lang.reflect.Modifier.isAbstract(method.getModifiers()))
                  .filter(method -> implementedNames.contains(method.getName()))
                  .count();
      return new CallbackFacts(
          callbackInterface.getName(), abstractCallbacks, implementedCallbacks);
    } catch (ClassNotFoundException failure) {
      throw new IllegalStateException("Aeron ClusteredService is absent from the runtime", failure);
    }
  }

  private static VersionConfiguration versionConfiguration(Path root) {
    String catalog = read(root.resolve("gradle/libs.versions.toml"));
    String module = read(root.resolve("matching-cluster-runtime/build.gradle.kts"));
    String aeron = tomlVersion(catalog, "aeron");
    String agrona = tomlVersion(catalog, "agrona");
    boolean exact =
        "1.52.2".equals(aeron)
            && "2.5.0".equals(agrona)
            && catalog.contains(
                "aeron-cluster = { module = \"io.aeron:aeron-cluster\", version.ref = \"aeron\" }")
            && catalog.contains(
                "agrona = { module = \"org.agrona:agrona\", version.ref = \"agrona\" }")
            && module.contains("implementation(libs.aeron.cluster)")
            && module.contains("implementation(libs.agrona)");
    return new VersionConfiguration(aeron, agrona, exact);
  }

  private static String tomlVersion(String catalog, String key) {
    java.util.regex.Matcher matcher =
        Pattern.compile("(?m)^" + Pattern.quote(key) + "\\s*=\\s*\"([^\"]+)\"\\s*$")
            .matcher(catalog);
    return matcher.find() ? matcher.group(1) : "MISSING";
  }

  private static RuntimeMetadataSpy runtimeMetadataSpy() {
    try {
      M11CommandRequest request =
          new M11RequestCodec()
              .create(
                  2,
                  2,
                  new UUID(1, 1),
                  "m11-architecture-spy",
                  1,
                  1,
                  1,
                  new UUID(2, 2),
                  new M08Command.Place(
                      "BTC-USDT",
                      BigInteger.ONE,
                      "BUY",
                      BigInteger.valueOf(100),
                      BigInteger.ONE,
                      "GTC",
                      0,
                      "NONE",
                      Optional.empty()));
      M11ApplicationResult application = new DirectM11MatchingRuntime().submit(request);
      M11ServiceObservation first = new M11ServiceObservation(11, 17, 23, application);
      M11ServiceObservation second = new M11ServiceObservation(29, 31, 37, application);
      boolean stateContainsObservation =
          Arrays.stream(M11RuntimeState.class.getRecordComponents())
              .anyMatch(component -> component.getType() == M11ServiceObservation.class);
      boolean stable =
          !stateContainsObservation
              && first
                  .applicationResult()
                  .response()
                  .semanticStateDigest()
                  .equals(second.applicationResult().response().semanticStateDigest());
      return new RuntimeMetadataSpy(2, stable);
    } catch (io.github.lchareln.cex.matching.cluster.M11ProtocolException failure) {
      throw new IllegalStateException("cannot execute runtime metadata spy", failure);
    }
  }

  private static String methodBody(String source, String declaration) {
    int declarationIndex = source.indexOf(declaration);
    require(declarationIndex >= 0, "missing callback declaration " + declaration);
    int open = source.indexOf('{', declarationIndex);
    require(open >= 0, "missing callback body " + declaration);
    int depth = 0;
    for (int index = open; index < source.length(); index++) {
      char value = source.charAt(index);
      if (value == '{') {
        depth++;
      } else if (value == '}' && --depth == 0) {
        return source.substring(open + 1, index);
      }
    }
    throw new M11SemanticFailure("unterminated callback body " + declaration);
  }

  private static int occurrences(String source, String token) {
    int count = 0;
    int from = 0;
    while (true) {
      int index = source.indexOf(token, from);
      if (index < 0) {
        return count;
      }
      count++;
      from = index + token.length();
    }
  }

  private static List<String> imports(String source) {
    return source
        .lines()
        .map(String::strip)
        .filter(line -> line.startsWith("import ") && line.endsWith(";"))
        .map(line -> line.substring("import ".length(), line.length() - 1))
        .toList();
  }

  private static int countTokens(String source, List<String> tokens) {
    int count = 0;
    for (String token : tokens) {
      if (source.contains(token)) {
        count++;
      }
    }
    return count;
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException failure) {
      throw new IllegalStateException("cannot read " + path, failure);
    }
  }

  private static String portable(Path path) {
    return path.toString().replace(path.getFileSystem().getSeparator(), "/");
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new M11SemanticFailure(message);
    }
  }

  record Facts(
      List<String> callbackReachableProductionSources,
      int walViolations,
      int externalIoViolations,
      int callbackModuleViolations,
      int businessApplyCalls,
      int logCallbackBusinessApplyCalls,
      int nonLogCallbackBusinessApplyCalls,
      int egressStateInputViolations,
      String callbackInterface,
      int abstractProductionCallbacks,
      int implementedProductionCallbacks,
      String configuredAeronVersion,
      String configuredAgronaVersion,
      boolean versionConfigurationExact,
      int runtimeMetadataVariants,
      boolean runtimeMetadataDigestStable) {
    Facts {
      callbackReachableProductionSources = List.copyOf(callbackReachableProductionSources);
    }
  }

  private record SourceUnit(String path, String source) {}

  private record CallbackFacts(
      String interfaceName, int abstractCallbacks, int implementedCallbacks) {}

  private record VersionConfiguration(String aeron, String agrona, boolean exact) {}

  private record RuntimeMetadataSpy(int variants, boolean digestStable) {}
}

package io.github.lchareln.cex.matching.benchmark;

import io.github.lchareln.cex.matching.SingleInstrumentMatchingEngine;
import io.github.lchareln.cex.matching.local.LocalMatchingService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipFile;

/** Binds a qualification run to the checked-out source and loaded production class files. */
public record RuntimeProvenance(
    String repositoryHead,
    boolean repositoryDirty,
    String matchingBenchmarkClassesSha256,
    String matchingLocalRuntimeClassesSha256,
    String matchingCoreClassesSha256,
    String combinedRuntimeClassesSha256) {
  public RuntimeProvenance {
    requireSha(repositoryHead, 40, "repositoryHead");
    requireSha(matchingBenchmarkClassesSha256, 64, "matchingBenchmarkClassesSha256");
    requireSha(matchingLocalRuntimeClassesSha256, 64, "matchingLocalRuntimeClassesSha256");
    requireSha(matchingCoreClassesSha256, 64, "matchingCoreClassesSha256");
    requireSha(combinedRuntimeClassesSha256, 64, "combinedRuntimeClassesSha256");
  }

  static RuntimeProvenance capture(
      Path repositoryRoot, String claimedSourceCommit, boolean releaseEligible) throws IOException {
    Path root =
        Objects.requireNonNull(repositoryRoot, "repositoryRoot").toAbsolutePath().normalize();
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("repository root is not a real directory: " + root);
    }
    String head = git(root, "rev-parse", "HEAD").strip();
    if (!head.equals(claimedSourceCommit)) {
      throw new IOException(
          "--source-commit does not equal the checked-out HEAD: "
              + claimedSourceCommit
              + " != "
              + head);
    }
    boolean dirty = !git(root, "status", "--porcelain=v1", "--untracked-files=all").isBlank();
    if (releaseEligible && dirty) {
      throw new IOException("release qualification requires a clean Git working tree");
    }

    String benchmark = hashCodeSource(CoreMatchingBenchmark.class);
    String localRuntime = hashCodeSource(LocalMatchingService.class);
    String core = hashCodeSource(SingleInstrumentMatchingEngine.class);
    String combined =
        hashNamedValues(
            Map.of(
                "matchingBenchmarkClassesSha256", benchmark,
                "matchingCoreClassesSha256", core,
                "matchingLocalRuntimeClassesSha256", localRuntime));
    return new RuntimeProvenance(head, dirty, benchmark, localRuntime, core, combined);
  }

  private static String git(Path root, String... arguments) throws IOException {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.add("-C");
    command.add(root.toString());
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (InputStream input = process.getInputStream()) {
      input.transferTo(output);
    }
    final int exitCode;
    try {
      exitCode = process.waitFor();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while inspecting Git provenance", interrupted);
    }
    String text = output.toString(StandardCharsets.UTF_8);
    if (exitCode != 0) {
      throw new IOException("Git provenance command failed: " + text.strip());
    }
    return text;
  }

  private static String hashCodeSource(Class<?> anchor) throws IOException {
    final Path source;
    try {
      source =
          Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI())
              .toAbsolutePath()
              .normalize();
    } catch (URISyntaxException | NullPointerException failure) {
      throw new IOException("cannot locate loaded classes for " + anchor.getName(), failure);
    }
    if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
      return hashClassDirectory(source);
    }
    if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
      return hashClassArchive(source);
    }
    throw new IOException("loaded class source is not a real directory or archive: " + source);
  }

  private static String hashClassDirectory(Path root) throws IOException {
    List<Path> classes;
    try (var paths = Files.walk(root)) {
      classes =
          paths
              .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
              .filter(path -> path.getFileName().toString().endsWith(".class"))
              .sorted(Comparator.comparing(path -> relative(root, path)))
              .toList();
    }
    if (classes.isEmpty()) {
      throw new IOException("loaded class directory contains no class files: " + root);
    }
    MessageDigest digest = sha256();
    for (Path file : classes) {
      updateEntry(digest, relative(root, file), Files.readAllBytes(file));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String hashClassArchive(Path archive) throws IOException {
    MessageDigest digest = sha256();
    int classes = 0;
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      List<String> entries =
          zip.stream()
              .map(java.util.zip.ZipEntry::getName)
              .filter(name -> name.endsWith(".class"))
              .sorted()
              .toList();
      for (String name : entries) {
        try (InputStream input = zip.getInputStream(zip.getEntry(name))) {
          updateEntry(digest, name, input.readAllBytes());
        }
        classes++;
      }
    }
    if (classes == 0) {
      throw new IOException("loaded class archive contains no class files: " + archive);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String hashNamedValues(Map<String, String> values) {
    MessageDigest digest = sha256();
    values.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry ->
                updateEntry(
                    digest, entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8)));
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void updateEntry(MessageDigest digest, String name, byte[] bytes) {
    digest.update(name.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
    digest.update(bytes);
    digest.update((byte) 0);
  }

  private static String relative(Path root, Path file) {
    return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static void requireSha(String value, int length, String name) {
    Objects.requireNonNull(value, name);
    if (!value.matches("[0-9a-f]{" + length + "}")) {
      throw new IllegalArgumentException(name + " must be a lowercase hexadecimal digest");
    }
  }
}

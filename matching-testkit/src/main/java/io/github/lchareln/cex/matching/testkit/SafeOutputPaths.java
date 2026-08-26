package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Rejects output paths that could follow a pre-existing symbolic-link component. */
final class SafeOutputPaths {
  private SafeOutputPaths() {}

  static Path resolveTrustedOutput(Path trustedAnchor, Path target) {
    Path normalizedAnchor = trustedAnchor.toAbsolutePath().normalize();
    Path normalizedTarget = target.toAbsolutePath().normalize();
    require(
        normalizedTarget.startsWith(normalizedAnchor), "output path escapes its trusted anchor");
    require(Files.exists(normalizedAnchor, LinkOption.NOFOLLOW_LINKS), "trusted anchor is missing");
    require(!Files.isSymbolicLink(normalizedAnchor), "trusted anchor must not be a symlink");
    require(
        Files.isDirectory(normalizedAnchor, LinkOption.NOFOLLOW_LINKS),
        "trusted anchor must be a directory");
    requireNoSymlinkComponents(normalizedAnchor, normalizedTarget);
    try {
      Path realAnchor = normalizedAnchor.toRealPath();
      Path resolved = realAnchor.resolve(normalizedAnchor.relativize(normalizedTarget)).normalize();
      requireNoSymlinkComponents(realAnchor, resolved);
      return resolved;
    } catch (IOException exception) {
      throw new IllegalStateException("cannot resolve trusted output path", exception);
    }
  }

  static void requireNoSymlinkComponents(Path anchor, Path target) {
    Path normalizedAnchor = anchor.toAbsolutePath().normalize();
    Path normalizedTarget = target.toAbsolutePath().normalize();
    require(normalizedTarget.startsWith(normalizedAnchor), "path escapes its trusted anchor");
    Path current = normalizedAnchor;
    require(!Files.isSymbolicLink(current), "trusted path anchor must not be a symlink");
    for (Path component : normalizedAnchor.relativize(normalizedTarget)) {
      current = current.resolve(component);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        require(!Files.isSymbolicLink(current), "symlink path component is forbidden: " + current);
      }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }
}

package io.github.lchareln.cex.matching.testkit;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class AtomicFiles {
  private AtomicFiles() {}

  static void write(Path target, byte[] bytes) {
    try {
      Files.createDirectories(target.getParent());
      Path temporary =
          Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
      try {
        Files.write(temporary, bytes);
        move(temporary, target);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("cannot atomically write " + target, exception);
    }
  }

  private static void move(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}

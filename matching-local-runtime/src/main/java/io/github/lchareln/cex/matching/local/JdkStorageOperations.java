package io.github.lchareln.cex.matching.local;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Production JDK implementation; ordinary runtime opens are permanently wired to this delegate. */
final class JdkStorageOperations implements StorageOperations {
  static final JdkStorageOperations INSTANCE = new JdkStorageOperations();

  private JdkStorageOperations() {}

  @Override
  public void forceFile(Path path, FileChannel channel) throws IOException {
    channel.force(true);
  }

  @Override
  public void atomicMove(Path source, Path target) throws IOException {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
  }

  @Override
  public void forceDirectory(Path directory) throws IOException {
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  @Override
  public void delete(Path path) throws IOException {
    Files.delete(path);
  }
}

package io.github.lchareln.cex.matching.local;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/** Actual namespace and force operations used by snapshot publication and WAL retirement. */
interface StorageOperations {
  void forceFile(Path path, FileChannel channel) throws IOException;

  void atomicMove(Path source, Path target) throws IOException;

  void forceDirectory(Path directory) throws IOException;

  void delete(Path path) throws IOException;
}

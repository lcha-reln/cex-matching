package io.github.lchareln.cex.matching.cluster;

import io.aeron.ExclusivePublication;
import io.aeron.FragmentAssembler;
import io.aeron.Image;
import io.aeron.Publication;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

/** Publication/Image transport for the application snapshot frames. */
public final class M11AeronSnapshotTransport {
  private static final int FRAGMENT_LIMIT = 10;

  private final M11SnapshotFrameCodec frameCodec = new M11SnapshotFrameCodec();

  public void write(
      ExclusivePublication publication,
      byte[] canonicalSnapshot,
      long snapshotSequence,
      IdleStrategy idleStrategy) {
    write(
        publication,
        canonicalSnapshot,
        snapshotSequence,
        idleStrategy,
        M11BoundedProgress.DEFAULT_TIMEOUT,
        () -> null);
  }

  void write(
      ExclusivePublication publication,
      byte[] canonicalSnapshot,
      long snapshotSequence,
      IdleStrategy idleStrategy,
      Duration timeout,
      Supplier<? extends Throwable> componentFailure) {
    Objects.requireNonNull(publication, "publication");
    Objects.requireNonNull(idleStrategy, "idleStrategy");
    M11BoundedProgress progress =
        M11BoundedProgress.start("M11 snapshot publication", timeout, componentFailure);
    for (byte[] frame : frameCodec.encode(canonicalSnapshot, snapshotSequence)) {
      UnsafeBuffer buffer = new UnsafeBuffer(frame);
      idleStrategy.reset();
      while (true) {
        progress.checkpoint(publication.isClosed());
        long result = publication.offer(buffer, 0, frame.length);
        if (result >= 0) {
          break;
        }
        if (result != Publication.BACK_PRESSURED && result != Publication.ADMIN_ACTION) {
          throw new IllegalStateException(
              "snapshot publication failed: " + Publication.errorString(result));
        }
        progress.checkpoint(publication.isClosed());
        idleStrategy.idle();
      }
    }
  }

  public LoadedSnapshot read(Image image, IdleStrategy idleStrategy) throws M11ProtocolException {
    return read(image, idleStrategy, M11BoundedProgress.DEFAULT_TIMEOUT, () -> null);
  }

  LoadedSnapshot read(
      Image image,
      IdleStrategy idleStrategy,
      Duration timeout,
      Supplier<? extends Throwable> componentFailure)
      throws M11ProtocolException {
    Objects.requireNonNull(image, "image");
    Objects.requireNonNull(idleStrategy, "idleStrategy");
    M11BoundedProgress progress =
        M11BoundedProgress.start("M11 snapshot load", timeout, componentFailure);
    M11SnapshotFrameCodec.Accumulator accumulator = frameCodec.accumulator();
    SnapshotLoadFailure[] failure = new SnapshotLoadFailure[1];
    FragmentAssembler assembler =
        new FragmentAssembler(
            (buffer, offset, length, header) -> {
              if (failure[0] != null) {
                return;
              }
              byte[] frame = new byte[length];
              buffer.getBytes(offset, frame);
              try {
                accumulator.accept(frame);
              } catch (M11ProtocolException exception) {
                failure[0] = new SnapshotLoadFailure(exception);
              }
            });
    idleStrategy.reset();
    while (!image.isEndOfStream()) {
      progress.checkpoint(snapshotImageUnavailable(image));
      int fragments = image.poll(assembler, FRAGMENT_LIMIT);
      if (failure[0] != null) {
        throw failure[0].protocolFailure();
      }
      progress.checkpoint(snapshotImageUnavailable(image));
      idleStrategy.idle(fragments);
    }
    if (failure[0] != null) {
      throw failure[0].protocolFailure();
    }
    return new LoadedSnapshot(accumulator.snapshotSequence(), accumulator.finish());
  }

  private static boolean snapshotImageUnavailable(Image image) {
    return !image.isEndOfStream() && (image.isClosed() || image.isPublicationRevoked());
  }

  public record LoadedSnapshot(long snapshotSequence, byte[] canonicalBytes) {
    public LoadedSnapshot {
      canonicalBytes = canonicalBytes.clone();
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }

  private record SnapshotLoadFailure(M11ProtocolException protocolFailure) {}
}

package io.github.lchareln.cex.matching.cluster;

import io.aeron.ExclusivePublication;
import io.aeron.FragmentAssembler;
import io.aeron.Image;
import io.aeron.Publication;
import java.util.Objects;
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
    Objects.requireNonNull(publication, "publication");
    Objects.requireNonNull(idleStrategy, "idleStrategy");
    for (byte[] frame : frameCodec.encode(canonicalSnapshot, snapshotSequence)) {
      UnsafeBuffer buffer = new UnsafeBuffer(frame);
      idleStrategy.reset();
      while (true) {
        long result = publication.offer(buffer, 0, frame.length);
        if (result >= 0) {
          break;
        }
        if (result != Publication.BACK_PRESSURED && result != Publication.ADMIN_ACTION) {
          throw new IllegalStateException(
              "snapshot publication failed: " + Publication.errorString(result));
        }
        idleStrategy.idle();
      }
    }
  }

  public LoadedSnapshot read(Image image, IdleStrategy idleStrategy) throws M11ProtocolException {
    Objects.requireNonNull(image, "image");
    Objects.requireNonNull(idleStrategy, "idleStrategy");
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
      int fragments = image.poll(assembler, FRAGMENT_LIMIT);
      if (failure[0] != null) {
        throw failure[0].protocolFailure();
      }
      idleStrategy.idle(fragments);
    }
    if (failure[0] != null) {
      throw failure[0].protocolFailure();
    }
    return new LoadedSnapshot(accumulator.snapshotSequence(), accumulator.finish());
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

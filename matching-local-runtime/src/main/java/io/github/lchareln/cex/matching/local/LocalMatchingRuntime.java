package io.github.lchareln.cex.matching.local;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable single-process ingress that owns its fresh core: canonicalize, bind, force, apply, ACK.
 */
public final class LocalMatchingRuntime implements AutoCloseable {
  private final WalConfig config;
  private final M08EnvelopeCodec envelopeCodec;
  private final CommandApplier commandApplier;
  private final FaultInjector faultInjector;
  private final SegmentedWal wal;
  private final IdentityIndex identities = new IdentityIndex();

  private RuntimeState state = RuntimeState.OPEN;
  private String failureDetail = "";

  private LocalMatchingRuntime(
      WalConfig config,
      M08EnvelopeCodec envelopeCodec,
      CommandApplier commandApplier,
      FaultInjector faultInjector)
      throws IOException {
    this.config = Objects.requireNonNull(config, "config");
    this.envelopeCodec = Objects.requireNonNull(envelopeCodec, "envelopeCodec");
    this.commandApplier = Objects.requireNonNull(commandApplier, "commandApplier");
    this.faultInjector = Objects.requireNonNull(faultInjector, "faultInjector");
    wal = SegmentedWal.open(config, faultInjector);
    try {
      recover();
    } catch (Throwable failure) {
      try {
        wal.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  public static LocalMatchingRuntime open(WalConfig config) throws IOException {
    return open(config, FaultInjector.NONE);
  }

  public static LocalMatchingRuntime open(WalConfig config, FaultInjector faultInjector)
      throws IOException {
    return new LocalMatchingRuntime(
        config, new M08EnvelopeCodec(), new MatchingCoreCommandApplier(), faultInjector);
  }

  static LocalMatchingRuntime openForTesting(
      WalConfig config, CommandApplier commandApplier, FaultInjector faultInjector)
      throws IOException {
    return new LocalMatchingRuntime(config, new M08EnvelopeCodec(), commandApplier, faultInjector);
  }

  public synchronized SubmissionResult submit(byte[] canonicalEnvelope) {
    Objects.requireNonNull(canonicalEnvelope, "canonicalEnvelope");
    if (state != RuntimeState.OPEN) {
      return new SubmissionResult.FailedClosed(failureDetail);
    }

    final M08Envelope envelope;
    try {
      envelope = envelopeCodec.decodeCanonical(canonicalEnvelope, config.shardId());
    } catch (StructuralRejectionException rejection) {
      return new SubmissionResult.StructuralRejected(rejection.code(), rejection.getMessage());
    }
    if (!wal.acceptsEnvelope(canonicalEnvelope.length)) {
      return new SubmissionResult.StructuralRejected(
          StructuralRejectionCode.ENVELOPE_SIZE_LIMIT,
          "canonical envelope exceeds configured M08W1 record capacity");
    }
    if (!commandApplier.supports(envelope.command())) {
      return new SubmissionResult.StructuralRejected(
          StructuralRejectionCode.UNSUPPORTED_COMMAND,
          "this core adapter cannot yet apply the canonical command variant");
    }

    IdentityIndex.Decision decision = identities.preflight(envelope);
    if (decision instanceof IdentityIndex.Duplicate duplicate) {
      IdentityIndex.Binding binding = duplicate.binding();
      return new SubmissionResult.DuplicateReplayed(binding.position(), binding.result());
    }
    if (decision instanceof IdentityIndex.Rejected rejected) {
      return new SubmissionResult.PreflightRejected(rejected.code());
    }

    long applicationSequence = commandApplier.nextApplicationSequence();
    WalPosition position;
    try {
      position = wal.append(canonicalEnvelope, applicationSequence);
    } catch (WalAppendException failure) {
      return failClosed(failure.attemptedPosition(), "APPEND_OR_FORCE", describeFailure(failure));
    } catch (IOException | RuntimeException failure) {
      return failClosed(Optional.empty(), "APPEND_OR_FORCE", describeFailure(failure));
    }

    try {
      faultInjector.hit(FaultPoint.BEFORE_LIVE_APPLY);
      CanonicalResult result = commandApplier.apply(envelope.command());
      verifyApplied(position, result);
      identities.commit(envelope, position, result);
      faultInjector.hit(FaultPoint.AFTER_LIVE_APPLY_BEFORE_ACK);
      return new SubmissionResult.NewDurablyApplied(position, result);
    } catch (IOException | RuntimeException failure) {
      return failClosed(Optional.of(position), "APPLY_OR_ACK", describeFailure(failure));
    }
  }

  public synchronized RuntimeState state() {
    return state;
  }

  public synchronized long nextWalSequence() {
    return wal.nextWalSequence();
  }

  public synchronized String semanticStateDigest() {
    return commandApplier.semanticStateDigest();
  }

  @Override
  public synchronized void close() throws IOException {
    if (state == RuntimeState.CLOSED) {
      return;
    }
    state = RuntimeState.CLOSED;
    wal.close();
  }

  private void recover() throws IOException {
    for (RecoveredRecord record : wal.recoveredRecords()) {
      final M08Envelope envelope;
      try {
        envelope = envelopeCodec.decodeCanonical(record.envelopeBytes(), config.shardId());
      } catch (StructuralRejectionException rejection) {
        throw new RecoveryException("durable M08W1 record is not canonical M08C1", rejection);
      }
      if (!commandApplier.supports(envelope.command())) {
        throw new RecoveryException("durable M08C1 command has no installed apply adapter");
      }
      if (!(identities.preflight(envelope) instanceof IdentityIndex.New)) {
        throw new RecoveryException("durable M08C1 identities are not a new contiguous stream");
      }
      if (record.position().applicationSequence() != commandApplier.nextApplicationSequence()) {
        throw new RecoveryException("durable application sequence is not contiguous");
      }
      try {
        faultInjector.hit(FaultPoint.BEFORE_RECOVERY_APPLY);
        CanonicalResult result = commandApplier.apply(envelope.command());
        verifyApplied(record.position(), result);
        identities.commit(envelope, record.position(), result);
      } catch (IOException | RuntimeException failure) {
        throw new RecoveryException("deterministic recovery apply failed", failure);
      }
    }
  }

  private SubmissionResult.DurabilityUnknown failClosed(
      Optional<WalPosition> position, String stage, String detail) {
    state = RuntimeState.FAILED_CLOSED;
    failureDetail = stage + ": " + detail;
    return new SubmissionResult.DurabilityUnknown(position, stage, detail);
  }

  private static void verifyApplied(WalPosition position, CanonicalResult result) {
    if (result.applicationSequence() != position.applicationSequence()) {
      throw new IllegalStateException("applier returned a different application sequence");
    }
  }

  private static String describeFailure(Throwable failure) {
    String message = failure.getMessage();
    return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}

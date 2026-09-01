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
  private CommandApplier commandApplier;
  private final FaultInjector faultInjector;
  private final SegmentedWal wal;
  private IdentityIndex identities = new IdentityIndex();

  private RuntimeState state = RuntimeState.OPEN;
  private String failureDetail = "";
  private boolean operationInProgress;

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
      restoreSnapshot();
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
    if (operationInProgress) {
      state = RuntimeState.FAILED_CLOSED;
      failureDetail = "REENTRANT_SUBMIT: a fault callback attempted nested ingress";
      return new SubmissionResult.FailedClosed(failureDetail);
    }
    if (state != RuntimeState.OPEN) {
      return new SubmissionResult.FailedClosed(failureDetail);
    }
    operationInProgress = true;
    try {
      return submitOnce(canonicalEnvelope);
    } finally {
      operationInProgress = false;
    }
  }

  private SubmissionResult submitOnce(byte[] canonicalEnvelope) {
    // The caller retains its byte array. Own one immutable-by-convention copy so validation,
    // journal append, apply, and identity binding all refer to exactly the same bytes.
    byte[] ownedEnvelope = canonicalEnvelope.clone();

    final M08Envelope envelope;
    try {
      envelope = envelopeCodec.decodeCanonical(ownedEnvelope, config.shardId());
    } catch (StructuralRejectionException rejection) {
      return new SubmissionResult.StructuralRejected(rejection.code(), rejection.getMessage());
    }
    if (!wal.acceptsEnvelope(ownedEnvelope.length)) {
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
    if (!wal.hasRecoveryBudgetFor(ownedEnvelope.length)) {
      return wal.checkpointRequired();
    }

    long applicationSequence = commandApplier.nextApplicationSequence();
    WalPosition position;
    try {
      position = wal.append(ownedEnvelope, applicationSequence);
    } catch (WalAppendException failure) {
      return failClosed(failure.attemptedPosition(), "APPEND_OR_FORCE", describeFailure(failure));
    } catch (IOException | RuntimeException failure) {
      return failClosed(Optional.empty(), "APPEND_OR_FORCE", describeFailure(failure));
    }
    if (state != RuntimeState.OPEN) {
      return new SubmissionResult.DurabilityUnknown(
          Optional.of(position), "REENTRANT_SUBMIT", failureDetail);
    }

    try {
      faultInjector.hit(FaultPoint.BEFORE_LIVE_APPLY);
      requireOpenAfterFaultCallback();
      CanonicalResult result = commandApplier.apply(envelope.command());
      verifyApplied(position, result);
      identities.commit(envelope, position, result);
      faultInjector.hit(FaultPoint.AFTER_LIVE_APPLY_BEFORE_ACK);
      requireOpenAfterFaultCallback();
      return new SubmissionResult.NewDurablyApplied(position, result);
    } catch (IOException | RuntimeException failure) {
      return failClosed(Optional.of(position), "APPLY_OR_ACK", describeFailure(failure));
    }
  }

  private void requireOpenAfterFaultCallback() throws IOException {
    if (state != RuntimeState.OPEN) {
      throw new IOException(failureDetail);
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

  /**
   * Publishes one synchronous M09S1 checkpoint at the current applied command boundary.
   *
   * <p>Any ambiguous publication, rollover, or retention failure leaves this instance failed
   * closed. A fresh open resolves the durable directory state.
   */
  public synchronized CheckpointResult checkpoint() throws IOException {
    if (operationInProgress) {
      state = RuntimeState.FAILED_CLOSED;
      failureDetail = "REENTRANT_OPERATION: a callback attempted nested runtime work";
      throw new IOException(failureDetail);
    }
    if (state != RuntimeState.OPEN) {
      throw new IOException("runtime is not open: " + failureDetail);
    }
    operationInProgress = true;
    try {
      long lastWal = wal.nextWalSequence() - 1;
      long lastApplication = commandApplier.nextApplicationSequence() - 1;
      LocalRuntimeStateImage image =
          new LocalRuntimeStateImage(
              commandApplier.stateImage(), identities.stateImage(), lastWal, lastApplication);
      CheckpointResult result = wal.checkpoint(image);
      if (state != RuntimeState.OPEN) {
        throw new IOException(failureDetail);
      }
      return result;
    } catch (IOException | RuntimeException failure) {
      state = RuntimeState.FAILED_CLOSED;
      failureDetail = "CHECKPOINT_OR_RETENTION: " + describeFailure(failure);
      if (failure instanceof IOException ioFailure) {
        throw ioFailure;
      }
      throw new IOException(failureDetail, failure);
    } finally {
      operationInProgress = false;
    }
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

  private void restoreSnapshot() throws IOException {
    Optional<M09SnapshotCodec.DecodedSnapshot> snapshot = wal.recoveredSnapshot();
    if (snapshot.isEmpty()) {
      return;
    }
    LocalRuntimeStateImage image = snapshot.orElseThrow().state();
    try {
      commandApplier = commandApplier.restore(image.applierState());
      if (image.identityBindings().stream()
          .anyMatch(binding -> binding.slot().shardId() != config.shardId())) {
        throw new IllegalArgumentException("M09S1 identity binding targets another shard");
      }
      identities = IdentityIndex.restore(image.identityBindings());
      if (!commandApplier
          .semanticStateDigest()
          .equals(image.applierState().semanticStateDigest())) {
        throw new IllegalArgumentException("restored semantic digest disagrees with M09S1");
      }
    } catch (RuntimeException failure) {
      throw new RecoveryException("M09S1 state restore failed", failure);
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

package io.github.lchareln.cex.matching.local;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Decoded canonical M08C1 envelope and the exact command payload bytes hashed by its identity. */
public final class M08Envelope {
  private final Slot slot;
  private final UUID commandId;
  private final String payloadHash;
  private final byte[] commandPayload;
  private final M08Command command;

  M08Envelope(
      Slot slot, UUID commandId, String payloadHash, byte[] commandPayload, M08Command command) {
    this.slot = Objects.requireNonNull(slot, "slot");
    this.commandId = Objects.requireNonNull(commandId, "commandId");
    this.payloadHash = Objects.requireNonNull(payloadHash, "payloadHash");
    this.commandPayload = commandPayload.clone();
    this.command = Objects.requireNonNull(command, "command");
  }

  public Slot slot() {
    return slot;
  }

  public UUID commandId() {
    return commandId;
  }

  public String payloadHash() {
    return payloadHash;
  }

  public byte[] commandPayload() {
    return commandPayload.clone();
  }

  public M08Command command() {
    return command;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof M08Envelope that
        && slot.equals(that.slot)
        && commandId.equals(that.commandId)
        && payloadHash.equals(that.payloadHash)
        && Arrays.equals(commandPayload, that.commandPayload)
        && command.equals(that.command);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(slot, commandId, payloadHash, command);
    return 31 * result + Arrays.hashCode(commandPayload);
  }
}

package io.github.lchareln.cex.matching;

import java.util.Objects;

/** Normalized opaque participant group and taker-side self-trade disposition. */
public record SelfTradePreventionInstruction(
    long participantGroupId, SelfTradePreventionPolicy policy) {

  private static final SelfTradePreventionInstruction LEGACY =
      new SelfTradePreventionInstruction(0, SelfTradePreventionPolicy.NONE);

  public SelfTradePreventionInstruction {
    Objects.requireNonNull(policy, "policy");
    if (participantGroupId < 0) {
      throw new IllegalArgumentException("participant group id must be non-negative");
    }
    if ((participantGroupId == 0) != (policy == SelfTradePreventionPolicy.NONE)) {
      throw new IllegalArgumentException(
          "group zero requires NONE and a positive group requires an active disposition");
    }
  }

  public static SelfTradePreventionInstruction legacy() {
    return LEGACY;
  }

  public boolean conflictsWith(SelfTradePreventionInstruction makerInstruction) {
    Objects.requireNonNull(makerInstruction, "makerInstruction");
    return participantGroupId > 0 && participantGroupId == makerInstruction.participantGroupId;
  }
}

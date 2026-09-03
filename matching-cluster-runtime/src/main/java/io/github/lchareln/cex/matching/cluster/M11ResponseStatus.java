package io.github.lchareln.cex.matching.cluster;

/** Frozen outcome IDs for M11R. */
public enum M11ResponseStatus {
  NEW_APPLIED(1),
  DUPLICATE_REPLAYED(2),
  REJECTED(3);

  private final int wireId;

  M11ResponseStatus(int wireId) {
    this.wireId = wireId;
  }

  int wireId() {
    return wireId;
  }

  static M11ResponseStatus fromWire(int value) throws M11ProtocolException {
    for (M11ResponseStatus status : values()) {
      if (status.wireId == value) {
        return status;
      }
    }
    throw new M11ProtocolException(
        M11ProtocolException.Code.INVALID_VALUE, "unknown M11 response outcome");
  }
}

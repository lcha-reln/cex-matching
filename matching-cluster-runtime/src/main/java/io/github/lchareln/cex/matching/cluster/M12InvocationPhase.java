package io.github.lchareln.cex.matching.cluster;

/** Observable, non-wire phase used to make the response-delivery boundary testable. */
public enum M12InvocationPhase {
  OFFERING,
  OFFER_ACCEPTED,
  RESPONSE_BUFFERED,
  TERMINAL
}

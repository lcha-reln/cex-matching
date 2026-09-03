package io.github.lchareln.cex.matching.cluster;

/**
 * Terminal client-side knowledge about one M12 invocation.
 *
 * <p>These values are deliberately not matching outcomes and are never encoded on the M11 wire.
 */
public enum M12InvocationState {
  NOT_SUBMITTED,
  UNKNOWN,
  ACKNOWLEDGED
}

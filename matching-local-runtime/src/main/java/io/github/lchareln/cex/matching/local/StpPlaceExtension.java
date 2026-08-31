package io.github.lchareln.cex.matching.local;

import io.github.lchareln.cex.matching.ExecutionBatch;
import io.github.lchareln.cex.matching.SingleInstrumentMatchingEngine;

/**
 * M07 cherry-pick seam. The future implementation maps retained group/policy fields to M07 core
 * request types without changing M08C1 bytes.
 */
@FunctionalInterface
interface StpPlaceExtension {
  ExecutionBatch apply(SingleInstrumentMatchingEngine engine, M08Command.Place command);
}

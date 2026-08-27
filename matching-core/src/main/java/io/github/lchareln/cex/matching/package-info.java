/**
 * Deterministic matching semantics. M00 defines the input contract; M01 adds a single-writer,
 * in-memory GTC price-time book. The core contains no I/O, clocks, randomness, concurrency,
 * persistence, or runtime integration.
 */
package io.github.lchareln.cex.matching;

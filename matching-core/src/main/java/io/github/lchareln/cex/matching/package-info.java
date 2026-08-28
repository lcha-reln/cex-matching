/**
 * Deterministic matching semantics. M00 defines the input contract, M01 adds a single-writer GTC
 * price-time book, and M02 adds addressable cancellation with irreversible in-memory terminal
 * states. The core contains no I/O, clocks, randomness, concurrency, persistence, or runtime
 * integration.
 */
package io.github.lchareln.cex.matching;

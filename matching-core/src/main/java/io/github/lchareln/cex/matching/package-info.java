/**
 * Deterministic matching semantics. M00 defines the input contract, M01 adds a single-writer GTC
 * price-time book, M02 adds addressable cancellation, M04 adds execution policy, and M05 adds
 * content-addressed versioned order-entry price-band activation. The core contains no I/O, clocks,
 * randomness, concurrency, persistence, or runtime integration.
 */
package io.github.lchareln.cex.matching;

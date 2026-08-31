/**
 * Deterministic matching semantics. M00 defines the input contract, M01 adds a single-writer GTC
 * price-time book, M02 adds addressable cancellation, M04 adds execution policy, M05 adds
 * content-addressed versioned order-entry price-band activation, and M06 adds serialized market
 * operating modes plus deterministic Mass Cancel. The core contains no I/O, clocks, randomness,
 * concurrency, persistence, or runtime integration.
 */
package io.github.lchareln.cex.matching;

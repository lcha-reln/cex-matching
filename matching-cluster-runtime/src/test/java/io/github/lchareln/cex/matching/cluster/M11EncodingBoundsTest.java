package io.github.lchareln.cex.matching.cluster;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class M11EncodingBoundsTest {
  @Test
  void writersUseTheSameEntryBoundsAsReaders() {
    assertDoesNotThrow(
        () -> M11CommandStateCodec.requireEncodableOrderCount(M11CommandStateCodec.MAX_ORDERS));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            M11CommandStateCodec.requireEncodableOrderCount(
                Math.incrementExact(M11CommandStateCodec.MAX_ORDERS)));

    assertDoesNotThrow(
        () -> M11RuntimeStateCodec.requireEncodableBindingCount(M11RuntimeStateCodec.MAX_BINDINGS));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            M11RuntimeStateCodec.requireEncodableBindingCount(
                Math.incrementExact(M11RuntimeStateCodec.MAX_BINDINGS)));

    assertDoesNotThrow(
        () -> M11FullResultCodec.requireEncodableEventCount(M11FullResultCodec.MAX_EVENTS));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            M11FullResultCodec.requireEncodableEventCount(
                Math.incrementExact(M11FullResultCodec.MAX_EVENTS)));
  }
}

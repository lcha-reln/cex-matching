package io.github.lchareln.cex.matching.benchmark;

import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

/**
 * Primitive, growable buffer used for exact derivation before raw shards are independently read.
 */
final class LongSampleBuffer {
  private long[] values = new long[1_024];
  private int size;

  void add(long value) {
    if (value < 0) {
      throw new IllegalArgumentException("sample must be non-negative");
    }
    if (size == values.length) {
      values = Arrays.copyOf(values, Math.multiplyExact(values.length, 2));
    }
    values[size++] = value;
  }

  int size() {
    return size;
  }

  boolean isEmpty() {
    return size == 0;
  }

  long[] copy() {
    return Arrays.copyOf(values, size);
  }

  List<Long> boxedCopy() {
    return LongStream.of(copy()).boxed().toList();
  }
}

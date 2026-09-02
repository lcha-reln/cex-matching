package io.github.lchareln.cex.matching.benchmark;

import io.github.lchareln.cex.matching.PlaceLimitOrderInput;
import io.github.lchareln.cex.matching.PlaceLimitOrderRequest;
import io.github.lchareln.cex.matching.SingleInstrumentMatchingEngine;
import io.github.lchareln.cex.matching.local.M08Command;
import io.github.lchareln.cex.matching.local.M08EnvelopeCodec;
import java.math.BigInteger;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** Diagnostic-only core hot-path sample; it is never an end-to-end or release-gating result. */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 2)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
public class CoreMatchingBenchmark {
  @Benchmark
  public void restingMakerThenMatchingTaker(CoreState state, Blackhole blackhole) {
    long makerId = state.nextOrderId();
    blackhole.consume(state.engine.placeRequest(order(makerId, "SELL", 100, "GTC")));
    blackhole.consume(state.engine.placeRequest(order(makerId + 1, "BUY", 100, "IOC")));
    state.afterPair();
  }

  /** Canonical codec isolation; fixed input and no WAL/service work are part of this sample. */
  @Benchmark
  public void canonicalEnvelopeDecode(CodecState state, Blackhole blackhole) throws Exception {
    blackhole.consume(state.codec.decodeCanonical(state.canonicalEnvelope, 1));
  }

  private static PlaceLimitOrderRequest order(
      long orderId, String side, long priceTicks, String executionPolicy) {
    return new PlaceLimitOrderRequest(
        new PlaceLimitOrderInput(
            "BTC-USDT",
            BigInteger.valueOf(orderId),
            side,
            BigInteger.valueOf(priceTicks),
            BigInteger.ONE),
        executionPolicy);
  }

  @State(Scope.Thread)
  public static class CoreState {
    private static final long PAIRS_BEFORE_RESET = 4_096;

    SingleInstrumentMatchingEngine engine;
    private long nextOrderId;
    private long pairs;

    @Setup
    public void setup() {
      reset();
    }

    long nextOrderId() {
      return nextOrderId;
    }

    void afterPair() {
      nextOrderId += 2;
      pairs++;
      if (pairs == PAIRS_BEFORE_RESET) {
        reset();
      }
    }

    private void reset() {
      engine = new SingleInstrumentMatchingEngine();
      nextOrderId = 1;
      pairs = 0;
    }
  }

  @State(Scope.Thread)
  public static class CodecState {
    M08EnvelopeCodec codec;
    byte[] canonicalEnvelope;

    @Setup
    public void setup() {
      codec = new M08EnvelopeCodec();
      canonicalEnvelope =
          codec.encode(
              "m10-jmh-codec",
              1,
              1,
              1,
              new UUID(0x4d31304a4d48434fL, 1),
              new M08Command.Place(
                  "BTC-USDT",
                  BigInteger.ONE,
                  "BUY",
                  BigInteger.valueOf(100),
                  BigInteger.ONE,
                  "IOC",
                  0,
                  "NONE",
                  Optional.empty()));
    }
  }
}

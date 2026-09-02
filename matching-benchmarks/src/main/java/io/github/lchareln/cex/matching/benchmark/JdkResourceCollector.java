package io.github.lchareln.cex.matching.benchmark;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

/** JDK collector for the allocation, GC, CPU, memory, and queue dimensions frozen by M10. */
public final class JdkResourceCollector {
  private final com.sun.management.ThreadMXBean threads;
  private final OperatingSystemMXBean operatingSystem;
  private final MemoryMXBean memory;
  private final List<GarbageCollectorMXBean> garbageCollectors;
  private final IntSupplier queueDepth;

  public JdkResourceCollector(IntSupplier queueDepth) {
    this.queueDepth = Objects.requireNonNull(queueDepth, "queueDepth");
    if (!(ManagementFactory.getThreadMXBean()
        instanceof com.sun.management.ThreadMXBean threadBean)) {
      throw new IllegalStateException("thread allocation collector is unavailable");
    }
    threads = threadBean;
    if (!threads.isThreadAllocatedMemorySupported()) {
      throw new IllegalStateException("thread allocation measurement is unsupported");
    }
    if (!threads.isThreadAllocatedMemoryEnabled()) {
      threads.setThreadAllocatedMemoryEnabled(true);
    }
    if (!(ManagementFactory.getOperatingSystemMXBean()
        instanceof OperatingSystemMXBean operatingSystemBean)) {
      throw new IllegalStateException("process resource collector is unavailable");
    }
    operatingSystem = operatingSystemBean;
    memory = ManagementFactory.getMemoryMXBean();
    garbageCollectors = List.copyOf(ManagementFactory.getGarbageCollectorMXBeans());
    if (garbageCollectors.isEmpty()) {
      throw new IllegalStateException("garbage collection counters are unavailable");
    }
  }

  public ResourceObservation observe(long relativeNanos) {
    long allocated =
        requireAvailable(threads.getTotalThreadAllocatedBytes(), "total thread allocation");
    long gcCount = 0;
    long gcMillis = 0;
    for (GarbageCollectorMXBean collector : garbageCollectors) {
      gcCount =
          Math.addExact(gcCount, requireAvailable(collector.getCollectionCount(), "GC count"));
      gcMillis =
          Math.addExact(gcMillis, requireAvailable(collector.getCollectionTime(), "GC time"));
    }
    long processCpu = requireAvailable(operatingSystem.getProcessCpuTime(), "process CPU time");
    long virtualMemory =
        requireAvailable(operatingSystem.getCommittedVirtualMemorySize(), "virtual memory");
    long totalMemory = requireAvailable(operatingSystem.getTotalMemorySize(), "total memory");
    long freeMemory = requireAvailable(operatingSystem.getFreeMemorySize(), "free memory");
    if (freeMemory > totalMemory) {
      throw new IllegalStateException("free memory exceeds total memory");
    }
    return new ResourceObservation(
        relativeNanos,
        allocated,
        gcCount,
        gcMillis,
        processCpu,
        memory.getHeapMemoryUsage().getUsed(),
        virtualMemory,
        totalMemory - freeMemory,
        queueDepth.getAsInt());
  }

  private static long requireAvailable(long value, String dimension) {
    if (value < 0) {
      throw new IllegalStateException(dimension + " collector returned unavailable");
    }
    return value;
  }
}

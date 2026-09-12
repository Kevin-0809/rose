package com.spdb.message;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongSupplier;

/**
 * 5 秒滑动窗口计数器：环形秒桶实现，用于统计实时 TPS。
 */
public final class TpsWindow {

    static final int WINDOW_SECONDS = 5;
    private static final int SLOT_COUNT = WINDOW_SECONDS + 1;

    private final AtomicLongArray counts = new AtomicLongArray(SLOT_COUNT);
    private final AtomicLongArray epochs = new AtomicLongArray(SLOT_COUNT);
    private final LongSupplier clock;
    private final Object writeLock = new Object();

    public TpsWindow() {
        this(System::currentTimeMillis);
    }

    public TpsWindow(LongSupplier clock) {
        this.clock = clock;
        for (int i = 0; i < SLOT_COUNT; i++) {
            epochs.set(i, Long.MIN_VALUE);
        }
    }

    public void record() {
        long second = currentSecond();
        int slot = slotOf(second);
        synchronized (writeLock) {
            if (epochs.get(slot) != second) {
                epochs.set(slot, second);
                counts.set(slot, 0L);
            }
            counts.incrementAndGet(slot);
        }
    }

    public long countInWindow() {
        long second = currentSecond();
        long from = second - (WINDOW_SECONDS - 1);
        long sum = 0L;
        for (int i = 0; i < SLOT_COUNT; i++) {
            long epoch = epochs.get(i);
            if (epoch >= from && epoch <= second) {
                sum += counts.get(i);
            }
        }
        return sum;
    }

    public double tps() {
        return Math.round(countInWindow() / (double) WINDOW_SECONDS * 10.0) / 10.0;
    }

    public void reset() {
        synchronized (writeLock) {
            for (int i = 0; i < SLOT_COUNT; i++) {
                epochs.set(i, Long.MIN_VALUE);
                counts.set(i, 0L);
            }
        }
    }

    private long currentSecond() {
        return clock.getAsLong() / 1000L;
    }

    private int slotOf(long second) {
        return (int) Math.floorMod(second, SLOT_COUNT);
    }
}

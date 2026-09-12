package com.spdb.message;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TpsWindowTest {

    private final AtomicLong clock = new AtomicLong(0L);

    private TpsWindow newWindow() {
        return new TpsWindow(clock::get);
    }

    @Test
    void countsRecordsInsideFiveSecondWindow() {
        TpsWindow window = newWindow();
        clock.set(1_000L);
        window.record();
        window.record();
        clock.set(3_000L);
        window.record();

        assertThat(window.countInWindow()).isEqualTo(3L);
        assertThat(window.tps()).isEqualTo(0.6);
    }

    @Test
    void excludesRecordsOlderThanWindow() {
        TpsWindow window = newWindow();
        clock.set(1_000L);
        window.record();
        clock.set(7_000L);

        assertThat(window.countInWindow()).isZero();
        assertThat(window.tps()).isZero();
    }

    @Test
    void reusesSlotsWithoutCountingStaleEpochs() {
        TpsWindow window = newWindow();
        clock.set(1_000L);
        window.record();
        clock.set(7_000L);
        window.record();

        assertThat(window.countInWindow()).isEqualTo(1L);
    }

    @Test
    void resetClearsAllCounts() {
        TpsWindow window = newWindow();
        clock.set(1_000L);
        window.record();
        window.reset();

        assertThat(window.countInWindow()).isZero();
    }
}

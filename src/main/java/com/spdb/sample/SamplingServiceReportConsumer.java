package com.spdb.sample;

@FunctionalInterface
public interface SamplingServiceReportConsumer {
    void accept(SamplingServiceReportRow row);
}

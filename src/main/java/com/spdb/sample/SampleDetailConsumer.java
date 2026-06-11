package com.spdb.sample;

@FunctionalInterface
public interface SampleDetailConsumer {
    void accept(SampleDetailRow row);
}

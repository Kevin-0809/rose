package com.spdb.sample;

@FunctionalInterface
public interface SampleGroupConsumer {
    void accept(SampleGroupRow row);
}

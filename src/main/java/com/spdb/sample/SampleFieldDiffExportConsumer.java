package com.spdb.sample;

@FunctionalInterface
public interface SampleFieldDiffExportConsumer {
    void accept(SampleFieldDiffRow row);
}

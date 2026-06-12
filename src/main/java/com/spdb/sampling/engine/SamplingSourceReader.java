package com.spdb.sampling.engine;

import java.util.function.Consumer;

public interface SamplingSourceReader {
    void readTranFacts(String origCdate, Consumer<TranFact> consumer);

    void readReturnCodes(String origCdate, Consumer<ReturnCodeDiff> consumer);

    void readFieldDiffs(String origCdate, Consumer<FieldDiff> consumer);
}

package com.spdb.sampling.engine;

public record SourceKey(String mesgSeq) implements Comparable<SourceKey> {
    @Override
    public int compareTo(SourceKey other) {
        return mesgSeq.compareTo(other.mesgSeq);
    }
}

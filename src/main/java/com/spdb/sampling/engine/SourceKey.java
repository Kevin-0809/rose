package com.spdb.sampling.engine;

public record SourceKey(String mesgSeq, int convIndex, int convCindex) implements Comparable<SourceKey> {
    @Override
    public int compareTo(SourceKey other) {
        int byMesgSeq = mesgSeq.compareTo(other.mesgSeq);
        if (byMesgSeq != 0) {
            return byMesgSeq;
        }
        int byConvIndex = Integer.compare(convIndex, other.convIndex);
        if (byConvIndex != 0) {
            return byConvIndex;
        }
        return Integer.compare(convCindex, other.convCindex);
    }
}

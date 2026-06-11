package com.spdb.sampling;

public record SamplingTranItem(
        String batchId,
        String origCdate,
        String mesgSeq,
        Integer convIndex,
        Integer convCindex,
        String destTrcd,
        String serviceCode,
        String tranCode,
        String compResult
) {
}

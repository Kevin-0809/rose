package com.spdb.sampling.engine;

public record ReturnCodeDiff(
        String mesgSeq,
        String origCdate,
        String destTrcd,
        String serviceCode,
        String messageType,
        String origErrorCode,
        String origErrorDesc,
        String destErrorCode,
        String destErrorDesc
) {
}

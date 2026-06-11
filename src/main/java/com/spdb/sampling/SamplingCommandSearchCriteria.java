package com.spdb.sampling;

public record SamplingCommandSearchCriteria(
        String batchId,
        String origCdate,
        String status
) {
}

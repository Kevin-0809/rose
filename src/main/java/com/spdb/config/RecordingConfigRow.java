package com.spdb.config;

import java.time.LocalDateTime;

public record RecordingConfigRow(
        Long id,
        String txnCode,
        Integer txnSwitch,
        Integer recordRatio,
        String description,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {
    public boolean enabled() {
        return txnSwitch != null && txnSwitch == 1;
    }
}

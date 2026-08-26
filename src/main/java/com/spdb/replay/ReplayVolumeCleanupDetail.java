package com.spdb.replay;

import java.time.LocalDateTime;

public record ReplayVolumeCleanupDetail(
        long cleanupId,
        long checkId,
        String serviceCode,
        String mappedTranCodes,
        boolean catalogHit,
        long pendingCleanupRowCount,
        long actualCleanupRowCount,
        ReplayVolumeCleanupStatus status,
        String errorMessage,
        LocalDateTime createdTime,
        LocalDateTime startedTime,
        LocalDateTime endedTime) {
}

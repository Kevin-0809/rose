package com.spdb.replay;

import java.time.LocalDateTime;

public record ReplayVolumeCheckBatch(
        long checkId,
        ReplayVolumeCheckBatchStatus status,
        LocalDateTime catalogSnapshotTime,
        int sampleSize,
        int lookbackDays,
        long catalogCount,
        long hasVolumeCount,
        long noVolumeCount,
        long cleanupServiceCount,
        long cleanupRowCount,
        long actualCleanupRowCount,
        Long migrationCommandId,
        LocalDateTime createdTime,
        LocalDateTime startedTime,
        LocalDateTime endedTime,
        String errorMessage) {

    public ReplayVolumeCheckBatch(long checkId, ReplayVolumeCheckBatchStatus status, LocalDateTime catalogSnapshotTime,
                                  int sampleSize, int lookbackDays, long catalogCount, long noVolumeCount,
                                  long cleanupServiceCount, long cleanupRowCount, long actualCleanupRowCount,
                                  Long migrationCommandId, LocalDateTime createdTime, LocalDateTime startedTime,
                                  LocalDateTime endedTime, String errorMessage) {
        this(checkId, status, catalogSnapshotTime, sampleSize, lookbackDays, catalogCount,
                0L, noVolumeCount, cleanupServiceCount, cleanupRowCount, actualCleanupRowCount,
                migrationCommandId, createdTime, startedTime, endedTime, errorMessage);
    }
}

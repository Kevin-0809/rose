package com.spdb.replay;

import java.time.LocalDateTime;

public record ReplayVolumeCheckDetail(
        long detailId,
        long checkId,
        String tranCode,
        String tranName,
        String businessDomain,
        String batchType,
        long mappedServiceCount,
        long completeVolumeCount,
        ReplayVolumeCheckDetailStatus status,
        ReplayVolumeMigrationStatus migrationStatus,
        String errorMessage,
        LocalDateTime createdTime) {
}

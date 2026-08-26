package com.spdb.replay;

import java.time.LocalDateTime;

public record ReplayTransactionCatalogRow(
        String tranCode,
        String tranName,
        String businessDomain,
        String batchType,
        String newCoreTranCode,
        String newTranName,
        String replayRequired,
        String originalServiceSceneCode,
        String newServiceSceneCode,
        String latestTransactionDate,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

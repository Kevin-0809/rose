package com.spdb.replay;

public record ReplayTransactionCatalogSearch(
        String tranCode,
        String tranName,
        String businessDomain,
        String batchType,
        String replayRequired
) {
}

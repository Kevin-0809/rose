package com.spdb.config;

import java.util.List;

public record ConfigImportBatchResult(
        int tranInserted,
        int tranUpdated,
        int fieldInserted,
        int fieldUpdated,
        int fieldSkipped,
        List<ConfigImportResult> results
) {
}

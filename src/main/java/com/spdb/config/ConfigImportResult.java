package com.spdb.config;

public record ConfigImportResult(
        int tranInserted,
        int tranUpdated,
        int fieldInserted,
        int fieldUpdated,
        int fieldSkipped,
        ParsedConfigImport parsed
) {
}

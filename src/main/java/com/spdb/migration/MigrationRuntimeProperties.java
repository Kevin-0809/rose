package com.spdb.migration;

public record MigrationRuntimeProperties(
        String sourceLabel,
        String targetSchema
) {
}

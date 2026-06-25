package com.spdb.migration;

public record MigrationRuntimeProperties(
        String sourceDataSource,
        String targetDataSource
) {
}

package com.spdb.config;

public record TransactionListEntry(
        String tranCode,
        String moduleName,
        String owner
) {
}

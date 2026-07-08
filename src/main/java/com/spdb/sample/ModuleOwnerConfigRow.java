package com.spdb.sample;

public record ModuleOwnerConfigRow(
        String moduleName,
        String primaryOwner,
        String backupOwner,
        String remark,
        String status
) {
}

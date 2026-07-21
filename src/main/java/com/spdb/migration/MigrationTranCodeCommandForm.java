package com.spdb.migration;

public record MigrationTranCodeCommandForm(
        String tranCodes,
        int sampleSize,
        int parallelism,
        String remark
) {
    public static MigrationTranCodeCommandForm empty() {
        return new MigrationTranCodeCommandForm("", 1, 2, "");
    }
}

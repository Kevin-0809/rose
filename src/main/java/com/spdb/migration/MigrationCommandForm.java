package com.spdb.migration;

public record MigrationCommandForm(
        long timeFrom,
        long timeTo,
        long windowSeconds,
        int parallelism,
        String remark
) {
    public static MigrationCommandForm empty() {
        return new MigrationCommandForm(0L, 0L, 3600L, 2, "");
    }
}

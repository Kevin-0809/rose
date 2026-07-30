package com.spdb.migration;

public record MigrationTranCodeCommandForm(
        String tranCodes,
        int sampleSize,
        int lookbackDays,
        int parallelism,
        String remark
) {
    public static final int DEFAULT_LOOKBACK_DAYS = 5;
    public static final int ALL_LOOKBACK_DAYS = 10_000;

    public static MigrationTranCodeCommandForm empty() {
        return new MigrationTranCodeCommandForm("", 1, DEFAULT_LOOKBACK_DAYS, 8, "");
    }
}

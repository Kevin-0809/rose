package com.spdb.migration;

public record MigrationTranCodeCommandForm(
        String tranCodes,
        int sampleSize,
        int lookbackDays,
        int parallelism,
        String remark
) {
    public static final int DEFAULT_LOOKBACK_DAYS = 5;
    public static final int DEFAULT_SAMPLE_SIZE = 100;
    public static final int DEFAULT_PARALLELISM = 16;
    public static final int ALL_LOOKBACK_DAYS = 10_000;

    public static MigrationTranCodeCommandForm empty() {
        return new MigrationTranCodeCommandForm("", DEFAULT_SAMPLE_SIZE, DEFAULT_LOOKBACK_DAYS, DEFAULT_PARALLELISM, "");
    }
}

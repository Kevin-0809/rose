package com.spdb.migration;

import java.time.LocalDateTime;
import java.util.List;

public final class MigrationMockData {

    private MigrationMockData() {}

    public static List<MigrationCommandRow> commandRows() {
        return List.of(
                new MigrationCommandRow(1L, "bxds", "tss", "CREATED", 1719100000L, 1719186400L, 3600L, 2,
                        24L, 0L, 0L, 0L, 0L, 0L, "-",
                        LocalDateTime.of(2026, 6, 23, 15, 0), null, null, null, "首批迁移"),
                new MigrationCommandRow(2L, "bxds", "tss", "RUNNING", 1719100000L, 1719186400L, 3600L, 4,
                        24L, 15L, 1L, 89300000L, 1200L, 350L, "30分20秒",
                        LocalDateTime.of(2026, 6, 23, 14, 30), LocalDateTime.of(2026, 6, 23, 14, 30), null, null, "并行4"),
                new MigrationCommandRow(3L, "bxds", "tss", "COMPLETED", 1719000000L, 1719086400L, 3600L, 4,
                        24L, 24L, 0L, 120000000L, 5000L, 800L, "1时12分",
                        LocalDateTime.of(2026, 6, 22, 10, 0), LocalDateTime.of(2026, 6, 22, 10, 0),
                        LocalDateTime.of(2026, 6, 22, 11, 12), null, "完成"),
                new MigrationCommandRow(4L, "bxds", "tss", "FAILED", 1718900000L, 1718986400L, 3600L, 2,
                        24L, 18L, 6L, 45000000L, 200L, 120L, "45分",
                        LocalDateTime.of(2026, 6, 21, 9, 0), LocalDateTime.of(2026, 6, 21, 9, 0),
                        LocalDateTime.of(2026, 6, 21, 9, 45), "3个分片执行超时", "失败批次"),
                new MigrationCommandRow(5L, "bxds", "tss", "CANCELLED", 1718800000L, 1718886400L, 3600L, 2,
                        24L, 10L, 0L, 20000000L, 100L, 50L, "20分",
                        LocalDateTime.of(2026, 6, 20, 8, 0), LocalDateTime.of(2026, 6, 20, 8, 0),
                        LocalDateTime.of(2026, 6, 20, 8, 20), null, "用户取消")
        );
    }

    public static MigrationProgressRow progress(long commandId) {
        if (commandId == 2L) {
            return runningProgress();
        }
        if (commandId == 3L) {
            return completedProgress();
        }
        if (commandId == 4L) {
            return failedProgress();
        }
        if (commandId == 5L) {
            return cancelledProgress();
        }
        if (commandId == 1L) {
            return createdProgress();
        }
        return null;
    }

    private static MigrationProgressRow createdProgress() {
        return new MigrationProgressRow(1L, "bxds", "tss", "CREATED", 1719100000L, 1719186400L, 3600L, 2,
                24L, 0L, 0L, 0L, 0L, 0L, null,
                null, null, null, List.of());
    }

    private static MigrationProgressRow runningProgress() {
        return new MigrationProgressRow(2L, "bxds", "tss", "RUNNING", 1719100000L, 1719186400L, 3600L, 4,
                24L, 15L, 1L, 89300000L, 1200L, 350L, 1820L,
                LocalDateTime.of(2026, 6, 23, 14, 30), null, null, runningShards());
    }

    private static MigrationProgressRow completedProgress() {
        return new MigrationProgressRow(3L, "bxds", "tss", "COMPLETED", 1719000000L, 1719086400L, 3600L, 4,
                24L, 24L, 0L, 120000000L, 5000L, 800L, 4320L,
                LocalDateTime.of(2026, 6, 22, 10, 0), LocalDateTime.of(2026, 6, 22, 11, 12), null,
                completedShards());
    }

    private static MigrationProgressRow failedProgress() {
        return new MigrationProgressRow(4L, "bxds", "tss", "FAILED", 1718900000L, 1718986400L, 3600L, 2,
                24L, 18L, 6L, 45000000L, 200L, 120L, 2700L,
                LocalDateTime.of(2026, 6, 21, 9, 0), LocalDateTime.of(2026, 6, 21, 9, 45),
                "3个分片执行超时", failedShards());
    }

    private static MigrationProgressRow cancelledProgress() {
        return new MigrationProgressRow(5L, "bxds", "tss", "CANCELLED", 1718800000L, 1718886400L, 3600L, 2,
                24L, 10L, 0L, 20000000L, 100L, 50L, 1200L,
                LocalDateTime.of(2026, 6, 20, 8, 0), LocalDateTime.of(2026, 6, 20, 8, 20),
                null, cancelledShards());
    }

    private static List<MigrationShardRow> runningShards() {
        return List.of(
                new MigrationShardRow(0, 1719100000L, 1719103600L, "COMPLETED", 5000000L, 50L, 12L, 1, 75L, null),
                new MigrationShardRow(1, 1719103600L, 1719107200L, "COMPLETED", 4800000L, 30L, 8L, 1, 70L, null),
                new MigrationShardRow(2, 1719107200L, 1719110800L, "RUNNING", 0L, 0L, 0L, 1, 45L, null),
                new MigrationShardRow(3, 1719110800L, 1719114400L, "PENDING", 0L, 0L, 0L, 0, 0L, null),
                new MigrationShardRow(4, 1719114400L, 1719118000L, "FAILED", 0L, 0L, 0L, 2, 30L, "执行超时"),
                new MigrationShardRow(5, 1719118000L, 1719121600L, "SKIPPED", 0L, 0L, 0L, 1, 1L, null)
        );
    }

    private static List<MigrationShardRow> completedShards() {
        return List.of(
                new MigrationShardRow(0, 1719000000L, 1719003600L, "COMPLETED", 5000000L, 50L, 12L, 1, 75L, null),
                new MigrationShardRow(1, 1719003600L, 1719007200L, "COMPLETED", 4800000L, 30L, 8L, 1, 70L, null),
                new MigrationShardRow(2, 1719007200L, 1719010800L, "COMPLETED", 5100000L, 40L, 10L, 1, 78L, null),
                new MigrationShardRow(3, 1719010800L, 1719014400L, "SKIPPED", 0L, 0L, 0L, 1, 1L, null),
                new MigrationShardRow(4, 1719014400L, 1719018000L, "COMPLETED", 4900000L, 35L, 9L, 1, 72L, null),
                new MigrationShardRow(5, 1719018000L, 1719021600L, "COMPLETED", 4700000L, 25L, 7L, 1, 68L, null)
        );
    }

    private static List<MigrationShardRow> failedShards() {
        return List.of(
                new MigrationShardRow(0, 1718900000L, 1718903600L, "COMPLETED", 5000000L, 50L, 12L, 1, 75L, null),
                new MigrationShardRow(1, 1718903600L, 1718907200L, "COMPLETED", 4800000L, 30L, 8L, 1, 70L, null),
                new MigrationShardRow(2, 1718907200L, 1718910800L, "FAILED", 0L, 0L, 0L, 2, 30L, "执行超时"),
                new MigrationShardRow(3, 1718910800L, 1718914400L, "FAILED", 0L, 0L, 0L, 2, 28L, "连接源库失败"),
                new MigrationShardRow(4, 1718914400L, 1718918000L, "FAILED", 0L, 0L, 0L, 3, 35L, "执行超时"),
                new MigrationShardRow(5, 1718918000L, 1718921600L, "FAILED", 0L, 0L, 0L, 2, 25L, "目标库写入失败"),
                new MigrationShardRow(6, 1718921600L, 1718925200L, "FAILED", 0L, 0L, 0L, 2, 32L, "执行超时"),
                new MigrationShardRow(7, 1718925200L, 1718928800L, "FAILED", 0L, 0L, 0L, 3, 40L, "源库查询超时")
        );
    }

    private static List<MigrationShardRow> cancelledShards() {
        return List.of(
                new MigrationShardRow(0, 1718800000L, 1718803600L, "COMPLETED", 5000000L, 50L, 12L, 1, 75L, null),
                new MigrationShardRow(1, 1718803600L, 1718807200L, "COMPLETED", 4800000L, 30L, 8L, 1, 70L, null),
                new MigrationShardRow(2, 1718807200L, 1718810800L, "COMPLETED", 4700000L, 25L, 7L, 1, 68L, null),
                new MigrationShardRow(3, 1718810800L, 1718814400L, "COMPLETED", 4900000L, 35L, 9L, 1, 72L, null),
                new MigrationShardRow(4, 1718814400L, 1718818000L, "PENDING", 0L, 0L, 0L, 0, 0L, null),
                new MigrationShardRow(5, 1718818000L, 1718821600L, "PENDING", 0L, 0L, 0L, 0, 0L, null)
        );
    }
}

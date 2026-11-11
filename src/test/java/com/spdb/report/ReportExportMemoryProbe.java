package com.spdb.report;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.time.LocalDateTime;

/**
 * Opt-in PostgreSQL process probe. Run in a separate JVM with a fixed -Xmx, not in the ordinary test suite.
 * The shared fixture creates and removes an isolated schema; never point this at a production database.
 */
public final class ReportExportMemoryProbe {
    private ReportExportMemoryProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("rose.test.postgres.url") == null) {
            throw new IllegalArgumentException("需要独立 PostgreSQL 测试实例：-Drose.test.postgres.url=...");
        }
        int count = args.length == 0 ? 100_000 : Integer.parseInt(args[0]);
        boolean legacy = args.length > 1 && "legacy".equals(args[1]);
        ReportExportBatchRunnerTest fixture = new ReportExportBatchRunnerTest();
        fixture.setUp();
        try {
            var field = ReportExportBatchRunnerTest.class.getDeclaredField("jdbc");
            field.setAccessible(true);
            JdbcTemplate jdbc = (JdbcTemplate) field.get(fixture);
            jdbc.execute("create index probe_field_identity on tss_field_comp ((coalesce(mesg_seq, '') || '|' || coalesce(orig_cdate, '') || '|' || cast(coalesce(conv_index, 0) as varchar) || '|' || cast(coalesce(conv_cindex, 0) as varchar)))");
            jdbc.execute("create index probe_field_service_seq on tss_field_comp ((" + ReportExportSourceReader.service("dest_trcd") + "), mesg_seq)");
            jdbc.execute("create index probe_ignored_identity on tss_tran_comp ((coalesce(mesg_seq, '') || '|' || coalesce(orig_cdate, '') || '|' || cast(coalesce(conv_index, 0) as varchar) || '|' || cast(coalesce(conv_cindex, 0) as varchar))) where comp_result = '5'");
            jdbc.update("insert into tss_tran_comp select 'FLOW' || n, '20260727', 1, 1, 'HOT&soap', '4' from generate_series(1, ?) n", count);
            jdbc.update("insert into tss_retcode_comp select 'FLOW' || n, '20260727', 'HOT&soap', 'AAAAAAA', 'orig', 'AAAAAAA', 'dest' from generate_series(1, ?) n", count);
            jdbc.update("insert into tss_field_comp select 'FLOW' || n, '20260727', 1, 1, 1, 'HOT&soap', 'Request.amount.extra', repeat('a', 2000), 'dest', repeat('b', 2000) from generate_series(1, ?) n", count);
            jdbc.execute("analyze tss_tran_comp");
            jdbc.execute("analyze tss_retcode_comp");
            jdbc.execute("analyze tss_field_comp");
            var named = new NamedParameterJdbcTemplate(jdbc.getDataSource());
            var manager = new JdbcTransactionManager(jdbc.getDataSource());
            LocalDateTime time = LocalDateTime.of(2026, 7, 27, 10, 0);
            long started = System.nanoTime();
            if (legacy) {
                LegacyReportExportReference.run(named, manager, "MEMORY-PROBE", "20260727", time);
            } else {
                new ReportExportBatchRunner(named, manager).run("MEMORY-PROBE", "20260727", time);
            }
            Long affected = jdbc.queryForObject("select affected_tran_count from ana_field_diff_tracking_export", Long.class);
            Long sent = jdbc.queryForObject("select sent_transaction_count from ana_report_export_summary", Long.class);
            if (affected == null || affected != count || sent == null || sent != count) {
                throw new AssertionError("压力数据的影响笔数或汇总笔数不正确：" + affected + "/" + sent);
            }
            System.out.println("MEMORY_PROBE_OK rows=" + count + " heapLimitBytes=" + Runtime.getRuntime().maxMemory()
                    + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000L + " legacy=" + legacy);
        } finally {
            fixture.removeIsolatedSchema();
        }
    }
}

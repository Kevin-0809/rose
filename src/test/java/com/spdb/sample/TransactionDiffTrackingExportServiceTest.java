package com.spdb.sample;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionDiffTrackingExportServiceTest {
    private NamedParameterJdbcTemplate jdbc;
    private DataSource dataSource;
    private TransactionDiffTrackingExportService service;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:tracking_export_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        service = new TransactionDiffTrackingExportService(
                jdbc,
                new DataSourceTransactionManager(dataSource),
                Clock.fixed(Instant.parse("2026-07-24T10:15:30Z"), ZoneOffset.UTC));
        createTables();
    }

    @Test
    void exportsOnlyTheFirstRowOfEachBatchScopedResponseCodeTriple() {
        jdbc.getJdbcTemplate().update("""
                insert into ana_tran_catalog(tran_code, service_code, tran_name, module_name, owner)
                values ('T001', 'SVC-A', '付款', '支付', '目录负责人')
                """);
        insertResult(10L, "BATCH-A", "T001", "SVC-A", "ORIG-1", "原!^\r\n描述", "DEST-1", "目标\r\n描述", "结果负责人", "SEQ-10");
        insertResult(20L, "BATCH-A", "T999", "SVC-A", "ORIG-1", "应被归一", "DEST-1", "应被归一", "后续负责人", "SEQ-20");
        insertResult(5L, "BATCH-B", "T002", "SVC-A", "ORIG-1", "其他批次", "DEST-1", "其他批次", "其他负责人", "SEQ-5");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.export("BATCH-A", output);

        String text = output.toString(StandardCharsets.UTF_8);
        String[] lines = text.split("\\n", -1);
        assertThat(lines).hasSize(3);
        assertThat(lines[0].split(Pattern.quote("!^"), -1)).containsExactly(
                "业务日期", "序号", "批次", "交易码", "交易名称", "问题级别", "登记日", "字段名", "问题描述", "交易负责人",
                "问题类型", "初步问题分析", "最终处理方案", "解决日期", "需协调", "解决人员", "流水号", "缺陷修复日期");

        String[] columns = lines[1].split(Pattern.quote("!^"), -1);
        assertThat(columns).hasSize(18);
        assertThat(columns[0]).isEqualTo("20260724");
        assertThat(columns[1]).isEqualTo("1");
        assertThat(columns[2]).isEqualTo("BATCH-A");
        assertThat(columns[3]).isEqualTo("T001");
        assertThat(columns[4]).isEqualTo("付款");
        assertThat(columns[5]).isEmpty();
        assertThat(columns[6]).isEmpty();
        assertThat(columns[7]).isEmpty();
        assertThat(columns[8]).isEqualTo("528响应码：ORIG-1；528响应描述：原   描述；CCBS响应码：DEST-1；CCBS响应描述：目标  描述");
        assertThat(columns[9]).isEqualTo("结果负责人");
        assertThat(columns[10]).isEmpty();
        assertThat(columns[11]).isEmpty();
        assertThat(columns[12]).isEmpty();
        assertThat(columns[13]).isEmpty();
        assertThat(columns[14]).isEmpty();
        assertThat(columns[15]).isEmpty();
        assertThat(columns[16]).isEqualTo("SEQ-10");
        assertThat(columns[17]).isEmpty();
        assertThat(text).doesNotContain("BATCH-B", "后续负责人", "\r");

        Integer storedCount = jdbc.getJdbcTemplate().queryForObject(
                "select count(*) from ana_tran_diff_tracking_export", Integer.class);
        assertThat(storedCount).isEqualTo(1);
        Map<String, Object> stored = jdbc.getJdbcTemplate().queryForMap("""
                select source_batch_id, business_date, row_no, module_name, transaction_owner, tran_seq_no,
                       problem_level, registration_date, field_name, problem_description, problem_type,
                       preliminary_analysis, final_solution, resolution_date, coordination_required, resolver, defect_fix_date
                  from ana_tran_diff_tracking_export
                """);
        assertThat(stored.get("source_batch_id")).isEqualTo("BATCH-A");
        assertThat(stored.get("business_date")).isEqualTo("20260724");
        assertThat(stored.get("row_no")).isEqualTo(1L);
        assertThat(stored.get("module_name")).isEqualTo("支付");
        assertThat(stored.get("transaction_owner")).isEqualTo("结果负责人");
        assertThat(stored.get("tran_seq_no")).isEqualTo("SEQ-10");
        assertThat(stored).containsEntry("problem_level", null)
                .containsEntry("registration_date", null)
                .containsEntry("field_name", null)
                .containsEntry("problem_description", null)
                .containsEntry("problem_type", null)
                .containsEntry("preliminary_analysis", null)
                .containsEntry("final_solution", null)
                .containsEntry("resolution_date", null)
                .containsEntry("coordination_required", null)
                .containsEntry("resolver", null)
                .containsEntry("defect_fix_date", null);
    }

    @Test
    void rollsBackRowsAndDoesNotWriteOutputWhenTheSecondInsertFails() {
        insertResult(10L, "BATCH-ROLLBACK", "T001", "SVC-A", "ORIG-1", "原描述", "DEST-1", "目标描述", "负责人一", "SEQ-1");
        insertResult(20L, "BATCH-ROLLBACK", "T002", "SVC-A", "ORIG-2", "原描述", "DEST-1", "目标描述", "负责人二", "SEQ-2");
        jdbc.getJdbcTemplate().execute("""
                alter table ana_tran_diff_tracking_export
                add constraint uk_tracking_export_source_batch unique (source_batch_id)
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThatThrownBy(() -> service.export("BATCH-ROLLBACK", output))
                .isInstanceOf(RuntimeException.class);

        Integer storedCount = jdbc.getJdbcTemplate().queryForObject(
                "select count(*) from ana_tran_diff_tracking_export", Integer.class);
        assertThat(storedCount).isZero();
        assertThat(output.toByteArray()).isEmpty();
    }

    @Test
    void writesToTheCallerOnlyAfterTheTransactionHasCommitted() {
        insertResult(10L, "BATCH-COMMIT", "T001", "SVC-A", "ORIG-1", "原描述", "DEST-1", "目标描述", "负责人", "SEQ-1");
        CommitAwareOutputStream output = new CommitAwareOutputStream(jdbc, 1);

        service.export("BATCH-COMMIT", output);

        assertThat(output.checkedCommittedRows()).isTrue();
        assertThat(output.toByteArray()).isNotEmpty();
    }

    @Test
    void streamsRowsThroughTheRowCallbackQueryInsteadOfLoadingAList() {
        FetchSizeRecordingDataSource streamingDataSource = new FetchSizeRecordingDataSource(dataSource);
        RecordingJdbcTemplate recordingJdbc = new RecordingJdbcTemplate(streamingDataSource);
        service = new TransactionDiffTrackingExportService(
                new NamedParameterJdbcTemplate(recordingJdbc),
                new DataSourceTransactionManager(streamingDataSource),
                Clock.fixed(Instant.parse("2026-07-24T10:15:30Z"), ZoneOffset.UTC));
        insertResult(10L, "BATCH-STREAM", "T001", "SVC-A", "ORIG-1", "原描述", "DEST-1", "目标描述", "负责人一", "SEQ-1");
        insertResult(20L, "BATCH-STREAM", "T002", "SVC-A", "ORIG-2", "原描述", "DEST-1", "目标描述", "负责人二", "SEQ-2");

        service.export("BATCH-STREAM", new ByteArrayOutputStream());

        assertThat(recordingJdbc.usedRowCallback()).isTrue();
        assertThat(streamingDataSource.fetchSizes()).contains(1000);
    }

    @Test
    void treatsEveryServiceAndResponseCodeDifferenceAsAnIndependentGroup() {
        insertResult(40L, "BATCH-GROUPS", "T001", "SVC-A", "ORIG-1", "原描述", "DEST-1", "目标描述", "A-首条负责人", "A-首条流水");
        insertResult(50L, "BATCH-GROUPS", "T001", "SVC-A", "ORIG-1", "原描述", "DEST-1", "目标描述", "A-后续负责人", "A-后续流水");
        insertResult(20L, "BATCH-GROUPS", "T001", "SVC-B", "ORIG-1", "原描述", "DEST-1", "目标描述", "服务码负责人", "服务码流水");
        insertResult(30L, "BATCH-GROUPS", "T001", "SVC-A", "ORIG-2", "原描述", "DEST-1", "目标描述", "原码负责人", "原码流水");
        insertResult(10L, "BATCH-GROUPS", "T001", "SVC-A", "ORIG-1", "原描述", "DEST-2", "目标描述", "目标码负责人", "目标码流水");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.export("BATCH-GROUPS", output);

        String[] lines = output.toString(StandardCharsets.UTF_8).split("\\n", -1);
        assertThat(lines).hasSize(6);
        assertThat(jdbc.getJdbcTemplate().query("""
                select service_code, orig_error_code, dest_error_code, transaction_owner, tran_seq_no
                  from ana_tran_diff_tracking_export
                 order by row_no
                """, (rs, rowNum) -> List.of(
                rs.getString("service_code"), rs.getString("orig_error_code"), rs.getString("dest_error_code"),
                rs.getString("transaction_owner"), rs.getString("tran_seq_no")))).containsExactly(
                List.of("SVC-A", "ORIG-1", "DEST-1", "A-首条负责人", "A-首条流水"),
                List.of("SVC-A", "ORIG-1", "DEST-2", "目标码负责人", "目标码流水"),
                List.of("SVC-A", "ORIG-2", "DEST-1", "原码负责人", "原码流水"),
                List.of("SVC-B", "ORIG-1", "DEST-1", "服务码负责人", "服务码流水"));
    }

    @Test
    void rejectsBlankSourceBatch() {
        assertThatThrownBy(() -> service.export("  ", new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请选择批次后导出");
    }

    private void createTables() {
        jdbc.getJdbcTemplate().execute("""
                create table ana_tran_catalog (
                    tran_code varchar(32) not null,
                    service_code varchar(200) not null,
                    tran_name varchar(200),
                    module_name varchar(100),
                    owner varchar(100)
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table ana_tran_diff_result (
                    result_id bigint primary key,
                    batch_id varchar(64) not null,
                    tran_code varchar(32) not null,
                    service_code varchar(200) not null,
                    sample_tran_seq_no varchar(64),
                    orig_error_code varchar(64),
                    orig_error_desc varchar(500),
                    dest_error_code varchar(64),
                    dest_error_desc varchar(500),
                    owner varchar(100)
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table ana_tran_diff_tracking_export (
                    export_id bigint generated by default as identity primary key,
                    export_timestamp timestamp not null,
                    source_batch_id varchar(64) not null,
                    business_date varchar(8) not null,
                    row_no bigint not null,
                    service_code varchar(200) not null,
                    orig_error_code varchar(64),
                    dest_error_code varchar(64),
                    tran_code varchar(32),
                    tran_name varchar(200),
                    module_name varchar(100),
                    orig_error_desc varchar(500),
                    dest_error_desc varchar(500),
                    transaction_owner varchar(100),
                    tran_seq_no varchar(64),
                    problem_level varchar(100),
                    registration_date varchar(8),
                    field_name varchar(500),
                    problem_description text,
                    problem_type varchar(100),
                    preliminary_analysis text,
                    final_solution text,
                    resolution_date varchar(8),
                    coordination_required varchar(100),
                    resolver varchar(100),
                    defect_fix_date varchar(8)
                )
                """);
    }

    private void insertResult(long resultId, String batchId, String tranCode, String serviceCode,
                              String origCode, String origDesc, String destCode, String destDesc,
                              String owner, String sequenceNo) {
        jdbc.getJdbcTemplate().update("""
                insert into ana_tran_diff_result(
                    result_id, batch_id, tran_code, service_code, sample_tran_seq_no,
                    orig_error_code, orig_error_desc, dest_error_code, dest_error_desc, owner)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, resultId, batchId, tranCode, serviceCode, sequenceNo,
                origCode, origDesc, destCode, destDesc, owner);
    }

    private static final class CommitAwareOutputStream extends ByteArrayOutputStream {
        private final NamedParameterJdbcTemplate jdbc;
        private final int expectedRows;
        private boolean checkedCommittedRows;

        private CommitAwareOutputStream(NamedParameterJdbcTemplate jdbc, int expectedRows) {
            this.jdbc = jdbc;
            this.expectedRows = expectedRows;
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            if (!checkedCommittedRows) {
                Integer count = jdbc.getJdbcTemplate().queryForObject(
                        "select count(*) from ana_tran_diff_tracking_export", Integer.class);
                assertThat(count).isEqualTo(expectedRows);
                checkedCommittedRows = true;
            }
            super.write(bytes, offset, length);
        }

        private boolean checkedCommittedRows() {
            return checkedCommittedRows;
        }
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private boolean usedRowCallback;

        private RecordingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public void query(PreparedStatementCreator preparedStatementCreator, RowCallbackHandler rowCallbackHandler) {
            usedRowCallback = true;
            super.query(preparedStatementCreator, rowCallbackHandler);
        }

        @Override
        public <T> List<T> query(PreparedStatementCreator preparedStatementCreator, RowMapper<T> rowMapper) {
            throw new AssertionError("不应将导出查询加载为 List");
        }

        private boolean usedRowCallback() {
            return usedRowCallback;
        }
    }

    private static final class FetchSizeRecordingDataSource implements DataSource {
        private final DataSource delegate;
        private final List<Integer> fetchSizes = new ArrayList<>();

        private FetchSizeRecordingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return recordingConnection(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return recordingConnection(delegate.getConnection(username, password));
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }

        private List<Integer> fetchSizes() {
            return fetchSizes;
        }

        private Connection recordingConnection(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        Object result = invoke(connection, method, args);
                        return result instanceof PreparedStatement statement ? recordingStatement(statement) : result;
                    });
        }

        private PreparedStatement recordingStatement(PreparedStatement statement) {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        if ("setFetchSize".equals(method.getName())) {
                            fetchSizes.add((Integer) args[0]);
                        }
                        return invoke(statement, method, args);
                    });
        }

        private Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}

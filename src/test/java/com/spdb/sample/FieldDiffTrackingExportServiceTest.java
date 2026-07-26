package com.spdb.sample;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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

class FieldDiffTrackingExportServiceTest {
    private NamedParameterJdbcTemplate jdbc;
    private DataSource dataSource;
    private FieldDiffTrackingExportService service;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:field_tracking_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        service = new FieldDiffTrackingExportService(jdbc, new DataSourceTransactionManager(dataSource),
                Clock.fixed(Instant.parse("2026-07-24T10:15:30Z"), ZoneOffset.UTC));
        createTables();
    }

    @Test
    void exportsOneRandomizedRowPerServiceAndSoapFieldWithCatalogDetailsAndAllNineteenColumns() {
        insertCatalog(20, "OLD", "SVC-A", "旧名称", "旧组别", "旧负责人");
        insertCatalog(10, "CAT", "SVC-A", "目录名称", "目录组别", "目录负责人");
        insertResult(1, "BATCH-A", "D-1", "SVC-A", "sop\r\n!^", "soap", "biz", "中文", "MAPPED", "原\r\n!^值", "目标\r\n!^值", "来源负责人", "SEQ-1");
        insertResult(2, "BATCH-A", "D-2", "SVC-A", "sop\r\n!^", "soap", "biz", "中文", "MAPPED", "原\r\n!^值", "目标\r\n!^值", "来源负责人2", "SEQ-2");
        insertResult(3, "BATCH-OTHER", "OTHER", "SVC-A", "x", "otherSoap", "z", "其他", "MAPPED", "其他", "其他", "其他", "SEQ-3");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.export("BATCH-A", output);

        String[] lines = output.toString(StandardCharsets.UTF_8).split("\\n", -1);
        assertThat(lines).hasSize(3);
        assertThat(lines[0].split(Pattern.quote("!^"), -1)).containsExactly(
                "业务日期", "组别", "序号", "批次", "交易码", "交易名称", "字段级", "登记日期", "字段名", "问题描述", "交易负责人", "问题类型", "初步问题分析", "最终处理方案", "解决日期", "需协调", "解决人员", "流水号", "缺陷修复日期");
        String[] columns = lines[1].split(Pattern.quote("!^"), -1);
        assertThat(columns).containsExactly(
                "20260724", "目录组别", "1", "BATCH-A", columns[4], "目录名称", "字段级", "20260724", "sop   ,soap,biz,中文",
                "528字段值：原   值；CCBS字段值：目标   值；字段映射状态：MAPPED", "目录负责人", "", "", "", "", "", "", columns[17], "");
        assertThat(columns[4]).isIn("D-1", "D-2");
        assertThat(columns[17]).isEqualTo(columns[4].equals("D-1") ? "SEQ-1" : "SEQ-2");

        Map<String, Object> stored = jdbc.getJdbcTemplate().queryForMap("""
                select tran_code, tran_name, module_name, sop_field_name, soap_field_name, bizjson_field_name,
                       field_cn_name, mapping_status, orig_field_value, dest_field_value, transaction_owner,
                       tran_seq_no, problem_level, registration_date, field_name, problem_description,
                       problem_type, preliminary_analysis, final_solution, resolution_date, coordination_required,
                       resolver, defect_fix_date
                  from ana_field_diff_tracking_export
                """);
        assertThat(stored).containsEntry("tran_code", columns[4]).containsEntry("tran_name", "目录名称")
                .containsEntry("module_name", "目录组别").containsEntry("transaction_owner", "目录负责人")
                .containsEntry("sop_field_name", "sop\r\n!^").containsEntry("soap_field_name", "soap")
                .containsEntry("bizjson_field_name", "biz").containsEntry("field_cn_name", "中文")
                .containsEntry("mapping_status", "MAPPED").containsEntry("orig_field_value", "原\r\n!^值")
                .containsEntry("dest_field_value", "目标\r\n!^值").containsEntry("tran_seq_no", columns[4].equals("D-1") ? "SEQ-1" : "SEQ-2")
                .containsEntry("problem_level", "字段级").containsEntry("registration_date", "20260724")
                .containsEntry("field_name", "sop\r\n!^,soap,biz,中文")
                .containsEntry("problem_description", "528字段值：原   值；CCBS字段值：目标   值；字段映射状态：MAPPED")
                .containsEntry("problem_type", null).containsEntry("preliminary_analysis", null)
                .containsEntry("final_solution", null).containsEntry("resolution_date", null)
                .containsEntry("coordination_required", null).containsEntry("resolver", null)
                .containsEntry("defect_fix_date", null);
    }

    @Test
    void rejectsBlankBatch() {
        assertThatThrownBy(() -> service.export(" ", new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("请选择批次后导出");
    }

    @Test
    void rollsBackAndKeepsOutputEmptyWhenPersistingFails() {
        insertResult(1, "BATCH-ROLLBACK", "D-1", "SVC-A", "sop", "soap-1", "biz", "中", "MAPPED", "源", "目标", "负责人", "SEQ-1");
        insertResult(2, "BATCH-ROLLBACK", "D-2", "SVC-A", "sop", "soap-2", "biz", "中", "MAPPED", "源", "目标", "负责人", "SEQ-2");
        jdbc.getJdbcTemplate().execute("alter table ana_field_diff_tracking_export add constraint unique_batch unique (source_batch_id)");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertThatThrownBy(() -> service.export("BATCH-ROLLBACK", output)).isInstanceOf(RuntimeException.class);

        assertThat(jdbc.getJdbcTemplate().queryForObject("select count(*) from ana_field_diff_tracking_export", Integer.class)).isZero();
        assertThat(output.toByteArray()).isEmpty();
    }

    @Test
    void copiesToCallerOnlyAfterCommitAndStreamsWithForwardOnlyFetchSize() {
        insertResult(1, "BATCH-STREAM", "D-1", "SVC-A", "sop", "soap", "biz", "中", "MAPPED", "源", "目标", "负责人", "SEQ-1");
        FetchSizeRecordingDataSource streamingDataSource = new FetchSizeRecordingDataSource(dataSource);
        RecordingJdbcTemplate recordingJdbc = new RecordingJdbcTemplate(streamingDataSource);
        service = new FieldDiffTrackingExportService(new NamedParameterJdbcTemplate(recordingJdbc),
                new DataSourceTransactionManager(streamingDataSource), Clock.fixed(Instant.parse("2026-07-24T10:15:30Z"), ZoneOffset.UTC));
        CommitAwareOutputStream output = new CommitAwareOutputStream(jdbc);

        service.export("BATCH-STREAM", output);

        assertThat(output.committed()).isTrue();
        assertThat(recordingJdbc.usedRowCallback()).isTrue();
        assertThat(streamingDataSource.fetchSizes()).contains(1000);
    }

    private void createTables() {
        jdbc.getJdbcTemplate().execute("create table ana_tran_catalog (catalog_id bigint primary key, tran_code varchar(32), service_code varchar(200), tran_name varchar(200), module_name varchar(100), owner varchar(100))");
        jdbc.getJdbcTemplate().execute("create table ana_field_diff_result (result_id bigint primary key, batch_id varchar(64), tran_code varchar(32), service_code varchar(200), sop_field_name varchar(200), soap_field_name varchar(200), bizjson_field_name varchar(200), field_cn_name varchar(200), mapping_status varchar(32), orig_field_value varchar(2000), dest_field_value varchar(2000), sample_tran_seq_no varchar(64), owner varchar(100))");
        jdbc.getJdbcTemplate().execute("create table ana_field_diff_tracking_export (export_id bigint generated by default as identity primary key, export_timestamp timestamp not null, source_batch_id varchar(64) not null, business_date varchar(8) not null, row_no bigint not null, service_code varchar(200) not null, tran_code varchar(32), tran_name varchar(200), module_name varchar(100), sop_field_name varchar(200), soap_field_name varchar(200), bizjson_field_name varchar(200), field_cn_name varchar(200), mapping_status varchar(32), orig_field_value varchar(2000), dest_field_value varchar(2000), transaction_owner varchar(100), tran_seq_no varchar(64), problem_level varchar(100), registration_date varchar(8), field_name varchar(500), problem_description text, problem_type varchar(100), preliminary_analysis text, final_solution text, resolution_date varchar(8), coordination_required varchar(100), resolver varchar(100), defect_fix_date varchar(8))");
    }

    private void insertCatalog(long id, String code, String serviceCode, String name, String module, String owner) {
        jdbc.getJdbcTemplate().update("insert into ana_tran_catalog values (?, ?, ?, ?, ?, ?)", id, code, serviceCode, name, module, owner);
    }

    private void insertResult(long id, String batch, String tranCode, String serviceCode, String sop, String soap, String bizjson, String fieldCn, String status, String orig, String dest, String owner, String sequence) {
        jdbc.getJdbcTemplate().update("insert into ana_field_diff_result values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, batch, tranCode, serviceCode, sop, soap, bizjson, fieldCn, status, orig, dest, sequence, owner);
    }

    private static final class CommitAwareOutputStream extends ByteArrayOutputStream {
        private final NamedParameterJdbcTemplate jdbc;
        private boolean committed;

        private CommitAwareOutputStream(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            assertThat(jdbc.getJdbcTemplate().queryForObject("select count(*) from ana_field_diff_tracking_export", Integer.class)).isEqualTo(1);
            committed = true;
            super.write(bytes, offset, length);
        }

        private boolean committed() { return committed; }
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private boolean usedRowCallback;
        private RecordingJdbcTemplate(DataSource dataSource) { super(dataSource); }
        @Override public void query(PreparedStatementCreator creator, RowCallbackHandler callback) { usedRowCallback = true; super.query(creator, callback); }
        @Override public <T> List<T> query(PreparedStatementCreator creator, RowMapper<T> mapper) { throw new AssertionError("不应加载为列表"); }
        private boolean usedRowCallback() { return usedRowCallback; }
    }

    private static final class FetchSizeRecordingDataSource implements DataSource {
        private final DataSource delegate;
        private final List<Integer> fetchSizes = new ArrayList<>();
        private FetchSizeRecordingDataSource(DataSource delegate) { this.delegate = delegate; }
        @Override public Connection getConnection() throws SQLException { return connection(delegate.getConnection()); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return connection(delegate.getConnection(u, p)); }
        @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        @Override public void setLogWriter(PrintWriter writer) throws SQLException { delegate.setLogWriter(writer); }
        @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        @Override public Logger getParentLogger() { return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException { return delegate.unwrap(type); }
        @Override public boolean isWrapperFor(Class<?> type) throws SQLException { return delegate.isWrapperFor(type); }
        private List<Integer> fetchSizes() { return fetchSizes; }
        private Connection connection(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
                Object result = invoke(connection, method, args);
                return result instanceof PreparedStatement statement ? statement(statement) : result;
            });
        }
        private PreparedStatement statement(PreparedStatement statement) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class[]{PreparedStatement.class}, (proxy, method, args) -> {
                if ("setFetchSize".equals(method.getName())) fetchSizes.add((Integer) args[0]);
                return invoke(statement, method, args);
            });
        }
        private Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); } catch (InvocationTargetException exception) { throw exception.getCause(); }
        }
    }
}

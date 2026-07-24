package com.spdb.sample;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

@Service
public class TransactionDiffTrackingExportService {
    private static final int STREAM_FETCH_SIZE = 1000;
    private static final String SEPARATOR = "!^";
    private static final String HEADER = "业务日期!^序号!^批次!^交易码!^交易名称!^问题级别!^登记日!^字段名!^问题描述!^交易负责人!^问题类型!^初步问题分析!^最终处理方案!^解决日期!^需协调!^解决人员!^流水号!^缺陷修复日期\n";
    private static final String STREAM_QUERY = """
            select d.batch_id, d.service_code, d.orig_error_code, d.dest_error_code,
                   d.tran_code, c.tran_name, c.module_name, d.orig_error_desc,
                   d.dest_error_desc, d.owner, d.sample_tran_seq_no
              from (
                  select d.*, row_number() over (
                      partition by d.service_code, d.orig_error_code, d.dest_error_code
                      order by d.result_id
                  ) as row_num
                    from ana_tran_diff_result d
                   where d.batch_id = ?
              ) d
              left join ana_tran_catalog c
                on c.tran_code = d.tran_code
               and c.service_code = d.service_code
             where d.row_num = 1
             order by d.service_code, d.orig_error_code, d.dest_error_code
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public TransactionDiffTrackingExportService(NamedParameterJdbcTemplate jdbc,
                                                PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, Clock.systemDefaultZone());
    }

    public TransactionDiffTrackingExportService(NamedParameterJdbcTemplate jdbc,
                                                PlatformTransactionManager transactionManager,
                                                Clock clock) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void export(String sourceBatchId, OutputStream outputStream) {
        if (sourceBatchId == null || sourceBatchId.isBlank()) {
            throw new IllegalArgumentException("请选择批次后导出");
        }
        Objects.requireNonNull(outputStream, "outputStream");

        Path temporaryFile = createTemporaryFile();
        try {
            transactionTemplate.executeWithoutResult(status -> writeAndPersist(sourceBatchId, temporaryFile));
            Files.copy(temporaryFile, outputStream);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            deleteTemporaryFile(temporaryFile);
        }
    }

    private Path createTemporaryFile() {
        try {
            return Files.createTempFile("transaction-diff-tracking-export-", ".txt");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void writeAndPersist(String sourceBatchId, Path temporaryFile) {
        LocalDateTime exportTime = LocalDateTime.now(clock);
        String businessDate = exportTime.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        long[] rowNo = {0};
        try (BufferedWriter writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write(HEADER);
            jdbc.getJdbcTemplate().query(connection -> streamingStatement(connection, sourceBatchId), rs -> {
                TransactionDiffTrackingExportRow row = new TransactionDiffTrackingExportRow(
                        rs.getString("batch_id"),
                        rs.getString("service_code"),
                        rs.getString("orig_error_code"),
                        rs.getString("dest_error_code"),
                        rs.getString("tran_code"),
                        rs.getString("tran_name"),
                        rs.getString("module_name"),
                        rs.getString("orig_error_desc"),
                        rs.getString("dest_error_desc"),
                        rs.getString("owner"),
                        rs.getString("sample_tran_seq_no"));
                long currentRowNo = ++rowNo[0];
                insert(row, exportTime, businessDate, currentRowNo);
                writeTextRow(writer, row, businessDate, currentRowNo);
            });
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private PreparedStatement streamingStatement(Connection connection, String sourceBatchId) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
                STREAM_QUERY, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        statement.setFetchSize(STREAM_FETCH_SIZE);
        statement.setString(1, sourceBatchId);
        return statement;
    }

    private void insert(TransactionDiffTrackingExportRow row, LocalDateTime exportTime,
                        String businessDate, long rowNo) {
        jdbc.update("""
                insert into ana_tran_diff_tracking_export(
                    export_timestamp, source_batch_id, business_date, row_no, service_code,
                    orig_error_code, dest_error_code, tran_code, tran_name, module_name,
                    orig_error_desc, dest_error_desc, transaction_owner, tran_seq_no,
                    problem_level, registration_date, field_name, problem_description, problem_type,
                    preliminary_analysis, final_solution, resolution_date, coordination_required,
                    resolver, defect_fix_date
                ) values (
                    :exportTimestamp, :sourceBatchId, :businessDate, :rowNo, :serviceCode,
                    :origErrorCode, :destErrorCode, :tranCode, :tranName, :moduleName,
                    :origErrorDesc, :destErrorDesc, :transactionOwner, :tranSeqNo,
                    :problemLevel, :registrationDate, :fieldName, :problemDescription, :problemType,
                    :preliminaryAnalysis, :finalSolution, :resolutionDate, :coordinationRequired,
                    :resolver, :defectFixDate
                )
                """, new MapSqlParameterSource()
                .addValue("exportTimestamp", Timestamp.valueOf(exportTime))
                .addValue("sourceBatchId", row.sourceBatchId())
                .addValue("businessDate", businessDate)
                .addValue("rowNo", rowNo)
                .addValue("serviceCode", row.serviceCode())
                .addValue("origErrorCode", row.origErrorCode())
                .addValue("destErrorCode", row.destErrorCode())
                .addValue("tranCode", row.tranCode())
                .addValue("tranName", row.tranName())
                .addValue("moduleName", row.moduleName())
                .addValue("origErrorDesc", row.origErrorDesc())
                .addValue("destErrorDesc", row.destErrorDesc())
                .addValue("transactionOwner", row.transactionOwner())
                .addValue("tranSeqNo", row.tranSeqNo())
                .addValue("problemLevel", null)
                .addValue("registrationDate", null)
                .addValue("fieldName", null)
                .addValue("problemDescription", null)
                .addValue("problemType", null)
                .addValue("preliminaryAnalysis", null)
                .addValue("finalSolution", null)
                .addValue("resolutionDate", null)
                .addValue("coordinationRequired", null)
                .addValue("resolver", null)
                .addValue("defectFixDate", null));
    }

    private void writeTextRow(BufferedWriter writer, TransactionDiffTrackingExportRow row,
                              String businessDate, long rowNo) {
        String problemDescription = "528响应码：" + value(row.origErrorCode())
                + "；528响应描述：" + value(row.origErrorDesc())
                + "；CCBS响应码：" + value(row.destErrorCode())
                + "；CCBS响应描述：" + value(row.destErrorDesc());
        String line = String.join(SEPARATOR,
                text(businessDate), text(Long.toString(rowNo)), text(row.sourceBatchId()), text(row.tranCode()),
                text(row.tranName()), "", "", "", text(problemDescription), text(row.transactionOwner()),
                "", "", "", "", "", "", text(row.tranSeqNo()), "") + "\n";
        try {
            writer.write(line);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String text(String value) {
        return value(value).replace("!^", " ").replace('\r', ' ').replace('\n', ' ');
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private void deleteTemporaryFile(Path temporaryFile) {
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

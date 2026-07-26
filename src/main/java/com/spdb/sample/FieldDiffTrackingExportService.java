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
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class FieldDiffTrackingExportService {
    private static final int STREAM_FETCH_SIZE = 1000;
    private static final String SEPARATOR = "!^";
    private static final String HEADER = "业务日期!^组别!^序号!^批次!^交易码!^交易名称!^字段级!^登记日期!^字段名!^问题描述!^交易负责人!^问题类型!^初步问题分析!^最终处理方案!^解决日期!^需协调!^解决人员!^流水号!^缺陷修复日期\n";
    private static final String STREAM_QUERY = """
            select d.batch_id, d.tran_code, d.service_code, d.sop_field_name, d.soap_field_name,
                   d.bizjson_field_name, d.field_cn_name, d.mapping_status, d.orig_field_value,
                   d.dest_field_value, d.sample_tran_seq_no, c.tran_name, c.module_name, c.owner
              from (
                  select d.*, row_number() over (
                      partition by d.service_code, d.soap_field_name order by random()
                  ) as row_num
                    from ana_field_diff_result d
                   where d.batch_id = ?
              ) d
              left join lateral (
                  select c.tran_name, c.owner, c.module_name
                    from ana_tran_catalog c
                   where c.service_code = d.service_code
                   order by c.catalog_id
                   limit 1
              ) c on true
             where d.row_num = 1
             order by d.service_code, d.soap_field_name
            """;
    private static final String H2_STREAM_QUERY = """
            select d.batch_id, d.tran_code, d.service_code, d.sop_field_name, d.soap_field_name,
                   d.bizjson_field_name, d.field_cn_name, d.mapping_status, d.orig_field_value,
                   d.dest_field_value, d.sample_tran_seq_no, c.tran_name, c.module_name, c.owner
              from (
                  select d.*, row_number() over (
                      partition by d.service_code, d.soap_field_name order by random()
                  ) as row_num
                    from ana_field_diff_result d
                   where d.batch_id = ?
              ) d
              left join ana_tran_catalog c
                on c.service_code = d.service_code
               and c.catalog_id = (
                   select min(c2.catalog_id) from ana_tran_catalog c2 where c2.service_code = d.service_code
               )
             where d.row_num = 1
             order by d.service_code, d.soap_field_name
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public FieldDiffTrackingExportService(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, Clock.systemDefaultZone());
    }

    public FieldDiffTrackingExportService(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
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

    private void writeAndPersist(String sourceBatchId, Path temporaryFile) {
        LocalDateTime exportTime = LocalDateTime.now(clock);
        String businessDate = exportTime.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
        long[] rowNo = {0};
        try (BufferedWriter writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write(HEADER);
            jdbc.getJdbcTemplate().query(connection -> streamingStatement(connection, sourceBatchId), resultSet -> {
                FieldDiffTrackingExportRow row = row(resultSet);
                long currentRowNo = ++rowNo[0];
                String fieldName = fieldName(row);
                String problemDescription = text(problemDescription(row));
                insert(row, exportTime, businessDate, currentRowNo, fieldName, problemDescription);
                write(writer, row, businessDate, currentRowNo, fieldName, problemDescription);
            });
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private FieldDiffTrackingExportRow row(java.sql.ResultSet resultSet) throws SQLException {
        return new FieldDiffTrackingExportRow(resultSet.getString("batch_id"), resultSet.getString("tran_code"),
                resultSet.getString("service_code"), resultSet.getString("sop_field_name"), resultSet.getString("soap_field_name"),
                resultSet.getString("bizjson_field_name"), resultSet.getString("field_cn_name"), resultSet.getString("mapping_status"),
                resultSet.getString("orig_field_value"), resultSet.getString("dest_field_value"), resultSet.getString("tran_name"),
                resultSet.getString("module_name"), resultSet.getString("owner"), resultSet.getString("sample_tran_seq_no"));
    }

    private PreparedStatement streamingStatement(Connection connection, String sourceBatchId) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(isH2(connection) ? H2_STREAM_QUERY : STREAM_QUERY,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        statement.setFetchSize(STREAM_FETCH_SIZE);
        statement.setString(1, sourceBatchId);
        return statement;
    }

    private boolean isH2(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("H2");
    }

    private void insert(FieldDiffTrackingExportRow row, LocalDateTime exportTime, String businessDate, long rowNo,
                        String fieldName, String problemDescription) {
        jdbc.update("""
                insert into ana_field_diff_tracking_export(
                    export_timestamp, source_batch_id, business_date, row_no, service_code, tran_code, tran_name,
                    module_name, sop_field_name, soap_field_name, bizjson_field_name, field_cn_name, mapping_status,
                    orig_field_value, dest_field_value, transaction_owner, tran_seq_no, problem_level,
                    registration_date, field_name, problem_description, problem_type, preliminary_analysis,
                    final_solution, resolution_date, coordination_required, resolver, defect_fix_date
                ) values (
                    :exportTimestamp, :sourceBatchId, :businessDate, :rowNo, :serviceCode, :tranCode, :tranName,
                    :moduleName, :sopFieldName, :soapFieldName, :bizjsonFieldName, :fieldCnName, :mappingStatus,
                    :origFieldValue, :destFieldValue, :transactionOwner, :tranSeqNo, :problemLevel,
                    :registrationDate, :fieldName, :problemDescription, null, null, null, null, null, null, null
                )
                """, new MapSqlParameterSource()
                .addValue("exportTimestamp", Timestamp.valueOf(exportTime)).addValue("sourceBatchId", row.sourceBatchId())
                .addValue("businessDate", businessDate).addValue("rowNo", rowNo).addValue("serviceCode", row.serviceCode())
                .addValue("tranCode", row.tranCode()).addValue("tranName", row.tranName()).addValue("moduleName", row.moduleName())
                .addValue("sopFieldName", row.sopFieldName()).addValue("soapFieldName", row.soapFieldName())
                .addValue("bizjsonFieldName", row.bizjsonFieldName()).addValue("fieldCnName", row.fieldCnName())
                .addValue("mappingStatus", row.mappingStatus()).addValue("origFieldValue", row.origFieldValue())
                .addValue("destFieldValue", row.destFieldValue()).addValue("transactionOwner", row.transactionOwner())
                .addValue("tranSeqNo", row.tranSeqNo()).addValue("problemLevel", "字段级")
                .addValue("registrationDate", businessDate).addValue("fieldName", fieldName)
                .addValue("problemDescription", problemDescription));
    }

    private void write(BufferedWriter writer, FieldDiffTrackingExportRow row, String businessDate, long rowNo,
                       String fieldName, String problemDescription) {
        String line = String.join(SEPARATOR, text(businessDate), text(row.moduleName()), text(Long.toString(rowNo)),
                text(row.sourceBatchId()), text(row.tranCode()), text(row.tranName()), "字段级", text(businessDate),
                text(fieldName), text(problemDescription), text(row.transactionOwner()), "", "", "", "", "", "",
                text(row.tranSeqNo()), "") + "\n";
        try {
            writer.write(line);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String fieldName(FieldDiffTrackingExportRow row) {
        return Stream.of(row.sopFieldName(), row.soapFieldName(), row.bizjsonFieldName(), row.fieldCnName())
                .filter(value -> value != null && !value.isBlank()).collect(Collectors.joining(","));
    }

    private String problemDescription(FieldDiffTrackingExportRow row) {
        return "528字段值：" + value(row.origFieldValue()) + "；CCBS字段值：" + value(row.destFieldValue())
                + "；字段映射状态：" + value(row.mappingStatus());
    }

    private String text(String value) {
        return value(value).replace(SEPARATOR, " ").replace('\r', ' ').replace('\n', ' ');
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private Path createTemporaryFile() {
        try {
            return Files.createTempFile("field-diff-tracking-export-", ".txt");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void deleteTemporaryFile(Path temporaryFile) {
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}

package com.spdb.message;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageFlowLogServiceTest {

    private MessageFlowLogService service;
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:message_flow_log;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().execute("drop table if exists msg_flow_log_request");
        jdbc.getJdbcTemplate().execute("drop table if exists msg_flow_log_response");
        jdbc.getJdbcTemplate().execute("""
                create table msg_flow_log_request (
                    source_ip varchar(64) not null,
                    trans_id varchar(64) not null,
                    txn_code varchar(64) not null,
                    txn_time bigint not null,
                    message_type varchar(32),
                    request_message bytea,
                    global_seq_no varchar(64),
                    tran_teller_no varchar(32)
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table msg_flow_log_response (
                    source_ip varchar(64) not null,
                    trans_id varchar(64) not null,
                    txn_code varchar(64) not null,
                    response_time bigint,
                    message_type varchar(32),
                    response_message bytea,
                    return_code varchar(32),
                    return_msg varchar(512)
                )
                """);
        jdbc.getJdbcTemplate().update("""
                insert into msg_flow_log_request (
                    source_ip, trans_id, txn_code, txn_time, message_type,
                    request_message, global_seq_no, tran_teller_no
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "10.10.1.11",
                "0200202606150001",
                "QRY_BAL",
                1781485220000L,
                "JSON",
                "{\"acctNo\":\"6222\"}".getBytes(StandardCharsets.UTF_8),
                "213290350200202606150001",
                "TELLER001"
        );
        jdbc.getJdbcTemplate().update("""
                insert into msg_flow_log_response (
                    source_ip, trans_id, txn_code, response_time, message_type,
                    response_message, return_code, return_msg
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "10.10.1.11",
                "0200202606150001",
                "QRY_BAL",
                1781485220123L,
                "JSON",
                "{\"balance\":\"100.00\"}".getBytes(StandardCharsets.UTF_8),
                "000000",
                "交易成功"
        );
        jdbc.getJdbcTemplate().update("""
                insert into msg_flow_log_request (
                    source_ip, trans_id, txn_code, txn_time, message_type,
                    request_message, global_seq_no, tran_teller_no
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "10.10.1.12",
                "0200202606150002",
                "QRY_BAL",
                1781485230000L,
                "JSON",
                "7B2274786E436F6465223A225152595F42414C227D".getBytes(StandardCharsets.UTF_8),
                "213290350200202606150002",
                "TELLER002"
        );
        service = new MessageFlowLogService(jdbc);
    }

    @Test
    void normalizesRawInputToTransId() {
        assertThat(MessageFlowLogService.normalizeTransId("0200202606150001")).isEqualTo("0200202606150001");
        assertThat(MessageFlowLogService.normalizeTransId("10.10.1.11_0200202606150001")).isEqualTo("0200202606150001");
        assertThat(MessageFlowLogService.normalizeTransId("  ESB-A/0200202606150001  ")).isEqualTo("0200202606150001");
        assertThat(MessageFlowLogService.normalizeTransId("10.10.1.11_ABC")).isEqualTo("10.10.1.11_ABC");
    }

    @Test
    void findsRequestAndResponseByNormalizedTransId() {
        List<MessageFlowLogRow> rows = service.search("10.10.1.11_0200202606150001");

        assertThat(rows).hasSize(1);
        MessageFlowLogRow row = rows.get(0);
        assertThat(row.transId()).isEqualTo("0200202606150001");
        assertThat(row.sourceIp()).isEqualTo("10.10.1.11");
        assertThat(row.txnCode()).isEqualTo("QRY_BAL");
        assertThat(row.requestMessage()).isEqualTo("{\"acctNo\":\"6222\"}");
        assertThat(row.responseMessage()).isEqualTo("{\"balance\":\"100.00\"}");
        assertThat(row.returnCode()).isEqualTo("000000");
        assertThat(row.returnMsg()).isEqualTo("交易成功");
    }

    @Test
    void decodesHexEncodedBlobTextToReadableMessage() {
        List<MessageFlowLogRow> rows = service.search("0200202606150002");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).requestMessage()).isEqualTo("{\"txnCode\":\"QRY_BAL\"}");
    }

    @Test
    void returnsEmptyRowsForBlankInput() {
        assertThat(service.search(" ")).isEmpty();
    }

    @Test
    void savesRequestAndResponseRowsFromEntryForm() {
        MessageFlowLogEntryForm form = new MessageFlowLogEntryForm(
                " 10.10.1.20 ",
                " 0200202606220001 ",
                " PAY001 ",
                "JSON",
                1782090000000L,
                " GLOBAL-1 ",
                " TELLER009 ",
                "{\"amount\":\"88.00\"}",
                1782090000123L,
                "000000",
                "交易成功",
                "{\"status\":\"OK\"}"
        );

        service.saveEntry(form);

        Map<String, Object> request = jdbc.getJdbcTemplate().queryForMap(
                "select * from msg_flow_log_request where trans_id = '0200202606220001'"
        );
        assertThat(request.get("source_ip")).isEqualTo("10.10.1.20");
        assertThat(request.get("txn_code")).isEqualTo("PAY001");
        assertThat(request.get("txn_time")).isEqualTo(1782090000000L);
        assertThat(request.get("message_type")).isEqualTo("JSON");
        assertThat(request.get("global_seq_no")).isEqualTo("GLOBAL-1");
        assertThat(request.get("tran_teller_no")).isEqualTo("TELLER009");
        assertThat(new String((byte[]) request.get("request_message"), StandardCharsets.UTF_8))
                .isEqualTo("{\"amount\":\"88.00\"}");

        Map<String, Object> response = jdbc.getJdbcTemplate().queryForMap(
                "select * from msg_flow_log_response where trans_id = '0200202606220001'"
        );
        assertThat(response.get("source_ip")).isEqualTo("10.10.1.20");
        assertThat(response.get("txn_code")).isEqualTo("PAY001");
        assertThat(response.get("response_time")).isEqualTo(1782090000123L);
        assertThat(response.get("message_type")).isEqualTo("JSON");
        assertThat(response.get("return_code")).isEqualTo("000000");
        assertThat(response.get("return_msg")).isEqualTo("交易成功");
        assertThat(new String((byte[]) response.get("response_message"), StandardCharsets.UTF_8))
                .isEqualTo("{\"status\":\"OK\"}");
    }

    @Test
    void savesOnlyRequestWhenResponseFieldsAreBlank() {
        MessageFlowLogEntryForm form = new MessageFlowLogEntryForm(
                "10.10.1.21",
                "0200202606220002",
                "PAY002",
                "JSON",
                1782090100000L,
                "",
                "",
                "{\"amount\":\"99.00\"}",
                null,
                "",
                "",
                ""
        );

        service.saveEntry(form);

        Integer requestCount = jdbc.getJdbcTemplate().queryForObject(
                "select count(*) from msg_flow_log_request where trans_id = '0200202606220002'",
                Integer.class
        );
        Integer responseCount = jdbc.getJdbcTemplate().queryForObject(
                "select count(*) from msg_flow_log_response where trans_id = '0200202606220002'",
                Integer.class
        );

        assertThat(requestCount).isEqualTo(1);
        assertThat(responseCount).isZero();
    }
}

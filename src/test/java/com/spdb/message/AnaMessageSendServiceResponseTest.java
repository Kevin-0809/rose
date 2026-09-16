package com.spdb.message;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnaMessageSendServiceResponseTest {
    private static final String TRANS_ID = "TEST-TXN-0001";

    private HttpServer server;
    private DataSource dataSource;
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = ("{\"transId\":\"02000643671024000000008228\","
                    + "\"txnCode\":\"S40777195FcyQryBnkChkQry&bzjson\","
                    + "\"returnCode\":\"000000\",\"returnMsg\":\"交易成功\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:ana_send;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        createSchema();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void storesResponseTextAndReturnCodeDecodedAsUtf8() {
        insertPendingRequest();

        service().start("528", 1, 60, 0, 5);

        assertThat(sendStatus()).isEqualTo("SUCCESS");
        Map<String, Object> row = responseRow();
        assertThat(row.get("return_code")).isEqualTo("000000");
        assertThat((String) row.get("response_message"))
                .contains("\"returnCode\":\"000000\"")
                .contains("交易成功")
                .doesNotContain("\uFFFD");
    }

    private AnaMessageSendService service() {
        return new AnaMessageSendService(jdbc, new DataSourceTransactionManager(dataSource));
    }

    private void insertPendingRequest() {
        jdbc.getJdbcTemplate().update("""
                insert into ana_msg_flow_log_request (
                    source_ip, trans_id, txn_code, txn_time, message_type,
                    request_message, send_status, send_attempts
                ) values (?, ?, ?, ?, ?, ?, 'PENDING', 0)
                """,
                "10.10.200.61", TRANS_ID, "TXN4001", 20260908110000000L, "bzjson",
                "{\"seq\":1}".getBytes(StandardCharsets.UTF_8));
    }

    private String sendStatus() {
        return jdbc.queryForObject("select send_status from ana_msg_flow_log_request where trans_id=:id",
                new MapSqlParameterSource("id", TRANS_ID), String.class);
    }

    private Map<String, Object> responseRow() {
        return jdbc.queryForMap("select return_code, response_message, http_status from ana_msg_flow_log_response " +
                "where trans_id=:id", new MapSqlParameterSource("id", TRANS_ID));
    }

    private void createSchema() {
        for (String table : new String[]{"ana_msg_flow_log_request", "ana_msg_flow_log_response",
                "tss_service_control", "tss_service_auth", "system_config"}) {
            jdbc.getJdbcTemplate().execute("drop table if exists " + table);
        }
        jdbc.getJdbcTemplate().execute("""
                create table ana_msg_flow_log_request (
                    source_ip varchar(64) not null, trans_id varchar(64) not null, txn_code varchar(200),
                    txn_time bigint, message_type varchar(32), request_message bytea,
                    global_seq_no varchar(64), tran_teller_no varchar(64),
                    send_status varchar(16) not null default 'PENDING', send_time timestamp,
                    send_http_status integer, send_error varchar(1000), send_attempts integer not null default 0,
                    primary key (source_ip, trans_id)
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table ana_msg_flow_log_response (
                    source_ip varchar(64) not null, trans_id varchar(64) not null, response_time bigint not null,
                    txn_code varchar(200), message_type varchar(32), response_message text, return_code varchar(64),
                    return_msg varchar(1000), http_status integer, service_address varchar(500),
                    created_time timestamp not null default current_timestamp,
                    primary key (source_ip, trans_id, response_time)
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table tss_service_control (
                    protocol_id varchar(100) primary key, service_address varchar(500), pkg_charset varchar(32)
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table tss_service_auth (
                    protocol_id varchar(100) primary key, sid varchar(64) not null, gk varchar(500),
                    wk varchar(500), pk varchar(500)
                )
                """);
        jdbc.getJdbcTemplate().execute("create table system_config (config_key varchar(100) primary key, config_value varchar(500))");
        jdbc.getJdbcTemplate().update("insert into tss_service_control (protocol_id, service_address, pkg_charset) values (?, ?, ?)",
                "528_json", "127.0.0.1:" + server.getAddress().getPort(), "GBK");
        jdbc.getJdbcTemplate().update("insert into tss_service_auth (protocol_id, sid, gk, wk, pk) values (?, ?, ?, ?, ?)",
                "528_json", "10530013", "P3FWPOZ4TmU8TV4jOiFDJA==", "8ODkUKS5SnKloea3uh7KyQ==", "he4KpKWTQbYHINRahOZ+Fw==");
    }
}

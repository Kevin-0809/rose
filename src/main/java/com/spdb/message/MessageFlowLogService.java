package com.spdb.message;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class MessageFlowLogService {
    private static final String TRANS_ID_PREFIX = "0200";
    private static final Charset GBK = Charset.forName("GBK");

    private final NamedParameterJdbcTemplate jdbc;

    public MessageFlowLogService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MessageFlowLogRow> search(String rawQuery) {
        String transId = normalizeTransId(rawQuery);
        if (!StringUtils.hasText(transId)) {
            return List.of();
        }
        return jdbc.query("""
                select r.source_ip,
                       r.trans_id,
                       r.txn_code,
                       r.txn_time,
                       r.message_type as request_message_type,
                       r.global_seq_no,
                       r.tran_teller_no,
                       s.response_time,
                       s.message_type as response_message_type,
                       s.return_code,
                       s.return_msg,
                       r.request_message,
                       s.response_message
                from msg_flow_log_request r
                left join msg_flow_log_response s
                  on s.trans_id = r.trans_id
                 and s.source_ip = r.source_ip
                where r.trans_id = :transId
                union all
                select s.source_ip,
                       s.trans_id,
                       s.txn_code,
                       null as txn_time,
                       null as request_message_type,
                       null as global_seq_no,
                       null as tran_teller_no,
                       s.response_time,
                       s.message_type as response_message_type,
                       s.return_code,
                       s.return_msg,
                       null as request_message,
                       s.response_message
                from msg_flow_log_response s
                where s.trans_id = :transId
                  and not exists (
                      select 1
                      from msg_flow_log_request r
                      where r.trans_id = s.trans_id
                        and r.source_ip = s.source_ip
                  )
                order by txn_time desc, response_time desc, source_ip
                """, new MapSqlParameterSource("transId", transId), (rs, rowNum) -> new MessageFlowLogRow(
                rs.getString("source_ip"),
                rs.getString("trans_id"),
                rs.getString("txn_code"),
                (Long) rs.getObject("txn_time"),
                rs.getString("request_message_type"),
                rs.getString("global_seq_no"),
                rs.getString("tran_teller_no"),
                (Long) rs.getObject("response_time"),
                rs.getString("response_message_type"),
                rs.getString("return_code"),
                rs.getString("return_msg"),
                decode(rs.getBytes("request_message")),
                decode(rs.getBytes("response_message"))
        ));
    }

    public static String normalizeTransId(String rawQuery) {
        if (!StringUtils.hasText(rawQuery)) {
            return "";
        }
        String value = rawQuery.trim();
        if (value.startsWith(TRANS_ID_PREFIX)) {
            return value;
        }
        int index = value.indexOf(TRANS_ID_PREFIX);
        if (index >= 0) {
            return value.substring(index).trim();
        }
        return value;
    }

    private static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String text = decodeText(bytes);
        if (isHexText(text)) {
            return decodeText(hexToBytes(text));
        }
        return text;
    }

    private static String decodeText(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            return GBK.decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    private static boolean isHexText(String text) {
        if (text.length() < 2 || text.length() % 2 != 0) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.digit(text.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] hexToBytes(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < text.length(); i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(text.substring(i, i + 2), 16);
        }
        return bytes;
    }
}

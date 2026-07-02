package com.spdb.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.io.IOException;
import java.io.StringReader;

@Service
public class MessageFlowLogService {
    private static final String TRANS_ID_PREFIX = "0200";
    private static final Charset GBK = Charset.forName("GBK");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;

    public MessageFlowLogService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void saveEntry(MessageFlowLogEntryForm form) {
        if (form == null || !form.hasRequiredRequestFields()) {
            throw new IllegalArgumentException("sourceIp, transId, txnCode, txnTime, and requestMessage are required");
        }
        MapSqlParameterSource requestParams = new MapSqlParameterSource()
                .addValue("sourceIp", form.cleanSourceIp())
                .addValue("transId", form.cleanTransId())
                .addValue("txnCode", form.cleanTxnCode())
                .addValue("txnTime", form.txnTime())
                .addValue("messageType", form.cleanMessageType())
                .addValue("requestMessage", encodeForBlob(form.cleanRequestMessage()))
                .addValue("globalSeqNo", form.cleanGlobalSeqNo())
                .addValue("tranTellerNo", form.cleanTranTellerNo());
        jdbc.update("""
                insert into msg_flow_log_request (
                    source_ip, trans_id, txn_code, txn_time, message_type,
                    request_message, global_seq_no, tran_teller_no
                ) values (
                    :sourceIp, :transId, :txnCode, :txnTime, :messageType,
                    :requestMessage, :globalSeqNo, :tranTellerNo
                )
                """, requestParams);

        if (!form.hasResponseFields()) {
            return;
        }
        MapSqlParameterSource responseParams = new MapSqlParameterSource()
                .addValue("sourceIp", form.cleanSourceIp())
                .addValue("transId", form.cleanTransId())
                .addValue("txnCode", form.cleanTxnCode())
                .addValue("responseTime", form.responseTime())
                .addValue("messageType", form.cleanMessageType())
                .addValue("responseMessage", encodeForBlob(form.cleanResponseMessage()))
                .addValue("returnCode", form.cleanReturnCode())
                .addValue("returnMsg", form.cleanReturnMsg());
        jdbc.update("""
                insert into msg_flow_log_response (
                    source_ip, trans_id, txn_code, response_time, message_type,
                    response_message, return_code, return_msg
                ) values (
                    :sourceIp, :transId, :txnCode, :responseTime, :messageType,
                    :responseMessage, :returnCode, :returnMsg
                )
                """, responseParams);
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
        byte[] messageBytes = bytes;
        String rawText = decodeText(bytes).trim();
        if (isHexText(rawText)) {
            messageBytes = hexToBytes(rawText);
        }
        String messageText = decodeText(messageBytes);
        if (isReadableMessage(messageText)) {
            return messageText;
        }
        return toHex(messageBytes);
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

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }

    private static boolean isReadableMessage(String text) {
        String trimmed = text.trim();
        return isJson(trimmed) || isXml(trimmed);
    }

    private static boolean isJson(String text) {
        if (!text.startsWith("{") && !text.startsWith("[")) {
            return false;
        }
        try {
            OBJECT_MAPPER.readTree(text);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean isXml(String text) {
        if (!text.startsWith("<")) {
            return false;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            factory.newDocumentBuilder().parse(new InputSource(new StringReader(text)));
            return true;
        } catch (IOException | ParserConfigurationException | SAXException ex) {
            return false;
        }
    }

    private static String encodeForBlob(String value) {
        if (value == null) {
            return null;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }
}

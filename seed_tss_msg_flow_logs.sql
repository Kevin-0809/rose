-- 数据源: tss
-- 目标表:
--   tss.msg_flow_log_request
--   tss.msg_flow_log_response
-- 规则:
--   1. 基于 tss.ana_tran_catalog 中每个 service_code 生成 500 笔交易
--   2. request / response 使用相同 source_ip、trans_id、txn_code 一一对应
--   3. txn_code = service_code || '&' || 随机报文类型(bzjson/sop/soap)
--   4. trans_id 以 0200 开头，总长 26 位，且本次生成内不重复
--   5. source_ip 从 8 个固定 IP 中随机选择

WITH catalog AS (
    SELECT
        catalog_id,
        service_code
    FROM tss.ana_tran_catalog
    WHERE service_code IS NOT NULL
    ORDER BY catalog_id
),
expanded AS (
    SELECT
        c.catalog_id,
        c.service_code,
        g.seq_no
    FROM catalog c
    CROSS JOIN generate_series(1, 500) AS g(seq_no)
),
numbered AS (
    SELECT
        row_number() OVER (ORDER BY random(), catalog_id, seq_no) AS rn,
        service_code,
        seq_no
    FROM expanded
),
base_rows AS (
    SELECT
        (ARRAY[
            '10.10.20.11',
            '10.10.20.12',
            '10.10.20.13',
            '10.10.20.14',
            '10.10.20.15',
            '10.10.20.16',
            '10.10.20.17',
            '10.10.20.18'
        ])[floor(random() * 8)::int + 1] AS source_ip,
        '0200' || lpad((floor(random() * 10000000000)::bigint)::text, 10, '0')
               || lpad(rn::text, 12, '0') AS trans_id,
        service_code,
        (ARRAY['bzjson', 'sop', 'soap'])[floor(random() * 3)::int + 1] AS msg_kind,
        (extract(epoch FROM clock_timestamp())::bigint * 1000 + rn) AS txn_time,
        rn,
        seq_no,
        md5(random()::text || rn::text) AS request_token,
        md5(clock_timestamp()::text || random()::text || rn::text) AS response_token
    FROM numbered
),
payload_rows AS (
    SELECT
        source_ip,
        trans_id,
        service_code || '&' || msg_kind AS txn_code,
        txn_time,
        msg_kind AS message_type,
        encode(convert_to((
            '{"transId":"' || trans_id ||
            '","txnCode":"' || txn_code ||
            '","seqNo":' || seq_no ||
            ',"amount":' || (floor(random() * 99999900 + 100)::bigint)::text ||
            ',"customerNo":"C' || substring(request_token, 1, 12) ||
            '","trace":"' || request_token || '"}'
        ), 'UTF8'), 'hex')::blob AS request_message,
        trans_id AS global_seq_no,
        'T' || lpad((floor(random() * 1000000)::int)::text, 6, '0') AS tran_teller_no,
        (txn_time + floor(random() * 3000 + 10)::bigint) AS response_time,
        encode(convert_to((
            '{"transId":"' || trans_id ||
            '","txnCode":"' || txn_code ||
            '","returnCode":"000000"' ||
            ',"returnMsg":"SUCCESS"' ||
            ',"trace":"' || response_token || '"}'
        ), 'UTF8'), 'hex')::blob AS response_message
    FROM base_rows
),
insert_request AS (
    INSERT INTO tss.msg_flow_log_request (
        source_ip,
        trans_id,
        txn_code,
        txn_time,
        message_type,
        request_message,
        global_seq_no,
        tran_teller_no
    )
    SELECT
        source_ip,
        trans_id,
        txn_code,
        txn_time,
        message_type,
        request_message,
        global_seq_no,
        tran_teller_no
    FROM payload_rows
    RETURNING source_ip, trans_id
)
INSERT INTO tss.msg_flow_log_response (
    source_ip,
    trans_id,
    txn_code,
    response_time,
    message_type,
    response_message,
    return_code,
    return_msg
)
SELECT
    p.source_ip,
    p.trans_id,
    p.txn_code,
    p.response_time,
    p.message_type,
    p.response_message,
    '000000',
    'SUCCESS'
FROM payload_rows p
JOIN insert_request r
    ON r.source_ip = p.source_ip
   AND r.trans_id = p.trans_id;

SELECT
    count(*) AS request_count
FROM tss.msg_flow_log_request;

SELECT
    count(*) AS response_count
FROM tss.msg_flow_log_response;

SELECT
    txn_prefix,
    min(trans_id_len) AS min_trans_id_len,
    max(trans_id_len) AS max_trans_id_len,
    count(*) AS total_count,
    count(DISTINCT trans_id) AS distinct_trans_id_count
FROM (
    SELECT
        substring(trans_id, 1, 4) AS txn_prefix,
        length(trans_id) AS trans_id_len,
        trans_id
    FROM tss.msg_flow_log_request
) s
GROUP BY txn_prefix;

SELECT
    count(*) AS unmatched_response_count
FROM tss.msg_flow_log_response rsp
LEFT JOIN tss.msg_flow_log_request req
    ON req.source_ip = rsp.source_ip
   AND req.trans_id = rsp.trans_id
WHERE req.trans_id IS NULL;

SELECT
    split_part(req.txn_code, '&', 1) AS service_code,
    count(*) AS request_count,
    count(rsp.trans_id) AS matched_response_count
FROM tss.msg_flow_log_request req
LEFT JOIN tss.msg_flow_log_response rsp
    ON rsp.source_ip = req.source_ip
   AND rsp.trans_id = req.trans_id
GROUP BY split_part(req.txn_code, '&', 1)
ORDER BY service_code;

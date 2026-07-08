-- 数据源: tss
-- 目标表:
--   tss.tss_tran_comp
--   tss.tss_retcode_comp
--   tss.tss_field_comp
--
-- 规则:
--   1. 基于 tss.msg_flow_log_request 中 0200 开头、26 位纯数字流水造数
--   2. orig_* 表示 528，dest_* 表示 CCBS
--   3. 四种交易返回状态分布:
--      - 528成功 + CCBS成功: 50%
--      - 528失败 + CCBS成功: 剩余部分约 1/3
--      - 528成功 + CCBS失败: 剩余部分约 1/3
--      - 528失败 + CCBS失败: 剩余部分约 1/3
--   4. tss_field_comp 只登记 528/CCBS 都成功交易的差异字段
--   5. tss_field_comp 只插入差异字段，comp_result = '0'，不登记比对成功字段

DELETE FROM tss.tss_field_comp
WHERE mesg_seq IN (
    SELECT trans_id
    FROM tss.msg_flow_log_request
    WHERE trans_id ~ '^0200[0-9]{22}$'
);

DELETE FROM tss.tss_tran_comp
WHERE mesg_seq IN (
    SELECT trans_id
    FROM tss.msg_flow_log_request
    WHERE trans_id ~ '^0200[0-9]{22}$'
);

DELETE FROM tss.tss_retcode_comp
WHERE mesg_seq IN (
    SELECT trans_id
    FROM tss.msg_flow_log_request
    WHERE trans_id ~ '^0200[0-9]{22}$'
);

WITH req AS (
    SELECT
        row_number() OVER (ORDER BY trans_id) AS rn,
        count(*) OVER () AS total_count,
        trans_id AS mesg_seq,
        txn_code AS dest_trcd,
        to_char(clock_timestamp(), 'YYYYMMDD') AS biz_date
    FROM tss.msg_flow_log_request
    WHERE trans_id ~ '^0200[0-9]{22}$'
),
bucketed AS (
    SELECT
        *,
        total_count / 2 AS both_success_count,
        (total_count - total_count / 2 + 2) / 3 AS orig_fail_dest_success_count,
        (total_count - total_count / 2 + 1) / 3 AS orig_success_dest_fail_count
    FROM req
),
classified AS (
    SELECT
        rn,
        mesg_seq,
        dest_trcd,
        biz_date,
        CASE
            WHEN rn <= both_success_count THEN '1'
            WHEN rn <= both_success_count + orig_fail_dest_success_count THEN '0'
            WHEN rn <= both_success_count + orig_fail_dest_success_count + orig_success_dest_fail_count THEN '1'
            ELSE '0'
        END AS orig_tran_res,
        CASE
            WHEN rn <= both_success_count THEN '1'
            WHEN rn <= both_success_count + orig_fail_dest_success_count THEN '1'
            WHEN rn <= both_success_count + orig_fail_dest_success_count + orig_success_dest_fail_count THEN '0'
            ELSE '0'
        END AS dest_tran_res,
        CASE
            WHEN rn <= both_success_count THEN '4'
            WHEN rn <= both_success_count + orig_fail_dest_success_count THEN '1'
            WHEN rn <= both_success_count + orig_fail_dest_success_count + orig_success_dest_fail_count THEN '2'
            ELSE '3'
        END AS comp_result
    FROM bucketed
)
INSERT INTO tss.tss_tran_comp (
    mesg_seq,
    orig_cdate,
    conv_index,
    conv_cindex,
    comp_date,
    dest_trcd,
    orig_tran_res,
    dest_tran_res,
    comp_result
)
SELECT
    mesg_seq,
    biz_date,
    1,
    1,
    biz_date,
    dest_trcd,
    orig_tran_res,
    dest_tran_res,
    comp_result
FROM classified;

WITH req AS (
    SELECT
        row_number() OVER (ORDER BY trans_id) AS rn,
        count(*) OVER () AS total_count,
        trans_id AS mesg_seq,
        txn_code AS service_code,
        to_char(clock_timestamp(), 'YYYYMMDD') AS biz_date,
        md5(trans_id || txn_code) AS token
    FROM tss.msg_flow_log_request
    WHERE trans_id ~ '^0200[0-9]{22}$'
),
bucketed AS (
    SELECT
        *,
        total_count / 2 AS both_success_count,
        (total_count - total_count / 2 + 2) / 3 AS orig_fail_dest_success_count,
        (total_count - total_count / 2 + 1) / 3 AS orig_success_dest_fail_count
    FROM req
),
classified AS (
    SELECT
        rn,
        mesg_seq,
        service_code,
        biz_date,
        token,
        CASE
            WHEN rn <= both_success_count THEN '1'
            WHEN rn <= both_success_count + orig_fail_dest_success_count THEN '0'
            WHEN rn <= both_success_count + orig_fail_dest_success_count + orig_success_dest_fail_count THEN '1'
            ELSE '0'
        END AS orig_tran_res,
        CASE
            WHEN rn <= both_success_count THEN '1'
            WHEN rn <= both_success_count + orig_fail_dest_success_count THEN '1'
            WHEN rn <= both_success_count + orig_fail_dest_success_count + orig_success_dest_fail_count THEN '0'
            ELSE '0'
        END AS dest_tran_res
    FROM bucketed
)
INSERT INTO tss.tss_retcode_comp (
    mesg_seq,
    service_code,
    orig_cdate,
    orig_error_code,
    orig_error_desc,
    dest_error_code,
    dest_error_desc,
    remark
)
SELECT
    mesg_seq,
    service_code,
    biz_date,
    CASE
        WHEN orig_tran_res = '1' THEN '000000000000'
        ELSE 'E528' || lpad(((rn % 900000) + 100000)::text, 6, '0')
    END AS orig_error_code,
    CASE
        WHEN orig_tran_res = '1' THEN '交易成功'
        ELSE (ARRAY['528账户状态异常', '528余额不足', '528客户信息校验失败', '528交易超时'])[rn % 4 + 1]
    END AS orig_error_desc,
    CASE
        WHEN dest_tran_res = '1' THEN '000000000000'
        ELSE 'ECCBS' || lpad(((rn % 900000) + 100000)::text, 6, '0')
    END AS dest_error_code,
    CASE
        WHEN dest_tran_res = '1' THEN '交易成功'
        ELSE (ARRAY['CCBS账户状态异常', 'CCBS余额不足', 'CCBS客户信息校验失败', 'CCBS交易超时'])[rn % 4 + 1]
    END AS dest_error_desc,
    '根据 request 交易流水生成的 528/CCBS 返回码对比数据'
FROM classified;

WITH success_tran AS (
    SELECT
        c.mesg_seq,
        c.orig_cdate,
        c.dest_trcd,
        c.conv_index,
        c.conv_cindex,
        row_number() OVER (ORDER BY c.mesg_seq) AS rn
    FROM tss.tss_tran_comp c
    WHERE c.mesg_seq IN (
        SELECT trans_id
        FROM tss.msg_flow_log_request
        WHERE trans_id ~ '^0200[0-9]{22}$'
    )
      AND c.orig_tran_res = '1'
      AND c.dest_tran_res = '1'
      AND c.comp_result = '4'
),
field_seed AS (
    SELECT
        s.*,
        f.field_index
    FROM success_tran s
    CROSS JOIN generate_series(1, 3) AS f(field_index)
    WHERE f.field_index <= 1 + (s.rn % 3)
)
INSERT INTO tss.tss_field_comp (
    mesg_seq,
    orig_cdate,
    dest_trcd,
    conv_index,
    conv_cindex,
    redo_index,
    field_index,
    field_file_flag,
    orig_field_name,
    orig_field_value,
    dest_field_name,
    dest_field_value,
    comp_result
)
SELECT
    mesg_seq,
    orig_cdate,
    dest_trcd,
    conv_index,
    conv_cindex,
    0,
    field_index,
    'BODY',
    CASE field_index
        WHEN 1 THEN 'acctNo'
        WHEN 2 THEN 'amount'
        ELSE 'currency'
    END AS orig_field_name,
    CASE field_index
        WHEN 1 THEN '6222' || substring(md5(mesg_seq || field_index::text), 1, 12)
        WHEN 2 THEN ((rn * 37 + field_index * 11) % 999999)::text
        ELSE 'CNY'
    END AS orig_field_value,
    CASE field_index
        WHEN 1 THEN 'AcctNo'
        WHEN 2 THEN 'Amt'
        ELSE 'CurrencyId'
    END AS dest_field_name,
    CASE field_index
        WHEN 1 THEN '6223' || substring(md5(dest_trcd || mesg_seq || field_index::text), 1, 12)
        WHEN 2 THEN ((rn * 37 + field_index * 11 + 100) % 999999)::text
        ELSE 'USD'
    END AS dest_field_value,
    '0'
FROM field_seed;

SELECT
    comp_result,
    orig_tran_res AS is_528_success,
    dest_tran_res AS is_ccbs_success,
    count(*) AS tran_count
FROM tss.tss_tran_comp
WHERE mesg_seq IN (
    SELECT trans_id
    FROM tss.msg_flow_log_request
    WHERE trans_id ~ '^0200[0-9]{22}$'
)
GROUP BY comp_result, orig_tran_res, dest_tran_res
ORDER BY comp_result;

SELECT
    count(*) AS retcode_count
FROM tss.tss_retcode_comp
WHERE mesg_seq IN (
    SELECT trans_id
    FROM tss.msg_flow_log_request
    WHERE trans_id ~ '^0200[0-9]{22}$'
);

SELECT
    count(*) AS field_diff_count,
    count(DISTINCT mesg_seq) AS success_tran_with_field_diff_count
FROM tss.tss_field_comp
WHERE mesg_seq IN (
    SELECT trans_id
    FROM tss.msg_flow_log_request
    WHERE trans_id ~ '^0200[0-9]{22}$'
);

SELECT
    count(*) AS non_success_tran_field_count
FROM tss.tss_field_comp f
JOIN tss.tss_tran_comp c
    ON c.mesg_seq = f.mesg_seq
WHERE c.mesg_seq IN (
    SELECT trans_id
    FROM tss.msg_flow_log_request
    WHERE trans_id ~ '^0200[0-9]{22}$'
)
  AND NOT (
      c.orig_tran_res = '1'
      AND c.dest_tran_res = '1'
      AND c.comp_result = '4'
  );

SELECT
    comp_result,
    count(*) AS field_count
FROM tss.tss_field_comp
WHERE mesg_seq IN (
    SELECT trans_id
    FROM tss.msg_flow_log_request
    WHERE trans_id ~ '^0200[0-9]{22}$'
)
GROUP BY comp_result
ORDER BY comp_result;

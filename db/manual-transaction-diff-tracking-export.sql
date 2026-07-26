-- 交易级差异问题跟踪表手工导出（PostgreSQL）
-- 当前导出批次：SMP20260707-194149-5393。
-- 在 DBeaver、DataGrip 等客户端执行后，按 UTF-8、!^ 分隔符导出结果集。
-- 本脚本只查询数据，不写入 ana_tran_diff_tracking_export。
-- “字段名”根据响应码判定：7 个 A 或 12 个 0 表示成功，其余表示失败。
-- 原字段中的 !^ 按原值保留；回车换行会替换为空格，避免导出文本产生额外行。

WITH params AS (
    SELECT 'SMP20260707-194149-5393'::varchar(64) AS batch_id
), ranked_diff AS (
    SELECT
        d.result_id,
        d.batch_id,
        d.service_code,
        d.orig_error_code,
        d.orig_error_desc,
        d.dest_error_code,
        d.dest_error_desc,
        d.tran_code,
        d.owner,
        d.sample_tran_seq_no,
        row_number() OVER (
            PARTITION BY d.service_code, d.orig_error_code, d.dest_error_code
            ORDER BY d.result_id
        ) AS group_row_num
    FROM ana_tran_diff_result d
    JOIN params p ON p.batch_id = d.batch_id
), normalized_diff AS (
    SELECT *
    FROM ranked_diff
    WHERE group_row_num = 1
), export_rows AS (
    SELECT
        nd.batch_id,
        nd.service_code,
        nd.orig_error_code,
        nd.orig_error_desc,
        nd.dest_error_code,
        nd.dest_error_desc,
        nd.tran_code,
        c.tran_name,
        c.owner AS transaction_owner,
        c.module_name AS group_name,
        nd.sample_tran_seq_no
    FROM normalized_diff nd
    LEFT JOIN LATERAL (
        SELECT c1.tran_code, c1.tran_name, c1.owner, c1.module_name
        FROM ana_tran_catalog c1
        WHERE c1.service_code = nd.service_code
        ORDER BY c1.catalog_id
        LIMIT 1
    ) c ON true
), cleaned_rows AS (
    SELECT
        translate(coalesce(batch_id, ''), chr(13) || chr(10), '  ') AS batch_id,
        translate(coalesce(service_code, ''), chr(13) || chr(10), '  ') AS service_code,
        translate(coalesce(orig_error_code, ''), chr(13) || chr(10), '  ') AS orig_error_code,
        translate(coalesce(orig_error_desc, ''), chr(13) || chr(10), '  ') AS orig_error_desc,
        translate(coalesce(dest_error_code, ''), chr(13) || chr(10), '  ') AS dest_error_code,
        translate(coalesce(dest_error_desc, ''), chr(13) || chr(10), '  ') AS dest_error_desc,
        translate(coalesce(tran_code, ''), chr(13) || chr(10), '  ') AS tran_code,
        translate(coalesce(tran_name, ''), chr(13) || chr(10), '  ') AS tran_name,
        translate(coalesce(transaction_owner, ''), chr(13) || chr(10), '  ') AS transaction_owner,
        translate(coalesce(group_name, ''), chr(13) || chr(10), '  ') AS group_name,
        translate(coalesce(sample_tran_seq_no, ''), chr(13) || chr(10), '  ') AS sample_tran_seq_no
    FROM export_rows
)
SELECT
    to_char(current_date, 'YYYYMMDD') AS "业务日期",
    group_name AS "组别",
    row_number() OVER (ORDER BY service_code, orig_error_code, dest_error_code) AS "序号",
    batch_id AS "批次",
    tran_code AS "交易码",
    tran_name AS "交易名称",
    '交易级' AS "问题级别",
    to_char(current_date, 'YYYYMMDD') AS "登记日期",
    CASE
        WHEN orig_error_code ~ '^[A]{7}$' AND dest_error_code !~ '^[0]{12}$' THEN '528成功/ccbs失败'
        WHEN orig_error_code !~ '^[A]{7}$' AND dest_error_code ~ '^[0]{12}$' THEN '528失败/ccbs成功'
        WHEN orig_error_code ~ '^[A]{7}$' AND dest_error_code ~ '^[0]{12}$' THEN '528成功/ccbs成功'
        ELSE '二者均失败'
    END AS "字段名",
    '528响应码：' || orig_error_code
        || '；528响应描述：' || orig_error_desc
        || '；CCBS响应码：' || dest_error_code
        || '；CCBS响应描述：' || dest_error_desc AS "问题描述",
    transaction_owner AS "交易负责人",
    '' AS "问题类型",
    '' AS "初步问题分析",
    '' AS "最终处理方案",
    '' AS "解决日期",
    '' AS "需协调",
    '' AS "解决人员",
    sample_tran_seq_no AS "流水号",
    '' AS "缺陷修复日期"
FROM cleaned_rows
ORDER BY service_code, orig_error_code, dest_error_code;

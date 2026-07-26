-- 字段级差异问题跟踪表手工导出（PostgreSQL）
-- 当前导出批次：SMP20260707-194149-5393。
-- 在 DBeaver、DataGrip 等客户端执行后，按 UTF-8、!^ 分隔符导出结果集。
-- 本脚本只查询 ana_field_diff_result，不写入任何流水表。
-- 每个 service_code + soap_field_name 组合随机保留一条记录作为导出样例。
-- 原字段中的 !^ 按原值保留；回车换行会替换为空格，避免导出文本产生额外行。

WITH params AS (
    SELECT 'SMP20260707-194149-5393'::varchar(64) AS batch_id
), ranked_diff AS (
    SELECT
        d.*,
        row_number() OVER (
            PARTITION BY d.service_code, d.soap_field_name
            ORDER BY random()
        ) AS group_row_num
    FROM ana_field_diff_result d
    JOIN params p ON p.batch_id = d.batch_id
), normalized_diff AS (
    SELECT *
    FROM ranked_diff
    WHERE group_row_num = 1
), export_rows AS (
    SELECT
        d.result_id,
        d.batch_id,
        d.service_code,
        d.tran_code,
        d.field_cn_name,
        d.sop_field_name,
        d.soap_field_name,
        d.bizjson_field_name,
        d.mapping_status,
        d.orig_field_value,
        d.dest_field_value,
        d.sample_tran_seq_no,
        c.tran_name,
        c.owner AS transaction_owner,
        c.module_name AS group_name
    FROM normalized_diff d
    LEFT JOIN LATERAL (
        SELECT c1.tran_name, c1.owner, c1.module_name
        FROM ana_tran_catalog c1
        WHERE c1.service_code = d.service_code
        ORDER BY c1.catalog_id
        LIMIT 1
    ) c ON true
), cleaned_rows AS (
    SELECT
        result_id,
        translate(coalesce(batch_id, ''), chr(13) || chr(10), '  ') AS batch_id,
        translate(coalesce(service_code, ''), chr(13) || chr(10), '  ') AS service_code,
        translate(coalesce(tran_code, ''), chr(13) || chr(10), '  ') AS tran_code,
        translate(
            concat_ws(
                ',',
                nullif(sop_field_name, ''),
                nullif(soap_field_name, ''),
                nullif(bizjson_field_name, ''),
                nullif(field_cn_name, '')
            ),
            chr(13) || chr(10),
            '  '
        ) AS field_name,
        translate(coalesce(mapping_status, ''), chr(13) || chr(10), '  ') AS mapping_status,
        translate(coalesce(orig_field_value, ''), chr(13) || chr(10), '  ') AS orig_field_value,
        translate(coalesce(dest_field_value, ''), chr(13) || chr(10), '  ') AS dest_field_value,
        translate(coalesce(tran_name, ''), chr(13) || chr(10), '  ') AS tran_name,
        translate(coalesce(transaction_owner, ''), chr(13) || chr(10), '  ') AS transaction_owner,
        translate(coalesce(group_name, ''), chr(13) || chr(10), '  ') AS group_name,
        translate(coalesce(sample_tran_seq_no, ''), chr(13) || chr(10), '  ') AS sample_tran_seq_no
    FROM export_rows
)
SELECT
    to_char(current_date, 'YYYYMMDD') AS "业务日期",
    group_name AS "组别",
    row_number() OVER (ORDER BY service_code, tran_code, field_name, result_id) AS "序号",
    batch_id AS "批次",
    tran_code AS "交易码",
    tran_name AS "交易名称",
    '字段级' AS "问题级别",
    to_char(current_date, 'YYYYMMDD') AS "登记日期",
    field_name AS "字段名",
    '528字段值：' || orig_field_value
        || '；CCBS字段值：' || dest_field_value
        || '；字段映射状态：' || mapping_status AS "问题描述",
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
ORDER BY service_code, tran_code, field_name, result_id;

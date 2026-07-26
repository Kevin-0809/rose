-- 领域交易统计（PostgreSQL / openGauss）
-- msg_flow_log_response 为已发送交易全集；txn_code 的服务码部分关联
-- tp_online_service_in.esf_service_code（移除点号）确认服务码；
-- tss_tran_comp 为全部交易比对结果；本脚本不关联任何批次号。
-- 本脚本只读，不写入报表表。

SELECT
    c.module_name AS "领域",
    count(DISTINCT replace(trim(p.esf_service_code), '.', '')) AS "覆盖528接口",
    count(*) AS "发送交易量",
    count(t.mesg_seq) AS "已比对交易量",
    sum(CASE WHEN t.comp_result = '1' THEN 1 ELSE 0 END) AS "528成功/CCBS失败",
    sum(CASE WHEN t.comp_result = '2' THEN 1 ELSE 0 END) AS "528失败/CCBS成功",
    sum(CASE WHEN t.comp_result = '3' THEN 1 ELSE 0 END) AS "二者均成功",
    round(
        100.0 * sum(CASE WHEN t.comp_result = '3' THEN 1 ELSE 0 END)
        / nullif(count(*), 0),
        2
    ) AS "成功率(%)",
    sum(CASE WHEN t.comp_result = '4' THEN 1 ELSE 0 END) AS "二者均失败",
    sum(CASE WHEN t.comp_result = '8' THEN 1 ELSE 0 END) AS "其他比对结果"
FROM msg_flow_log_response r
JOIN tp_online_service_in p
  ON lower(replace(trim(p.esf_service_code), '.', '')) = lower(trim(split_part(r.txn_code, '&', 1)))
LEFT JOIN ana_tran_catalog c
  ON lower(trim(c.service_code)) = lower(replace(trim(p.esf_service_code), '.', ''))
LEFT JOIN tss_tran_comp t
  ON t.mesg_seq = replace(coalesce(r.source_ip, '') || coalesce(r.trans_id, ''), '.', '')
WHERE p.esf_service_code IS NOT NULL
  AND trim(p.esf_service_code) <> ''
GROUP BY c.module_name
ORDER BY "领域";

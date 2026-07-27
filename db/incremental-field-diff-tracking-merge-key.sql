-- 字段级差异跟踪导出表 merge insert 去重键。
-- 用于支持 ana_field_diff_tracking_export 采集入库时按同一批次、同一服务、同一归一化问题键只插入一次。

create unique index if not exists uk_ana_field_diff_tracking_export_batch_issue
on ana_field_diff_tracking_export(source_batch_id, service_code, issue_key);

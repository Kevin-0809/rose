-- Restore ana_diff_issue from a report export backup batch.
-- Replace :batchId with the report export batch id that created the backup.
-- Review the backup row count before running the restore block.

select backup_batch_id, count(*) as issue_count, min(backup_time) as first_backup_time,
       max(backup_time) as last_backup_time
  from ana_diff_issue_backup
 where backup_batch_id = :batchId
 group by backup_batch_id;

begin;

delete from ana_diff_issue;

insert into ana_diff_issue (
    issue_id,
    issue_key,
    issue_level,
    service_code,
    tran_code,
    tran_name,
    module_name,
    transaction_owner,
    orig_error_code,
    dest_error_code,
    normalized_source_field_name,
    problem_type,
    problem_description,
    preliminary_analysis,
    final_solution,
    issue_status,
    coordination_required,
    resolver,
    resolution_date,
    defect_fix_date,
    first_seen_date,
    last_seen_date,
    first_seen_batch_id,
    last_seen_batch_id,
    occurrence_batch_count,
    created_at,
    updated_at
)
select
    issue_id,
    issue_key,
    issue_level,
    service_code,
    tran_code,
    tran_name,
    module_name,
    transaction_owner,
    orig_error_code,
    dest_error_code,
    normalized_source_field_name,
    problem_type,
    problem_description,
    preliminary_analysis,
    final_solution,
    issue_status,
    coordination_required,
    resolver,
    resolution_date,
    defect_fix_date,
    first_seen_date,
    last_seen_date,
    first_seen_batch_id,
    last_seen_batch_id,
    occurrence_batch_count,
    coalesce(issue_created_at, backup_time),
    coalesce(issue_updated_at, backup_time)
  from ana_diff_issue_backup
 where backup_batch_id = :batchId;

select setval(
    pg_get_serial_sequence('ana_diff_issue', 'issue_id'),
    coalesce((select max(issue_id) from ana_diff_issue), 1),
    true
);

-- Optional cleanup before re-running the same report export batch.
delete from ana_tran_diff_tracking_export where source_batch_id = :batchId;
delete from ana_field_diff_tracking_export where source_batch_id = :batchId;
delete from ana_report_export_summary where batch_id = :batchId;

commit;


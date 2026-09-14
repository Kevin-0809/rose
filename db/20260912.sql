-- Add base_date for replay volume check nearby-collection migration.
alter table ana_replay_volume_check_batch
    add column if not exists base_date date;

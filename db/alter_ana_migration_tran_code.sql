-- Incremental DDL for transaction-code migration.
-- Target datasource: tss
-- Safe to run repeatedly before using /migration/tran-code-commands.

alter table tss.ana_migration_command
    add column if not exists tran_codes text;

alter table tss.ana_migration_command
    add column if not exists sample_size integer;

alter table tss.ana_migration_shard
    add column if not exists tran_code varchar(32);

alter table tss.ana_migration_command
    drop constraint if exists ck_ana_migration_command_type;

alter table tss.ana_migration_command
    add constraint ck_ana_migration_command_type
    check (command_type in ('TIME_RANGE', 'SQL', 'TRAN_CODE'));

alter table tss.ana_migration_command
    drop constraint if exists ck_ana_migration_command_tran_code_parameters;

alter table tss.ana_migration_command
    add constraint ck_ana_migration_command_tran_code_parameters
    check (
        command_type <> 'TRAN_CODE'
        or (
            tran_codes is not null
            and length(trim(tran_codes)) > 0
            and sample_size is not null
            and sample_size > 0
        )
    );

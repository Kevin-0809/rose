\set ON_ERROR_STOP on

-- Indexes for set-based sampling execution.
create index if not exists idx_tss_field_comp_sampling_diff
on tss_field_comp(orig_cdate, comp_result, orig_field_name, mesg_seq, conv_index, conv_cindex, dest_field_name);

create index if not exists idx_tss_tran_comp_sampling_join
on tss_tran_comp(orig_cdate, mesg_seq, conv_index, conv_cindex, comp_result, dest_trcd);

create index if not exists idx_ana_tran_catalog_service
on ana_tran_catalog(service_code);

create index if not exists idx_ana_field_mapping_sampling
on ana_field_mapping(service_code, sop_field_name, bizjson_field_name, tran_code);

create table if not exists ana_msg_flow_log_request (
 source_ip varchar(64) not null, trans_id varchar(64) not null, txn_code varchar(200), txn_time bigint,
 message_type varchar(32), request_message bytea, global_seq_no varchar(64), tran_teller_no varchar(64),
 send_status varchar(16) not null default 'PENDING', send_time timestamp, send_http_status integer,
 send_error varchar(1000), send_attempts integer not null default 0, primary key (source_ip, trans_id)
);
create index if not exists idx_ana_msg_req_send on ana_msg_flow_log_request(send_status, txn_time);
create index if not exists idx_ana_msg_req_txn_time on ana_msg_flow_log_request(txn_time);
create table if not exists ana_msg_flow_log_response (
 source_ip varchar(64) not null, trans_id varchar(64) not null, response_time bigint not null,
 txn_code varchar(200), message_type varchar(32), response_message text, return_code varchar(64),
 return_msg varchar(1000), http_status integer, service_address varchar(500), created_time timestamp not null default current_timestamp,
 primary key (source_ip, trans_id, response_time)
);
create index if not exists idx_ana_msg_resp_trans on ana_msg_flow_log_response(trans_id, response_time desc);
do $$ begin
  if exists (select 1 from information_schema.columns where table_name='ana_msg_flow_log_response' and column_name='response_message' and data_type='bytea') then
    alter table ana_msg_flow_log_response alter column response_message type text using encode(response_message, 'escape');
  end if;
end $$;
insert into system_config(config_key, config_value, description) values ('micServId','10530013','HTTP micServId') on conflict (config_key) do update set config_value=excluded.config_value;

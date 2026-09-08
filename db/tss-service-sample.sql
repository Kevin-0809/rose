create table if not exists tss_service_auth (
    protocol_id varchar(100) primary key,
    sid varchar(64) not null,
    gk varchar(500),
    wk varchar(500),
    pk varchar(500),
    create_time timestamp default current_timestamp,
    update_time timestamp default current_timestamp
);

merge into tss_service_control target using (select 'ccbs_json' protocol_id,'HTTP' protocol_type,'1.0' protocol_version,'21.64.64.20:7602' service_address,'HTTP' client_class,'GBK' pkg_charset,15 connect_timeout union all
select 'ccbs_xml','HTTP','1.0','21.64.64.20:7603','HTTP','GBK',15 union all select 'ccbs_sop','HTTP','1.0','21.64.64.20:7604','HTTP','GBK',15 union all
select '528_json','HTTP','1.0','10.251.152.202:7602','HTTP','GBK',15 union all select '528_xml','HTTP','1.0','10.251.152.202:7603','HTTP','GBK',15 union all
select '528_sop','HTTP','1.0','10.251.152.202:7604','HTTP','GBK',15 union all select 'ccbs_spec','HTTP','1.0','21.64.64.20:7610','HTTP','GBK',15 union all
select '528_spec','HTTP','1.0','10.251.152.202:7610','HTTP','GBK',15) source
on (target.protocol_id=source.protocol_id)
when matched then update set protocol_type=source.protocol_type, protocol_version=source.protocol_version, service_address=source.service_address, client_class=source.client_class, pkg_charset=source.pkg_charset, connect_timeout=source.connect_timeout
when not matched then insert (protocol_id,protocol_type,protocol_version,service_address,client_class,pkg_charset,connect_timeout) values (source.protocol_id,source.protocol_type,source.protocol_version,source.service_address,source.client_class,source.pkg_charset,source.connect_timeout);

/* Original column list retained as documentation for the sample mapping.
insert into tss_service_control (
    protocol_id, protocol_type, protocol_version, sub_head_length, req_head_length,
    res_head_length, service_address, client_class, loadbalance_rule_class,
    loadbalance_ping_class, pkg_charset, connect_timeout
) values
('ccbs_json','HTTP','1.0',null,null,null,'21.64.64.20:7602',null,null,null,null,null),
('ccbs_xml','HTTP','1.0',null,null,null,'21.64.64.20:7603',null,null,null,null,null),
('ccbs_sop','HTTP','1.0',null,null,null,'21.64.64.20:7604',null,null,null,null,null),
('528_json','HTTP','1.0',null,null,null,'10.251.152.202:7602',null,null,null,null,null),
('528_xml','HTTP','1.0',null,null,null,'10.251.152.202:7603',null,null,null,null,null),
('528_sop','HTTP','1.0',null,null,null,'10.251.152.202:7604',null,null,null,null,null),
('ccbs_spec','HTTP','1.0',null,null,null,'21.64.64.20:7610',null,null,null,null,null),
('528_spec','HTTP','1.0',null,null,null,'10.251.152.202:7610',null,null,null,null,null)
on conflict (protocol_id) do update set
    protocol_type=excluded.protocol_type,
    protocol_version=excluded.protocol_version,
    service_address=excluded.service_address; */

merge into tss_service_auth target using (values
('ccbs_sop','10530013','P3FWPOZ4TmU8TV4jOiFDJA==','IEd88Dq79TeRb9Xs0+z6rg==','5UzcYp4WeGLFZE6opPyhw=='),
('ccbs_xml','10530013','P3FWPOZ4TmU8TV4jOiFDJA==','IEd88Dq79TeRb9Xs0+z6rg==','5UzcYp4WeGLFZE6opPyhw=='),
('ccbs_json','10530013','P3FWPOZ4TmU8TV4jOiFDJA==','IEd88Dq79TeRb9Xs0+z6rg==','5UzcYp4WeGLFZE6opPyhw=='),
('528_sop','10530013','P3FWPOZ4TmU8TV4jOiFDJA==','8ODkUKS5SnKloea3uh7KyQ==','he4KpKWTQbYHINRahOZ+Fw=='),
('528_xml','10530013','P3FWPOZ4TmU8TV4jOiFDJA==','8ODkUKS5SnKloea3uh7KyQ==','he4KpKWTQbYHINRahOZ+Fw=='),
('528_json','10530013','P3FWPOZ4TmU8TV4jOiFDJA==','8ODkUKS5SnKloea3uh7KyQ==','he4KpKWTQbYHINRahOZ+Fw=='),
('ccbs_spec','10530013','P3FWPOZ4TmU8TV4jOiFDJA==','IEd88Dq79TeRb9Xs0+z6rg==','5UzcYp4WeGLFZE6opPyhw=='),
('528_spec','10530013','P3FWPOZ4TmU8TV4jOiFDJA==','8ODkUKS5SnKloea3uh7KyQ==','he4KpKWTQbYHINRahOZ+Fw==')) source(protocol_id,sid,gk,wk,pk)
on (target.protocol_id=source.protocol_id)
when matched then update set sid=source.sid,gk=source.gk,wk=source.wk,pk=source.pk,update_time=current_timestamp
when not matched then insert (protocol_id,sid,gk,wk,pk) values (source.protocol_id,source.sid,source.gk,source.wk,source.pk);

/* PostgreSQL form retained as documentation.
insert into tss_service_auth (protocol_id, sid, gk, wk, pk) values
('ccbs_sop','10530013','P3FWPOZ4TmU8TV4jOiFDJA==','IEd88Dq79TeRb9Xs0+z6rg==','5UzcYp4WeGLFZE6opPyhw=='),
('ccbs_xml','10530013','P3FWPOZ4TmU8TV4jOiFDJA==','IEd88Dq79TeRb9Xs0+z6rg==','5UzcYp4WeGLFZE6opPyhw=='),
('ccbs_json','10530013','P3FWPOZ4TmU8TV4jOiFDJA==','IEd88Dq79TeRb9Xs0+z6rg==','5UzcYp4WeGLFZE6opPyhw=='),
('528_sop','10530013','P3FWPOZ4TmU8TV4jOiFDJA==','8ODkUKS5SnKloea3uh7KyQ==','he4KpKWTQbYHINRahOZ+Fw=='),
('528_xml','10530013','P3FWPOZ4TmU8TV4jOiFDJA==','8ODkUKS5SnKloea3uh7KyQ==','he4KpKWTQbYHINRahOZ+Fw=='),
('528_json','10530013','P3FWPOZ4TmU8TV4jOiFDJA==','8ODkUKS5SnKloea3uh7KyQ==','he4KpKWTQbYHINRahOZ+Fw=='),
('ccbs_spec','10530013','P3FWPOZ4TmU8TV4jOiFDJA==','IEd88Dq79TeRb9Xs0+z6rg==','5UzcYp4WeGLFZE6opPyhw=='),
('528_spec','10530013','P3FWPOZ4TmU8TV4jOiFDJA==','8ODkUKS5SnKloea3uh7KyQ==','he4KpKWTQbYHINRahOZ+Fw==')
on conflict (protocol_id) do update set
    sid=excluded.sid, gk=excluded.gk, wk=excluded.wk, pk=excluded.pk, update_time=current_timestamp; */

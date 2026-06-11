CREATE TABLE system_config (
    config_key character varying(100) NOT NULL,
    config_value character varying(200) NOT NULL,
    description character varying(500),
    updated_time timestamp(6) without time zone DEFAULT pg_systimestamp() NOT NULL
)
WITH (
    orientation = row,
    compression = no,
    storage_type = USTORE,
    segment = off
);

COMMENT ON TABLE system_config IS '系统配置表';
COMMENT ON COLUMN system_config.config_key IS '配置键';
COMMENT ON COLUMN system_config.config_value IS '配置值';
COMMENT ON COLUMN system_config.description IS '描述';
COMMENT ON COLUMN system_config.updated_time IS '更新时间';

ALTER TABLE system_config
    ADD CONSTRAINT system_config_pkey PRIMARY KEY (config_key) WITH (storage_type = USTORE);

CREATE TABLE recording_config (
    id bigint DEFAULT nextval('seq_recording_config_id'::regclass) NOT NULL,
    txn_code character varying(100) NOT NULL,
    txn_switch tinyint DEFAULT 1 NOT NULL,
    record_ratio integer DEFAULT 100 NOT NULL,
    description character varying(500),
    created_time timestamp(6) without time zone DEFAULT pg_systimestamp() NOT NULL,
    updated_time timestamp(6) without time zone DEFAULT pg_systimestamp() NOT NULL
)
WITH (
    orientation = row,
    compression = no,
    storage_type = USTORE,
    segment = off
);

COMMENT ON TABLE recording_config IS '录制配置表';
COMMENT ON COLUMN recording_config.id IS '主键ID';
COMMENT ON COLUMN recording_config.txn_code IS '交易代码，如 S010020110LtSzTnyLtrApl111';
COMMENT ON COLUMN recording_config.txn_switch IS '交易级开关： 0=关闭， 1=启用';
COMMENT ON COLUMN recording_config.record_ratio IS '录制比例： 0-100， 100表示100%录制';
COMMENT ON COLUMN recording_config.description IS '描述';
COMMENT ON COLUMN recording_config.created_time IS '创建时间';
COMMENT ON COLUMN recording_config.updated_time IS '更新时间';

CREATE UNIQUE INDEX uk_txn_code
    ON recording_config USING ubtree (txn_code)
    WITH (storage_type = USTORE)
    TABLESPACE pg_default;

ALTER TABLE recording_config
    ADD CONSTRAINT recording_config_pkey PRIMARY KEY (id) WITH (storage_type = USTORE);

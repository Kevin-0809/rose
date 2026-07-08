begin;

insert into tss.ana_tran_catalog
    (tran_code, service_code, tran_name, module_name, "owner", importance_level, is_key_tran, remark)
select v.tran_code, v.service_code, v.tran_name, v.module_name, v.owner_name, v.importance_level, v.is_key_tran, v.remark
from (
    values
        ('A675', 'S68124590AcctBalDtlQry', '账户余额明细查询', '存款', '张三/李四', null, 'false', '服务名称=账户余额明细查询(S68124590); 服务操作=AcctBalDtlQry(账户余额明细查询); 原始接口=SPDBSI'),
        ('A676', 'S52670831CustInfoVerifyQry', '客户信息核验查询', '客户', '张三/李四', null, 'true', '服务名称=客户信息核验查询(S52670831); 服务操作=CustInfoVerifyQry(客户信息核验查询); 原始接口=SPDBSI'),
        ('A677', 'S91437625PayOrderStatusQry', '支付指令状态查询', '支付', '张三/李四', null, 'true', '服务名称=支付指令状态查询(S91437625); 服务操作=PayOrderStatusQry(支付指令状态查询); 原始接口=SPDBSI'),
        ('A678', 'S30759418FcySetlRateInq', '外币结算汇率查询', '存款', '张三/李四', null, 'false', '服务名称=外币结算汇率查询(S30759418); 服务操作=FcySetlRateInq(外币结算汇率查询); 原始接口=SPDBSI'),
        ('A679', 'S74816203LoanRepayPlanQry', '贷款还款计划查询', '贷款', '张三/李四', null, 'false', '服务名称=贷款还款计划查询(S74816203); 服务操作=LoanRepayPlanQry(贷款还款计划查询); 原始接口=SPDBSI')
) as v(tran_code, service_code, tran_name, module_name, owner_name, importance_level, is_key_tran, remark)
where not exists (
    select 1
    from tss.ana_tran_catalog c
    where c.tran_code = v.tran_code
      and c.service_code = v.service_code
);

insert into tss.ana_field_mapping
    (tran_code, service_code, std_field_name, field_cn_name, sop_field_name, soap_field_name, bizjson_field_name, remark)
select v.tran_code, v.service_code, v.std_field_name, v.field_cn_name, v.sop_field_name, v.soap_field_name, v.bizjson_field_name, v.remark
from (
    values
        ('A675', 'S68124590AcctBalDtlQry', 'HUOBDH', '货币代号', 'HUOBDH', 'CurrencyId', 'CurrencyId', '输出; 类型=char(2)->string(3); 原备注=0A6752; 目标备注=0A6752'),
        ('A675', 'S68124590AcctBalDtlQry', 'ZHUHAO', '账号', 'ZHUHAO', 'AcctNo', 'AcctNo', '输出; 类型=char(32)->string(32)'),
        ('A675', 'S68124590AcctBalDtlQry', 'JINE', '金额', 'JINE', 'Amt', 'Amt', '输出; 类型=decimal(18,2)->decimal(18,2)'),
        ('A675', 'S68124590AcctBalDtlQry', 'YUEBZJ', '账户余额明细数 组', 'YUEBZJ', 'AcctBalDtlList', 'AcctBalDtlList', '输出; 数组; 类型=array->Array; 原备注=Start; 目标备注=Start'),
        ('A676', 'S52670831CustInfoVerifyQry', 'HUOBDH', '货币代号', 'HUOBDH', 'CurrencyId', 'CurrencyId', '输出; 类型=char(2)->string(3); 原备注=0A6762; 目标备注=0A6762'),
        ('A676', 'S52670831CustInfoVerifyQry', 'KEHUHAO', '客户号', 'KEHUHAO', 'CustNo', 'CustNo', '输出; 类型=char(20)->string(32)'),
        ('A676', 'S52670831CustInfoVerifyQry', 'KHRMC', '客户名称', 'KHRMC', 'CustName', 'CustName', '输出; 类型=char(60)->string(80)'),
        ('A676', 'S52670831CustInfoVerifyQry', 'KHYZJG', '客户核验结果数 组', 'KHYZJG', 'CustVerifyResultList', 'CustVerifyResultList', '输出; 数组; 类型=array->Array; 原备注=Start; 目标备注=Start'),
        ('A677', 'S91437625PayOrderStatusQry', 'HUOBDH', '货币代号', 'HUOBDH', 'CurrencyId', 'CurrencyId', '输出; 类型=char(2)->string(3); 原备注=0A6772; 目标备注=0A6772'),
        ('A677', 'S91437625PayOrderStatusQry', 'ZHUHAO', '账号', 'ZHUHAO', 'AcctNo', 'AcctNo', '输出; 类型=char(32)->string(32)'),
        ('A677', 'S91437625PayOrderStatusQry', 'RIQI', '日期', 'RIQI', 'TxnDate', 'TxnDate', '输出; 类型=char(8)->string(8)'),
        ('A677', 'S91437625PayOrderStatusQry', 'ZFZTJG', '支付状态结果数 组', 'ZFZTJG', 'PayStatusResultList', 'PayStatusResultList', '输出; 数组; 类型=array->Array; 原备注=Start; 目标备注=Start'),
        ('A678', 'S30759418FcySetlRateInq', 'HUOBDH', '货币代号', 'HUOBDH', 'CurrencyId', 'CurrencyId', '输出; 类型=char(2)->string(3); 原备注=0A6782; 目标备注=0A6782'),
        ('A678', 'S30759418FcySetlRateInq', 'RIQI', '日期', 'RIQI', 'TxnDate', 'TxnDate', '输出; 类型=char(8)->string(8)'),
        ('A678', 'S30759418FcySetlRateInq', 'JINE', '金额', 'JINE', 'Amt', 'Amt', '输出; 类型=decimal(18,2)->decimal(18,2)'),
        ('A678', 'S30759418FcySetlRateInq', 'HUILJG', '汇率查询结果数 组', 'HUILJG', 'FcyRateResultList', 'FcyRateResultList', '输出; 数组; 类型=array->Array; 原备注=Start; 目标备注=Start'),
        ('A679', 'S74816203LoanRepayPlanQry', 'HUOBDH', '货币代号', 'HUOBDH', 'CurrencyId', 'CurrencyId', '输出; 类型=char(2)->string(3); 原备注=0A6792; 目标备注=0A6792'),
        ('A679', 'S74816203LoanRepayPlanQry', 'KEHUHAO', '客户号', 'KEHUHAO', 'CustNo', 'CustNo', '输出; 类型=char(20)->string(32)'),
        ('A679', 'S74816203LoanRepayPlanQry', 'JINE', '金额', 'JINE', 'Amt', 'Amt', '输出; 类型=decimal(18,2)->decimal(18,2)'),
        ('A679', 'S74816203LoanRepayPlanQry', 'HKJHMX', '还款计划明细数 组', 'HKJHMX', 'RepayPlanDtlList', 'RepayPlanDtlList', '输出; 数组; 类型=array->Array; 原备注=Start; 目标备注=Start')
) as v(tran_code, service_code, std_field_name, field_cn_name, sop_field_name, soap_field_name, bizjson_field_name, remark)
where not exists (
    select 1
    from tss.ana_field_mapping m
    where m.tran_code = v.tran_code
      and m.service_code = v.service_code
      and m.std_field_name = v.std_field_name
);

commit;

-- 为 SMP20260707-185450-4937 关联的成功交易补充字段级差异测试数据。
-- 可重复执行：仅插入当前不存在的字段比对主键。
begin;

insert into tss_field_comp (
    mesg_seq, orig_cdate, dest_trcd, conv_index, conv_cindex, redo_index,
    field_index, field_file_flag, orig_field_name, orig_field_value,
    dest_field_name, dest_field_value, comp_result
)
select v.mesg_seq, '20260707', v.dest_trcd, 1, 1, 0,
       v.field_index, 'BODY', v.orig_field_name, v.orig_field_value,
       v.dest_field_name, v.dest_field_value, '0'
from (
    values
        ('1010201102000148993385000000002418', 'S91437625PayOrderStatusQry&soap', 1,
         'AcctNo', '6222000000000001', 'AcctNo', '6222000000000002'),
        ('1010201102000148993385000000002418', 'S91437625PayOrderStatusQry&soap', 2,
         'CurrencyId', 'CNY', 'CurrencyId', 'USD'),
        ('1010201102000340756890000000008364', 'S91437625PayOrderStatusQry&soap', 1,
         'UnsupportedField', 'SOURCE_VALUE', 'UnsupportedField', 'TARGET_VALUE'),
        ('1010201102000345219923000000007493', 'S30759418FcySetlRateInq&soap', 1,
         'CurrencyId', 'CNY', 'CurrencyId', 'HKD'),
        ('1010201102000345219923000000007493', 'S30759418FcySetlRateInq&soap', 2,
         'Amt', '100.00', 'Amt', '101.00'),
        ('1010201102000207689912000000000098', 'S030030015FcyCollCrspBnkLkgQry&soap', 1,
         'TestField', 'ORIG_TEST', 'TestField', 'DEST_TEST')
) as v(mesg_seq, dest_trcd, field_index, orig_field_name, orig_field_value, dest_field_name, dest_field_value)
where not exists (
    select 1
    from tss_field_comp f
    where f.mesg_seq = v.mesg_seq
      and f.conv_index = 1
      and f.conv_cindex = 1
      and f.field_index = v.field_index
      and f.orig_field_name = v.orig_field_name
);

commit;

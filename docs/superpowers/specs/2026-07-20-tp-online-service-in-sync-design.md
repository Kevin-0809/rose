# tp_online_service_in 补齐设计

## 背景

交易码迁移功能需要通过用户输入的 `tran_code` 找到对应 ESF 服务码，再派生 response 表中的 `txn_code`。现有仓库中 `ana_tran_catalog` 已维护 `tran_code` 与不带报文类型的 `service_code`，而 `tss_tran_comp.dest_trcd` 记录了实际出现过的带报文类型服务码，例如：

```text
S030030014FcyCollCrspBnkLkgQry&bzjson
```

目标是根据 `tss_tran_comp.dest_trcd` 中出现过的服务码，参考 `ana_tran_catalog` 补齐或更新 `tp_online_service_in`。

## 数据来源

- `tss_tran_comp.dest_trcd`：用于筛选实际出现过的服务码。取 `&` 前面的基础服务码。
- `ana_tran_catalog.service_code`：与基础服务码匹配，提供标准服务码。
- `ana_tran_catalog.tran_code`：写入 `tp_online_service_in.tran_code`。
- `tp_online_service_in.esf_service_code`：由 `ana_tran_catalog.service_code` 标准化生成。

## 标准化规则

从 response 或采样结果中的服务码：

```text
S030030014FcyCollCrspBnkLkgQry
```

生成 `tp_online_service_in.esf_service_code`：

```text
S03003001.4FcyCollCrspBnkLkgQry
```

规则：对基础服务码去空格后，在第 9 个字符之前插入英文点号，即：

```text
substring(service_code, 1, 8) || '.' || substring(service_code, 9)
```

后续迁移使用时再去掉英文点号，并拼接 `&bzjson`、`&sop`、`&soap` 去匹配 `msg_flow_log_response.txn_code`。

## 同步策略

采用可重复执行 SQL 补丁：

1. 从 `tss_tran_comp` 提取 distinct 基础服务码：`split_part(dest_trcd, '&', 1)`。
2. 过滤空值和不包含报文类型后缀的异常值。
3. 关联 `ana_tran_catalog.service_code` 获取 `tran_code`。
4. 生成 `esf_service_code`。
5. 对 `tp_online_service_in` 执行 upsert：已有记录更新，缺失记录插入。

首选匹配键为 `(tran_code, esf_service_code)`。如果目标库实际唯一键不同，执行补丁前按真实表结构调整冲突键。

## 验收标准

- `S030030014FcyCollCrspBnkLkgQry&bzjson` 能生成 `S03003001.4FcyCollCrspBnkLkgQry`。
- 仅同步能在 `ana_tran_catalog` 中匹配到 `service_code` 的记录。
- SQL 可重复执行，不产生重复业务记录。
- 已有记录按新规则更新。

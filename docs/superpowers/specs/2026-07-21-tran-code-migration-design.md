# 按交易码报文迁移设计

> 更新记录：2026-09-12 起，交易码迁移调整为**只迁移请求报文**，并引入请求表分区访问、keyset 分页、就近扫描顺序和命令级服务码映射批量加载。本文档描述当前实现；最初的“完整请求/响应对”方案见 `docs/superpowers/plans/2026-07-21-tran-code-migration.md`。

## 目标

在现有报文数据迁移功能中保留按交易码迁移模式。用户一次输入多个逗号分隔的交易码、全局每类数量 N、回溯自然日数 L、并行度，以及可选的“就近原则采集”。系统按交易码并行执行，从源库 `msg_flow_log_request` 按请求时间 `txn_time` 分区读取，为 `bzjson`、`sop`、`soap` 三类报文各迁移最多 N 条请求报文至目标库 `msg_flow_log_request`。

> 2026-09-12 起，交易码迁移不再读取或写入 `msg_flow_log_response`，也不再做请求/响应配对检查；`dropped`（丢弃）始终为 0。

## 范围与口径

- 交易码输入采用逗号分隔，去除首尾空白并去重，保留首次出现顺序，单个交易码最长 32 个字符。
- N 是本次命令的全局参数，对每个交易码和每种报文类型分别生效。
- 报文类型固定为 `bzjson`、`sop`、`soap` 三类，不包含 `sop2cbsp`。
- 扫描基准时间为请求时间 `txn_time`，自然日按 `Asia/Shanghai` 计算。
- 普通模式从当前日 `T` 向前连续取 L 天：`T, T-1, …, T-(L-1)`。
- 就近模式（`nearbyCollection=true` 且填写铺底日期 `B`）：先按 `B, B-1, …, B-(L-1)` 向前（更早）取满 L 天，再从 `B+1` 向后扫到当前日 `T`。该顺序同时修正了旧实现“铺底日 + 前 L 天”比回溯天数多一天的问题。
- 全量回溯（`lookbackDays = ALL_LOOKBACK_DAYS = 10000`）：实际回溯天数 = 请求表最早分区日期到当前日 + 1。最早分区通过系统表读取，不做 `min()` 全表聚合；取不到分区时返回 0。
- 源表按自然日分区，分区名规则为 `p{yyyyMMdd}`，`txn_time = D` 的记录落在 `partition(p{D})`。分区不存在时跳过该日，不计入迁移、跳过或丢弃。
- 服务码来源：优先查询 `ana_tran_code_service_mapping`（`"528_service_code"` → `ccbs_service_code`）；该交易码在该表中没有有效映射时，回退到 `tp_online_service_in.esf_service_code`（lookup 与 target 同为该服务码）。
- 服务码去除点号后分别拼接 `&bzjson`、`&sop`、`&soap` 作为源库 `txn_code`；命中后写入目标库的 `txn_code` 使用目标服务码。
- 每个自然日内按 `txn_time` 倒序（由近到远）取数；每类报文最多迁移 N 条。
- 目标库已存在相同 `(source_ip, trans_id)` 的记录计为跳过，不占用 N，继续向后取更早记录。
- 某交易码在回溯范围内没有任何命中请求，或没有任何服务码配置时，该分片标记 `SKIPPED`，不视为失败。

## 架构

复用 `ana_migration_command` 和 `ana_migration_shard` 状态模型，命令类型为 `TRAN_CODE`。

一个交易码对应一个迁移分片。现有 `MigrationBatchRunner` 用命令并行度限制多个交易码分片的并行执行；单个交易码分片按 `bzjson`、`sop`、`soap` 依次处理，避免为单笔命令创建更多线程和连接。

命令表保存 `tran_codes`、`sample_size`、`lookback_days`、`nearby_collection`、`base_date`；分片表保存 `tran_code` 和 `actual_lookback_days`，以便任务详情显示每个交易码及其执行结果。现有时间范围和 SQL 命令的数据与行为保持不变。

```text
输入交易码、N、回溯天数、就近策略
  -> TRAN_CODE 命令
  -> 每个交易码一个分片
  -> 命令级批量加载服务码映射并分片共享
  -> 逐报文类型、逐自然日按分区读取请求（keyset 分页）
  -> 目标事务写入 msg_flow_log_request
  -> 汇总迁移、跳过和分片状态
```

## 执行与一致性

- 以 `msg_flow_log_request` 为主表，使用分区 `partition(p{D})` 读取，并保留 `txn_time` 范围条件保证精确。
- 分区内使用 keyset 分页：`order by txn_time desc, source_ip desc, trans_id desc`，下一页用 `(txn_time, source_ip, trans_id)` 三元组小于上一批末尾，替代 `OFFSET`。
- 目标写入沿用按 `(source_ip, trans_id)` 预查的幂等跳过策略；每个批次在同一个目标库事务内写入请求报文。
- 每种报文类型的数量按成功写入的请求数计算。目标已存在的请求计为跳过。
- 取消在派发新交易码分片以及切换报文类型、自然日、分页批次前检查。运行中的目标写入批次完成后停止。
- 某个交易码异常时，该分片标记 `FAILED` 并记录错误，其余分片继续。续传只重跑 `PENDING` 和 `FAILED` 分片，`SKIPPED` 不重跑。
- 命令执行开始时由 `MigrationBatchRunner` 一次性加载本命令所有交易码的服务码映射并分片共享，避免每个分片重复查询映射表。

## 界面与接口

- 新增 `GET/POST /migration/tran-code-commands` 和对应 Thymeleaf 页面。
- 表单字段为交易码、每类迁移笔数 N、回溯自然日数、就近原则采集、铺底日期、并行度、备注；数据源标签沿用现有 `bxds -> primary`。
- 列表仅显示 `TRAN_CODE` 命令；进度页复用现有页面，并在命令和分片区域展示交易码、N、分片交易码与实际回溯天数。
- 扩展取消、续传及进度 JSON 的既有接口，不新增独立任务体系。

## 测试

- 命令创建：交易码解析、去重、参数校验、交易码分片及 `TRAN_CODE` 持久化。
- 运行器：多交易码并行、跳过分片、失败续传和命令级映射批量加载。
- 分片执行：服务码展开与回退、三种报文类型、请求驱动、分区访问、分区缺失跳过、keyset 分页、每天由近到远、每类 N 上限、目标幂等跳过、就近扫描顺序、全量回溯的最早分区推算、当天以当前时间为上限。
- 页面与控制器：新页面路由、表单和列表内容。
- DDL：字段、命令类型和分片状态约束的结构检查。

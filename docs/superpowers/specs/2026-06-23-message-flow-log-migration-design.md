# 报文日志跨库迁移设计

## 背景与目标

本项目主数据源（tss schema，PostgreSQL/openGauss）需要从同实例的 bxds schema 迁移
`msg_flow_log_request` 与 `msg_flow_log_response` 两张表的海量历史数据（亿级以上），
用于回放比对分析。两表为一笔交易的请求与响应，通过 `(trans_id, source_ip)` 一一对应，
必须配对迁移；单边缺失的交易予以丢弃。

目标：
- 支持亿级数据量迁移，时间窗口分片 + 线程池并行执行
- 页面实时展示迁移进度（完成分片数/总数、迁移交易笔数、丢弃数、耗时）
- 支持中途取消与断点续传
- 配对一致性：request 与 response 同一事务内成对写入，单边丢弃

## 表结构约束（已确认）

- `msg_flow_log_request`：PK = `(trans_id, source_ip)`，索引 `idx_txn_time`、`idx_txn_code`，
  报文字段 `request_message` 为 blob
- `msg_flow_log_response`：PK = `(trans_id, source_ip)`，索引 `idx_response_time`、`idx_txn_code`，
  报文字段 `response_message` 为 blob
- 源 schema `bxds` 与目标 schema `tss` 表结构完全一致，位于同一数据库实例，可跨 schema SQL 迁移

## 整体架构

新建 `com.spdb.migration` 包，复用现有「状态表 + ThreadPoolTaskExecutor + 状态机」异步任务模式
（与 `sampling` 包一致）。

```
用户提交迁移表单 (源schema=bxds, 响应时间范围, 窗口大小, 并行度)
        │
        ▼
MigrationCommandService.createCommand()
   ├─ 1. 预切分：按 [timeFrom, timeTo] 以 windowSeconds 步进，生成 N 个 PENDING 分片
   ├─ 2. 主任务记录入库（status=CREATED）
   └─ 3. MigrationTaskLauncher.launch(commandId) 异步派发
        │
        ▼
MigrationAsyncExecutor.run(commandId)
   ├─ markRunning(commandId)
   ├─ 顺序取 PENDING 分片，按 parallelism 限流提交线程池
   │     MigrationShardRunner.run(shardId)  (@Transactional)
   │       ├─ 临时表暂存窗口内配对键集合
   │       ├─ insert into tss.msg_flow_log_response ... on conflict do nothing
   │       ├─ insert into tss.msg_flow_log_request  ... on conflict do nothing
   │       ├─ 统计 migrated_rows / skipped_rows / dropped_rows
   │       └─ 原子累加主任务进度计数
   ├─ 派发前检测 CANCEL_REQUESTED → 停止派发
   └─ 收尾：全完成→COMPLETED；有失败→FAILED；取消→CANCELLED

页面轮询 GET /migration/commands/{id}/progress (JSON, 3秒间隔)
```

## 数据模型

### `ana_migration_command` 迁移指令表

| 字段 | 类型 | 说明 |
|---|---|---|
| command_id | bigserial PK | 迁移指令ID |
| source_schema | varchar(64) NOT NULL | 源 schema，固定 bxds |
| target_schema | varchar(64) NOT NULL DEFAULT 'tss' | 目标 schema |
| time_from | bigint NOT NULL | 迁移响应时间范围起点 |
| time_to | bigint NOT NULL | 迁移响应时间范围终点 |
| window_seconds | bigint NOT NULL | 每个分片的响应时间窗口大小（秒） |
| parallelism | integer NOT NULL DEFAULT 2 | 并行分片数 |
| status | varchar(32) NOT NULL DEFAULT 'CREATED' | 状态机值 |
| total_shard_count | bigint NOT NULL DEFAULT 0 | 总分片数 |
| completed_shard_count | bigint NOT NULL DEFAULT 0 | 已完成分片数 |
| failed_shard_count | bigint NOT NULL DEFAULT 0 | 失败分片数 |
| migrated_rows | bigint NOT NULL DEFAULT 0 | 已迁移交易笔数累计 |
| skipped_rows | bigint NOT NULL DEFAULT 0 | 冲突跳过行数累计 |
| dropped_rows | bigint NOT NULL DEFAULT 0 | 单边丢弃交易数累计 |
| error_message | varchar(2000) | 失败原因 |
| remark | varchar(1000) | 备注 |
| created_by | varchar(100) | 创建人 |
| created_time | timestamp NOT NULL DEFAULT current_timestamp | 创建时间 |
| started_time | timestamp | 开始时间 |
| ended_time | timestamp | 结束时间 |
| updated_at | timestamp NOT NULL DEFAULT current_timestamp | 更新时间 |

约束：`ck_ana_migration_command_status check (status in ('CREATED','RUNNING','COMPLETED','FAILED','CANCEL_REQUESTED','CANCELLED'))`

说明：`migrated_rows` 统计的是交易笔数（= response 插入行数 = request 插入行数，两表成对）。

### `ana_migration_shard` 迁移分片表

| 字段 | 类型 | 说明 |
|---|---|---|
| shard_id | bigserial PK | 分片ID |
| command_id | bigint NOT NULL | 关联指令ID（逻辑关联） |
| shard_seq | integer NOT NULL | 分片序号（从0开始） |
| time_from | bigint NOT NULL | 分片响应时间起点 |
| time_to | bigint NOT NULL | 分片响应时间终点 |
| status | varchar(32) NOT NULL DEFAULT 'PENDING' | 分片状态 |
| migrated_rows | bigint NOT NULL DEFAULT 0 | 本分片迁移交易笔数 |
| skipped_rows | bigint NOT NULL DEFAULT 0 | 本分片冲突跳过行数 |
| dropped_rows | bigint NOT NULL DEFAULT 0 | 本分片丢弃交易数 |
| error_message | varchar(2000) | 失败原因 |
| attempts | integer NOT NULL DEFAULT 0 | 执行尝试次数 |
| created_time | timestamp NOT NULL DEFAULT current_timestamp | 创建时间 |
| started_time | timestamp | 开始时间 |
| ended_time | timestamp | 结束时间 |

约束：
- `ck_ana_migration_shard_status check (status in ('PENDING','RUNNING','COMPLETED','FAILED','SKIPPED'))`
- `uk_ana_migration_shard_seq unique (command_id, shard_seq)`

索引：`idx_ana_migration_shard_command_status on (command_id, status)`

### DDL

追加到 `db/ddl.sql`，遵循现有 `create table if not exists` + `comment on` 风格。

## 状态机

### 主任务

```
CREATED ──launch──▶ RUNNING ──全部分片完成──▶ COMPLETED
                       │  ├──存在失败分片──▶ FAILED
                       └──用户取消──▶ CANCEL_REQUESTED ──等待运行中分片──▶ CANCELLED
```

- CREATED：指令已建，分片已切分，尚未派发
- RUNNING：异步线程派发/执行分片中
- CANCEL_REQUESTED：用户请求取消，停止派发新分片，等待运行中分片结束
- CANCELLED：运行中分片结束，未执行分片保留 PENDING，支持续传
- COMPLETED：全部分片 COMPLETED/SKIPPED
- FAILED：存在失败分片（不阻断其他分片，最终汇总判定，可续传重试失败分片）

### 分片

```
PENDING ──派发──▶ RUNNING ──成功──▶ COMPLETED (或 SKIPPED 当源表无数据)
                    └──失败──▶ FAILED ──续传重试──▶ RUNNING
```

- PENDING：已切分未派发
- RUNNING：分片 SQL 执行中（执行前 attempts+1）
- COMPLETED：迁移成功，记录 migrated_rows/skipped_rows/dropped_rows
- SKIPPED：分片范围内源表无数据（迁移 0 行），仍计为已完成
- FAILED：SQL 异常，记录 error_message，不影响其他分片

## 分片迁移 SQL（亿级优化：临时表驱动）

每个 `response_time` 窗口 `[lo, hi]` 在单事务内执行四步，临时表暂存配对键集合，
避免对 request 表全表扫描：

```sql
-- 步骤1：临时表存放窗口内"有对应 request"的 response 键
create local temp table tmp_mig_keys (
    trans_id varchar(64), source_ip varchar(64),
    primary key (trans_id, source_ip)
) on commit drop;

insert into tmp_mig_keys (trans_id, source_ip)
select s.trans_id, s.source_ip
from bxds.msg_flow_log_response s
where s.response_time between :lo and :hi
  and exists (select 1 from bxds.msg_flow_log_request r
              where r.trans_id = s.trans_id and r.source_ip = s.source_ip);
-- response 走 idx_response_time 窗口扫描，每行 EXISTS 走 request PK 查找

-- 步骤2：迁 response（键集合 join 窗口内 response）
insert into tss.msg_flow_log_response (
    source_ip, trans_id, txn_code, response_time, message_type,
    response_message, return_code, return_msg
)
select s.source_ip, s.trans_id, s.txn_code, s.response_time, s.message_type,
       s.response_message, s.return_code, s.return_msg
from bxds.msg_flow_log_response s
join tmp_mig_keys k using (trans_id, source_ip)
where s.response_time between :lo and :hi
on conflict (trans_id, source_ip) do nothing;

-- 步骤3：迁 request（键集合 join request，走 PK 查找，无全表扫描）
insert into tss.msg_flow_log_request (
    source_ip, trans_id, txn_code, txn_time, message_type,
    request_message, global_seq_no, tran_teller_no
)
select r.source_ip, r.trans_id, r.txn_code, r.txn_time, r.message_type,
       r.request_message, r.global_seq_no, r.tran_teller_no
from bxds.msg_flow_log_request r
join tmp_mig_keys k using (trans_id, source_ip)
on conflict (trans_id, source_ip) do nothing;

-- 步骤4：统计丢弃数 = 窗口内 response 总数 − 临时表键数
select count(*) from bxds.msg_flow_log_response
where response_time between :lo and :hi;
```

关键优化点：
1. 临时表驱动：配对键集合在分片内只算一次，后续两表 insert 都走 PK 查找，消除全表扫描
2. 临时表带 PK 索引，join 走索引
3. `on commit drop`：分片事务结束自动清理；openGauss 临时表会话级可见，多线程并行互不干扰
4. 单事务 `@Transactional` 包裹四步，任一失败整体回滚，保证配对原子性
5. 窗口大小默认 3600 秒（1小时），窗口内 response 行数控制在百万级；亿级总量约 100+ 分片

性能预估：单分片（百万级 response）四步合计约 1-3 分钟；8 并行 × 100 分片，亿级数据约 1-2 小时。

## 并发控制

- 线程池：新增独立 `ThreadPoolTaskExecutor` bean（`migrationTaskExecutor`），core/max 与 parallelism 上限（8）匹配，
  queueCapacity=0，与采样线程池隔离
- 派发策略：`MigrationBatchRunner` 顺序取 PENDING 分片，用 Semaphore 限制在途分片数 ≤ parallelism；
  派发前检查主任务状态，CANCEL_REQUESTED 则停止派发
- 分片占用：PENDING→RUNNING 用乐观更新 `where status='PENDING'`，确保不重复派发
- 计数聚合：每个分片完成后，主任务计数用 `set x = x + :delta where command_id=:id` 原子累加
- 取消检测：每个分片执行前再查主任务状态，CANCEL_REQUESTED 则跳过执行

## 断点续传

- 重新触发 CANCELLED/FAILED 指令时，不重建分片，只对 `status in ('PENDING','FAILED')` 的分片重新派发
- 主任务状态置回 RUNNING，重置 started_time，保留原有进度计数
- attempts > 3 的分片不再允许续传重试，需人工介入

## 服务层

### `com.spdb.migration` 包新增类

| 类 | 职责 |
|---|---|
| MigrationCommandForm | 表单 record（sourceSchema, timeFrom, timeTo, windowSeconds, parallelism, remark） |
| MigrationCommandRow | 指令列表/详情 record |
| MigrationShardRow | 分片详情 record |
| MigrationProgressRow | 进度聚合 record（command + 分片列表） |
| MigrationCommandService | 指令 CRUD、分片切分、状态机变更、进度查询 |
| MigrationShardRunner | 单个分片 SQL 执行（@Transactional） |
| MigrationBatchRunner | 主任务编排：派发分片、聚合结果、状态收尾 |
| MigrationAsyncExecutor | 实现 MigrationTaskLauncher，异步启动 MigrationBatchRunner |
| MigrationTaskLauncher | 异步启动接口 |

### MigrationCommandService 关键方法

- `createCommand(form)`：校验 → 切分时间窗口生成分片 → 插入指令 → 异步派发
- `getProgress(commandId)`：主任务 + 分片聚合统计
- `search(criteria, page)`：分页查询指令列表
- `markRunning / markCompleted / markFailed / requestCancel / markCancelled`
- `resumeCommand(commandId)`：断点续传
- `tryStartShard(shardId)` / `markShardCompleted` / `markShardFailed` / `accumulateProgress`

## 控制器 `MigrationController`（com.spdb.web）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /migration/commands | 指令列表页（分页），含创建表单 |
| POST | /migration/commands | 提交创建指令，重定向到进度页 |
| GET | /migration/commands/{id} | 指令进度详情页（主任务 + 分片列表） |
| POST | /migration/commands/{id}/cancel | 请求取消 |
| POST | /migration/commands/{id}/resume | 断点续传 |
| GET | /migration/commands/{id}/progress | JSON 接口，页面轮询获取进度 |

## 页面与进度展示

### 新增模板

- `templates/migration/commands.html`：指令列表 + 创建表单
- `templates/migration/progress.html`：单指令进度详情页

布局沿用现有 `fragments/layout :: topbar` + `page-head` + `panel` 风格，状态用彩色 tag。

### commands.html — 列表页

创建表单区（panel）字段：
- 源 schema：只读 bxds
- 目标 schema：只读 tss
- 响应时间范围起 timeFrom / 止 timeTo：数字输入（bigint）
- 窗口大小 windowSeconds：数字输入，默认 3600，提示「每个分片的响应时间窗口秒数」
- 并行度 parallelism：数字输入，默认 2，范围 1-8
- 备注 remark：文本框
- 提交按钮

指令列表区（panel + 分页）列：指令ID、状态、进度（完成分片/总分片）、已迁移交易笔数、丢弃数、跳过数、耗时、创建时间、操作（查看进度/取消/续传）。

### progress.html — 进度详情页

顶部主任务概览卡片：状态（大号 tag）、进度百分比、已迁移交易笔数、丢弃交易数、跳过行数、耗时、失败分片数、操作按钮（取消/续传）。

进度条：横向条形图，宽度按百分比，运行中带脉冲动画。

分片明细表列：序号、响应时间窗口 [time_from → time_to]、状态（tag）、迁移笔数、丢弃数、跳过数、尝试次数、耗时、错误信息。

### 实时进度轮询

页面 JS 定时调用 `GET /migration/commands/{id}/progress`（JSON），仅在状态为 RUNNING/CANCEL_REQUESTED 时轮询，间隔 3 秒；终态停止轮询并刷新页面。

JSON 返回结构：
```json
{
  "commandId": 1,
  "status": "RUNNING",
  "timeFrom": 1719100000,
  "timeTo": 1719186400,
  "windowSeconds": 3600,
  "totalShardCount": 240,
  "completedShardCount": 156,
  "failedShardCount": 2,
  "migratedRows": 89300000,
  "skippedRows": 1200,
  "droppedRows": 350,
  "startedTime": "2026-06-23T15:00:00",
  "endedTime": null,
  "durationSeconds": 1820,
  "errorMessage": null,
  "shards": [
    {"shardSeq":0,"timeFrom":1719100000,"timeTo":1719103600,"status":"COMPLETED","migratedRows":3000,"skippedRows":0,"droppedRows":1,"attempts":1,"durationSeconds":12}
  ]
}
```

### 导航接入

`fragments/layout.html` nav 增加：
```html
<a th:classappend="${active == 'migration'} ? 'active'" href="/migration/commands">数据迁移</a>
```

## 错误处理与边界

### 分片级
- 单分片失败不阻断整体：捕获异常 → markShardFailed + 记录 error_message（截断 2000 字符）
- 失败不自动重试，通过续传手动重试，attempts 累加；attempts > 3 不再允许重试

### 数据一致性
- 配对原子性：步骤1-4 单事务，任一失败整体回滚，无单边迁移
- 幂等性：ON CONFLICT (trans_id, source_ip) DO NOTHING，重试/续传已迁移交易跳过
- 丢弃计数：dropped_rows = 窗口内 response 总数 − 临时表键数，事务内一致

### 并发与取消
- 重复提交防护：同一时间只允许一个 RUNNING/CREATED 指令，createCommand 时检查拒绝
- 取消优雅性：requestCancel 仅置 CANCEL_REQUESTED，不中断运行中分片；派发前检测停止派发
- 续传前置校验：仅 CANCELLED/FAILED 状态允许 resume
- 并发计数安全：set x = x + :delta 原子累加

### 参数校验（createCommand）
- timeFrom < timeTo，否则 IllegalArgumentException
- windowSeconds ∈ [60, 86400]，默认 3600
- parallelism ∈ [1, 8]，默认 2
- 时间范围跨度 ≤ 400天（分片数上限 9600）

### 系统级
- 并行度上限 8，峰值连接数 9（8 分片 + 1 主任务），在 HikariCP 默认池容量内
- 临时表 on commit drop 清理；JVM 崩溃时会话断开自动清理
- getProgress 查询失败时页面显示「进度获取失败，请刷新」，不影响后台任务

## 实现顺序

按用户偏好，分两阶段迭代：

### 阶段一：静态页面 + 静态测试数据
1. 新增 `templates/migration/commands.html`、`templates/migration/progress.html`
2. `MigrationController` 先返回静态 Mock 数据（硬编码的指令列表、分片列表、进度 JSON）
3. `fragments/layout.html` 增加导航项
4. 不接入数据库与异步逻辑，仅展示页面效果与交互（含 JS 轮询 Mock JSON）
5. 静态测试数据覆盖各状态：CREATED/RUNNING/COMPLETED/FAILED/CANCELLED，含分片明细

### 阶段二：后端逻辑接入
1. DDL：新增 ana_migration_command / ana_migration_shard 表
2. 服务层：MigrationCommandService / MigrationShardRunner / MigrationBatchRunner / MigrationAsyncExecutor
3. 线程池配置 migrationTaskExecutor
4. 控制器替换 Mock 为真实数据库查询与异步派发
5. db/seed.sql 补充少量测试数据

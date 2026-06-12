# 批量采样程序简化设计

## 目标

在采样结果、统计口径、页面行为不变的前提下，简化批量采样程序。保留当前聚合 SQL 方案，不引入 Spring Batch，不恢复分区或逐笔流式处理。

## 范围

- 删除已废弃的分块采样入口和 `SamplingTranItem`。
- 将一次批量采样执行从 `SamplingCommandService` 拆到独立执行组件。
- `SamplingCommandService` 保留采样指令创建、分页查询、状态更新和批次初始化。
- 保留现有临时表、候选表、分组、明细和统计汇总 SQL 的行为。

## 设计

新增 `SamplingBatchRunner`，由它负责 `run(batchId)` 的采样主流程：初始化批次、生成候选、生成分组、生成明细、更新统计、收尾清理候选数据。

`SamplingCommandService` 注入 `SamplingBatchRunner`，对外保留 `runSamplingBatch(batchId)` 方法，作为异步执行器的稳定入口。这样控制器和 `SamplingAsyncExecutor` 不需要调整调用方式。

SQL 仍使用 `NamedParameterJdbcTemplate` 和 `TransactionTemplate`。临时表相关操作仍放在事务内，避免改变 openGauss 临时表生命周期和执行计划统计行为。

## 验证

- 结构测试直接读取源码，确保废弃分块路径不存在。
- 保留现有结构测试对聚合 SQL、临时表、明细抽样和非 Spring Batch 的约束。
- 运行完整 `mvn test`。

# 报表比较源表有界内存改造及兼容性验证

## 目标和范围

消除 `ReportExportBatchRunner` 对 `tss_tran_comp`、`tss_field_comp`、`tss_retcode_comp` 的全量 Java List/Map，以及明细处理中的全量排序和受影响流水号 Set。保持原来的全表输入范围，不自动按报表日期过滤，不增加表结构迁移。

小型配置目录和字段映射仍缓存；领域级汇总使用计数对象。数据库承担分组、排序、去重，JDBC 使用 `autoCommit=false`、`fetchSize=500` 的游标读取。

Unicode 分组键必须与当前 JDK 一致，不能用数据库 `lower()` 近似替代。读取器将去重后的字段名、响应码和相关服务码流式转换为 Java `Locale.ROOT` 小写/trim 小写结果，每批最多 500 条写入连接本地临时字典，由后续 SQL 查询使用。PostgreSQL 临时表在事务提交时删除，不修改持久表结构；部署账号须具备数据库 TEMP 权限。临时字典随不同读取阶段重建，有额外数据库扫描与临时存储成本。

## 兼容性验收

`LegacyReportExportReference` 从固定原版提交 `377af98` 提取并编译未经修改的生成器，在隔离 ClassLoader 中执行。新旧实现分别读取同一份测试源数据，生成时使用相同批次号、日期和导出时间。

这项差分测试需要 JDK 编译器及包含该提交的 Git 历史；浅克隆环境应先获取该提交。测试不会把当前新实现当作参考答案。

逐字段比较以下六类表：

- 领域汇总；
- 接口汇总；
- 交易级问题明细；
- 字段级问题明细；
- 全局问题台账；
- 台账备份。

业务字段、问题 ID、行号、代表流水号、原值、目标值、去重计数、比率、映射结果和历史快照均参加比较。仅排除每次运行自然变化的台账创建/更新时间、备份时间；固定传入的导出时间仍参与比较。

同时对日报、未脱敏日报、周报、全量问题清单四类 Excel 比较 Sheet 名称、单元格位置/类型/值、样式编号、行高和合并区域，而不是比较包含 ZIP 元数据的文件字节。

## 数据覆盖

- 原有报表功能、阶段日志、失败清理、台账回滚、历史问题和每周基准测试；
- 三组固定随机种子：`20261110`、`42`、`739`；
- 0～8 状态、忽略比较、缺失响应码、空值和大小写/空白响应码；
- 多业务日期、多接口、未配置领域、同流水多转换；
- 字段前两段归一化、下划线后缀、前后点号、连续点号、空字段、大小写及制表符/换行；
- 问题键分隔符碰撞：保留原版已有的字符串键分组语义；
- 单热门接口 1,205 条交易、1,205 条响应码、2,410 条字段记录，跨越 JDBC fetch 窗口，验证去重计数和代表记录；
- 相同批次重复生成，比较台账和备份；
- JDBC 访问约束检查：源表 PreparedStatement 必须关闭自动提交、设置正数 fetch size，旧的全量装载方法不得保留。
- 独立审查发现并先补失败测试后修正：制表符/Unicode 空白领域、单连接池事务连接复用、Java UTF-16 顺序、复合身份字符串分隔符碰撞，以及 Java/数据库 Unicode 小写转换差异（希腊末尾 sigma、U+0130 展开映射及目录匹配）。

## 复现命令

默认 H2 测试和项目回归：

```powershell
mvn test -q
```

使用独立 PostgreSQL 测试实例：

```powershell
mvn -q "-Dtest=ReportExportBatchRunnerTest" "-Drose.test.postgres.url=jdbc:postgresql://127.0.0.1:25439/postgres" test
```

PostgreSQL 测试为每个用例创建随机独立 schema，完成后删除该 schema。测试账号为 `postgres`，使用本地测试实例的 trust 认证；不得指向生产数据库。

## 已执行验证

2026-11-10：

- 最终本地独立 PostgreSQL 16.6：37 项报表测试，0 失败、0 错误、0 跳过。
- 最终全项目 `mvn test -q`：443 项，0 失败、0 错误、3 跳过，其中 2 项是原有禁用测试，1 项是仅在 PostgreSQL 下执行的单连接池测试。
- 独立审查及两轮修正复审：发现的问题均已补测试并处理，最终只读复审未发现新增 Important/Critical 问题。审查不是任意输入正确性的证明。

## 独立 JVM 有界堆内存对照

在独立 PostgreSQL 16.6 测试 schema 中，每张源表插入 100,000 行，字段原/目标值各 2,000 字符；热门接口、同一字段问题。准备测试表达式索引并执行 `ANALYZE`。同样数据分别在独立 `-Xmx128m` JVM 中运行新旧实现，不在普通 Surefire JVM 中测量。

```powershell
mvn -q test-compile dependency:build-classpath "-Dmdep.outputFile=target/probe-classpath.txt"
$cp = 'target/test-classes;target/classes;' + (Get-Content target/probe-classpath.txt -Raw).Trim()
java -Xmx128m "-Drose.test.postgres.url=jdbc:postgresql://127.0.0.1:25439/postgres" -cp $cp com.spdb.report.ReportExportMemoryProbe 100000
java -Xmx128m "-Drose.test.postgres.url=jdbc:postgresql://127.0.0.1:25439/postgres" -cp $cp com.spdb.report.ReportExportMemoryProbe 100000 legacy
```

实测结果：

- 新实现：退出码 0，`MEMORY_PROBE_OK rows=100000 heapLimitBytes=134217728 elapsedMs=10553 legacy=false`，汇总笔数及字段影响笔数均为 100,000。
- 原版 `377af98`：退出码 1，源数据加载阶段抛出 `OutOfMemoryError: Java heap space`。
- 日志分别位于 `target/memory-probe.log`、`target/memory-probe-legacy.log`；普通回归和 PostgreSQL 回归日志为 `target/full-tests.log`、`target/postgres-tests.log`。这些运行产物不纳入 Git。

该对照证明这一明确场景中消除了原版的全量源数据装载问题，不是生产规模容量承诺，也未测出完整堆峰值曲线；同一问题组的设计刻意隔离源数据规模与输出问题数，不能据此证明大量独立问题的台账/Excel 也有界。

## 尚未解决或不能据此承诺的事项

- 兼容性、跨 fetch 边界和上述 100,000 行固定堆对照均已执行，但没有进行生产百万级压力测试或完整 JVM 峰值内存测量。
- 数据库排序/聚合仍有 CPU、内存及临时磁盘成本，需要结合实际索引与执行计划压测。
- 源表在报表生成期间仍需保持稳定；本次未引入跨阶段统一源快照。
- 原有台账问题 List、周重复问题集合、Excel 快照集合和部分长事务仍然存在；本次不承诺整个报表系统不存在其他 OOM 或超时风险。
- 原代码部分代表记录依赖没有完整排序的数据库读取顺序；响应码同键冲突值、完全相同排序键的多条不同字段值，以及并行服务间行号，本来就不是跨执行稳定的业务规则。测试对已覆盖数据严格比较，但不能把测试通过等同于任意异常数据、任意数据库排序环境的数学证明。

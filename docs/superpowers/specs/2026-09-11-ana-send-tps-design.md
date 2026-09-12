# ana 报文发送实时 TPS 统计设计

## 范围

在现有报文发送功能基础上增加：启动异步化、实时 TPS 统计、本次运行内存计数、状态轮询接口与页面展示。不改变发送协议映射、地址选择、鉴权、响应解析和重置逻辑。

## 背景与问题

- `AnaMessageSendController.start` 同步调用 `AnaMessageSendService.start`，HTTP 请求会阻塞到全部发完或手动停止才返回 `redirect`。运行期间页面不加载，无法展示实时指标，也无法通过页面触发停止。
- 页面上 PENDING/SENDING/SUCCESS/FAILED 四个统计来自数据库整表 `count`，不适合每 2 秒轮询。
- 现有实现没有实时速率指标；`ThreadLocal` 的 `sentCount` 仅用于日志。

## 启动异步化

沿用项目现有 launcher 模式：

- 新增接口 `AnaMessageSendTaskLauncher`，方法 `void launch(String target, int concurrency, int rangeMinutes, int retries, int timeout)`。
- 新增 `@Component AnaMessageSendAsyncExecutor implements AnaMessageSendTaskLauncher`，注入 `@Qualifier("anaMessageSendTaskExecutor") ThreadPoolTaskExecutor`，`launch` 执行 `executor.execute(() -> service.start(...))`。
- 新增 `AnaMessageSendExecutionConfig` 提供 `anaMessageSendTaskExecutor` bean：`corePoolSize=1`、`maxPoolSize=1`、`queueCapacity=1`、线程名前缀 `ana-message-send-`，保证同一时刻只有一个发送任务在跑。
- `AnaMessageSendController.start` 改为调用 `launcher.launch(...)` 后立即 `redirect:/messages/ana-send`；提交前若 `service.isRunning()` 为真则直接忽略重复启动。
- `AnaMessageSendService.start` 内部逻辑不变：仍是时间分片 + 有界队列 + 固定线程池并发消费，只是执行线程从 HTTP 线程改为后台线程。

## 内存计数与 TPS

新增 `TpsWindow`（`com.spdb.message`）：

- 5 秒滑动窗口，环形秒桶实现：`AtomicLongArray counts[6]` 与 `AtomicLongArray epochs[6]`，每个槽记录其归属的秒编号，写入时若 epoch 不匹配当前秒则先清零再累加。
- `record()` 记录一笔完成；`countInWindow()` 汇总最近 5 秒有效槽的笔数；`reset()` 清零；时钟通过 `LongSupplier`（毫秒）注入以便测试。

`AnaMessageSendService` 增加：

- `LongAdder successCount`、`LongAdder failedCount`、`AtomicInteger inFlight`、`volatile long total`、`TpsWindow tpsWindow`。
- `start()` 一开始就重置内存计数与 TPS 窗口（`total=0`）；取得快照边界后执行一次 `select count(*) from ana_msg_flow_log_request where send_status in ('PENDING','FAILED') and send_attempts <= :retries and txn_time is not null` 得到本次快照总数 `total`。若无待发送记录则提前结束，`liveStats()` 显示全 0。
- `sendOne`：进入时 `inFlight.incrementAndGet()`；HTTP 2xx 累加 `successCount`，非 2xx 与异常累加 `failedCount`；`finally` 中 `inFlight.decrementAndGet()` 并调用 `tpsWindow.record()`。
- `liveStats()` 返回 Map：`running`（`isRunning()`）、`tps`（保留一位小数，成功+失败都计入）、`pending`（`max(0, total - success - failed)`）、`sending`（`inFlight`）、`success`（`successCount`）、`failed`（`failedCount`）。

统计口径为「本次运行」：`pending` 表示本次任务尚未处理完的笔数（队列中 + 未读取 + 在途），任务停止后可能大于 0；窗口过期后 `tps` 自然为 0。该口径为单机单实例视角。

## 状态接口与页面

- `AnaMessageSendController` 新增 `@GetMapping("/messages/ana-send/status") @ResponseBody`，直接返回 `liveStats()`，轮询期间零数据库查询。
- 页面统计区新增「实时 TPS」卡片，四个统计卡片补充元素 id（`pending/sending/success/failed`），TPS 卡片 id 为 `ana-tps`。
- 页面脚本每 2 秒 `fetch(/messages/ana-send/status)` 更新 TPS 与四个统计；一旦**观察到运行中的任务结束**（`running` 由真变假）则停止轮询并把 TPS 显示为 `0.0`，避免启动瞬间后台线程尚未置位 `running` 导致误停。
- 页面 model 增加 `running`（来自 `service.isRunning()`），供页面初始渲染参考。

## 边界与错误处理

- 未运行或窗口过期：`tps=0.0`。
- 轮询单次请求失败：保留上一次显示值，不报错、不清零。
- 运行中重复点击启动：由 `isRunning()` 判断忽略。
- `status` 接口异常不影响后台发送任务。
- 重置仍在运行中禁止（沿用现有 `IllegalStateException`）。

## 测试

- `TpsWindowTest`：注入可控时钟，覆盖窗口内计数、跨窗口过期归零、环形槽覆盖、`reset`。
- `AnaMessageSendControllerTest`：`status` 返回预期 JSON 字段；`start` 调用 launcher 并立即返回 `redirect`（不阻塞）；运行中重复启动被忽略；`stop`/`reset` 行为。
- `AnaSendTemplateTest`：`ana-send` 页面包含 TPS 卡片、四个统计 id 与轮询脚本。
- 回归现有 `ResponseMessageParserTest`、`MessageFlowLogServiceTest`、`MessageFlowLogTemplateTest`、`MessageFlowLogEntryControllerTest`、`MessageFlowLogEntryTemplateTest`。

# ana_msg_flow_log_request 发送设计

## 范围

本阶段不实现历史数据迁移、迁移游标、迁移任务或迁移配置。只实现新表建模、HTTP 发送服务和发送管理界面。新表由外部导入或后续独立脚本填充。

## 数据库

新增 `ana_msg_flow_log_request`，保留 `msg_flow_log_request` 的请求字段：`source_ip`、`trans_id`、`txn_code`、`txn_time`、`message_type`、`request_message`、`global_seq_no`、`tran_teller_no`。主键/唯一约束沿用 `(source_ip, trans_id)`。

新增发送字段：`send_status`（PENDING/SENDING/SUCCESS/FAILED）、`send_time`、`send_http_status`、`send_error`、`send_attempts`。增加按状态和时间查询的索引。`system_config` 初始化 `micServId=10530013`；认证密钥不入配置，由 `tss_service_auth` 按 `protocol_id` 提供（`sid`、`gk`、`wk`、`pk`）。

新增 `ana_msg_flow_log_response`，以 `(source_ip, trans_id, response_time)` 为唯一定位，保存 `txn_code`、`message_type`、原始二进制 `response_message`、`return_code`、`return_msg`、`http_status`、`service_address`、`response_time` 和创建时间，并建立 `(trans_id, response_time desc)` 查询索引。请求和响应表均保留原始二进制；页面按报文类型转换展示，不改变存储内容。

返回码解析规则：`json/bzjson` 查找 JSON 字段 `ReturnCode`；`soap` 按元素本地名查找 `ReturnCode`，忽略 XML 命名空间（可匹配 `<s:ReturnCode>`）；`sop/sop2cbsp` 从 Java 0-based offset 90 开始读取 7 个字节，使用 GBK 解码。当前未定义返回信息解析规则，`return_msg` 保留为空。

## 发送流程

用户在发送页面选择目标系统（528 或 CCBS）、并发度、区间分钟数（时间分片粒度，默认 60 分钟）、最大重试次数和超时秒数后启动任务。发送采用时间分片驱动模型：启动时读取待发送记录（`send_status in ('PENDING','FAILED')` 且 `send_attempts <= retries`）的最小和最大 `txn_time` 作为快照边界，从最小值开始每次按区间时长（`[cursor, cursor+rangeMillis)`，按 `txn_time` 正序）推进。区间数据通过流式游标逐行写入固定容量（1 万条）的有界内存队列：队列满时读取阻塞暂停，消费者腾出空间后自动补充，因此内存占用以队列容量为上限，不会随区间大小膨胀。固定大小线程池从队列消费并发送：并发度为 1 时单线程按队列 FIFO 严格按 `txn_time` 顺序串行消费；多线程消费不保证完成顺序。读完全部区间后投放毒丸使消费者退出。任务期间新写入的记录（`txn_time` 大于快照最大值）留待下一轮。读取不置 SENDING，状态仅作为结果标记；启动时将遗留 SENDING 记录恢复为 PENDING。读取条件包含 `send_attempts <= retries`，重发受重试上限约束，发送结果回写时 `send_attempts` 自增。停止操作不再读取后续区间，队列剩余记录继续消费完。worker 先将 `message_type` 映射为标准协议类型（`bzjson`→`json`、`soap`→`xml`、`sop`→`sop`、`sop2cbsp`→`spec`，支持 `json`/`xml`/`sop`/`spec`），加环境前缀组成 `protocol_id`（如 `528_xml`）；`tss_service_control` 的 `service_address` 与 `tss_service_auth` 的密钥均按该 `protocol_id` 查询，地址对逗号分隔值随机选择一个。

HTTP body 直接使用数据库中的原始二进制 `request_message`。Header 为 `micServId` 和 `authContent`；`micServId`、服务地址与认证密钥按任务生命周期缓存（每轮 start 重新加载，负面结果不缓存），authContent 每次发送前按 `protocol_id` 取密钥并调用 `AuthUtil.packToken` 实时生成一次性 token（密钥缺失或解链失败按 FAILED 回写错误信息）。按报文类型设置 JSON/XML 内容类型，未知类型使用 `application/octet-stream`。请求和响应均以原始二进制保存；页面展示时，`sop` 与 `sop2cbsp` 使用 HEX 字符串，`json` 与 `bzjson` 使用 JSON，`soap` 使用 XML。

2xx 标记 SUCCESS；非 2xx、无服务地址、配置缺失、超时或网络异常标记 FAILED，保存 HTTP 状态和截断后的错误信息。每笔发送完成后立即落库：插入响应记录并更新请求状态（`send_attempts` 自增），不使用批量缓冲；落库失败时异常由消费者捕获并记录日志，记录保持原状态可在下轮重发。超过重试次数不再读取。停止操作阻止读取后续区间，队列剩余记录自然消费完。

## 页面与接口

新增发送页面和控制器，提供目标系统下拉框、并发度、区间分钟数、最大重试次数、超时秒数、启动/停止操作，以及 PENDING/SENDING/SUCCESS/FAILED 统计。启动请求同步执行：按区间读取数据放入有界内存队列，固定线程池并发消费，直到无记录或手动停止后返回页面。

新增重置功能：页面提供重置范围（全部已发送 / 仅失败），发送任务运行中禁止重置。重置将记录恢复为初始态（`send_status='PENDING'`、`send_attempts=0`、清空 `send_time/send_http_status/send_error`），使记录可被再次发送，重试次数重新计算；同时同步删除对应的 `ana_msg_flow_log_response` 响应记录。为避免长事务，按 `txn_time` 每小时分批执行（先删响应再回退状态，同一事务提交），页面直接执行并在返回时提示重置笔数。

## 测试

覆盖协议 ID 组成、地址随机选择、二进制 body 和两个 Header、配置读取、无地址/HTTP 异常状态回写、重试上限、并发领取幂等，以及控制器和 Thymeleaf 页面字段。

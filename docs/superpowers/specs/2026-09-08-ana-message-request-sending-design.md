# ana_msg_flow_log_request 发送设计

## 范围

本阶段不实现历史数据迁移、迁移游标、迁移任务或迁移配置。只实现新表建模、HTTP 发送服务和发送管理界面。新表由外部导入或后续独立脚本填充。

## 数据库

新增 `ana_msg_flow_log_request`，保留 `msg_flow_log_request` 的请求字段：`source_ip`、`trans_id`、`txn_code`、`txn_time`、`message_type`、`request_message`、`global_seq_no`、`tran_teller_no`。主键/唯一约束沿用 `(source_ip, trans_id)`。

新增发送字段：`send_status`（PENDING/SENDING/SUCCESS/FAILED）、`send_time`、`send_http_status`、`send_error`、`send_attempts`。增加按状态和时间查询的索引。`system_config` 初始化 `micServId=10530013`；认证密钥不入配置，由 `tss_service_auth` 按 `protocol_id` 提供（`sid`、`gk`、`wk`、`pk`）。

新增 `ana_msg_flow_log_response`，以 `(source_ip, trans_id, response_time)` 为唯一定位，保存 `txn_code`、`message_type`、原始二进制 `response_message`、`return_code`、`return_msg`、`http_status`、`service_address`、`response_time` 和创建时间，并建立 `(trans_id, response_time desc)` 查询索引。请求和响应表均保留原始二进制；页面按报文类型转换展示，不改变存储内容。

返回码解析规则：`json/bzjson` 查找 JSON 字段 `ReturnCode`；`soap` 按元素本地名查找 `ReturnCode`，忽略 XML 命名空间（可匹配 `<s:ReturnCode>`）；`sop/sop2cbsp` 从 Java 0-based offset 90 开始读取 7 个字节，使用 GBK 解码。当前未定义返回信息解析规则，`return_msg` 保留为空。

## 发送流程

用户在发送页面选择目标系统（528 或 CCBS）、并发度、批量大小和重试次数后启动任务。任务以条件更新方式领取 PENDING/FAILED 记录，将状态改为 SENDING；worker 根据目标系统与 `message_type` 组成 `protocol_id`（`528_` 或 `ccbs_` 加下划线加类型），查询 `tss_service_control` 的 `service_address`，对逗号分隔地址随机选择一个。

HTTP body 直接使用数据库中的原始二进制 `request_message`。Header 为 `micServId` 和 `authContent`；`micServId` 从 `system_config` 读取，`authContent` 每次发送前实时计算：将 `message_type` 映射为标准协议类型（`bzjson`→`json`、`soap`→`xml`、`sop`→`sop`、`sop2cbsp`→`spec`），加环境前缀组成 `protocol_id`（如 `528_json`）后查询 `tss_service_auth` 获取 `sid`、`gk`、`wk`、`pk`，调用 `AuthUtil.packToken` 生成一次性 token（密钥缺失或解链失败按 FAILED 回写错误信息）。按报文类型设置 JSON/XML 内容类型，未知类型使用 `application/octet-stream`。请求和响应均以原始二进制保存；页面展示时，`sop` 与 `sop2cbsp` 使用 HEX 字符串，`json` 与 `bzjson` 使用 JSON，`soap` 使用 XML。

2xx 标记 SUCCESS；非 2xx、无服务地址、配置缺失、超时或网络异常标记 FAILED，保存 HTTP 状态和截断后的错误信息。超过重试次数不再领取。停止操作阻止继续领取新任务，已发送请求允许自然结束。

## 页面与接口

新增发送页面和控制器，提供目标系统下拉框、并发度、批量大小、最大重试次数、启动/停止操作，以及 PENDING/SENDING/SUCCESS/FAILED 统计和最近失败记录。后台任务使用现有 Spring 异步/线程池风格，单并发和多并发共享同一发送逻辑。

## 测试

覆盖协议 ID 组成、地址随机选择、二进制 body 和两个 Header、配置读取、无地址/HTTP 异常状态回写、重试上限、并发领取幂等，以及控制器和 Thymeleaf 页面字段。

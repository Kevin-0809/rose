# Transaction List Async Import Design

## Goal

金融业务交易信息登记表导入改为后台任务，支持慢下载站点下的批次级重试，并在页面展示可刷新的导入进度。

## Architecture

沿用迁移模块的模式：任务状态落库，控制器创建任务后跳转到进度页，后台线程执行任务，页面轮询 JSON 进度接口。上传文件先复制到临时文件，任务表保存临时路径和原文件名；后台任务结束后删除临时文件。

## Data Model

新增 `ana_transaction_list_import_task`：

- `task_id`：主键。
- `status`：`CREATED`、`RUNNING`、`COMPLETED`、`FAILED`。
- `original_filename`、`list_file_path`：上传清单来源。
- 进度计数：`total_count`、`request_batch_count`、`completed_batch_count`、`failed_batch_count`、`imported_count`。
- 结果计数：交易新增/更新、字段新增/更新/跳过。
- `failure_message`：失败批次和缺失交易码摘要。
- `created_time`、`started_time`、`ended_time`、`updated_at`。

## Execution Flow

1. `/config/import/list` 收到上传文件后创建任务并跳转 `/config/import/list-tasks/{id}`。
2. `TransactionListImportAsyncExecutor` 在线程池中运行任务。
3. runner 解析清单后更新总交易数和请求批次数。
4. 每个映射文档下载批次最多重试 3 次，间隔 1s、2s、4s。
5. 每个批次成功或失败后立即更新进度。
6. 下载完成后导入已成功下载的 workbook，写入结果计数。
7. 未在映射文档中出现的交易码写入失败摘要；存在失败摘要但任务仍完成时，状态使用 `COMPLETED`，页面展示失败明细。
8. 顶层异常导致任务状态 `FAILED`。

## UI

导入页提交后进入进度页。进度页展示状态、总交易数、批次完成度、失败批次、已导入交易数和结果计数，并每 3 秒轮询 JSON 接口；任务结束后刷新显示最终结果。

## Testing

测试覆盖 DDL、任务服务创建和进度更新、下载重试、控制器重定向/JSON、进度模板轮询元素。

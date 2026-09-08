# ana Message Sending Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build binary HTTP sending for `ana_msg_flow_log_request`, persist parsed responses, and provide a concurrent sending page.

**Architecture:** Add PostgreSQL DDL for request/response analytics tables. A Spring service claims rows with conditional updates, resolves protocol addresses, sends through Java `HttpClient`, parses return codes by message type, and updates request/response state. A Thymeleaf controller exposes configuration, start/stop, progress, and recent response views.

**Tech Stack:** Spring Boot 3, JDBC, Java 17 `HttpClient`, Thymeleaf, PostgreSQL-compatible SQL, JUnit/Spring Boot Test.

---

### Task 1: Database tables and configuration

**Files:** Create `db/ana-message-sending.sql`; modify `src/main/resources/application.properties` only if HTTP timeout properties are absent.

- [ ] Add `ana_msg_flow_log_request` with original request columns, send status fields, primary key `(source_ip, trans_id)`, and status/time indexes.
- [ ] Add `ana_msg_flow_log_response` with request key, raw binary response, parsed `return_code/return_msg`, HTTP metadata, and `(trans_id,response_time desc)` index.
- [ ] Seed `system_config` keys `micServId=10530013` and `authContent=` with conflict-safe inserts.
- [ ] Add an integration DDL assertion test for table names, columns, and indexes.

### Task 2: Response parsing and HTTP sender

**Files:** Create `src/main/java/com/spdb/message/ResponseMessageParser.java`, `HttpRequestMessageSender.java`, `AnaMessageSendResult.java`; tests under `src/test/java/com/spdb/message/`.

- [ ] Implement parser dispatch: `sop/sop2cbsp` reads 7 bytes from zero-based offset 90 using GBK; `json/bzjson` reads JSON property `ReturnCode`; `soap` walks DOM elements by local name `ReturnCode` with namespace ignored; unknown types return empty code and diagnostic error.
- [ ] Implement sender using Java `HttpClient`, raw byte body, `micServId`/`authContent` headers, content type mapping, connect/request timeout, and response byte capture.
- [ ] Test all parser branches, short SOP payloads, namespaced SOAP, invalid JSON/XML, header/body preservation, and non-2xx results.

### Task 3: Concurrent send service

**Files:** Create `src/main/java/com/spdb/message/AnaMessageSendService.java`, `AnaMessageSendCommand.java`, `AnaMessageSendProgress.java`; tests under `src/test/java/com/spdb/message/`.

- [ ] Load `micServId` and `authContent` from `system_config`, treating missing `authContent` as empty string.
- [ ] Claim rows with an atomic status update, resolve `protocol_id` as `528_`/`ccbs_` plus normalized message type, query `tss_service_control`, split and randomly select nonblank address.
- [ ] Execute with bounded executor, honor requested concurrency/batch/retry values, stop new claims on cancellation, and conditionally update status to prevent duplicate processing.
- [ ] Persist parsed response rows and update request send fields for success/failure, HTTP status, elapsed time, attempts, and truncated error.
- [ ] Test single/multi-worker claiming, retry cap, cancellation, address selection, missing service address, and response persistence.

### Task 4: Web page and controller

**Files:** Create `src/main/java/com/spdb/web/AnaMessageSendController.java`; create `src/main/resources/templates/messages/ana-send.html`; modify `src/main/resources/templates/fragments/layout.html`; tests under `src/test/java/com/spdb/web/`.

- [ ] Add GET page, POST start, POST stop, JSON progress, and recent response endpoints.
- [ ] Render target selector, concurrency/batch/retry/timeout controls, statistics, active rows, and response preview with type-specific display labels.
- [ ] Add sidebar navigation and responsive styles consistent with existing `app.css`.
- [ ] Test controller validation, route responses, template fields, and mobile-safe response container behavior.

### Task 5: Verification

- [ ] Run focused message/web tests.
- [ ] Run full Maven test suite and report any pre-existing failures separately.
- [ ] Review DDL and endpoint behavior against the approved design before committing.

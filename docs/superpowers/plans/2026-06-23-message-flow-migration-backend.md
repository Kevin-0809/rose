# Message Flow Migration Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the static migration mock with a real asynchronous two-datasource migration from bxds message-flow tables into the primary datasource schema.

**Architecture:** Keep the existing primary datasource untouched for all current features and add a separate `rose.datasource.bxds.*` source datasource for migration reads only. Migration command and shard state live in the primary datasource; each shard streams paired source rows from bxds and writes request/response rows to the primary datasource in target-side transactions, with cancel/resume status tracked in `ana_migration_command` and `ana_migration_shard`.

**Tech Stack:** Spring Boot 3, Java 17, JDBC/NamedParameterJdbcTemplate, Hikari DataSource, ThreadPoolTaskExecutor, Thymeleaf, PostgreSQL/openGauss SQL, H2-backed unit tests where practical.

---

## File Structure

| File | Responsibility |
|---|---|
| `db/ddl.sql` | Add `ana_migration_command` and `ana_migration_shard` state tables plus indexes/comments. |
| `src/main/resources/application.properties` | Document source datasource defaults for `rose.datasource.bxds.*`; keep primary datasource unchanged. |
| `src/main/java/com/spdb/migration/MigrationDataSourceConfig.java` | Create source datasource and source `NamedParameterJdbcTemplate`; expose target schema/source label properties. |
| `src/main/java/com/spdb/migration/MigrationExecutionConfig.java` | Create isolated migration async executor. |
| `src/main/java/com/spdb/migration/MigrationTaskLauncher.java` | Interface for async launch by command id. |
| `src/main/java/com/spdb/migration/MigrationAsyncExecutor.java` | Launch background batch execution on the migration executor. |
| `src/main/java/com/spdb/migration/MigrationCommandService.java` | Validate forms, create commands/shards, query rows/progress, cancel/resume, mark statuses. |
| `src/main/java/com/spdb/migration/MigrationBatchRunner.java` | Orchestrate shard execution for one command with parallelism and final status. |
| `src/main/java/com/spdb/migration/MigrationShardRunner.java` | Run one shard: read paired source rows, write target rows, compute counters. |
| `src/main/java/com/spdb/migration/MigrationShardResult.java` | Immutable result for one shard execution. |
| `src/main/java/com/spdb/migration/MigrationSourceRow.java` | Immutable paired request/response row read from source. |
| `src/main/java/com/spdb/migration/MigrationCommandForm.java` | Keep current form fields; add validation through service, not the record. |
| `src/main/java/com/spdb/migration/MigrationCommandRow.java` | Add source/target labels while preserving current template helpers. |
| `src/main/java/com/spdb/migration/MigrationProgressRow.java` | Add source/target labels while preserving current template helpers. |
| `src/main/java/com/spdb/web/MigrationController.java` | Replace mock calls with service-backed create/search/progress/cancel/resume. |
| `src/main/resources/templates/migration/commands.html` | Render source datasource label and target schema from model/service, not hard-coded values. |
| `src/main/resources/templates/migration/progress.html` | No structural change required except any source/target metadata if exposed. |
| `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java` | Add DDL assertions for migration state tables. |
| `src/test/java/com/spdb/migration/MigrationCommandServiceTest.java` | Cover command creation, shard splitting, progress query, cancel/resume state changes. |
| `src/test/java/com/spdb/migration/MigrationShardRunnerTest.java` | Cover paired migration, dropped single-sided rows, conflict skips, target transaction behavior. |
| `src/test/java/com/spdb/migration/MigrationBatchRunnerTest.java` | Cover orchestration final statuses and resume/cancel behavior. |
| `src/test/java/com/spdb/web/MigrationControllerTest.java` | Update from mock controller to mocked service dependency. |
| `src/test/java/com/spdb/web/MigrationCommandsTemplateTest.java` | Assert source/target values are dynamic model expressions. |

## Global Constraints

- Primary datasource remains `spring.datasource.*`; existing services must keep autowiring their current `NamedParameterJdbcTemplate`.
- Source datasource is read-only from the application perspective and configured through `rose.datasource.bxds.*`.
- Target schema is the primary datasource schema from `spring.jpa.properties.hibernate.default_schema`; default to `tss` when absent.
- Source display label is `bxds`; target display label is the resolved target schema.
- Migration state tables live in the primary datasource and use unqualified table names, matching the app's default schema behavior.
- Message flow tables are `msg_flow_log_request` and `msg_flow_log_response` on both source and target.
- Pairing key is `(trans_id, source_ip)`.
- Shard time range uses response `response_time` with half-open windows `[time_from, time_to)`, avoiding overlap at boundaries.
- `migrated_rows` is transaction count, meaning rows written as request/response pairs.
- `skipped_rows` is paired source rows skipped because either target table already contains the key.
- `dropped_rows` is source response rows without matching request in the shard window.
- Cross-database atomicity is impossible; each target write batch must be target-transactional and shard state must make retry idempotent.

---

### Task 1: DDL For Migration State

**Files:**
- Modify: `db/ddl.sql`
- Test: `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`

- [ ] **Step 1: Write failing DDL layout test**

Add this test method to `DatabaseScriptLayoutTest`:

```java
@Test
void ddlContainsMigrationStateTables() throws IOException {
    String ddl = Files.readString(Path.of("db/ddl.sql"));

    assertThat(ddl).contains("create table if not exists ana_migration_command");
    assertThat(ddl).contains("create table if not exists ana_migration_shard");
    assertThat(ddl).contains("ck_ana_migration_command_status");
    assertThat(ddl).contains("ck_ana_migration_shard_status");
    assertThat(ddl).contains("idx_ana_migration_shard_command_status");
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
mvn -Dtest=DatabaseScriptLayoutTest#ddlContainsMigrationStateTables test
```

Expected: FAIL because DDL does not contain the migration tables.

- [ ] **Step 3: Add DDL**

Append this block before the final indexes section or at the end of `db/ddl.sql`:

```sql
create table if not exists ana_migration_command (
    command_id bigserial primary key,
    source_label varchar(64) not null default 'bxds',
    target_schema varchar(64) not null default 'tss',
    time_from bigint not null,
    time_to bigint not null,
    window_seconds bigint not null,
    parallelism integer not null default 2,
    status varchar(32) not null default 'CREATED',
    total_shard_count bigint not null default 0,
    completed_shard_count bigint not null default 0,
    failed_shard_count bigint not null default 0,
    migrated_rows bigint not null default 0,
    skipped_rows bigint not null default 0,
    dropped_rows bigint not null default 0,
    error_message varchar(2000),
    remark varchar(1000),
    created_by varchar(100),
    created_time timestamp not null default current_timestamp,
    started_time timestamp,
    ended_time timestamp,
    updated_at timestamp not null default current_timestamp
);

alter table ana_migration_command drop constraint if exists ck_ana_migration_command_status;
alter table ana_migration_command add constraint ck_ana_migration_command_status
check (status in ('CREATED','RUNNING','COMPLETED','FAILED','CANCEL_REQUESTED','CANCELLED'));

create table if not exists ana_migration_shard (
    shard_id bigserial primary key,
    command_id bigint not null,
    shard_seq integer not null,
    time_from bigint not null,
    time_to bigint not null,
    status varchar(32) not null default 'PENDING',
    migrated_rows bigint not null default 0,
    skipped_rows bigint not null default 0,
    dropped_rows bigint not null default 0,
    error_message varchar(2000),
    attempts integer not null default 0,
    created_time timestamp not null default current_timestamp,
    started_time timestamp,
    ended_time timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_ana_migration_shard_seq unique (command_id, shard_seq)
);

alter table ana_migration_shard drop constraint if exists ck_ana_migration_shard_status;
alter table ana_migration_shard add constraint ck_ana_migration_shard_status
check (status in ('PENDING','RUNNING','COMPLETED','FAILED','SKIPPED'));

comment on table ana_migration_command is '报文日志迁移指令表';
comment on column ana_migration_command.source_label is '源数据源显示名，固定bxds';
comment on column ana_migration_command.target_schema is '目标schema，来自主数据源配置';
comment on table ana_migration_shard is '报文日志迁移分片表';

create index if not exists idx_ana_migration_command_status
on ana_migration_command(status, created_time desc);

create index if not exists idx_ana_migration_shard_command_status
on ana_migration_shard(command_id, status, shard_seq);
```

- [ ] **Step 4: Run test and verify GREEN**

Run:

```bash
mvn -Dtest=DatabaseScriptLayoutTest#ddlContainsMigrationStateTables test
```

Expected: PASS.

---

### Task 2: Dual Datasource Configuration

**Files:**
- Create: `src/main/java/com/spdb/migration/MigrationDataSourceConfig.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/spdb/migration/MigrationDataSourceConfigTest.java`

- [ ] **Step 1: Write failing config test**

Create `MigrationDataSourceConfigTest.java`:

```java
package com.spdb.migration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationDataSourceConfigTest {

    @Test
    void targetSchemaDefaultsToTssWhenBlank() {
        MigrationDataSourceConfig config = new MigrationDataSourceConfig();

        assertThat(config.targetSchema(null)).isEqualTo("tss");
        assertThat(config.targetSchema("")).isEqualTo("tss");
        assertThat(config.targetSchema(" main_schema ")).isEqualTo("main_schema");
    }

    @Test
    void sourceLabelIsBxds() {
        MigrationDataSourceConfig config = new MigrationDataSourceConfig();

        assertThat(config.sourceLabel()).isEqualTo("bxds");
    }
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
mvn -Dtest=MigrationDataSourceConfigTest test
```

Expected: FAIL because `MigrationDataSourceConfig` does not exist.

- [ ] **Step 3: Implement config class**

Create `MigrationDataSourceConfig.java`:

```java
package com.spdb.migration;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

@Configuration
public class MigrationDataSourceConfig {
    static final String SOURCE_LABEL = "bxds";

    @Bean
    @ConfigurationProperties("rose.datasource.bxds")
    public DataSourceProperties bxdsDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("rose.datasource.bxds.hikari")
    public HikariDataSource bxdsDataSource(@Qualifier("bxdsDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    public NamedParameterJdbcTemplate bxdsJdbcTemplate(@Qualifier("bxdsDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    public MigrationRuntimeProperties migrationRuntimeProperties(
            @Value("${spring.jpa.properties.hibernate.default_schema:tss}") String targetSchema) {
        return new MigrationRuntimeProperties(sourceLabel(), targetSchema(targetSchema));
    }

    String sourceLabel() {
        return SOURCE_LABEL;
    }

    String targetSchema(String targetSchema) {
        return StringUtils.hasText(targetSchema) ? targetSchema.trim() : "tss";
    }
}
```

Create `MigrationRuntimeProperties.java`:

```java
package com.spdb.migration;

public record MigrationRuntimeProperties(
        String sourceLabel,
        String targetSchema
) {}
```

- [ ] **Step 4: Add properties documentation**

Append to `src/main/resources/application.properties`:

```properties
rose.datasource.bxds.url=${BXDS_DATASOURCE_URL:jdbc:postgresql://localhost:15432/postgres}
rose.datasource.bxds.username=${BXDS_DATASOURCE_USERNAME:tss}
rose.datasource.bxds.password=${BXDS_DATASOURCE_PASSWORD:Tss@123456}
rose.datasource.bxds.driver-class-name=${BXDS_DATASOURCE_DRIVER:org.postgresql.Driver}
```

- [ ] **Step 5: Run test and verify GREEN**

Run:

```bash
mvn -Dtest=MigrationDataSourceConfigTest test
```

Expected: PASS.

---

### Task 3: Command Service State Model

**Files:**
- Modify: `src/main/java/com/spdb/migration/MigrationCommandRow.java`
- Modify: `src/main/java/com/spdb/migration/MigrationProgressRow.java`
- Create: `src/main/java/com/spdb/migration/MigrationCommandService.java`
- Test: `src/test/java/com/spdb/migration/MigrationCommandServiceTest.java`

- [ ] **Step 1: Write failing service test**

Create `MigrationCommandServiceTest.java` with H2 tables matching the DDL:

```java
package com.spdb.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MigrationCommandServiceTest {
    private NamedParameterJdbcTemplate jdbc;
    private JdbcTemplate plainJdbc;
    private MigrationCommandService service;
    private MigrationTaskLauncher launcher;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:migration_command;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        plainJdbc = new JdbcTemplate(dataSource);
        plainJdbc.execute("drop table if exists ana_migration_shard");
        plainJdbc.execute("drop table if exists ana_migration_command");
        plainJdbc.execute("""
                create table ana_migration_command (
                    command_id bigint generated by default as identity primary key,
                    source_label varchar(64) not null,
                    target_schema varchar(64) not null,
                    time_from bigint not null,
                    time_to bigint not null,
                    window_seconds bigint not null,
                    parallelism integer not null,
                    status varchar(32) not null,
                    total_shard_count bigint not null default 0,
                    completed_shard_count bigint not null default 0,
                    failed_shard_count bigint not null default 0,
                    migrated_rows bigint not null default 0,
                    skipped_rows bigint not null default 0,
                    dropped_rows bigint not null default 0,
                    error_message varchar(2000),
                    remark varchar(1000),
                    created_by varchar(100),
                    created_time timestamp default current_timestamp,
                    started_time timestamp,
                    ended_time timestamp,
                    updated_at timestamp default current_timestamp
                )
                """);
        plainJdbc.execute("""
                create table ana_migration_shard (
                    shard_id bigint generated by default as identity primary key,
                    command_id bigint not null,
                    shard_seq integer not null,
                    time_from bigint not null,
                    time_to bigint not null,
                    status varchar(32) not null,
                    migrated_rows bigint not null default 0,
                    skipped_rows bigint not null default 0,
                    dropped_rows bigint not null default 0,
                    error_message varchar(2000),
                    attempts integer not null default 0,
                    created_time timestamp default current_timestamp,
                    started_time timestamp,
                    ended_time timestamp,
                    updated_at timestamp default current_timestamp
                )
                """);
        launcher = mock(MigrationTaskLauncher.class);
        ObjectProvider<MigrationTaskLauncher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(launcher);
        service = new MigrationCommandService(
                jdbc,
                provider,
                new MigrationRuntimeProperties("bxds", "tss")
        );
    }

    @Test
    void createCommandSplitsHalfOpenWindowsAndLaunches() {
        long commandId = service.createCommand(new MigrationCommandForm(100L, 250L, 60L, 2, "demo"));

        assertThat(commandId).isPositive();
        assertThat(plainJdbc.queryForObject("select count(*) from ana_migration_shard where command_id = " + commandId, Long.class))
                .isEqualTo(3L);
        assertThat(plainJdbc.queryForList("select time_from from ana_migration_shard where command_id = " + commandId + " order by shard_seq", Long.class))
                .containsExactly(100L, 160L, 220L);
        assertThat(plainJdbc.queryForList("select time_to from ana_migration_shard where command_id = " + commandId + " order by shard_seq", Long.class))
                .containsExactly(160L, 220L, 250L);
        verify(launcher).launch(commandId);
    }

    @Test
    void progressReturnsCommandAndShardRows() {
        long commandId = service.createCommand(new MigrationCommandForm(100L, 220L, 60L, 2, "demo"));

        MigrationProgressRow progress = service.progress(commandId);

        assertThat(progress.commandId()).isEqualTo(commandId);
        assertThat(progress.sourceLabel()).isEqualTo("bxds");
        assertThat(progress.targetSchema()).isEqualTo("tss");
        assertThat(progress.totalShardCount()).isEqualTo(2);
        assertThat(progress.shards()).hasSize(2);
    }

    @Test
    void cancelAndResumeUpdateCommandStatus() {
        long commandId = service.createCommand(new MigrationCommandForm(100L, 220L, 60L, 2, "demo"));

        service.requestCancel(commandId);
        assertThat(service.progress(commandId).status()).isEqualTo("CANCEL_REQUESTED");

        service.resume(commandId);
        assertThat(service.progress(commandId).status()).isEqualTo("CREATED");
    }
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
mvn -Dtest=MigrationCommandServiceTest test
```

Expected: FAIL because service and launcher do not exist or row signatures lack source/target fields.

- [ ] **Step 3: Update row records**

Add `sourceLabel` and `targetSchema` after `commandId` in both command/progress records and update all constructors in tests/mock data. Keep `progressText()` and `completionPercent()` unchanged.

- [ ] **Step 4: Implement launcher interface**

Create `MigrationTaskLauncher.java`:

```java
package com.spdb.migration;

public interface MigrationTaskLauncher {
    void launch(long commandId);
}
```

- [ ] **Step 5: Implement service**

Create `MigrationCommandService.java` with:

```java
package com.spdb.migration;

import com.spdb.web.PageRequestParams;
import com.spdb.web.PagedResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class MigrationCommandService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectProvider<MigrationTaskLauncher> launcherProvider;
    private final MigrationRuntimeProperties runtimeProperties;

    public MigrationCommandService(NamedParameterJdbcTemplate jdbc,
                                   ObjectProvider<MigrationTaskLauncher> launcherProvider,
                                   MigrationRuntimeProperties runtimeProperties) {
        this.jdbc = jdbc;
        this.launcherProvider = launcherProvider;
        this.runtimeProperties = runtimeProperties;
    }

    public long createCommand(MigrationCommandForm form) {
        validate(form);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                insert into ana_migration_command (
                    source_label, target_schema, time_from, time_to, window_seconds, parallelism,
                    status, total_shard_count, remark, created_by
                ) values (
                    :sourceLabel, :targetSchema, :timeFrom, :timeTo, :windowSeconds, :parallelism,
                    'CREATED', :totalShardCount, :remark, '系统'
                )
                """, params(form).addValue("totalShardCount", shardCount(form)), keyHolder);
        long commandId = generatedLongKey(keyHolder, "command_id");
        insertShards(commandId, form);
        MigrationTaskLauncher launcher = launcherProvider.getIfAvailable();
        if (launcher != null) {
            launcher.launch(commandId);
        }
        return commandId;
    }

    public PagedResult<MigrationCommandRow> search(PageRequestParams page) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", page.size())
                .addValue("offset", page.offset());
        List<MigrationCommandRow> rows = jdbc.query("""
                select *
                from ana_migration_command
                order by created_time desc, command_id desc
                limit :limit offset :offset
                """, params, (rs, i) -> mapCommand(rs));
        Long total = jdbc.queryForObject("select count(*) from ana_migration_command", new MapSqlParameterSource(), Long.class);
        return PagedResult.of(rows, total == null ? 0 : total, page);
    }

    public MigrationProgressRow progress(long commandId) {
        List<MigrationCommandRow> commands = jdbc.query("""
                select *
                from ana_migration_command
                where command_id = :commandId
                """, new MapSqlParameterSource("commandId", commandId), (rs, i) -> mapCommand(rs));
        if (commands.isEmpty()) {
            return null;
        }
        List<MigrationShardRow> shards = jdbc.query("""
                select *
                from ana_migration_shard
                where command_id = :commandId
                order by shard_seq
                """, new MapSqlParameterSource("commandId", commandId), (rs, i) -> new MigrationShardRow(
                rs.getInt("shard_seq"),
                rs.getLong("time_from"),
                rs.getLong("time_to"),
                rs.getString("status"),
                rs.getLong("migrated_rows"),
                rs.getLong("skipped_rows"),
                rs.getLong("dropped_rows"),
                rs.getInt("attempts"),
                durationSeconds(rs.getTimestamp("started_time"), rs.getTimestamp("ended_time")),
                rs.getString("error_message")
        ));
        MigrationCommandRow command = commands.get(0);
        return new MigrationProgressRow(
                command.commandId(), command.sourceLabel(), command.targetSchema(), command.status(),
                command.timeFrom(), command.timeTo(), command.windowSeconds(), command.parallelism(),
                command.totalShardCount(), command.completedShardCount(), command.failedShardCount(),
                command.migratedRows(), command.skippedRows(), command.droppedRows(),
                command.startedTime() == null ? null : durationSeconds(Timestamp.valueOf(command.startedTime()),
                        command.endedTime() == null ? null : Timestamp.valueOf(command.endedTime())),
                command.startedTime(), command.endedTime(), command.errorMessage(), shards
        );
    }

    public void requestCancel(long commandId) {
        jdbc.update("""
                update ana_migration_command
                   set status = 'CANCEL_REQUESTED', updated_at = current_timestamp
                 where command_id = :commandId and status in ('CREATED','RUNNING')
                """, new MapSqlParameterSource("commandId", commandId));
    }

    public void resume(long commandId) {
        jdbc.update("""
                update ana_migration_command
                   set status = 'CREATED', error_message = null, ended_time = null, updated_at = current_timestamp
                 where command_id = :commandId and status in ('FAILED','CANCELLED','CANCEL_REQUESTED')
                """, new MapSqlParameterSource("commandId", commandId));
        MigrationTaskLauncher launcher = launcherProvider.getIfAvailable();
        if (launcher != null) {
            launcher.launch(commandId);
        }
    }

    /* Add package-private helpers used by batch runner in later tasks:
       markRunning, markCompleted, markFailed, markCancelled, pendingShardIds, tryStartShard,
       markShardCompleted, markShardFailed, isCancelRequested, refreshCommandCounters. */
}
```

Fill in the private methods exactly as exercised by tests: validation, `params`, `shardCount`, `insertShards`, `generatedLongKey`, `mapCommand`, `durationText`, `durationSeconds`, `abbreviate`.

- [ ] **Step 6: Run test and verify GREEN**

Run:

```bash
mvn -Dtest=MigrationCommandServiceTest test
```

Expected: PASS.

---

### Task 4: Shard Runner Data Migration

**Files:**
- Create: `src/main/java/com/spdb/migration/MigrationSourceRow.java`
- Create: `src/main/java/com/spdb/migration/MigrationShardResult.java`
- Create: `src/main/java/com/spdb/migration/MigrationShardRunner.java`
- Test: `src/test/java/com/spdb/migration/MigrationShardRunnerTest.java`

- [ ] **Step 1: Write failing shard runner test**

Create H2 source and target datasources. Use unqualified tables on both because each datasource is separate.

```java
@Test
void migratesOnlyPairedRowsAndCountsDroppedAndSkipped() {
    seedSourcePairedRow("02001", "10.0.0.1", 100L);
    seedSourceResponseOnly("02002", "10.0.0.2", 110L);
    seedSourcePairedRow("02003", "10.0.0.3", 120L);
    seedTargetPair("02003", "10.0.0.3");

    MigrationShardResult result = runner.run(1L, 100L, 130L, 100);

    assertThat(result.migratedRows()).isEqualTo(1);
    assertThat(result.droppedRows()).isEqualTo(1);
    assertThat(result.skippedRows()).isEqualTo(1);
    assertThat(targetCount("msg_flow_log_request")).isEqualTo(2);
    assertThat(targetCount("msg_flow_log_response")).isEqualTo(2);
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
mvn -Dtest=MigrationShardRunnerTest test
```

Expected: FAIL because runner does not exist.

- [ ] **Step 3: Implement row/result records**

Create `MigrationSourceRow.java`:

```java
package com.spdb.migration;

public record MigrationSourceRow(
        String sourceIp,
        String transId,
        String requestTxnCode,
        Long txnTime,
        String requestMessageType,
        byte[] requestMessage,
        String globalSeqNo,
        String tranTellerNo,
        String responseTxnCode,
        Long responseTime,
        String responseMessageType,
        byte[] responseMessage,
        String returnCode,
        String returnMsg
) {}
```

Create `MigrationShardResult.java`:

```java
package com.spdb.migration;

public record MigrationShardResult(
        long migratedRows,
        long skippedRows,
        long droppedRows
) {}
```

- [ ] **Step 4: Implement shard runner**

Create `MigrationShardRunner.java`:

```java
package com.spdb.migration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

@Component
public class MigrationShardRunner {
    private static final int DEFAULT_BATCH_SIZE = 1000;

    private final NamedParameterJdbcTemplate sourceJdbc;
    private final NamedParameterJdbcTemplate targetJdbc;
    private final TransactionTemplate targetTransaction;

    public MigrationShardRunner(@Qualifier("bxdsJdbcTemplate") NamedParameterJdbcTemplate sourceJdbc,
                                NamedParameterJdbcTemplate targetJdbc,
                                PlatformTransactionManager transactionManager) {
        this.sourceJdbc = sourceJdbc;
        this.targetJdbc = targetJdbc;
        this.targetTransaction = new TransactionTemplate(transactionManager);
    }

    MigrationShardRunner(NamedParameterJdbcTemplate sourceJdbc,
                         NamedParameterJdbcTemplate targetJdbc,
                         PlatformTransactionManager transactionManager,
                         int batchSize) {
        this.sourceJdbc = sourceJdbc;
        this.targetJdbc = targetJdbc;
        this.targetTransaction = new TransactionTemplate(transactionManager);
    }

    public MigrationShardResult run(long shardId, long timeFrom, long timeTo, int fetchSize) {
        long responseCount = countResponses(timeFrom, timeTo);
        List<MigrationSourceRow> rows = pairedRows(timeFrom, timeTo);
        long droppedRows = responseCount - rows.size();
        Counter counter = new Counter();
        for (int start = 0; start < rows.size(); start += DEFAULT_BATCH_SIZE) {
            List<MigrationSourceRow> batch = rows.subList(start, Math.min(start + DEFAULT_BATCH_SIZE, rows.size()));
            targetTransaction.executeWithoutResult(status -> writeBatch(batch, counter));
        }
        return new MigrationShardResult(counter.migratedRows, counter.skippedRows, droppedRows);
    }

    private long countResponses(long timeFrom, long timeTo) {
        Long count = sourceJdbc.queryForObject("""
                select count(*)
                from msg_flow_log_response
                where response_time >= :timeFrom and response_time < :timeTo
                """, new MapSqlParameterSource().addValue("timeFrom", timeFrom).addValue("timeTo", timeTo), Long.class);
        return count == null ? 0 : count;
    }

    private List<MigrationSourceRow> pairedRows(long timeFrom, long timeTo) {
        return sourceJdbc.query("""
                select r.source_ip, r.trans_id,
                       r.txn_code as request_txn_code, r.txn_time, r.message_type as request_message_type,
                       r.request_message, r.global_seq_no, r.tran_teller_no,
                       s.txn_code as response_txn_code, s.response_time, s.message_type as response_message_type,
                       s.response_message, s.return_code, s.return_msg
                  from msg_flow_log_response s
                  join msg_flow_log_request r
                    on r.trans_id = s.trans_id
                   and r.source_ip = s.source_ip
                 where s.response_time >= :timeFrom and s.response_time < :timeTo
                 order by s.response_time, s.trans_id, s.source_ip
                """, new MapSqlParameterSource().addValue("timeFrom", timeFrom).addValue("timeTo", timeTo), (rs, i) -> new MigrationSourceRow(
                rs.getString("source_ip"), rs.getString("trans_id"),
                rs.getString("request_txn_code"), (Long) rs.getObject("txn_time"), rs.getString("request_message_type"),
                rs.getBytes("request_message"), rs.getString("global_seq_no"), rs.getString("tran_teller_no"),
                rs.getString("response_txn_code"), (Long) rs.getObject("response_time"), rs.getString("response_message_type"),
                rs.getBytes("response_message"), rs.getString("return_code"), rs.getString("return_msg")
        ));
    }

    private void writeBatch(List<MigrationSourceRow> rows, Counter counter) {
        for (MigrationSourceRow row : rows) {
            if (targetExists(row)) {
                counter.skippedRows++;
                continue;
            }
            insertResponse(row);
            insertRequest(row);
            counter.migratedRows++;
        }
    }

    private boolean targetExists(MigrationSourceRow row) {
        Long count = targetJdbc.queryForObject("""
                select count(*)
                  from msg_flow_log_response s
                  join msg_flow_log_request r on r.trans_id = s.trans_id and r.source_ip = s.source_ip
                 where s.trans_id = :transId and s.source_ip = :sourceIp
                """, keys(row), Long.class);
        return count != null && count > 0;
    }

    private void insertResponse(MigrationSourceRow row) {
        targetJdbc.update("""
                insert into msg_flow_log_response (
                    source_ip, trans_id, txn_code, response_time, message_type,
                    response_message, return_code, return_msg
                ) values (
                    :sourceIp, :transId, :txnCode, :responseTime, :messageType,
                    :responseMessage, :returnCode, :returnMsg
                )
                """, keys(row)
                .addValue("txnCode", row.responseTxnCode())
                .addValue("responseTime", row.responseTime())
                .addValue("messageType", row.responseMessageType())
                .addValue("responseMessage", row.responseMessage())
                .addValue("returnCode", row.returnCode())
                .addValue("returnMsg", row.returnMsg()));
    }

    private void insertRequest(MigrationSourceRow row) {
        targetJdbc.update("""
                insert into msg_flow_log_request (
                    source_ip, trans_id, txn_code, txn_time, message_type,
                    request_message, global_seq_no, tran_teller_no
                ) values (
                    :sourceIp, :transId, :txnCode, :txnTime, :messageType,
                    :requestMessage, :globalSeqNo, :tranTellerNo
                )
                """, keys(row)
                .addValue("txnCode", row.requestTxnCode())
                .addValue("txnTime", row.txnTime())
                .addValue("messageType", row.requestMessageType())
                .addValue("requestMessage", row.requestMessage())
                .addValue("globalSeqNo", row.globalSeqNo())
                .addValue("tranTellerNo", row.tranTellerNo()));
    }

    private MapSqlParameterSource keys(MigrationSourceRow row) {
        return new MapSqlParameterSource()
                .addValue("sourceIp", row.sourceIp())
                .addValue("transId", row.transId());
    }

    private static class Counter {
        private long migratedRows;
        private long skippedRows;
    }
}
```

- [ ] **Step 5: Run test and verify GREEN**

Run:

```bash
mvn -Dtest=MigrationShardRunnerTest test
```

Expected: PASS.

---

### Task 5: Batch Runner And Async Execution

**Files:**
- Create: `src/main/java/com/spdb/migration/MigrationExecutionConfig.java`
- Create: `src/main/java/com/spdb/migration/MigrationAsyncExecutor.java`
- Create: `src/main/java/com/spdb/migration/MigrationBatchRunner.java`
- Modify: `src/main/java/com/spdb/migration/MigrationCommandService.java`
- Test: `src/test/java/com/spdb/migration/MigrationBatchRunnerTest.java`

- [ ] **Step 1: Write failing batch runner tests**

Create tests using a fake `MigrationShardRunner` that returns fixed results:

```java
@Test
void batchRunnerCompletesPendingShardsAndAggregatesCounters() {
    long commandId = service.createCommand(new MigrationCommandForm(100L, 220L, 60L, 2, "demo"));

    batchRunner.run(commandId);

    MigrationProgressRow progress = service.progress(commandId);
    assertThat(progress.status()).isEqualTo("COMPLETED");
    assertThat(progress.completedShardCount()).isEqualTo(2);
    assertThat(progress.migratedRows()).isEqualTo(20);
}

@Test
void batchRunnerStopsWhenCancelRequested() {
    long commandId = service.createCommand(new MigrationCommandForm(100L, 220L, 60L, 2, "demo"));
    service.requestCancel(commandId);

    batchRunner.run(commandId);

    assertThat(service.progress(commandId).status()).isEqualTo("CANCELLED");
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
mvn -Dtest=MigrationBatchRunnerTest test
```

Expected: FAIL because batch runner does not exist and service lacks package helpers.

- [ ] **Step 3: Add command service helpers**

Add these package-private methods to `MigrationCommandService`:

```java
MigrationCommandRow command(long commandId)
List<Long> runnableShardIds(long commandId)
boolean tryStartShard(long shardId)
void markRunning(long commandId)
void markShardCompleted(long shardId, MigrationShardResult result)
void markShardFailed(long shardId, String errorMessage)
void refreshCommandCounters(long commandId)
void markCompleted(long commandId)
void markFailed(long commandId, String errorMessage)
void markCancelled(long commandId)
boolean isCancelRequested(long commandId)
MigrationShardRow shard(long shardId)
```

Use SQL updates with status guards:

```sql
update ana_migration_shard
   set status='RUNNING', attempts=attempts+1, started_time=current_timestamp, error_message=null
 where shard_id=:shardId and status in ('PENDING','FAILED')
```

`refreshCommandCounters` should recompute from shards:

```sql
update ana_migration_command c
   set completed_shard_count = (select count(*) from ana_migration_shard where command_id=c.command_id and status in ('COMPLETED','SKIPPED')),
       failed_shard_count = (select count(*) from ana_migration_shard where command_id=c.command_id and status='FAILED'),
       migrated_rows = (select coalesce(sum(migrated_rows),0) from ana_migration_shard where command_id=c.command_id),
       skipped_rows = (select coalesce(sum(skipped_rows),0) from ana_migration_shard where command_id=c.command_id),
       dropped_rows = (select coalesce(sum(dropped_rows),0) from ana_migration_shard where command_id=c.command_id),
       updated_at = current_timestamp
 where c.command_id=:commandId
```

- [ ] **Step 4: Implement execution config**

Create `MigrationExecutionConfig.java`:

```java
package com.spdb.migration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class MigrationExecutionConfig {

    @Bean
    public ThreadPoolTaskExecutor migrationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("migration-exec-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(16);
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 5: Implement batch runner**

Create `MigrationBatchRunner.java`:

```java
package com.spdb.migration;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Component
public class MigrationBatchRunner {
    private final MigrationCommandService commandService;
    private final MigrationShardRunner shardRunner;
    private final Executor migrationTaskExecutor;

    public MigrationBatchRunner(MigrationCommandService commandService,
                                MigrationShardRunner shardRunner,
                                Executor migrationTaskExecutor) {
        this.commandService = commandService;
        this.shardRunner = shardRunner;
        this.migrationTaskExecutor = migrationTaskExecutor;
    }

    public void run(long commandId) {
        MigrationCommandRow command = commandService.command(commandId);
        if (command == null) {
            throw new IllegalArgumentException("迁移指令不存在：" + commandId);
        }
        if ("CANCEL_REQUESTED".equals(command.status())) {
            commandService.markCancelled(commandId);
            return;
        }
        commandService.markRunning(commandId);
        List<Long> shardIds = commandService.runnableShardIds(commandId);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Long shardId : shardIds) {
            if (commandService.isCancelRequested(commandId)) {
                break;
            }
            futures.add(CompletableFuture.runAsync(() -> runShard(shardId), migrationTaskExecutor));
            if (futures.size() >= Math.max(1, command.parallelism())) {
                joinAndClear(futures);
            }
        }
        joinAndClear(futures);
        commandService.refreshCommandCounters(commandId);
        MigrationProgressRow progress = commandService.progress(commandId);
        if (commandService.isCancelRequested(commandId)) {
            commandService.markCancelled(commandId);
        } else if (progress.failedShardCount() > 0) {
            commandService.markFailed(commandId, "存在失败分片：" + progress.failedShardCount());
        } else {
            commandService.markCompleted(commandId);
        }
    }

    private void runShard(long shardId) {
        if (!commandService.tryStartShard(shardId)) {
            return;
        }
        MigrationShardRow shard = commandService.shard(shardId);
        try {
            MigrationShardResult result = shardRunner.run(shardId, shard.timeFrom(), shard.timeTo(), 1000);
            commandService.markShardCompleted(shardId, result);
        } catch (Exception ex) {
            commandService.markShardFailed(shardId, ex.getMessage());
        }
    }

    private void joinAndClear(List<CompletableFuture<Void>> futures) {
        for (CompletableFuture<Void> future : futures) {
            future.join();
        }
        futures.clear();
    }
}
```

- [ ] **Step 6: Implement async executor**

Create `MigrationAsyncExecutor.java`:

```java
package com.spdb.migration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class MigrationAsyncExecutor implements MigrationTaskLauncher {
    private final ObjectProvider<MigrationBatchRunner> batchRunnerProvider;
    private final ThreadPoolTaskExecutor migrationTaskExecutor;

    public MigrationAsyncExecutor(ObjectProvider<MigrationBatchRunner> batchRunnerProvider,
                                  ThreadPoolTaskExecutor migrationTaskExecutor) {
        this.batchRunnerProvider = batchRunnerProvider;
        this.migrationTaskExecutor = migrationTaskExecutor;
    }

    @Override
    public void launch(long commandId) {
        migrationTaskExecutor.execute(() -> batchRunnerProvider.getObject().run(commandId));
    }
}
```

- [ ] **Step 7: Run tests and verify GREEN**

Run:

```bash
mvn -Dtest=MigrationBatchRunnerTest,MigrationCommandServiceTest test
```

Expected: PASS.

---

### Task 6: Replace Mock Controller With Service

**Files:**
- Modify: `src/main/java/com/spdb/web/MigrationController.java`
- Modify: `src/main/resources/templates/migration/commands.html`
- Test: `src/test/java/com/spdb/web/MigrationControllerTest.java`
- Test: `src/test/java/com/spdb/web/MigrationCommandsTemplateTest.java`

- [ ] **Step 1: Write failing controller test**

Update `MigrationControllerTest` to use a mocked `MigrationCommandService` and assert create redirects to the returned id:

```java
@Test
void createCommandRedirectsToCreatedCommand() {
    MigrationCommandService service = mock(MigrationCommandService.class);
    when(service.createCommand(MigrationCommandForm.empty())).thenReturn(42L);
    MigrationController controller = new MigrationController(service);

    String view = controller.createCommand(MigrationCommandForm.empty());

    assertThat(view).isEqualTo("redirect:/migration/commands/42");
}
```

- [ ] **Step 2: Run test and verify RED**

Run:

```bash
mvn -Dtest=MigrationControllerTest test
```

Expected: FAIL because controller still has no service constructor.

- [ ] **Step 3: Implement controller changes**

Change `MigrationController` to:

```java
@Controller
public class MigrationController {
    private final MigrationCommandService migrationCommandService;

    public MigrationController(MigrationCommandService migrationCommandService) {
        this.migrationCommandService = migrationCommandService;
    }

    @GetMapping("/migration/commands")
    public String commandsPage(@RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size,
                               Model model) {
        PageRequestParams params = PageRequestParams.of(page, size);
        model.addAttribute("active", "migration");
        model.addAttribute("result", migrationCommandService.search(params));
        model.addAttribute("form", MigrationCommandForm.empty());
        return "migration/commands";
    }

    @PostMapping("/migration/commands")
    public String createCommand(@ModelAttribute MigrationCommandForm form) {
        long commandId = migrationCommandService.createCommand(form);
        return "redirect:/migration/commands/" + commandId;
    }

    @GetMapping("/migration/commands/{id}")
    public String progressPage(@PathVariable long id, Model model) {
        model.addAttribute("active", "migration");
        model.addAttribute("progress", migrationCommandService.progress(id));
        return "migration/progress";
    }

    @GetMapping("/migration/commands/{id}/progress")
    @ResponseBody
    public MigrationProgressRow progressJson(@PathVariable long id) {
        return migrationCommandService.progress(id);
    }

    @PostMapping("/migration/commands/{id}/cancel")
    public String cancel(@PathVariable long id) {
        migrationCommandService.requestCancel(id);
        return "redirect:/migration/commands/" + id;
    }

    @PostMapping("/migration/commands/{id}/resume")
    public String resume(@PathVariable long id) {
        migrationCommandService.resume(id);
        return "redirect:/migration/commands/" + id;
    }
}
```

- [ ] **Step 4: Update commands template**

Change the list loop from `${commands}` to `${result.rows()}` and add pager:

```html
<tr th:each="row : ${result.rows()}">
```

Below the table:

```html
<div th:replace="~{fragments/layout :: pager(${result})}"></div>
```

Change hard-coded source/target inputs:

```html
<input th:value="${result.rows().isEmpty()} ? 'bxds' : ${result.rows().get(0).sourceLabel()}" readonly class="muted-input">
<input th:value="${result.rows().isEmpty()} ? 'tss' : ${result.rows().get(0).targetSchema()}" readonly class="muted-input">
```

- [ ] **Step 5: Run tests and verify GREEN**

Run:

```bash
mvn -Dtest=MigrationControllerTest,MigrationCommandsTemplateTest test
```

Expected: PASS.

---

### Task 7: Final Verification And Runtime Check

**Files:**
- No new files.

- [ ] **Step 1: Run all tests**

Run:

```bash
mvn test
```

Expected: PASS with 0 failures/errors.

- [ ] **Step 2: Stop existing app if running**

If a previous `mvn spring-boot:run` session exists, send Ctrl-C and wait for shutdown.

- [ ] **Step 3: Start app**

Run:

```bash
mvn spring-boot:run
```

Expected: Tomcat starts on port 8080 and the primary datasource still connects.

- [ ] **Step 4: Runtime smoke check**

Run:

```bash
curl -s -o /tmp/rose-migration-commands.html -w '%{http_code}' http://localhost:8080/migration/commands
```

Expected: `200`.

- [ ] **Step 5: Database readiness note**

If `/migration/commands` fails because `ana_migration_command` does not exist, apply `db/ddl.sql` to the primary database before using the migration page. Do not alter source bxds tables from the app.

---

## Self-Review

- Spec coverage: The plan covers dual datasource configuration, dynamic source/target display, migration state DDL, command creation, shard splitting, paired migration, dropped/skipped counters, cancel/resume, async execution, controller replacement, and runtime verification.
- Placeholder scan: No `TBD`, `TODO`, or intentionally vague implementation steps remain.
- Type consistency: The plan consistently uses `MigrationCommandService`, `MigrationTaskLauncher`, `MigrationBatchRunner`, `MigrationShardRunner`, `MigrationRuntimeProperties`, `MigrationShardResult`, and `MigrationSourceRow`.

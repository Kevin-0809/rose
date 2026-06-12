# Sampling Batch Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild sampling execution, storage, pages, and exports around semantic field grouping, stream-based source reads, and three-level sample results.

**Architecture:** Keep Spring MVC and `NamedParameterJdbcTemplate`, but replace the SQL-heavy sampler with small Java components: config snapshot, source readers, semantic grouping, sample picking, and batch writers. Store results as problem groups, sample transactions, and sample field details so SOP/SOAP/BizJSON field names can collapse into the same business issue while preserving raw fields for analysis.

**Tech Stack:** Java 17, Spring Boot 3.3, JDBC templates, Thymeleaf, Apache POI, PostgreSQL/openGauss SQL.

---

## File Map

- Modify `db/ddl.sql`: add semantic grouping columns, add `ana_sample_detail_field`, add summary counters and indexes.
- Modify `db/seed.sql`: update sample data and field mappings so A825 SOP/BizJSON fields map to common semantic fields.
- Modify `src/main/java/com/spdb/sample/SampleGroupRow.java`: expose new group fields.
- Modify `src/main/java/com/spdb/sample/SampleDetailRow.java`: convert to sample-transaction-level row.
- Create `src/main/java/com/spdb/sample/SampleDetailFieldRow.java`: field-level row for sample details.
- Modify `src/main/java/com/spdb/sample/SampleSearchCriteria.java`: add `origCdate`, `configStatus`, `mappingStatus`, `messageType`, and `semanticFieldName`.
- Modify `src/main/java/com/spdb/sample/SampleQueryService.java`: query the new group/detail/detail-field model.
- Modify `src/main/java/com/spdb/sample/SampleExcelExportService.java`: export groups, sample transactions, and field details.
- Modify `src/main/java/com/spdb/web/SampleController.java`: wire new filters and field-detail export endpoint.
- Modify `src/main/resources/templates/samples/groups.html`: show semantic group columns.
- Modify `src/main/resources/templates/samples/details.html`: show sample transactions and field detail links/table.
- Modify `src/main/java/com/spdb/sample/SummaryStats.java`, `SamplingSummaryHistoryRow.java`, and templates using them: expose new counters.
- Create package files under `src/main/java/com/spdb/sampling/engine/`: small domain records and components for semantic sampling.
- Replace `src/main/java/com/spdb/sampling/SamplingBatchRunner.java`: orchestrate new engine components.
- Modify `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`: verify schema changes.
- Add focused tests under `src/test/java/com/spdb/sampling/engine/`.
- Update existing `SampleQueryServiceTest`, `SampleExcelExportServiceTest`, `SamplingExecutionStructureTest`, and web template tests.

## Task 1: Schema For Three-Level Sampling Results

**Files:**
- Modify: `db/ddl.sql`
- Modify: `db/seed.sql`
- Test: `src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java`

- [ ] **Step 1: Write failing schema tests**

Add tests to `DatabaseScriptLayoutTest`:

```java
@Test
void sampleGroupStoresSemanticGroupingFields() throws IOException {
    String ddl = Files.readString(Path.of("db/ddl.sql"));

    assertThat(ddl).contains("orig_cdate varchar(8)");
    assertThat(ddl).contains("config_status varchar(32)");
    assertThat(ddl).contains("mapping_status varchar(32)");
    assertThat(ddl).contains("semantic_signature varchar(2000)");
    assertThat(ddl).contains("semantic_signature_hash varchar(32)");
    assertThat(ddl).contains("semantic_field_names varchar(1000)");
    assertThat(ddl).contains("message_types varchar(200)");
    assertThat(ddl).contains("affected_tran_count bigint");
    assertThat(ddl).contains("affected_field_count bigint");
}

@Test
void sampleDetailFieldTableExists() throws IOException {
    String ddl = Files.readString(Path.of("db/ddl.sql"));

    assertThat(ddl).contains("create table if not exists ana_sample_detail_field");
    assertThat(ddl).contains("field_detail_id bigserial primary key");
    assertThat(ddl).contains("sample_id bigint not null");
    assertThat(ddl).contains("raw_field_name varchar(200)");
    assertThat(ddl).contains("std_field_name varchar(200)");
    assertThat(ddl).contains("mapping_status varchar(32)");
    assertThat(ddl).contains("idx_ana_sample_detail_field_sample");
}
```

- [ ] **Step 2: Run schema tests and verify failure**

Run: `mvn -Dtest=DatabaseScriptLayoutTest test`

Expected: FAIL because `ana_sample_detail_field` and semantic columns do not exist yet.

- [ ] **Step 3: Update DDL**

In `ana_sample_group`, add:

```sql
orig_cdate varchar(8),
config_status varchar(32) not null default 'CONFIGURED',
mapping_status varchar(32) not null default 'MAPPED',
semantic_signature varchar(2000),
semantic_signature_hash varchar(32),
semantic_field_names varchar(1000),
message_types varchar(200),
affected_tran_count bigint not null default 0,
affected_field_count bigint not null default 0,
```

In `ana_sample_detail`, make it transaction-level by adding:

```sql
orig_cdate varchar(8),
config_status varchar(32) not null default 'CONFIGURED',
field_count integer not null default 0,
orig_error_code varchar(64),
orig_error_desc varchar(500),
dest_error_code varchar(64),
dest_error_desc varchar(500),
```

Add new table:

```sql
create table if not exists ana_sample_detail_field (
    field_detail_id bigserial primary key,
    sample_id bigint not null,
    group_id bigint not null,
    batch_id varchar(64) not null,
    mesg_seq varchar(64) not null,
    message_type varchar(32),
    raw_field_name varchar(200),
    std_field_name varchar(200),
    field_cn_name varchar(200),
    orig_field_value varchar(2000),
    dest_field_value varchar(2000),
    mapping_status varchar(32) not null default 'MAPPED',
    field_index integer,
    created_at timestamp not null default current_timestamp
);
```

Add indexes:

```sql
create index if not exists idx_ana_sample_group_semantic
on ana_sample_group(batch_id, sample_type, tran_code, service_code, semantic_signature_hash);

create index if not exists idx_ana_sample_group_status
on ana_sample_group(batch_id, config_status, mapping_status);

create index if not exists idx_ana_sample_detail_field_sample
on ana_sample_detail_field(sample_id, field_index);

create index if not exists idx_ana_sample_detail_field_lookup
on ana_sample_detail_field(batch_id, group_id, mesg_seq);
```

Extend `ana_sampling_summary`:

```sql
tran_issue_count bigint not null default 0,
return_code_issue_count bigint not null default 0,
field_diff_tran_count bigint not null default 0,
unconfigured_service_count bigint not null default 0,
unmapped_field_count bigint not null default 0,
```

- [ ] **Step 4: Update seed data**

Update `db/seed.sql` so A825 field mappings include:

```sql
('A825', 'S030030014FcyCollCrspBnkLkgQry', 'currency_id', '币种', 'HUOBDH', 'CurrencyId', 'CurrencyId', 'A825 semantic mapping'),
('A825', 'S030030014FcyCollCrspBnkLkgQry', 'link_info', '联动信息', 'FAB251', 'FcyCollCrspBnkLkg', 'FcyCollCrspBnkLkg', 'A825 semantic mapping')
```

- [ ] **Step 5: Run schema tests and verify pass**

Run: `mvn -Dtest=DatabaseScriptLayoutTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add db/ddl.sql db/seed.sql src/test/java/com/spdb/db/DatabaseScriptLayoutTest.java
git commit -m "feat: add semantic sampling result schema"
```

## Task 2: Sampling Engine Domain And Semantic Mapping

**Files:**
- Create: `src/main/java/com/spdb/sampling/engine/MessageType.java`
- Create: `src/main/java/com/spdb/sampling/engine/SourceKey.java`
- Create: `src/main/java/com/spdb/sampling/engine/TranFact.java`
- Create: `src/main/java/com/spdb/sampling/engine/FieldDiff.java`
- Create: `src/main/java/com/spdb/sampling/engine/FieldSemantic.java`
- Create: `src/main/java/com/spdb/sampling/engine/SamplingConfigSnapshot.java`
- Create: `src/main/java/com/spdb/sampling/engine/SemanticSignatureBuilder.java`
- Test: `src/test/java/com/spdb/sampling/engine/SamplingConfigSnapshotTest.java`
- Test: `src/test/java/com/spdb/sampling/engine/SemanticSignatureBuilderTest.java`

- [ ] **Step 1: Write failing semantic mapping tests**

Create `SamplingConfigSnapshotTest` with tests:

```java
@Test
void mapsBizjsonAndSopFieldsToSameSemanticField() {
    SamplingConfigSnapshot snapshot = SamplingConfigSnapshot.from(
            List.of(new SamplingConfigSnapshot.TranConfig("A825", "S030030014FcyCollCrspBnkLkgQry", "外币托收代理行联动查询", "loan", "张伟")),
            List.of(
                    new SamplingConfigSnapshot.FieldConfig("A825", "S030030014FcyCollCrspBnkLkgQry", "currency_id", "币种", "HUOBDH", "CurrencyId", "CurrencyId"),
                    new SamplingConfigSnapshot.FieldConfig("A825", "S030030014FcyCollCrspBnkLkgQry", "link_info", "联动信息", "FAB251", "FcyCollCrspBnkLkg", "FcyCollCrspBnkLkg")
            )
    );

    FieldSemantic bizjson = snapshot.resolveField("A825", "S030030014FcyCollCrspBnkLkgQry", "bizjson", "CurrencyId");
    FieldSemantic sop = snapshot.resolveField("A825", "S030030014FcyCollCrspBnkLkgQry", "sop", "HUOBDH");

    assertThat(bizjson.stdFieldName()).isEqualTo("currency_id");
    assertThat(sop.stdFieldName()).isEqualTo("currency_id");
    assertThat(bizjson.mappingStatus()).isEqualTo("MAPPED");
    assertThat(sop.mappingStatus()).isEqualTo("MAPPED");
}

@Test
void unmappedFieldFallsBackToRawName() {
    SamplingConfigSnapshot snapshot = SamplingConfigSnapshot.from(List.of(), List.of());

    FieldSemantic semantic = snapshot.resolveField("UNKNOWN", "MissingService", "bizjson", "RawField");

    assertThat(semantic.stdFieldName()).isEqualTo("RawField");
    assertThat(semantic.rawFieldName()).isEqualTo("RawField");
    assertThat(semantic.mappingStatus()).isEqualTo("UNMAPPED");
}
```

- [ ] **Step 2: Run semantic mapping tests and verify failure**

Run: `mvn -Dtest=SamplingConfigSnapshotTest test`

Expected: FAIL because classes do not exist.

- [ ] **Step 3: Implement domain records**

Implement records:

```java
public record SourceKey(String mesgSeq, int convIndex, int convCindex) implements Comparable<SourceKey> {
    @Override
    public int compareTo(SourceKey other) {
        int byMesg = mesgSeq.compareTo(other.mesgSeq);
        if (byMesg != 0) return byMesg;
        int byConv = Integer.compare(convIndex, other.convIndex);
        return byConv != 0 ? byConv : Integer.compare(convCindex, other.convCindex);
    }
}
```

```java
public record FieldSemantic(
        String rawFieldName,
        String stdFieldName,
        String fieldCnName,
        String mappingStatus
) {}
```

Create similar compact records for `TranFact` and `FieldDiff` with fields used in the spec.

- [ ] **Step 4: Implement `SamplingConfigSnapshot`**

Build maps for exact message-type lookup and fallback lookup:

```java
public FieldSemantic resolveField(String tranCode, String serviceCode, String messageType, String rawFieldName) {
    String normalizedType = MessageType.normalize(messageType);
    FieldConfig exact = fieldByTypedRawName.get(key(tranCode, serviceCode, normalizedType, rawFieldName));
    if (exact == null) {
        exact = fieldByAnyRawName.get(key(tranCode, serviceCode, "*", rawFieldName));
    }
    if (exact == null) {
        return new FieldSemantic(rawFieldName, rawFieldName, null, "UNMAPPED");
    }
    return new FieldSemantic(rawFieldName, exact.stdFieldName(), exact.fieldCnName(), "MAPPED");
}
```

- [ ] **Step 5: Write failing signature tests**

Create `SemanticSignatureBuilderTest`:

```java
@Test
void buildsSameSignatureForDifferentRawFieldsWithSameSemanticFields() {
    SemanticSignatureBuilder builder = new SemanticSignatureBuilder();

    String bizjson = builder.build(List.of(
            diff("CurrencyId", "currency_id", "111", "222"),
            diff("FcyCollCrspBnkLkg", "link_info", "A1/B1", "A/B")
    )).signature();
    String sop = builder.build(List.of(
            diff("HUOBDH", "currency_id", "111", "222"),
            diff("FAB251", "link_info", "A1/B1", "A/B")
    )).signature();

    assertThat(bizjson).isEqualTo("currency_id:111->222|link_info:A1/B1->A/B");
    assertThat(sop).isEqualTo(bizjson);
}
```

- [ ] **Step 6: Run signature test and verify failure**

Run: `mvn -Dtest=SemanticSignatureBuilderTest test`

Expected: FAIL because builder does not exist.

- [ ] **Step 7: Implement `SemanticSignatureBuilder`**

Sort by `stdFieldName`, join `stdFieldName + ":" + orig + "->" + dest`, and calculate MD5 hash.

- [ ] **Step 8: Run engine unit tests**

Run: `mvn -Dtest='SamplingConfigSnapshotTest,SemanticSignatureBuilderTest' test`

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/spdb/sampling/engine src/test/java/com/spdb/sampling/engine
git commit -m "feat: add semantic sampling engine primitives"
```

## Task 3: Stream-Based Readers

**Files:**
- Create: `src/main/java/com/spdb/sampling/engine/SamplingSourceReader.java`
- Create: `src/main/java/com/spdb/sampling/engine/JdbcSamplingSourceReader.java`
- Test: `src/test/java/com/spdb/sampling/engine/JdbcSamplingSourceReaderTest.java`

- [ ] **Step 1: Write failing reader tests**

Use a fake `NamedParameterJdbcTemplate` or integration-style H2 test to assert SQL does not contain `offset` and `fetchSize` is configured through `JdbcTemplate` callbacks. Add a source test that reads ordered rows for `orig_cdate`.

- [ ] **Step 2: Run reader tests and verify failure**

Run: `mvn -Dtest=JdbcSamplingSourceReaderTest test`

Expected: FAIL because reader does not exist.

- [ ] **Step 3: Implement reader interface**

Define callback methods:

```java
public interface SamplingSourceReader {
    void readTranFacts(String origCdate, Consumer<TranFact> consumer);
    void readReturnCodes(String origCdate, Consumer<ReturnCodeDiff> consumer);
    void readFieldDiffs(String origCdate, Consumer<FieldDiff> consumer);
}
```

- [ ] **Step 4: Implement JDBC streaming reads**

Use `JdbcTemplate.query(ConnectionCallback<?>)` or prepared-statement callbacks with:

```java
connection.setAutoCommit(false);
PreparedStatement ps = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
ps.setFetchSize(1000);
```

Do not use `limit`, `offset`, or page loops for original tables.

- [ ] **Step 5: Run reader tests**

Run: `mvn -Dtest=JdbcSamplingSourceReaderTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spdb/sampling/engine src/test/java/com/spdb/sampling/engine/JdbcSamplingSourceReaderTest.java
git commit -m "feat: stream sampling source tables"
```

## Task 4: Issue Grouping And Sampling

**Files:**
- Create: `src/main/java/com/spdb/sampling/engine/IssueCandidate.java`
- Create: `src/main/java/com/spdb/sampling/engine/SampleGroupDraft.java`
- Create: `src/main/java/com/spdb/sampling/engine/SampleDetailDraft.java`
- Create: `src/main/java/com/spdb/sampling/engine/SampleDetailFieldDraft.java`
- Create: `src/main/java/com/spdb/sampling/engine/IssueGrouper.java`
- Test: `src/test/java/com/spdb/sampling/engine/IssueGrouperTest.java`

- [ ] **Step 1: Write failing A825 grouping test**

Test that two candidates for `11111111111` and `11111111114` produce one `FIELD_DIFF` group with two sample details and four field details.

- [ ] **Step 2: Run grouping test and verify failure**

Run: `mvn -Dtest=IssueGrouperTest test`

Expected: FAIL because grouping classes do not exist.

- [ ] **Step 3: Implement drafts and grouper**

`IssueGrouper` groups by:

```text
origCdate + sampleType + tranCode + serviceCode + semanticSignatureHash
```

For `FIELD_DIFF`, message type is excluded from key but accumulated into `messageTypes`.

- [ ] **Step 4: Implement stable sample picking**

Keep at most 20 `SampleDetailDraft` per group, ordered by `SourceKey`.

- [ ] **Step 5: Run grouping tests**

Run: `mvn -Dtest=IssueGrouperTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spdb/sampling/engine src/test/java/com/spdb/sampling/engine/IssueGrouperTest.java
git commit -m "feat: group semantic sampling issues"
```

## Task 5: Rebuild SamplingBatchRunner

**Files:**
- Modify: `src/main/java/com/spdb/sampling/SamplingBatchRunner.java`
- Modify: `src/main/java/com/spdb/sampling/SamplingCommandService.java` if summary fields need query support.
- Test: `src/test/java/com/spdb/sampling/SamplingBatchRunnerTest.java`
- Test: `src/test/java/com/spdb/sampling/SamplingExecutionStructureTest.java`

- [ ] **Step 1: Write failing batch runner integration test**

Seed H2 with:

- A825 transaction config.
- A825 field mappings for `currency_id` and `link_info`.
- `tss_tran_comp` rows for `11111111111`, `11111111114`, `22222222222`, `22222222223`.
- `tss_field_comp` rows for two semantic field combinations.
- `tss_retcode_comp` rows for response code differences.

Assert:

- `ana_sampling_summary.total_tran_count = 4`.
- One `FIELD_DIFF` group covers both `11111111111` and `11111111114`.
- Two sample details exist under that group.
- Four `ana_sample_detail_field` rows exist.
- `TRAN_RESULT` groups exist independent of `tss_retcode_comp`.

- [ ] **Step 2: Run batch runner test and verify failure**

Run: `mvn -Dtest=SamplingBatchRunnerTest test`

Expected: FAIL under current SQL-heavy runner.

- [ ] **Step 3: Replace runner orchestration**

`SamplingBatchRunner.run(command)`:

1. Initializes/clears result tables for `batchId`.
2. Loads `SamplingConfigSnapshot`.
3. Streams `TranFact` rows, builds transaction fact index and transaction result candidates.
4. Streams return-code rows, builds return-code candidates.
5. Streams field diffs in order, aggregates current `SourceKey` into one field candidate.
6. Sends candidates to `IssueGrouper`.
7. Writes group/detail/detail-field/summary using batch writer methods.

- [ ] **Step 4: Add result writer methods**

Inside runner or `SamplingResultWriter`, implement `batchUpdate` for:

- `ana_sample_group`
- `ana_sample_detail`, retrieving generated IDs or using a deterministic temporary key map.
- `ana_sample_detail_field`
- `ana_sampling_summary`

- [ ] **Step 5: Run batch runner test**

Run: `mvn -Dtest=SamplingBatchRunnerTest test`

Expected: PASS.

- [ ] **Step 6: Run existing sampling tests**

Run: `mvn -Dtest='SamplingCommandServiceTest,SamplingExecutionStructureTest' test`

Expected: PASS after updating structure assertions to require streaming readers and forbid offset pagination.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/spdb/sampling src/test/java/com/spdb/sampling src/test/java/com/spdb/sampling/engine
git commit -m "feat: execute semantic sampling batches"
```

## Task 6: Query Service Rows For New Model

**Files:**
- Modify: `src/main/java/com/spdb/sample/SampleGroupRow.java`
- Modify: `src/main/java/com/spdb/sample/SampleDetailRow.java`
- Create: `src/main/java/com/spdb/sample/SampleDetailFieldRow.java`
- Modify: `src/main/java/com/spdb/sample/SampleSearchCriteria.java`
- Modify: `src/main/java/com/spdb/sample/SampleQueryService.java`
- Test: `src/test/java/com/spdb/sample/SampleQueryServiceTest.java`

- [ ] **Step 1: Write failing query tests**

Assert:

- Group query returns semantic fields, message types, config and mapping status.
- Detail query returns one row per sampled transaction, not one row per field.
- Field-detail query returns raw and standard field names.

- [ ] **Step 2: Run query tests and verify failure**

Run: `mvn -Dtest=SampleQueryServiceTest test`

Expected: FAIL because row classes and queries do not expose new fields.

- [ ] **Step 3: Update row records and criteria**

Add fields from the spec to row records and criteria. Keep constructor parameter order explicit and update all tests.

- [ ] **Step 4: Update SQL queries**

`groups()` selects from `ana_sample_group`.

`details()` selects from `ana_sample_detail`.

Add:

```java
public PagedResult<SampleDetailFieldRow> detailFields(Long sampleId, PageRequestParams page)
```

and export streaming equivalent.

- [ ] **Step 5: Run query tests**

Run: `mvn -Dtest=SampleQueryServiceTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/spdb/sample src/test/java/com/spdb/sample/SampleQueryServiceTest.java
git commit -m "feat: query semantic sampling results"
```

## Task 7: Pages And Exports

**Files:**
- Modify: `src/main/java/com/spdb/web/SampleController.java`
- Modify: `src/main/java/com/spdb/sample/SampleExcelExportService.java`
- Modify: `src/main/resources/templates/samples/groups.html`
- Modify: `src/main/resources/templates/samples/details.html`
- Test: `src/test/java/com/spdb/web/SampleControllerExportTest.java`
- Test: `src/test/java/com/spdb/web/SampleDetailTemplateTest.java`
- Test: `src/test/java/com/spdb/web/AppCssStyleTest.java` if CSS changes.
- Test: `src/test/java/com/spdb/sample/SampleExcelExportServiceTest.java`

- [ ] **Step 1: Write failing web/export tests**

Assert:

- Group template includes `semanticFieldNames`, `messageTypes`, `configStatus`, and `mappingStatus`.
- Detail template includes sample transaction columns and a field detail section.
- Excel export has group, sample transaction, and sample field detail headers.

- [ ] **Step 2: Run tests and verify failure**

Run: `mvn -Dtest='SampleControllerExportTest,SampleDetailTemplateTest,SampleExcelExportServiceTest' test`

Expected: FAIL because templates and export still use old fields.

- [ ] **Step 3: Update controller filters and endpoints**

Add request params:

- `origCdate`
- `messageType`
- `configStatus`
- `mappingStatus`
- `semanticFieldName`
- `sampleId` for field detail lookup

Add export endpoint for field details:

```text
/samples/detail-fields/export
```

- [ ] **Step 4: Update templates**

`groups.html` shows semantic issue groups.

`details.html` shows sample transaction rows and field-level detail table or link.

- [ ] **Step 5: Update Excel export**

Produce workbook with three sheets for full export, or separate endpoints wired to existing controller style. Headers must include both `std_field_name` and `raw_field_name`.

- [ ] **Step 6: Run web/export tests**

Run: `mvn -Dtest='SampleControllerExportTest,SampleDetailTemplateTest,SampleExcelExportServiceTest' test`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/spdb/web/SampleController.java src/main/java/com/spdb/sample/SampleExcelExportService.java src/main/resources/templates/samples src/test/java/com/spdb/web src/test/java/com/spdb/sample/SampleExcelExportServiceTest.java
git commit -m "feat: display semantic sampling results"
```

## Task 8: Summary Pages And Final Verification

**Files:**
- Modify: `src/main/java/com/spdb/sample/SummaryStats.java`
- Modify: `src/main/java/com/spdb/sample/SamplingSummaryHistoryRow.java`
- Modify: `src/main/java/com/spdb/sample/SampleQueryService.java`
- Modify: `src/main/java/com/spdb/sampling/SamplingCommandRow.java`
- Modify: `src/main/java/com/spdb/sampling/SamplingCommandService.java`
- Modify: `src/main/resources/templates/home.html`
- Modify: `src/main/resources/templates/sampling/commands.html`
- Modify: `src/main/resources/templates/sampling/summaries.html`
- Test: existing summary and command template tests.

- [ ] **Step 1: Write failing summary tests**

Extend tests to assert new counters appear:

- `tranIssueCount`
- `returnCodeIssueCount`
- `fieldDiffTranCount`
- `unconfiguredServiceCount`
- `unmappedFieldCount`

- [ ] **Step 2: Run summary tests and verify failure**

Run: `mvn -Dtest='SamplingCommandServiceTest,SamplingCommandTemplateTest,SamplingSummaryTemplateTest,HomeTemplateTest' test`

Expected: FAIL until query rows and templates expose counters.

- [ ] **Step 3: Update summary records and queries**

Select new columns from `ana_sampling_summary`, defaulting missing values to zero in tests and empty states.

- [ ] **Step 4: Update templates**

Show new counters on home, command, and summary history pages.

- [ ] **Step 5: Run summary tests**

Run: `mvn -Dtest='SamplingCommandServiceTest,SamplingCommandTemplateTest,SamplingSummaryTemplateTest,HomeTemplateTest' test`

Expected: PASS.

- [ ] **Step 6: Run full test suite**

Run: `mvn test`

Expected: PASS with all tests green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/spdb/sample src/main/java/com/spdb/sampling src/main/resources/templates src/test/java
git commit -m "feat: summarize semantic sampling batches"
```

## Task 9: Manual Database Smoke Test

**Files:**
- No source files unless smoke test reveals a bug.

- [ ] **Step 1: Apply DDL to local openGauss**

Run the project DDL against local database:

```bash
gsql -h localhost -p 15432 -d postgres -U tss -W 'Tss@123456' -f db/ddl.sql
```

Expected: SQL completes without errors.

- [ ] **Step 2: Start the app**

Run:

```bash
mvn spring-boot:run
```

Expected: app starts on `http://localhost:8080`.

- [ ] **Step 3: Trigger sampling**

Create a sampling command for `20260611` from `/sampling/commands`.

Expected:

- command reaches `COMPLETED`.
- summary total matches `tss_tran_comp`.
- A825 SOP/BizJSON field combinations collapse into one `FIELD_DIFF` group when field mappings exist.

- [ ] **Step 4: Verify pages**

Open:

- `/samples/groups`
- `/samples/details`

Expected:

- group page shows semantic field combinations and statuses.
- detail page shows sample transactions.
- field detail view shows raw field names and standard field names.

- [ ] **Step 5: Final full verification**

Run:

```bash
mvn test
```

Expected: PASS.

- [ ] **Step 6: Commit smoke-test fixes**

When the smoke test reveals a source or SQL defect, commit the exact files changed for that defect. If the smoke test requires no source changes, skip this step and record that no smoke-test fix commit was needed in the final report.

```bash
git status --short
git add db/ddl.sql db/seed.sql src/main/java src/main/resources/templates src/test/java
git commit -m "fix: stabilize semantic sampling smoke test"
```

## Completion

After all tasks:

1. Run `git status --short`.
2. Run `mvn test`.
3. Push commits to `origin master`.
4. Report final commit IDs and verification output.

# tp_online_service_in Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a repeatable SQL patch that refreshes `tp_online_service_in` from `tss_tran_comp.dest_trcd` and `ana_tran_catalog`.

**Architecture:** Keep this as a standalone database patch under `db/` because the feature is data-maintenance work, not application runtime behavior. Add a lightweight JUnit structure test so the repository guards the service-code normalization and upsert shape.

**Tech Stack:** PostgreSQL/openGauss-compatible SQL, Java 17, JUnit 5, AssertJ.

---

### Task 1: Add Repeatable SQL Patch

**Files:**
- Create: `db/sync_tp_online_service_in.sql`
- Test: `src/test/java/com/spdb/db/TpOnlineServiceInSyncScriptTest.java`

- [x] **Step 1: Write the failing structure test**

Create `src/test/java/com/spdb/db/TpOnlineServiceInSyncScriptTest.java` with:

```java
package com.spdb.db;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TpOnlineServiceInSyncScriptTest {

    @Test
    void syncScriptBuildsTpOnlineServiceInFromTranCompAndCatalog() throws Exception {
        String sql = Files.readString(Path.of("db/sync_tp_online_service_in.sql"));

        assertThat(sql).contains("tp_online_service_in");
        assertThat(sql).contains("tss_tran_comp");
        assertThat(sql).contains("ana_tran_catalog");
        assertThat(sql).contains("split_part(trim(dest_trcd), '&', 1)");
        assertThat(sql).contains("substring(c.service_code from 1 for 8) || '.' || substring(c.service_code from 9)");
        assertThat(sql).contains("on conflict (tran_code, esf_service_code) do update");
        assertThat(sql).contains("where position('&' in trim(dest_trcd)) > 0");
    }
}
```

- [x] **Step 2: Run the test to verify it fails**

Run:

```powershell
mvn -Dtest=TpOnlineServiceInSyncScriptTest test
```

Expected: FAIL because `db/sync_tp_online_service_in.sql` does not exist.

- [x] **Step 3: Add the SQL patch**

Create `db/sync_tp_online_service_in.sql` with:

```sql
-- Repeatable patch: refresh tp_online_service_in from observed transaction comparison service codes.
-- Source:
--   tss_tran_comp.dest_trcd contains service code plus message type, for example:
--   S030030014FcyCollCrspBnkLkgQry&bzjson
-- Catalog:
--   ana_tran_catalog.service_code stores the base service code without message type.
-- Target:
--   tp_online_service_in.esf_service_code stores the ESF code with a dot after the eighth character:
--   S03003001.4FcyCollCrspBnkLkgQry

with observed_service as (
    select distinct split_part(trim(dest_trcd), '&', 1) as service_code
    from tss_tran_comp
    where dest_trcd is not null
      and trim(dest_trcd) <> ''
      and position('&' in trim(dest_trcd)) > 0
),
catalog_service as (
    select distinct
           c.tran_code,
           c.service_code,
           substring(c.service_code from 1 for 8) || '.' || substring(c.service_code from 9) as esf_service_code
    from observed_service o
    join ana_tran_catalog c
      on c.service_code = o.service_code
    where c.tran_code is not null
      and trim(c.tran_code) <> ''
      and c.service_code is not null
      and length(c.service_code) >= 9
)
insert into tp_online_service_in (
    tran_code,
    esf_service_code
)
select
    tran_code,
    esf_service_code
from catalog_service
on conflict (tran_code, esf_service_code) do update
set esf_service_code = excluded.esf_service_code;
```

- [x] **Step 4: Run the focused test**

Run:

```powershell
mvn -Dtest=TpOnlineServiceInSyncScriptTest test
```

Expected: PASS.

- [x] **Step 5: Run the database script layout tests**

Run:

```powershell
mvn -Dtest=DatabaseScriptLayoutTest,TpOnlineServiceInSyncScriptTest test
```

Expected: PASS.

- [x] **Step 6: Review the working tree**

Run:

```powershell
git diff -- db/sync_tp_online_service_in.sql src/test/java/com/spdb/db/TpOnlineServiceInSyncScriptTest.java
git status --short
```

Expected: the diff only includes the new SQL patch and new test. Existing unrelated workspace changes remain unstaged.

- [x] **Step 7: Commit**

Run:

```powershell
git add -- db/sync_tp_online_service_in.sql src/test/java/com/spdb/db/TpOnlineServiceInSyncScriptTest.java
git commit -m "feat: add tp online service sync script"
```

Expected: commit succeeds with only the two files above.

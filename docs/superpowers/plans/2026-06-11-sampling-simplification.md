# Sampling Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Simplify the batch sampling program without changing sampling results, SQL strategy, or UI behavior.

**Architecture:** Keep `SamplingCommandService` as the command-facing service and move batch execution into `SamplingBatchRunner`. Delete the unused chunk-oriented path. Preserve set-based SQL, temporary tables, and existing summary calculations.

**Tech Stack:** Java 17, Spring Boot, Spring JDBC, Maven, JUnit 5, AssertJ.

---

### Task 1: Make Structure Tests Catch Source-Level Legacy Code

**Files:**
- Modify: `src/test/java/com/spdb/sampling/SamplingExecutionStructureTest.java`

- [ ] **Step 1: Write the failing test change**

Change `javaSource` to read directly from `src/main/java/com/spdb/sampling/<fileName>` so the test checks current source instead of stale compiled resources.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=SamplingExecutionStructureTest`

Expected: FAIL because `SamplingCommandService.java` currently contains `writeTranChunk`.

### Task 2: Extract Batch Runner and Remove Chunk Path

**Files:**
- Create: `src/main/java/com/spdb/sampling/SamplingBatchRunner.java`
- Modify: `src/main/java/com/spdb/sampling/SamplingCommandService.java`
- Delete: `src/main/java/com/spdb/sampling/SamplingTranItem.java`

- [ ] **Step 1: Implement minimal refactor**

Move these responsibilities from `SamplingCommandService` into `SamplingBatchRunner`:

- `runSamplingBatch` body as `run`
- candidate materialization
- sample group insertion
- detail candidate materialization
- detail insertion
- summary counting and updating
- final sample count update and candidate cleanup
- `groupedCandidateCte`, `allCandidateGroupsCte`, `countTranByResult`

Keep `SamplingCommandService.runSamplingBatch(batchId)` as a delegating method.

- [ ] **Step 2: Delete legacy chunk code**

Remove `writeTranChunk`, `countComp`, and `SamplingTranItem.java`.

- [ ] **Step 3: Run structure test**

Run: `mvn test -Dtest=SamplingExecutionStructureTest`

Expected: PASS.

### Task 3: Full Verification

**Files:**
- No additional files expected.

- [ ] **Step 1: Run all tests**

Run: `mvn test`

Expected: PASS.

- [ ] **Step 2: Review git diff**

Run: `git diff --stat` and `git diff -- src/main/java/com/spdb/sampling src/test/java/com/spdb/sampling docs/superpowers`

Expected: only sampling simplification and docs changes.

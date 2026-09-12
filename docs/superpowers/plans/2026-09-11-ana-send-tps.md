# ana 报文发送实时 TPS 统计 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为报文发送页面增加实时 TPS 统计，并将启动改为异步、统计改为本次运行的内存口径。

**Architecture:** 新增独立的 5 秒滑动窗口计数器 `TpsWindow`；`AnaMessageSendService` 在发送过程中维护内存计数（成功/失败/在途/快照总数）并通过 `liveStats()` 暴露；新增 launcher/executor 让 `start` 在后台线程执行、HTTP 立即返回；控制器新增 JSON `status` 接口；页面每 2 秒轮询刷新。

**Tech Stack:** Java 17、Spring Boot 3.3、Thymeleaf、JUnit 5、AssertJ、Mockito、Maven。

> 说明：本项目约定未经用户明确要求不自动提交 git；本计划的每个任务以「测试通过」作为完成标志，不包含 `git commit` 步骤。

---

## 文件结构

- 新增 `src/main/java/com/spdb/message/TpsWindow.java` — 5 秒环形秒桶滑动窗口计数器。
- 新增 `src/main/java/com/spdb/message/AnaMessageSendTaskLauncher.java` — 异步启动接口。
- 新增 `src/main/java/com/spdb/message/AnaMessageSendAsyncExecutor.java` — 用线程池执行 `service.start`。
- 新增 `src/main/java/com/spdb/message/AnaMessageSendExecutionConfig.java` — 提供 `anaMessageSendTaskExecutor` bean。
- 修改 `src/main/java/com/spdb/message/AnaMessageSendService.java` — 内存计数、`liveStats()`、`countPending()`、`resetCounters()`；删除不再使用的 `stats()`。
- 修改 `src/main/java/com/spdb/web/AnaMessageSendController.java` — 注入 launcher、异步 `start`、新增 `status` 接口、页面改用 `liveStats()`。
- 修改 `src/main/resources/templates/messages/ana-send.html` — TPS 卡片、统计 id、轮询脚本，整理为可读多行格式。
- 新增 `src/test/java/com/spdb/message/TpsWindowTest.java`
- 新增 `src/test/java/com/spdb/message/AnaMessageSendServiceLiveStatsTest.java`
- 新增 `src/test/java/com/spdb/message/AnaMessageSendAsyncExecutorTest.java`
- 新增 `src/test/java/com/spdb/web/AnaMessageSendControllerTest.java`
- 新增 `src/test/java/com/spdb/web/AnaSendTemplateTest.java`

---

## Task 1: TpsWindow 滑动窗口计数器

**Files:**
- Create: `src/main/java/com/spdb/message/TpsWindow.java`
- Test: `src/test/java/com/spdb/message/TpsWindowTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/spdb/message/TpsWindowTest.java`：

```java
package com.spdb.message;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TpsWindowTest {

    private final AtomicLong clock = new AtomicLong(0L);

    private TpsWindow newWindow() {
        return new TpsWindow(clock::get);
    }

    @Test
    void countsRecordsInsideFiveSecondWindow() {
        TpsWindow window = newWindow();
        clock.set(1_000L);
        window.record();
        window.record();
        clock.set(3_000L);
        window.record();

        assertThat(window.countInWindow()).isEqualTo(3L);
        assertThat(window.tps()).isEqualTo(0.6);
    }

    @Test
    void excludesRecordsOlderThanWindow() {
        TpsWindow window = newWindow();
        clock.set(1_000L);
        window.record();
        clock.set(7_000L);

        assertThat(window.countInWindow()).isZero();
        assertThat(window.tps()).isZero();
    }

    @Test
    void reusesSlotsWithoutCountingStaleEpochs() {
        TpsWindow window = newWindow();
        clock.set(1_000L);
        window.record();
        clock.set(7_000L);
        window.record();

        assertThat(window.countInWindow()).isEqualTo(1L);
    }

    @Test
    void resetClearsAllCounts() {
        TpsWindow window = newWindow();
        clock.set(1_000L);
        window.record();
        window.reset();

        assertThat(window.countInWindow()).isZero();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test "-Dtest=TpsWindowTest"`
Expected: 编译失败，提示找不到 `TpsWindow`。

- [ ] **Step 3: 写最小实现**

创建 `src/main/java/com/spdb/message/TpsWindow.java`：

```java
package com.spdb.message;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongSupplier;

/**
 * 5 秒滑动窗口计数器：环形秒桶实现，用于统计实时 TPS。
 */
public final class TpsWindow {

    static final int WINDOW_SECONDS = 5;
    private static final int SLOT_COUNT = WINDOW_SECONDS + 1;

    private final AtomicLongArray counts = new AtomicLongArray(SLOT_COUNT);
    private final AtomicLongArray epochs = new AtomicLongArray(SLOT_COUNT);
    private final LongSupplier clock;
    private final Object writeLock = new Object();

    public TpsWindow() {
        this(System::currentTimeMillis);
    }

    public TpsWindow(LongSupplier clock) {
        this.clock = clock;
        for (int i = 0; i < SLOT_COUNT; i++) {
            epochs.set(i, Long.MIN_VALUE);
        }
    }

    public void record() {
        long second = currentSecond();
        int slot = slotOf(second);
        synchronized (writeLock) {
            if (epochs.get(slot) != second) {
                epochs.set(slot, second);
                counts.set(slot, 0L);
            }
            counts.incrementAndGet(slot);
        }
    }

    public long countInWindow() {
        long second = currentSecond();
        long from = second - (WINDOW_SECONDS - 1);
        long sum = 0L;
        for (int i = 0; i < SLOT_COUNT; i++) {
            long epoch = epochs.get(i);
            if (epoch >= from && epoch <= second) {
                sum += counts.get(i);
            }
        }
        return sum;
    }

    public double tps() {
        return Math.round(countInWindow() / (double) WINDOW_SECONDS * 10.0) / 10.0;
    }

    public void reset() {
        synchronized (writeLock) {
            for (int i = 0; i < SLOT_COUNT; i++) {
                epochs.set(i, Long.MIN_VALUE);
                counts.set(i, 0L);
            }
        }
    }

    private long currentSecond() {
        return clock.getAsLong() / 1000L;
    }

    private int slotOf(long second) {
        return (int) Math.floorMod(second, SLOT_COUNT);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test "-Dtest=TpsWindowTest"`
Expected: 4 个测试全部通过。

---

## Task 2: 服务层内存计数与 liveStats

**Files:**
- Modify: `src/main/java/com/spdb/message/AnaMessageSendService.java`
- Test: `src/test/java/com/spdb/message/AnaMessageSendServiceLiveStatsTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/spdb/message/AnaMessageSendServiceLiveStatsTest.java`：

```java
package com.spdb.message;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnaMessageSendServiceLiveStatsTest {

    @Test
    void initialLiveStatsAreZeroAndIdle() {
        AnaMessageSendService service = new AnaMessageSendService(mockJdbc(), mock(PlatformTransactionManager.class));

        Map<String, Object> stats = service.liveStats();

        assertThat(stats.get("running")).isEqualTo(false);
        assertThat(stats.get("tps")).isEqualTo(0.0);
        assertThat(stats.get("pending")).isEqualTo(0L);
        assertThat(stats.get("sending")).isEqualTo(0);
        assertThat(stats.get("success")).isEqualTo(0L);
        assertThat(stats.get("failed")).isEqualTo(0L);
    }

    @Test
    void initialIsNotRunning() {
        AnaMessageSendService service = new AnaMessageSendService(mockJdbc(), mock(PlatformTransactionManager.class));

        assertThat(service.isRunning()).isFalse();
    }

    private NamedParameterJdbcTemplate mockJdbc() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.getJdbcTemplate()).thenReturn(mock(JdbcTemplate.class));
        return jdbc;
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test "-Dtest=AnaMessageSendServiceLiveStatsTest"`
Expected: 编译失败，提示找不到 `liveStats()`。

- [ ] **Step 3: 在 AnaMessageSendService 增加内存计数**

修改 `src/main/java/com/spdb/message/AnaMessageSendService.java`。

在 import 区补充（放在既有 `java.util.concurrent.atomic.AtomicInteger;` 之后）：

```java
import java.util.concurrent.atomic.LongAdder;
```

在字段区（`private volatile boolean running;` 之前）增加：

```java
    private final LongAdder successCount = new LongAdder();
    private final LongAdder failedCount = new LongAdder();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final TpsWindow tpsWindow = new TpsWindow();
    private volatile long total;
```

在 `start` 方法内 `running = true;` 之后、`configCache.clear();` 之前，增加一行：

```java
        resetCounters();
```

在 `start` 方法内拿到 `long[] range = minMax(retries);` 且 `range != null` 判断通过之后、`int window = Math.max(1, concurrency);` 之前，增加：

```java
        total = countPending(retries);
```

在 `sendOne` 方法体最开头（`String ip = ...` 之前）增加：

```java
        inFlight.incrementAndGet();
```

在 `sendOne` 成功分支的 `jdbc.update("update ana_msg_flow_log_request set send_status=:s, ...")` 调用之后、`log.debug(...)` 之前，按 HTTP 状态码累加（非 2xx 走的是同一分支但状态为 FAILED，必须计入失败）：

```java
            if (response.statusCode() / 100 == 2) {
                successCount.increment();
            } else {
                failedCount.increment();
            }
```

在 `sendOne` 的 `catch (Exception e)` 块内、`log.warn(...)` 之前，增加：

```java
            failedCount.increment();
```

把 `sendOne` 的 `finally` 块替换为：

```java
        } finally {
            inFlight.decrementAndGet();
            tpsWindow.record();
            int n = sentCount.get() + 1;
            sentCount.set(n);
            if (n % 100 == 0) {
                log.info("报文发送进度: target={}, 当前线程累计已发送 {} 笔", target, n);
            }
        }
```

在类中新增以下方法（放在 `isRunning()` 之后）：

```java
    public Map<String, Object> liveStats() {
        long success = successCount.sum();
        long failed = failedCount.sum();
        Map<String, Object> stats = new HashMap<>();
        stats.put("running", running);
        stats.put("tps", tpsWindow.tps());
        stats.put("pending", Math.max(0L, total - success - failed));
        stats.put("sending", inFlight.get());
        stats.put("success", success);
        stats.put("failed", failed);
        return stats;
    }

    private long countPending(int retries) {
        Integer count = jdbc.queryForObject(
                "select count(*) from ana_msg_flow_log_request " +
                        "where send_status in ('PENDING','FAILED') and send_attempts <= :retries and txn_time is not null",
                new MapSqlParameterSource("retries", retries),
                Integer.class);
        return count == null ? 0L : count.longValue();
    }

    private void resetCounters() {
        successCount.reset();
        failedCount.reset();
        inFlight.set(0);
        tpsWindow.reset();
        total = 0L;
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test "-Dtest=AnaMessageSendServiceLiveStatsTest"`
Expected: 2 个测试通过。

---

## Task 3: 异步启动组件

**Files:**
- Create: `src/main/java/com/spdb/message/AnaMessageSendTaskLauncher.java`
- Create: `src/main/java/com/spdb/message/AnaMessageSendExecutionConfig.java`
- Create: `src/main/java/com/spdb/message/AnaMessageSendAsyncExecutor.java`
- Test: `src/test/java/com/spdb/message/AnaMessageSendAsyncExecutorTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/spdb/message/AnaMessageSendAsyncExecutorTest.java`：

```java
package com.spdb.message;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AnaMessageSendAsyncExecutorTest {

    @Test
    void launchRunsServiceStartOnExecutor() {
        AnaMessageSendService service = mock(AnaMessageSendService.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        doAnswer(invocation -> {
            submitted.set(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any(Runnable.class));

        new AnaMessageSendAsyncExecutor(service, executor).launch("528", 2, 60, 3, 15);

        submitted.get().run();
        verify(service).start("528", 2, 60, 3, 15);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test "-Dtest=AnaMessageSendAsyncExecutorTest"`
Expected: 编译失败，提示找不到 `AnaMessageSendAsyncExecutor`。

- [ ] **Step 3: 写实现**

创建 `src/main/java/com/spdb/message/AnaMessageSendTaskLauncher.java`：

```java
package com.spdb.message;

public interface AnaMessageSendTaskLauncher {
    void launch(String target, int concurrency, int rangeMinutes, int retries, int timeout);
}
```

创建 `src/main/java/com/spdb/message/AnaMessageSendExecutionConfig.java`：

```java
package com.spdb.message;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AnaMessageSendExecutionConfig {

    @Bean
    public ThreadPoolTaskExecutor anaMessageSendTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ana-message-send-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.initialize();
        return executor;
    }
}
```

创建 `src/main/java/com/spdb/message/AnaMessageSendAsyncExecutor.java`：

```java
package com.spdb.message;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class AnaMessageSendAsyncExecutor implements AnaMessageSendTaskLauncher {

    private final AnaMessageSendService service;
    private final ThreadPoolTaskExecutor executor;

    public AnaMessageSendAsyncExecutor(AnaMessageSendService service,
                                       @Qualifier("anaMessageSendTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.service = service;
        this.executor = executor;
    }

    @Override
    public void launch(String target, int concurrency, int rangeMinutes, int retries, int timeout) {
        executor.execute(() -> service.start(target, concurrency, rangeMinutes, retries, timeout));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test "-Dtest=AnaMessageSendAsyncExecutorTest"`
Expected: 1 个测试通过。

---

## Task 4: 控制器异步启动与 status 接口

**Files:**
- Modify: `src/main/java/com/spdb/web/AnaMessageSendController.java`
- Modify: `src/main/java/com/spdb/message/AnaMessageSendService.java`（删除 `stats()`）
- Test: `src/test/java/com/spdb/web/AnaMessageSendControllerTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/spdb/web/AnaMessageSendControllerTest.java`：

```java
package com.spdb.web;

import com.spdb.message.AnaMessageSendService;
import com.spdb.message.AnaMessageSendTaskLauncher;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnaMessageSendControllerTest {

    @Test
    void pageAddsLiveStatsAndRunningFlag() {
        AnaMessageSendService service = mock(AnaMessageSendService.class);
        AnaMessageSendTaskLauncher launcher = mock(AnaMessageSendTaskLauncher.class);
        Map<String, Object> liveStats = Map.of("running", false, "tps", 0.0);
        when(service.liveStats()).thenReturn(liveStats);
        when(service.isRunning()).thenReturn(false);
        AnaMessageSendController controller = new AnaMessageSendController(service, launcher);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.page(model);

        assertThat(view).isEqualTo("messages/ana-send");
        assertThat(model.getAttribute("active")).isEqualTo("ana-send");
        assertThat(model.getAttribute("stats")).isEqualTo(liveStats);
        assertThat(model.getAttribute("running")).isEqualTo(false);
    }

    @Test
    void statusReturnsLiveStats() {
        AnaMessageSendService service = mock(AnaMessageSendService.class);
        AnaMessageSendTaskLauncher launcher = mock(AnaMessageSendTaskLauncher.class);
        Map<String, Object> liveStats = Map.of("running", true, "tps", 12.4);
        when(service.liveStats()).thenReturn(liveStats);
        AnaMessageSendController controller = new AnaMessageSendController(service, launcher);

        assertThat(controller.status()).isEqualTo(liveStats);
    }

    @Test
    void startLaunchesWhenIdle() {
        AnaMessageSendService service = mock(AnaMessageSendService.class);
        AnaMessageSendTaskLauncher launcher = mock(AnaMessageSendTaskLauncher.class);
        when(service.isRunning()).thenReturn(false);
        AnaMessageSendController controller = new AnaMessageSendController(service, launcher);

        String view = controller.start("528", 2, 60, 3, 15);

        assertThat(view).isEqualTo("redirect:/messages/ana-send");
        verify(launcher).launch("528", 2, 60, 3, 15);
    }

    @Test
    void startIsIgnoredWhenAlreadyRunning() {
        AnaMessageSendService service = mock(AnaMessageSendService.class);
        AnaMessageSendTaskLauncher launcher = mock(AnaMessageSendTaskLauncher.class);
        when(service.isRunning()).thenReturn(true);
        AnaMessageSendController controller = new AnaMessageSendController(service, launcher);

        String view = controller.start("528", 2, 60, 3, 15);

        assertThat(view).isEqualTo("redirect:/messages/ana-send");
        verifyNoInteractions(launcher);
    }

    @Test
    void stopDelegatesToService() {
        AnaMessageSendService service = mock(AnaMessageSendService.class);
        AnaMessageSendTaskLauncher launcher = mock(AnaMessageSendTaskLauncher.class);
        AnaMessageSendController controller = new AnaMessageSendController(service, launcher);

        String view = controller.stop();

        assertThat(view).isEqualTo("redirect:/messages/ana-send");
        verify(service).stop();
    }

    @Test
    void resetReturnsCountOnSuccess() {
        AnaMessageSendService service = mock(AnaMessageSendService.class);
        AnaMessageSendTaskLauncher launcher = mock(AnaMessageSendTaskLauncher.class);
        when(service.reset("failed")).thenReturn(7);
        AnaMessageSendController controller = new AnaMessageSendController(service, launcher);

        String view = controller.reset("failed");

        assertThat(view).isEqualTo("redirect:/messages/ana-send?resetCount=7");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test "-Dtest=AnaMessageSendControllerTest"`
Expected: 编译失败，提示构造器参数不匹配 / 缺少 `status()`。

- [ ] **Step 3: 重写控制器**

用以下内容整体替换 `src/main/java/com/spdb/web/AnaMessageSendController.java`：

```java
package com.spdb.web;

import com.spdb.message.AnaMessageSendService;
import com.spdb.message.AnaMessageSendTaskLauncher;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Controller
public class AnaMessageSendController {

    private final AnaMessageSendService service;
    private final AnaMessageSendTaskLauncher launcher;

    public AnaMessageSendController(AnaMessageSendService service, AnaMessageSendTaskLauncher launcher) {
        this.service = service;
        this.launcher = launcher;
    }

    @GetMapping("/messages/ana-send")
    public String page(Model model) {
        model.addAttribute("active", "ana-send");
        model.addAttribute("stats", service.liveStats());
        model.addAttribute("running", service.isRunning());
        return "messages/ana-send";
    }

    @GetMapping("/messages/ana-send/status")
    @ResponseBody
    public Map<String, Object> status() {
        return service.liveStats();
    }

    @PostMapping("/messages/ana-send/start")
    public String start(@RequestParam(defaultValue = "528") String target,
                        @RequestParam(defaultValue = "1") int concurrency,
                        @RequestParam(defaultValue = "60") int rangeMinutes,
                        @RequestParam(defaultValue = "2") int retries,
                        @RequestParam(defaultValue = "15") int timeout) {
        if (!service.isRunning()) {
            launcher.launch(target, concurrency, rangeMinutes, retries, timeout);
        }
        return "redirect:/messages/ana-send";
    }

    @PostMapping("/messages/ana-send/stop")
    public String stop() {
        service.stop();
        return "redirect:/messages/ana-send";
    }

    @PostMapping("/messages/ana-send/reset")
    public String reset(@RequestParam(defaultValue = "all") String scope) {
        try {
            int n = service.reset(scope);
            return "redirect:/messages/ana-send?resetCount=" + n;
        } catch (IllegalStateException e) {
            return "redirect:/messages/ana-send?resetError="
                    + URLEncoder.encode(String.valueOf(e.getMessage()), StandardCharsets.UTF_8);
        }
    }
}
```

- [ ] **Step 4: 删除服务层不再使用的 stats()**

在 `src/main/java/com/spdb/message/AnaMessageSendService.java` 中删除以下方法（controller 已改用 `liveStats()`，无其他引用）：

```java
    public Map<String, Object> stats() {
        return jdbc.queryForMap("select count(case when send_status='PENDING' then 1 end) pending, " +
                "count(case when send_status='SENDING' then 1 end) sending, " +
                "count(case when send_status='SUCCESS' then 1 end) success, " +
                "count(case when send_status='FAILED' then 1 end) failed from ana_msg_flow_log_request",
                new MapSqlParameterSource());
    }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test "-Dtest=AnaMessageSendControllerTest,AnaMessageSendServiceLiveStatsTest"`
Expected: 8 个测试通过（控制器 6 + 服务 2）。

---

## Task 5: 页面 TPS 卡片与轮询脚本

**Files:**
- Modify: `src/main/resources/templates/messages/ana-send.html`
- Test: `src/test/java/com/spdb/web/AnaSendTemplateTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/spdb/web/AnaSendTemplateTest.java`：

```java
package com.spdb.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AnaSendTemplateTest {

    @Test
    void anaSendPageShowsTpsCardAndPollingScript() throws Exception {
        String html = new String(
                getClass().getResourceAsStream("/templates/messages/ana-send.html").readAllBytes(),
                StandardCharsets.UTF_8
        );

        assertThat(html).contains("实时 TPS");
        assertThat(html).contains("id=\"ana-tps\"");
        assertThat(html).contains("id=\"ana-pending\"");
        assertThat(html).contains("id=\"ana-sending\"");
        assertThat(html).contains("id=\"ana-success\"");
        assertThat(html).contains("id=\"ana-failed\"");
        assertThat(html).contains("/messages/ana-send/status");
        assertThat(html).contains("setInterval");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test "-Dtest=AnaSendTemplateTest"`
Expected: FAIL，断言缺少 `实时 TPS` / id。

- [ ] **Step 3: 重写页面**

用以下内容整体替换 `src/main/resources/templates/messages/ana-send.html`：

```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>报文发送</title>
    <link rel="stylesheet" href="/css/app.css">
</head>
<body>
<div class="app-shell" th:attr="data-active=${active}">
    <div th:replace="~{fragments/layout :: sidebar(${active})}"></div>
    <div class="app-workspace">
        <div th:replace="~{fragments/layout :: workspaceBar}"></div>
        <main class="shell layout-frame">
            <div class="page-head">
                <div>
                    <div class="eyebrow">Request delivery</div>
                    <h1>报文发送</h1>
                    <p class="muted">从 ana_msg_flow_log_request 发送二进制报文并保存响应。</p>
                </div>
            </div>
            <section class="panel">
                <form class="filter-grid" method="post" action="/messages/ana-send/start">
                    <label>目标系统<select name="target"><option>528</option><option>ccbs</option></select></label>
                    <label>并发度<input name="concurrency" type="number" min="1" value="1"></label>
                    <label>区间分钟<input name="rangeMinutes" type="number" min="1" value="60"></label>
                    <label>最大重试<input name="retries" type="number" min="0" value="2"></label>
                    <label>超时秒数<input name="timeout" type="number" min="1" value="15"></label>
                    <div class="actions"><button class="btn primary" type="submit">启动发送</button></div>
                </form>
            </section>
            <section class="summary-grid">
                <div><span>实时 TPS</span><strong id="ana-tps" th:text="${stats.tps}">0.0</strong></div>
                <div><span>待发送</span><strong id="ana-pending" th:text="${stats.pending}">0</strong></div>
                <div><span>发送中</span><strong id="ana-sending" th:text="${stats.sending}">0</strong></div>
                <div><span>成功</span><strong id="ana-success" th:text="${stats.success}">0</strong></div>
                <div><span>失败</span><strong id="ana-failed" th:text="${stats.failed}">0</strong></div>
            </section>
            <form method="post" action="/messages/ana-send/stop">
                <button class="btn danger" type="submit">停止领取</button>
            </form>
            <section class="panel">
                <form class="filter-grid" method="post" action="/messages/ana-send/reset">
                    <label>重置范围<select name="scope"><option value="all">全部已发送</option><option value="failed">仅失败</option></select></label>
                    <div class="actions"><button class="btn danger" type="submit">重置</button></div>
                </form>
                <p class="muted" th:if="${param.resetCount != null}">已重置 <span th:text="${param.resetCount}">0</span> 笔，响应已同步删除</p>
                <p class="muted" th:if="${param.resetError != null}" th:text="${param.resetError}">重置失败</p>
            </section>
        </main>
    </div>
</div>
<div th:replace="~{fragments/layout :: sidebarScript}"></div>
<script>
    (() => {
        const statusUrl = '/messages/ana-send/status';
        const tpsEl = document.getElementById('ana-tps');
        const pendingEl = document.getElementById('ana-pending');
        const sendingEl = document.getElementById('ana-sending');
        const successEl = document.getElementById('ana-success');
        const failedEl = document.getElementById('ana-failed');
        const setText = (el, value) => {
            if (el && value !== undefined && value !== null) {
                el.textContent = value;
            }
        };
        let timer = null;
        let seenRunning = false;
        const stopPolling = () => {
            if (timer !== null) {
                window.clearInterval(timer);
                timer = null;
            }
        };
        const refresh = () => fetch(statusUrl, { headers: { 'Accept': 'application/json' } })
            .then((response) => response.ok ? response.json() : Promise.reject(new Error('status ' + response.status)))
            .then((current) => {
                if (!current) {
                    return;
                }
                setText(tpsEl, Number(current.tps).toFixed(1));
                setText(pendingEl, current.pending);
                setText(sendingEl, current.sending);
                setText(successEl, current.success);
                setText(failedEl, current.failed);
                if (current.running) {
                    seenRunning = true;
                } else if (seenRunning) {
                    stopPolling();
                    setText(tpsEl, '0.0');
                }
            })
            .catch(() => { });
        timer = window.setInterval(refresh, 2000);
        window.addEventListener('pagehide', stopPolling);
    })();
</script>
</body>
</html>
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test "-Dtest=AnaSendTemplateTest"`
Expected: 1 个测试通过。

---

## Task 6: 全量回归验证

**Files:** 无新增，仅运行验证。

- [ ] **Step 1: 运行本次新增的全部测试**

Run: `mvn -q test "-Dtest=TpsWindowTest,AnaMessageSendServiceLiveStatsTest,AnaMessageSendAsyncExecutorTest,AnaMessageSendControllerTest,AnaSendTemplateTest"`
Expected: 全部通过。

- [ ] **Step 2: 运行报文相关回归测试**

Run: `mvn -q test "-Dtest=ResponseMessageParserTest,MessageFlowLogServiceTest,MessageFlowLogTemplateTest,MessageFlowLogEntryControllerTest,MessageFlowLogEntryTemplateTest"`
Expected: 全部通过。

- [ ] **Step 3: 全量测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS，0 failures。

- [ ] **Step 4: 手动冒烟（需可用的数据库与服务地址，可选）**

启动应用后打开 `/messages/ana-send`：点击「启动发送」应立刻返回并渲染页面；`实时 TPS` 每 2 秒刷新；发送结束后 TPS 归 `0.0`，轮询停止；「停止领取」可在运行中点击生效。

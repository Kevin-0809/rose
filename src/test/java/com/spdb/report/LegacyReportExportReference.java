package com.spdb.report;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;

/** Compiles the unmodified pre-refactoring implementation in an isolated class loader. */
final class LegacyReportExportReference {
    // Fixed reference: never compare against HEAD, which changes as this refactoring is committed.
    private static final String REFERENCE = "377af98";
    private static Class<?> referenceClass;

    private LegacyReportExportReference() {
    }

    static void run(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager,
                    String batchId, String reportDate, LocalDateTime time) throws Exception {
        Class<?> type = referenceClass();
        Object runner = type.getConstructor(NamedParameterJdbcTemplate.class, PlatformTransactionManager.class, Executor.class)
                .newInstance(jdbc, manager, (Executor) Runnable::run);
        try {
            type.getMethod("run", String.class, String.class, LocalDateTime.class).invoke(runner, batchId, reportDate, time);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            throw new AssertionError("原版报表执行失败", exception.getCause());
        }
    }

    private static synchronized Class<?> referenceClass() throws Exception {
        if (referenceClass != null) return referenceClass;
        Process process = new ProcessBuilder("git", "show", REFERENCE
                + ":src/main/java/com/spdb/report/ReportExportBatchRunner.java").redirectErrorStream(true).start();
        String source = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new AssertionError("差分测试需要包含原版提交 " + REFERENCE + " 的 Git 历史：" + source);
        }
        Path directory = Files.createTempDirectory("report-legacy-reference-");
        Path file = directory.resolve("ReportExportBatchRunner.java");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-encoding", "UTF-8", "-proc:none", "-classpath", System.getProperty("java.class.path"),
                "-d", directory.toString(), file.toString());
        if (result != 0) throw new AssertionError("原版报表参考实现编译失败");
        URLClassLoader loader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, LegacyReportExportReference.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("com.spdb.report.ReportExportBatchRunner")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        };
        referenceClass = loader.loadClass("com.spdb.report.ReportExportBatchRunner");
        return referenceClass;
    }
}

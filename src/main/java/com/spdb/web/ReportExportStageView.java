package com.spdb.web;

import com.spdb.report.ReportExportStage;

import java.util.Arrays;
import java.util.List;

public record ReportExportStageView(String label, int number, String state, String stateLabel) {
    public static List<ReportExportStageView> forCommand(String commandStatus, String currentStage) {
        ReportExportStage active = parse(currentStage);
        return Arrays.stream(ReportExportStage.values())
                .map(stage -> view(commandStatus, active, stage))
                .toList();
    }

    private static ReportExportStageView view(String commandStatus, ReportExportStage active, ReportExportStage stage) {
        int number = stage.ordinal() + 1;
        if ("SUCCEEDED".equals(commandStatus)) return new ReportExportStageView(stage.label(), number, "completed", "已完成");
        if ("FAILED".equals(commandStatus) && stage == active) return new ReportExportStageView(stage.label(), number, "failed", "失败");
        if ("FAILED".equals(commandStatus) && active != null && stage.ordinal() > active.ordinal()) return new ReportExportStageView(stage.label(), number, "not-executed", "未执行");
        if ("RUNNING".equals(commandStatus) && stage == active) return new ReportExportStageView(stage.label(), number, "running", "处理中");
        if (("RUNNING".equals(commandStatus) || "FAILED".equals(commandStatus)) && active != null && stage.ordinal() < active.ordinal()) return new ReportExportStageView(stage.label(), number, "completed", "已完成");
        return new ReportExportStageView(stage.label(), number, "pending", "等待");
    }

    private static ReportExportStage parse(String currentStage) {
        if (currentStage == null || currentStage.isBlank()) return null;
        try { return ReportExportStage.valueOf(currentStage); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}

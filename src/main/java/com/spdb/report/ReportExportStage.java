package com.spdb.report;

public enum ReportExportStage {
    TRANSACTION_DETAILS("交易级明细"),
    FIELD_DETAILS("字段级明细"),
    SUMMARY("汇总报表");

    private final String label;

    ReportExportStage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

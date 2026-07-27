package com.spdb.report;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record DiffIssueRow(long issueId, String issueKey, String issueLevel, String serviceCode,
                           String tranCode, String tranName, String moduleName, String transactionOwner,
                           String origErrorCode, String destErrorCode, String normalizedSourceFieldName,
                           String problemType, String problemDescription, String preliminaryAnalysis,
                           String finalSolution, String issueStatus, String coordinationRequired, String resolver,
                           LocalDate resolutionDate, LocalDate defectFixDate, LocalDate firstSeenDate,
                           LocalDate lastSeenDate, String firstSeenBatchId, String lastSeenBatchId,
                           long occurrenceBatchCount, LocalDateTime updatedAt) {
}

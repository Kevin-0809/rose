package com.spdb.report;

import java.time.LocalDate;

public record DiffIssueUpdate(String problemType, String preliminaryAnalysis, String finalSolution,
                              String issueStatus, String coordinationRequired, String resolver,
                              LocalDate resolutionDate, LocalDate defectFixDate) {
}

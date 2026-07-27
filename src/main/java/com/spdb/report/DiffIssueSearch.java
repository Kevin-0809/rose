package com.spdb.report;

import java.time.LocalDate;

public record DiffIssueSearch(String issueLevel, String issueStatus, String serviceCode, String moduleName,
                              String transactionOwner, LocalDate firstSeenFrom, LocalDate firstSeenTo,
                              LocalDate lastSeenFrom, LocalDate lastSeenTo, String keyword) {
}

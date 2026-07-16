package com.spdb.report;

import org.springframework.stereotype.Component;

@Component
public class BatchDomainReportRunner {
    private final BatchDomainReportService service;

    public BatchDomainReportRunner(BatchDomainReportService service) {
        this.service = service;
    }

    public void run(String batchId) {
        if (!service.markRunning(batchId)) {
            return;
        }
        try {
            service.generate(batchId);
            service.markSucceeded(batchId);
        } catch (RuntimeException exception) {
            service.markFailed(batchId, exception.getMessage());
        }
    }
}

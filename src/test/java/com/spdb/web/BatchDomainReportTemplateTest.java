package com.spdb.web;

import com.spdb.report.BatchDomainReportCommandRow;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BatchDomainReportTemplateTest {

    @Test
    void rendersGenerationProgressAndReportRegions() {
        String html = render("RUNNING");

        assertThat(html)
                .contains("action=\"/sampling/domain-reports\"")
                .contains("name=\"batchId\"")
                .contains("RUNNING")
                .contains("for=\"report-batch-id\"")
                .contains("id=\"report-batch-id\"")
                .contains("aria-live=\"polite\"")
                .contains("id=\"report-status\"");
    }

    @Test
    void pollingReleasesItsIntervalForTerminalEmptyFailedAndPageExitPaths() throws Exception {
        String html = render("PENDING");

        assertThat(html)
                .contains("const intervalId = window.setInterval(poll, 3000)")
                .contains("const stopPolling = () => window.clearInterval(intervalId)")
                .contains("if (!command)")
                .contains(".catch(stopPolling)")
                .contains("window.addEventListener('pagehide', stopPolling)")
                .contains("window.addEventListener('unload', stopPolling)");
    }

    private String render(String status) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        MockHttpServletResponse response = new MockHttpServletResponse();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext).buildExchange(request, response), Locale.SIMPLIFIED_CHINESE, Map.of(
                "active", "batch-domain-reports",
                "batchId", "batch-1",
                "command", new BatchDomainReportCommandRow(1L, "batch-1", status, null, null, null, null),
                "transactionStats", List.of(),
                "fieldStats", List.of(),
                "gaps", List.of()
        ));
        return engine.process("sampling/domain-reports", context);
    }
}

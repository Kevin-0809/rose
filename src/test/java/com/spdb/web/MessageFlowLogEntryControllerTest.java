package com.spdb.web;

import com.spdb.message.MessageFlowLogEntryForm;
import com.spdb.message.MessageFlowLogService;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MessageFlowLogEntryControllerTest {

    @Test
    void newPageAddsEmptyFormToModel() {
        MessageFlowLogService service = mock(MessageFlowLogService.class);
        MessageFlowLogController controller = new MessageFlowLogController(service);
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.newEntryPage(model);

        assertThat(view).isEqualTo("messages/flow-log-entry");
        assertThat(model.getAttribute("active")).isEqualTo("message-flow-log-entry");
        assertThat(model.getAttribute("form")).isEqualTo(MessageFlowLogEntryForm.empty());
    }

    @Test
    void validPostSavesAndRedirectsToQueryPage() {
        MessageFlowLogService service = mock(MessageFlowLogService.class);
        MessageFlowLogController controller = new MessageFlowLogController(service);
        MessageFlowLogEntryForm form = new MessageFlowLogEntryForm(
                "10.10.1.20", "0200202606220001", "PAY001", "JSON",
                1782090000000L, "GLOBAL-1", "TELLER009", "{\"amount\":\"88.00\"}",
                null, "", "", ""
        );

        String view = controller.createEntry(form, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/messages/flow-logs?query=0200202606220001");
        verify(service).saveEntry(form);
    }

    @Test
    void invalidPostReturnsFormWithError() {
        MessageFlowLogService service = mock(MessageFlowLogService.class);
        MessageFlowLogController controller = new MessageFlowLogController(service);
        MessageFlowLogEntryForm form = MessageFlowLogEntryForm.empty();
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.createEntry(form, model);

        assertThat(view).isEqualTo("messages/flow-log-entry");
        assertThat(model.getAttribute("active")).isEqualTo("message-flow-log-entry");
        assertThat(model.getAttribute("form")).isEqualTo(form);
        assertThat(model.getAttribute("error")).isEqualTo("来源IP、流水号、交易码、请求时间、请求报文不能为空");
        verifyNoInteractions(service);
    }
}

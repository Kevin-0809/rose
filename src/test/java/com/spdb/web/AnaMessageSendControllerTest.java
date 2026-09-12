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

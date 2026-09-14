package com.spdb.web;

import com.spdb.replay.*;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReplayVolumeCheckControllerTest {
    private final ReplayVolumeCheckBatch batch = new ReplayVolumeCheckBatch(
            7L, ReplayVolumeCheckBatchStatus.WAITING_CONFIRM, LocalDateTime.now(), 100, 30, LocalDate.of(2026, 9, 1),
            2, 1, 1, 3, 0, null, LocalDateTime.now(), null, LocalDateTime.now(), null);

    @Test
    void pageLoadsLatestBatchAndHistory() {
        ReplayVolumeCheckService service = mock(ReplayVolumeCheckService.class);
        var history = PagedResult.of(List.of(batch), 1, PageRequestParams.of(1, 20));
        when(service.history(PageRequestParams.of(1, 20))).thenReturn(history);
        when(service.details(eq(7L), any())).thenReturn(PagedResult.of(List.of(), 0, PageRequestParams.of(1, 20)));
        when(service.cleanupDetails(eq(7L), any())).thenReturn(PagedResult.of(List.of(), 0, PageRequestParams.of(1, 20)));
        var controller = new ReplayVolumeCheckController(service);
        var model = new ExtendedModelMap();

        assertThat(controller.page(1, 20, model)).isEqualTo("config/replay-volume-check");
        assertThat(model.getAttribute("active")).isEqualTo("replay-volume-check");
        assertThat(model.getAttribute("currentBatch")).isEqualTo(batch);
        verify(service).history(PageRequestParams.of(1, 20));
    }

    @Test
    void pageLoadsStatusSpecificPagers() {
        ReplayVolumeCheckService service = mock(ReplayVolumeCheckService.class);
        when(service.history(any())).thenReturn(PagedResult.of(List.of(batch), 1, PageRequestParams.of(1, 20)));
        when(service.latest()).thenReturn(new ReplayVolumeCheckResult(batch, List.of(), List.of()));
        when(service.details(eq(7L), eq(ReplayVolumeCheckDetailStatus.NO_VOLUME), any())).thenReturn(PagedResult.of(List.of(), 0, PageRequestParams.of(1, 20)));
        when(service.details(eq(7L), eq(ReplayVolumeCheckDetailStatus.NO_MAPPING), any())).thenReturn(PagedResult.of(List.of(), 0, PageRequestParams.of(1, 20)));
        when(service.cleanupDetails(eq(7L), any())).thenReturn(PagedResult.of(List.of(), 0, PageRequestParams.of(1, 20)));
        var controller = new ReplayVolumeCheckController(service);
        controller.page(1, 20, new ExtendedModelMap());
        verify(service).details(eq(7L), eq(ReplayVolumeCheckDetailStatus.NO_VOLUME), any());
        verify(service).details(eq(7L), eq(ReplayVolumeCheckDetailStatus.NO_MAPPING), any());
    }

    @Test
    void startAndConfirmRedirectToDetail() {
        ReplayVolumeCheckService service = mock(ReplayVolumeCheckService.class);
        LocalDate baseDate = LocalDate.of(2026, 9, 1);
        when(service.check(100, 30, baseDate)).thenReturn(new ReplayVolumeCheckResult(batch, List.of(), List.of()));
        when(service.confirm(7L)).thenReturn(new ReplayVolumeCheckResult(batch, List.of(), List.of()));
        var controller = new ReplayVolumeCheckController(service);
        var model = new ExtendedModelMap();

        assertThat(controller.start(100, 30, "2026-09-01", model)).isEqualTo("redirect:/config/replay-volume-check/7");
        assertThat(controller.confirm(7L)).isEqualTo("redirect:/config/replay-volume-check/7");
        verify(service).check(100, 30, baseDate);
        verify(service).confirm(7L);
    }

    @Test
    void startRejectsInvalidBaseDateWithoutCallingService() {
        ReplayVolumeCheckService service = mock(ReplayVolumeCheckService.class);
        var controller = new ReplayVolumeCheckController(service);
        var model = new ExtendedModelMap();

        assertThat(controller.start(100, 30, "2026-13-40", model)).isEqualTo("config/replay-volume-check");
        assertThat(model.getAttribute("error")).isEqualTo("日期格式不正确，请使用 yyyy-MM-dd");
        assertThat(model.getAttribute("baseDate")).isEqualTo("2026-13-40");
        verifyNoInteractions(service);
    }

    @Test
    void startRejectsBlankBaseDateWithoutCallingService() {
        ReplayVolumeCheckService service = mock(ReplayVolumeCheckService.class);
        var controller = new ReplayVolumeCheckController(service);
        var model = new ExtendedModelMap();

        assertThat(controller.start(100, 30, "  ", model)).isEqualTo("config/replay-volume-check");
        assertThat(model.getAttribute("error")).isEqualTo("请填写铺底日期");
        verifyNoInteractions(service);
    }

    @Test
    void detailRefreshesAndLoadsBothPagedCollections() {
        ReplayVolumeCheckService service = mock(ReplayVolumeCheckService.class);
        when(service.refresh(7L)).thenReturn(new ReplayVolumeCheckResult(batch, List.of(), List.of()));
        when(service.details(eq(7L), eq(PageRequestParams.of(2, 50))))
                .thenReturn(PagedResult.of(List.of(), 0, PageRequestParams.of(2, 50)));
        when(service.cleanupDetails(eq(7L), eq(PageRequestParams.of(3, 50))))
                .thenReturn(PagedResult.of(List.of(), 0, PageRequestParams.of(3, 50)));
        var controller = new ReplayVolumeCheckController(service);
        var model = new ExtendedModelMap();

        assertThat(controller.detail(7L, 2, 50, 3, 50, model)).isEqualTo("config/replay-volume-check-detail");
        assertThat(model.getAttribute("batch")).isEqualTo(batch);
        verify(service).refresh(7L);
        verify(service).details(7L, PageRequestParams.of(2, 50));
        verify(service).cleanupDetails(7L, PageRequestParams.of(3, 50));
    }
}

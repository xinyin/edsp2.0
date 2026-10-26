package com.jsnx.edsp.service;

import com.jsnx.edsp.model.ApiModels.MonitorPoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MonitorPointsTest {
    @Test void fillsTwentyFourHoursAcrossMidnightWithoutInventingCalls() {
        List<MonitorPoint> points = InterfaceService.fillMonitorHours(
                LocalDateTime.of(2026, 10, 26, 1, 0), List.of(Map.of(
                        "hour_bucket", "2026-10-25 23:00:00", "calls", 3L,
                        "success_calls", 2L, "error_calls", 1L,
                        "avg_duration_ms", 120L, "avg_upstream_duration_ms", 80L)));

        assertThat(points).hasSize(24);
        assertThat(points.get(0).timeLabel()).isEqualTo("02:00");
        assertThat(points.get(21)).isEqualTo(new MonitorPoint("23:00", 3, 2, 1, 120, 80));
        assertThat(points.get(22)).isEqualTo(new MonitorPoint("00:00", 0, 0, 0, 0, 0));
        assertThat(points.get(23).timeLabel()).isEqualTo("01:00");
        assertThat(points.stream().mapToLong(MonitorPoint::calls).sum()).isEqualTo(3);
    }
}

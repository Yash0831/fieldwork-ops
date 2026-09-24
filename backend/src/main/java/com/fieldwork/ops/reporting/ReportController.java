package com.fieldwork.ops.reporting;

import com.fieldwork.ops.reporting.dto.DashboardSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops dashboard reads. Thin by design: {@link ReportingService} owns
 * the aggregation queries.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class ReportController {

    private final ReportingService reportingService;

    @GetMapping("/summary")
    public DashboardSummaryResponse summary() {
        return reportingService.dashboardSummary();
    }
}

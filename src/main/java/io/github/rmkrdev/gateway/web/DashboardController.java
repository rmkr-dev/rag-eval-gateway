package io.github.rmkrdev.gateway.web;

import io.github.rmkrdev.gateway.report.Bucket;
import io.github.rmkrdev.gateway.report.InvalidReportException;
import io.github.rmkrdev.gateway.report.ReportQuery;
import io.github.rmkrdev.gateway.report.ReportRequests;
import io.github.rmkrdev.gateway.report.ReportService;
import io.github.rmkrdev.gateway.report.Reports;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Server-rendered dashboard of cost, tokens and latency by client, model and provider. Plain
 * HTML and inline CSS and SVG, no scripts. Not authenticated until Entra arrives.
 */
@Controller
@ConditionalOnBooleanProperty(name = "gateway.tracking.enabled", matchIfMissing = true)
@ConditionalOnBooleanProperty("gateway.reports.enabled")
@ConditionalOnBooleanProperty("gateway.reports.dashboard.enabled")
class DashboardController {

    static final List<String> RANGES = List.of("1h", "6h", "24h", "7d", "30d");

    private final ReportService reports;
    private final ReportRequests requests;

    DashboardController(ReportService reports, ReportRequests requests) {
        this.reports = reports;
        this.requests = requests;
    }

    @GetMapping("/dashboard")
    String dashboard(@RequestParam(defaultValue = "24h") String range,
            @RequestParam(defaultValue = "UTC") String zone, Model model, HttpServletResponse response) {
        model.addAttribute("ranges", RANGES);
        model.addAttribute("range", range);
        model.addAttribute("zone", zone);
        try {
            ReportQuery query = requests.parse(null, null, range, zone, null, null, null);
            Reports.Overview overview = reports.overview(query, Bucket.forRange(query.range()));
            model.addAttribute("view", DashboardView.of(overview));
        } catch (InvalidReportException e) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("error", e.getMessage());
        }
        return "dashboard";
    }
}

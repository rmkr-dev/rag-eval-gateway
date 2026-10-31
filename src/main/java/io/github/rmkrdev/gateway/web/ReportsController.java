package io.github.rmkrdev.gateway.web;

import io.github.rmkrdev.gateway.auth.GatewayRoles;
import io.github.rmkrdev.gateway.report.Bucket;
import io.github.rmkrdev.gateway.report.GroupBy;
import io.github.rmkrdev.gateway.report.ReportQuery;
import io.github.rmkrdev.gateway.report.ReportRequests;
import io.github.rmkrdev.gateway.report.ReportService;
import io.github.rmkrdev.gateway.report.Reports;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cost, token and latency reports over stored calls, for callers with
 * {@link GatewayRoles#REPORTS}.
 */
@RestController
@RequestMapping("/v1/reports")
@ConditionalOnBooleanProperty(name = "gateway.tracking.enabled", matchIfMissing = true)
@ConditionalOnBooleanProperty(name = "gateway.reports.enabled", matchIfMissing = true)
@Secured(GatewayRoles.REPORTS)
class ReportsController {

    private final ReportService reports;
    private final ReportRequests requests;

    ReportsController(ReportService reports, ReportRequests requests) {
        this.reports = reports;
        this.requests = requests;
    }

    @GetMapping("/summary")
    Reports.Summary summary(
            @RequestParam(name = "group_by", defaultValue = "model") String groupBy,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String zone,
            @RequestParam(required = false) String client,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String provider) {
        GroupBy dimension = GroupBy.parse(groupBy);
        ReportQuery query = requests.parse(from, to, range, zone, client, model, provider);
        return reports.summary(query, dimension);
    }

    @GetMapping("/series")
    Reports.Series series(
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String zone,
            @RequestParam(required = false) String client,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String provider) {
        ReportQuery query = requests.parse(from, to, range, zone, client, model, provider);
        Bucket step = bucket == null ? Bucket.forRange(query.range()) : Bucket.parse(bucket);
        return reports.series(query, step);
    }
}

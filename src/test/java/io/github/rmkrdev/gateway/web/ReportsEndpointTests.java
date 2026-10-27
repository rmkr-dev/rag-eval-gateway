package io.github.rmkrdev.gateway.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.rmkrdev.gateway.report.ReportFixtures;
import io.github.rmkrdev.gateway.tracking.CallStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reports;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gateway.tracking.retention=0",
        "gateway.reports.max-rows=100"
})
@AutoConfigureMockMvc
class ReportsEndpointTests {

    private static final String FROM = "2026-10-26T00:00:00Z";
    private static final String TO = "2026-10-27T00:00:00Z";

    @Autowired
    MockMvc mvc;

    @Autowired
    CallStore store;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM gateway_call");
        store.insertAll(ReportFixtures.calls());
    }

    @Test
    void summarisesByClientBusiestFirst() throws Exception {
        mvc.perform(get("/v1/reports/summary").param("group_by", "client").param("from", FROM).param("to", TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group_by").value("client"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.from").value(FROM))
                .andExpect(jsonPath("$.totals.calls").value(15))
                .andExpect(jsonPath("$.totals.errors").value(1))
                .andExpect(jsonPath("$.totals.cost").value(0.1))
                .andExpect(jsonPath("$.totals.unpriced_calls").value(4))
                .andExpect(jsonPath("$.groups", hasSize(2)))
                .andExpect(jsonPath("$.groups[0].key").value("billing-api"))
                .andExpect(jsonPath("$.groups[0].calls").value(10))
                .andExpect(jsonPath("$.groups[0].prompt_tokens").value(10_000))
                .andExpect(jsonPath("$.groups[0].completion_tokens").value(2_000))
                .andExpect(jsonPath("$.groups[0].cache_read_tokens").value(1_000))
                .andExpect(jsonPath("$.groups[0].total_ms.p50").value(500))
                .andExpect(jsonPath("$.groups[0].total_ms.p95").value(1000))
                .andExpect(jsonPath("$.groups[0].ttft_ms.p99").value(100))
                .andExpect(jsonPath("$.groups[1].key").value("search"))
                .andExpect(jsonPath("$.groups[1].errors").value(1))
                .andExpect(jsonPath("$.groups[1].total_ms.p99").value(50))
                .andExpect(jsonPath("$.groups[1].ttft_ms").value(nullValue()));
    }

    @Test
    void groupsFailedCallsWithoutAProviderUnderNull() throws Exception {
        mvc.perform(get("/v1/reports/summary").param("group_by", "provider").param("from", FROM).param("to", TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups", hasSize(3)))
                .andExpect(jsonPath("$.groups[0].key").value("openai"))
                .andExpect(jsonPath("$.groups[1].key").value("local"))
                .andExpect(jsonPath("$.groups[2].key").value(nullValue()))
                .andExpect(jsonPath("$.groups[2].errors").value(1));
    }

    @Test
    void filtersNarrowTheCalls() throws Exception {
        mvc.perform(get("/v1/reports/summary").param("group_by", "model").param("from", FROM).param("to", TO)
                        .param("client", "search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.calls").value(5))
                .andExpect(jsonPath("$.groups", hasSize(1)))
                .andExpect(jsonPath("$.groups[0].key").value("embed"));
    }

    @Test
    void seriesHasEveryHourBucketInOrder() throws Exception {
        mvc.perform(get("/v1/reports/series").param("bucket", "hour").param("from", FROM).param("to", TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucket").value("hour"))
                .andExpect(jsonPath("$.points", hasSize(24)))
                .andExpect(jsonPath("$.points[0].start").value("2026-10-26T00:00:00Z"))
                .andExpect(jsonPath("$.points[0].calls").value(0))
                .andExpect(jsonPath("$.points[9].calls").value(10))
                .andExpect(jsonPath("$.points[9].cost").value(0.1))
                .andExpect(jsonPath("$.points[10].calls").value(5))
                .andExpect(jsonPath("$.points[10].errors").value(1));
    }

    @Test
    void dayBucketsFollowTheZone() throws Exception {
        mvc.perform(get("/v1/reports/series").param("bucket", "day").param("zone", "Asia/Kolkata")
                        .param("from", "2026-10-26").param("to", "2026-10-28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.points", hasSize(2)))
                .andExpect(jsonPath("$.points[0].start").value("2026-10-26T00:00:00+05:30"))
                // 26 Oct in Kolkata starts at 18:30 UTC on the 25th, so the late call on the 25th counts
                .andExpect(jsonPath("$.points[0].calls").value(16));
    }

    @Test
    void badInputIsA400Problem() throws Exception {
        mvc.perform(get("/v1/reports/summary").param("group_by", "region"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid report request"))
                .andExpect(jsonPath("$.detail").value("group_by must be one of client, model, provider"));
        mvc.perform(get("/v1/reports/summary").param("from", TO).param("to", FROM))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("from must be before to"));
        mvc.perform(get("/v1/reports/series").param("bucket", "week"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/reports/series").param("range", "45d"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("gateway.reports.max-range")));
    }

    @Test
    void refusesRangesWithMoreCallsThanMaxRows() throws Exception {
        java.util.List<io.github.rmkrdev.gateway.tracking.CallRecord> many = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            many.add(ReportFixtures.call("gw-many-" + i, ReportFixtures.DAY.plusSeconds(3600 * 12 + i), "load",
                    "chat", "openai", "ok", 1, 1, null, null, 1L, 1L));
        }
        store.insertAll(many);

        mvc.perform(get("/v1/reports/summary").param("from", FROM).param("to", TO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("gateway.reports.max-rows")));
    }

    @Test
    void dashboardRendersTotalsAndTablesWithoutScripts() throws Exception {
        mvc.perform(get("/dashboard").param("range", "30d"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("By client")))
                .andExpect(content().string(containsString("By provider")))
                .andExpect(content().string(containsString("<svg")))
                .andExpect(content().string(containsString("No authentication yet")))
                .andExpect(content().string(not(containsString("<script"))))
                .andExpect(content().string(not(containsString("http://"))));
    }

    @Test
    void dashboardShowsBadInputAsA400Page() throws Exception {
        mvc.perform(get("/dashboard").param("range", "90d"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("longer than the maximum")));
    }
}

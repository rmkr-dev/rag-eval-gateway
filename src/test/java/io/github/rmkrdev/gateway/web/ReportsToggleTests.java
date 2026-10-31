package io.github.rmkrdev.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.rmkrdev.gateway.report.ReportService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/** Which report endpoints are served. Who may call them is covered in {@code AuthEndpointTests}. */
class ReportsToggleTests {

    @Nested
    @SpringBootTest(properties = "entra.security.enabled=false")
    @AutoConfigureMockMvc
    class OnByDefault {

        @Autowired
        MockMvc mvc;

        @Autowired
        ApplicationContext context;

        @Test
        void theApiAndTheDashboardAreServed() throws Exception {
            assertThat(context.getBeanNamesForType(ReportService.class)).hasSize(1);
            mvc.perform(get("/v1/reports/summary")).andExpect(status().isOk());
            mvc.perform(get("/dashboard")).andExpect(status().isOk());
        }
    }

    @Nested
    @SpringBootTest(properties = {"entra.security.enabled=false", "gateway.reports.dashboard.enabled=false"})
    @AutoConfigureMockMvc
    class DashboardOff {

        @Autowired
        MockMvc mvc;

        @Test
        void onlyTheApiIsServed() throws Exception {
            mvc.perform(get("/v1/reports/summary")).andExpect(status().isOk());
            mvc.perform(get("/dashboard")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @SpringBootTest(properties = {"entra.security.enabled=false", "gateway.reports.enabled=false"})
    @AutoConfigureMockMvc
    class ReportsOff {

        @Autowired
        MockMvc mvc;

        @Autowired
        ApplicationContext context;

        @Test
        void theDashboardNeedsTheApi() throws Exception {
            assertThat(context.getBeanNamesForType(ReportService.class)).isEmpty();
            mvc.perform(get("/v1/reports/summary")).andExpect(status().isNotFound());
            mvc.perform(get("/dashboard")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @SpringBootTest(properties = {"entra.security.enabled=false", "gateway.tracking.enabled=false"})
    @AutoConfigureMockMvc
    class TrackingOff {

        @Autowired
        MockMvc mvc;

        @Test
        void reportsNeedTracking() throws Exception {
            mvc.perform(get("/v1/reports/summary")).andExpect(status().isNotFound());
            mvc.perform(get("/dashboard")).andExpect(status().isNotFound());
        }
    }
}

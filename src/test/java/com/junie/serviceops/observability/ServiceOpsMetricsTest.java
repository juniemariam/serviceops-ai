package com.junie.serviceops.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Prometheus alert rules in observability/prometheus/rules reference these meter names
 * and tag values as literal strings. Renaming one silently breaks an alert — nothing else in
 * the build connects the two — so the contract is pinned here.
 */
class ServiceOpsMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final ServiceOpsMetrics metrics = new ServiceOpsMetrics(registry);

    @Test
    void classificationIsTaggedByModelVersionSoFallbackIsAlertable() {
        metrics.classified("tfidf-logistic-v1");
        metrics.classified("java-fallback-v1");
        metrics.classified("java-fallback-v1");

        assertEquals(1.0, registry.get("serviceops.classifications")
                .tag("model_version", "tfidf-logistic-v1").counter().count());
        assertEquals(2.0, registry.get("serviceops.classifications")
                .tag("model_version", "java-fallback-v1").counter().count());
    }

    @Test
    void retrievalIsTaggedByModeSoKeywordFallbackIsAlertable() {
        metrics.retrieved(ServiceOpsMetrics.MODE_VECTOR, 3);
        metrics.retrieved(ServiceOpsMetrics.MODE_KEYWORD, 1);

        assertEquals(1.0, registry.get("serviceops.retrievals").tag("mode", "vector").counter().count());
        assertEquals(1.0, registry.get("serviceops.retrievals").tag("mode", "keyword").counter().count());
        assertEquals(2, registry.get("serviceops.retrieval.results").summary().count());
    }

    @Test
    void diagnosisCorrelationIsTaggedAsABooleanString() {
        metrics.diagnosed(true);
        metrics.diagnosed(false);

        assertEquals(1.0, registry.get("serviceops.diagnoses")
                .tag("deployment_correlated", "true").counter().count());
        assertEquals(1.0, registry.get("serviceops.diagnoses")
                .tag("deployment_correlated", "false").counter().count());
    }

    @Test
    void ingestionAndRemediationOutcomesUseTheDocumentedTagValues() {
        metrics.alertIngested("opened");
        metrics.alertIngested("collapsed");
        metrics.deploymentIngested("recorded");
        metrics.deploymentIngested("duplicate");
        metrics.remediation("EXECUTED_SANDBOX");
        metrics.remediation("REJECTED");

        assertNotNull(registry.get("serviceops.alerts.ingested").tag("outcome", "opened").counter());
        assertNotNull(registry.get("serviceops.alerts.ingested").tag("outcome", "collapsed").counter());
        assertNotNull(registry.get("serviceops.deployments.ingested").tag("outcome", "recorded").counter());
        assertNotNull(registry.get("serviceops.deployments.ingested").tag("outcome", "duplicate").counter());
        assertNotNull(registry.get("serviceops.remediations").tag("status", "EXECUTED_SANDBOX").counter());
        assertNotNull(registry.get("serviceops.remediations").tag("status", "REJECTED").counter());
    }

    @Test
    void incidentSourceNeverProducesANullTagValue() {
        metrics.incidentOpened(null);

        assertEquals(1.0, registry.get("serviceops.incidents.opened")
                .tag("source", "unknown").counter().count());
    }

    /**
     * Asserts the names as Prometheus actually exports them, not the Micrometer ids. The two
     * differ: the client appends {@code _total} to counters and strips reserved suffixes such
     * as {@code _created}. Asserting the Micrometer id alone let a dashboard query reference a
     * series that was never exported.
     */
    @Test
    void exportedPrometheusNamesMatchWhatTheRulesAndDashboardQuery() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ServiceOpsMetrics exported = new ServiceOpsMetrics(prometheus);

        exported.incidentOpened("API");
        exported.classified("java-fallback-v1");
        exported.retrieved(ServiceOpsMetrics.MODE_KEYWORD, 1);
        exported.diagnosed(true);
        exported.alertIngested("opened");
        exported.deploymentIngested("recorded");
        exported.remediation("REJECTED");
        exported.stopDiagnosis(exported.startDiagnosis());

        String scrape = prometheus.scrape();
        List<String> required = List.of(
                "serviceops_incidents_opened_total",
                "serviceops_classifications_total",
                "serviceops_retrievals_total",
                "serviceops_diagnoses_total",
                "serviceops_alerts_ingested_total",
                "serviceops_deployments_ingested_total",
                "serviceops_remediations_total",
                "serviceops_diagnosis_duration_seconds_bucket");
        for (String name : required) {
            assertTrue(scrape.contains(name), "Prometheus scrape is missing " + name);
        }
        assertTrue(scrape.contains("model_version=\"java-fallback-v1\""));
        assertTrue(scrape.contains("mode=\"keyword\""));
        assertTrue(scrape.contains("deployment_correlated=\"true\""));
    }

    @Test
    void diagnosisDurationIsRecordedAsATimer() {
        metrics.stopDiagnosis(metrics.startDiagnosis());

        assertEquals(1, registry.get("serviceops.diagnosis.duration").timer().count());
    }
}

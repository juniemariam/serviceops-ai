package com.junie.serviceops.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Domain metrics for the incident pipeline.
 *
 * <p>The degradation counters exist because both external dependencies fail silently by
 * design: an unreachable classifier falls back to a keyword heuristic, and an unreachable
 * embedding service falls back to full-text retrieval. Both keep serving traffic, so without
 * these meters a total outage of the model service looks identical to healthy operation from
 * the outside. {@code model_version} and {@code mode} are what make that visible and
 * alertable.
 *
 * <p>Tag values are all bounded sets — never an incident id, service name from a payload, or
 * anything else caller-controlled — because unbounded label cardinality is what kills a
 * Prometheus instance.
 */
@Component
public class ServiceOpsMetrics {

    public static final String MODE_VECTOR = "vector";
    public static final String MODE_KEYWORD = "keyword";

    private final MeterRegistry registry;
    private final Timer diagnosisTimer;

    public ServiceOpsMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.diagnosisTimer = Timer.builder("serviceops.diagnosis.duration")
                .description("Time to produce a diagnosis, including retrieval and correlation")
                .publishPercentileHistogram()
                .register(registry);
    }

    /**
     * @param source {@code API} or {@code ALERT}
     *
     * <p>Named "opened", not "created": {@code _created} is a reserved suffix in the Prometheus
     * client, which strips it, so {@code serviceops.incidents.created} would export as
     * {@code serviceops_incidents_total} and quietly not match any query written against the
     * obvious name.
     */
    public void incidentOpened(String source) {
        counter("serviceops.incidents.opened", "source", source).increment();
    }

    /** @param modelVersion the model that produced the label; the fallback has its own value */
    public void classified(String modelVersion) {
        counter("serviceops.classifications", "model_version", modelVersion).increment();
    }

    /** @param mode {@link #MODE_VECTOR} or {@link #MODE_KEYWORD} */
    public void retrieved(String mode, int results) {
        counter("serviceops.retrievals", "mode", mode).increment();
        registry.summary("serviceops.retrieval.results").record(results);
    }

    /** @param correlated whether a deployment explained the incident */
    public void diagnosed(boolean correlated) {
        counter("serviceops.diagnoses", "deployment_correlated", String.valueOf(correlated)).increment();
    }

    /** @param outcome {@code opened}, {@code collapsed}, {@code resolved} or {@code ignored} */
    public void alertIngested(String outcome) {
        counter("serviceops.alerts.ingested", "outcome", outcome).increment();
    }

    /** @param outcome {@code recorded} or {@code duplicate} */
    public void deploymentIngested(String outcome) {
        counter("serviceops.deployments.ingested", "outcome", outcome).increment();
    }

    /** @param status {@code EXECUTED_SANDBOX} or {@code REJECTED} */
    public void remediation(String status) {
        counter("serviceops.remediations", "status", status).increment();
    }

    public Timer.Sample startDiagnosis() {
        return Timer.start(registry);
    }

    public void stopDiagnosis(Timer.Sample sample) {
        sample.stop(diagnosisTimer);
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        return Counter.builder(name).tag(tagKey, tagValue == null ? "unknown" : tagValue).register(registry);
    }
}

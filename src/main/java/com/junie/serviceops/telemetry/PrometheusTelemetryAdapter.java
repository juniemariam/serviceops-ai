package com.junie.serviceops.telemetry;

import com.junie.serviceops.model.ServiceNode;
import com.junie.serviceops.model.ServiceTelemetry;
import com.junie.serviceops.service.ServiceCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Reads service health from the Prometheus HTTP query API.
 *
 * <p>A service is observable only if its catalog row carries a {@code telemetry_job}. For
 * anything else this reports absence rather than guessing, which matters because the
 * business services in the seed catalog are not really scraped — only ServiceOps' own
 * components are.
 */
@Component
@ConditionalOnProperty(name = "serviceops.telemetry.enabled", havingValue = "true", matchIfMissing = true)
public class PrometheusTelemetryAdapter implements TelemetryAdapter {

    private static final Logger log = LoggerFactory.getLogger(PrometheusTelemetryAdapter.class);

    private final RestClient client;
    private final ServiceCatalog catalog;
    private final String window;

    public PrometheusTelemetryAdapter(RestClient.Builder builder, ServiceCatalog catalog,
                                      @Value("${serviceops.telemetry.prometheus-url}") String url,
                                      @Value("${serviceops.telemetry.window:5m}") String window) {
        this.client = builder.clone().baseUrl(url).build();
        this.catalog = catalog;
        this.window = window;
    }

    @Override
    public ServiceTelemetry read(String service) {
        ServiceNode node = catalog.find(service);
        if (node == null) {
            return ServiceTelemetry.unavailable(service, "Not registered in the service catalog.");
        }
        if (node.telemetryJob() == null || node.telemetryJob().isBlank()) {
            return ServiceTelemetry.unavailable(service, "No telemetry job is configured for this service.");
        }
        String job = node.telemetryJob();
        Double up = scalar("max(up{job=\"%s\"})".formatted(job));
        if (up == null) {
            return ServiceTelemetry.unavailable(service,
                    "Prometheus returned no samples for job \"%s\".".formatted(job));
        }
        // `or` falls through to the right-hand expression only when the left yields no
        // samples, which lets one query cover both the Spring and the Python service without
        // the adapter needing to know which kind of service it is looking at.
        // `or vector(0)` on each numerator matters: with no failing requests the inner sum
        // yields an empty vector, not zero, so the whole division would come back empty and
        // a perfectly healthy service would be indistinguishable from one with no metrics.
        Double errorRatio = scalar("""
                ((sum(rate(http_server_requests_seconds_count{job="%1$s",outcome!="SUCCESS"}[%2$s])) or vector(0))
                  / clamp_min(sum(rate(http_server_requests_seconds_count{job="%1$s"}[%2$s])), 0.0001))
                or
                ((sum(rate(classifier_requests_total{job="%1$s",outcome="error"}[%2$s])) or vector(0))
                  / clamp_min(sum(rate(classifier_requests_total{job="%1$s"}[%2$s])), 0.0001))
                """.formatted(job, window));
        Double p95 = scalar("""
                histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{job="%1$s"}[%2$s])) by (le))
                or
                histogram_quantile(0.95, sum(rate(classifier_request_duration_seconds_bucket{job="%1$s"}[%2$s])) by (le))
                """.formatted(job, window));
        return new ServiceTelemetry(service, true, up >= 1.0, errorRatio, p95,
                "Prometheus job \"%s\" over %s.".formatted(job, window));
    }

    /** Returns the single scalar an instant query produced, or null if there was none. */
    private Double scalar(String query) {
        try {
            // The query goes in as a URI template *variable*, not as literal text. PromQL
            // label matchers contain braces, and UriComponentsBuilder would otherwise read
            // `{job="serviceops-api"}` as a template placeholder and fail to expand it,
            // which silently disables every telemetry reading.
            PrometheusResponse response = client.get()
                    .uri(uri -> uri.path("/api/v1/query").queryParam("query", "{promql}").build(query))
                    .retrieve()
                    .body(PrometheusResponse.class);
            if (response == null || !"success".equals(response.status()) || response.data() == null) {
                return null;
            }
            List<PrometheusResult> results = response.data().result();
            if (results == null || results.isEmpty() || results.get(0).value() == null
                    || results.get(0).value().size() < 2) {
                return null;
            }
            String raw = String.valueOf(results.get(0).value().get(1));
            // Prometheus serialises NaN as the string "NaN"; a histogram with no observations
            // produces it, and parsing it as a number would report nonsense latency.
            if ("NaN".equals(raw) || "+Inf".equals(raw) || "-Inf".equals(raw)) {
                return null;
            }
            return Double.valueOf(raw);
        } catch (Exception e) {
            log.warn("Prometheus query failed: {}", e.getMessage());
            return null;
        }
    }

    record PrometheusResponse(String status, PrometheusData data) {}

    record PrometheusData(String resultType, List<PrometheusResult> result) {}

    record PrometheusResult(Map<String, String> metric, List<Object> value) {}
}

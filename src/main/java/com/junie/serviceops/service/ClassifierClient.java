package com.junie.serviceops.service;

import com.junie.serviceops.model.Classification;
import com.junie.serviceops.model.Incident;
import com.junie.serviceops.observability.ServiceOpsMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ClassifierClient {

    private static final Logger log = LoggerFactory.getLogger(ClassifierClient.class);

    private final RestClient client;
    private final ServiceOpsMetrics metrics;

    /**
     * Takes the auto-configured builder so the request factory and timeouts from
     * {@code spring.http.client.*} apply. Building a bare {@code RestClient.builder()} here
     * would bypass both.
     */
    public ClassifierClient(RestClient.Builder builder, @Value("${serviceops.classifier-url}") String url,
                            ServiceOpsMetrics metrics) {
        this.client = builder.clone().baseUrl(url).build();
        this.metrics = metrics;
    }

    /**
     * Classifies via the model service, degrading to a keyword heuristic when it is
     * unreachable. The returned {@code modelVersion} says which path produced the result, so
     * a silent degradation is visible in the stored incident rather than invisible.
     */
    public Classification classify(Incident incident) {
        try {
            Classification result = client.post()
                    .uri("/predict")
                    .body(incident)
                    .retrieve()
                    .body(Classification.class);
            if (result != null) {
                metrics.classified(result.modelVersion());
                return result;
            }
            log.warn("Classifier returned an empty body; using the local heuristic");
        } catch (Exception e) {
            log.warn("Classifier unavailable, using the local heuristic: {}", e.getMessage());
        }
        Classification fallback = heuristic(incident);
        metrics.classified(fallback.modelVersion());
        return fallback;
    }

    private static Classification heuristic(Incident incident) {
        String text = (incident.title() + " " + incident.description()).toLowerCase();
        String category = text.contains("latency") || text.contains("timeout") ? "PERFORMANCE"
                : text.contains("login") || text.contains("auth") ? "SECURITY"
                : "AVAILABILITY";
        String priority = text.contains("payment") || text.contains("outage") ? "P1" : "P2";
        String group = category.equals("SECURITY") ? "identity-platform" : "platform-reliability";
        return new Classification(category, priority, group, 0.72, "java-fallback-v1");
    }
}

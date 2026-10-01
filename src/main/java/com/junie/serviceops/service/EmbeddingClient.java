package com.junie.serviceops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Wraps the classifier service's stateless /embed endpoint.
 *
 * <p>Every method returns an empty list when the classifier is unreachable rather than
 * throwing, so retrieval can degrade to keyword search instead of failing a diagnosis.
 */
@Service
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    /** Must match the VECTOR(n) column width in V1__init.sql. */
    public static final int DIMENSION = 256;

    private final RestClient client;

    /**
     * Takes the auto-configured builder so the request factory and timeouts from
     * {@code spring.http.client.*} apply. Building a bare {@code RestClient.builder()} here
     * would bypass both.
     */
    public EmbeddingClient(RestClient.Builder builder, @Value("${serviceops.classifier-url}") String url) {
        this.client = builder.clone().baseUrl(url).build();
    }

    public record EmbedRequest(List<String> texts) {}

    public record EmbedResponse(String model, int dimension, List<List<Double>> vectors) {}

    /** Returns one vector per input text, or an empty list if embedding failed. */
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        try {
            EmbedResponse response = client.post()
                    .uri("/embed")
                    .body(new EmbedRequest(texts))
                    .retrieve()
                    .body(EmbedResponse.class);
            if (response == null || response.vectors() == null) {
                log.warn("Embedding service returned no vectors for {} texts", texts.size());
                return List.of();
            }
            if (response.dimension() != DIMENSION) {
                log.error("Embedding service reports dimension {} but the schema expects {}; ignoring response",
                        response.dimension(), DIMENSION);
                return List.of();
            }
            return response.vectors().stream().map(EmbeddingClient::toFloatArray).toList();
        } catch (Exception e) {
            log.warn("Embedding service unavailable, falling back to keyword retrieval: {}", e.getMessage());
            return List.of();
        }
    }

    /** Returns the embedding for a single text, or null if embedding failed. */
    public float[] embedOne(String text) {
        List<float[]> vectors = embed(List.of(text));
        return vectors.isEmpty() ? null : vectors.get(0);
    }

    private static float[] toFloatArray(List<Double> values) {
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i).floatValue();
        }
        return vector;
    }

    /**
     * Formats a vector as a pgvector literal. Bind the result to a {@code ?::vector}
     * placeholder; the JDBC driver has no native pgvector type.
     */
    public static String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 8 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }
}

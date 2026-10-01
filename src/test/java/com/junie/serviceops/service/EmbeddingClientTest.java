package com.junie.serviceops.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingClientTest {

    @Test
    void formatsVectorsAsPgvectorLiterals() {
        assertEquals("[0.0,0.5,-1.25]", EmbeddingClient.toVectorLiteral(new float[] {0.0f, 0.5f, -1.25f}));
    }

    @Test
    void formatsAnEmptyVectorAsAnEmptyLiteral() {
        assertEquals("[]", EmbeddingClient.toVectorLiteral(new float[0]));
    }

    @Test
    void degradesToNullWhenTheEmbeddingServiceIsUnreachable() {
        EmbeddingClient client = new EmbeddingClient(RestClient.builder(), "http://localhost:65530");

        assertNull(client.embedOne("payment api timeout"));
        assertTrue(client.embed(List.of("payment api timeout")).isEmpty());
    }

    @Test
    void skipsTheCallEntirelyForAnEmptyBatch() {
        EmbeddingClient client = new EmbeddingClient(RestClient.builder(), "http://localhost:65530");

        assertTrue(client.embed(List.of()).isEmpty());
    }
}

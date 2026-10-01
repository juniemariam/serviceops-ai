package com.junie.serviceops.kafka;

import com.junie.serviceops.model.AlertEvent;
import com.junie.serviceops.model.DeploymentEvent;
import com.junie.serviceops.service.AlertIngestService;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * Consumer wiring for the two ingestion topics.
 *
 * <p>Each topic gets its own container factory because the payload types differ and external
 * producers do not send Spring's {@code __TypeId__} header — the target type is pinned here
 * instead of being inferred from the record.
 */
@Configuration
@ConditionalOnProperty(name = "serviceops.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaIngestionConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaIngestionConfig.class);

    public static final String ALERTS_TOPIC = "serviceops.alerts";
    public static final String DEPLOYMENTS_TOPIC = "serviceops.deployments";
    public static final String ALERTS_DLT = ALERTS_TOPIC + ".DLT";
    public static final String DEPLOYMENTS_DLT = DEPLOYMENTS_TOPIC + ".DLT";

    private final KafkaProperties properties;

    public KafkaIngestionConfig(KafkaProperties properties) {
        this.properties = properties;
    }

    @Bean
    NewTopic alertsTopic(@Value("${serviceops.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(ALERTS_TOPIC).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic deploymentsTopic(@Value("${serviceops.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(DEPLOYMENTS_TOPIC).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic alertsDeadLetterTopic() {
        return TopicBuilder.name(ALERTS_DLT).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic deploymentsDeadLetterTopic() {
        return TopicBuilder.name(DEPLOYMENTS_DLT).partitions(1).replicas(1).build();
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, AlertEvent> alertListenerContainerFactory(
            DefaultErrorHandler errorHandler) {
        return containerFactory(AlertEvent.class, errorHandler);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, DeploymentEvent> deploymentListenerContainerFactory(
            DefaultErrorHandler errorHandler) {
        return containerFactory(DeploymentEvent.class, errorHandler);
    }

    private <T> ConcurrentKafkaListenerContainerFactory<String, T> containerFactory(
            Class<T> payloadType, DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, T> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory(payloadType));
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    private <T> ConsumerFactory<String, T> consumerFactory(Class<T> payloadType) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        JsonDeserializer<T> payloadDeserializer = new JsonDeserializer<>(payloadType);
        // External producers do not tag records with a Java type, so trust the configured one.
        payloadDeserializer.setUseTypeHeaders(false);
        payloadDeserializer.ignoreTypeHeaders();
        // Wrapping means a malformed record surfaces as a failed record the error handler can
        // route to the DLT, rather than a deserialization exception that stalls the partition.
        return new DefaultKafkaConsumerFactory<>(config,
                new ErrorHandlingDeserializer<>(new StringDeserializer()),
                new ErrorHandlingDeserializer<>(payloadDeserializer));
    }

    /**
     * Retries transient failures a few times, then parks the record on the topic's dead letter
     * topic so one bad event cannot block its partition forever. Payload problems are not
     * retried at all, because a redelivery would fail identically.
     */
    @Bean
    DefaultErrorHandler errorHandler(KafkaOperations<Object, Object> template,
                                     @Value("${serviceops.kafka.retries:2}") long retries,
                                     @Value("${serviceops.kafka.retry-interval-ms:1000}") long retryIntervalMs) {
        // The destination is pinned rather than left to the default resolver, which names the
        // topic "<topic>-dlt" and reuses the source partition number. That default would
        // publish to topics other than the ones declared above, and to a partition index the
        // single-partition dead letter topics do not have.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", 0));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(retryIntervalMs, retries));
        handler.addNotRetryableExceptions(AlertIngestService.InvalidEventException.class);
        handler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn("Retry {} for {}-{} offset {}: {}", deliveryAttempt, record.topic(),
                        record.partition(), record.offset(), exception.getMessage()));
        return handler;
    }
}

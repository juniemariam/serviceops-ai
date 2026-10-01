package com.junie.serviceops.kafka;

import com.junie.serviceops.model.AlertEvent;
import com.junie.serviceops.service.AlertIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "serviceops.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class AlertEventListener {

    private static final Logger log = LoggerFactory.getLogger(AlertEventListener.class);

    private final AlertIngestService ingest;

    public AlertEventListener(AlertIngestService ingest) {
        this.ingest = ingest;
    }

    /**
     * Exceptions are intentionally allowed to propagate: the container's error handler owns
     * retry and dead-lettering. Swallowing them here would commit the offset and lose the event.
     */
    @KafkaListener(
            topics = KafkaIngestionConfig.ALERTS_TOPIC,
            groupId = "${serviceops.kafka.group-id:serviceops-ingest}",
            containerFactory = "alertListenerContainerFactory")
    public void onAlert(AlertEvent event) {
        log.debug("Received alert {} ({})", event.fingerprint(), event.status());
        ingest.handle(event);
    }
}

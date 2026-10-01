package com.junie.serviceops.kafka;

import com.junie.serviceops.model.DeploymentEvent;
import com.junie.serviceops.service.DeploymentIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "serviceops.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class DeploymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(DeploymentEventListener.class);

    private final DeploymentIngestService ingest;

    public DeploymentEventListener(DeploymentIngestService ingest) {
        this.ingest = ingest;
    }

    @KafkaListener(
            topics = KafkaIngestionConfig.DEPLOYMENTS_TOPIC,
            groupId = "${serviceops.kafka.group-id:serviceops-ingest}",
            containerFactory = "deploymentListenerContainerFactory")
    public void onDeployment(DeploymentEvent event) {
        log.debug("Received deployment event {}", event.eventId());
        ingest.handle(event);
    }
}

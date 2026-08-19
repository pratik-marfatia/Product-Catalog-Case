package com.example.catalog.messaging;

import com.example.catalog.domain.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * Publishes ProductChanged events to Kafka after a successful upsert.
 *
 * Message key = productId (UUID string). Using the product id as the partition
 * key guarantees that all events for the same product land on the same
 * partition, preserving per-product ordering for consumers.
 *
 * Delivery guarantee: at-most-once relative to the DB commit. If the process
 * crashes after the DB transaction commits but before the Kafka ack arrives,
 * the event is silently lost. The correct fix is the Transactional Outbox
 * pattern (see architecture.md ADR-004). Acceptable for this exercise.
 */
@Component
@SuppressWarnings("null") // topic comes from @Value (non-null by contract); UUID.toString() is never null
public class ProductEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProductEventPublisher.class);

    private final KafkaTemplate<String, ProductEvent> kafkaTemplate;
    private final String topic;

    public ProductEventPublisher(KafkaTemplate<String, ProductEvent> kafkaTemplate,
                                  @Value("${kafka.topics.product-changed}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(@NonNull Product product) {
        ProductEvent event = ProductEvent.from(product);
        kafkaTemplate.send(topic, product.getId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish ProductChanged for product={} version={}",
                                product.getId(), product.getVersion(), ex);
                    } else {
                        log.info("Published ProductChanged eventId={} product={} version={} topic={}",
                                event.eventId(), product.getId(), product.getVersion(), topic);
                    }
                });
    }
}

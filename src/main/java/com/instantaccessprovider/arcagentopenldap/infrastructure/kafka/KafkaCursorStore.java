// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.infrastructure.kafka;

import com.instantaccessprovider.arcagentopenldap.domain.port.CursorStorePort;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Curseur de reprise stocké dans un topic Kafka compacté (clé = système, valeur = instant ISO-8601).
 * La lecture rejoue le topic jusqu'à sa fin et retient la dernière valeur de la clé.
 */
public class KafkaCursorStore implements CursorStorePort {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final Map<String, Object> consumerProperties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    /**
     * @param consumerProperties propriétés du consommateur (serveurs, désérialiseurs) ; l'auto-commit et le
     *                           groupe sont neutralisés ici
     */
    public KafkaCursorStore(Map<String, Object> consumerProperties, KafkaTemplate<String, String> kafkaTemplate,
                            String topic) {
        this.consumerProperties = new java.util.HashMap<>(consumerProperties);
        this.consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        this.consumerProperties.remove(ConsumerConfig.GROUP_ID_CONFIG);
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public Optional<Instant> load(String system) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties)) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic, READ_TIMEOUT);
            if (infos == null || infos.isEmpty()) {
                return Optional.empty();
            }
            List<TopicPartition> partitions = infos.stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);

            String last = null;
            long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
            while (partitions.stream().anyMatch(p -> consumer.position(p) < ends.get(p))) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("lecture du curseur trop longue: topic=" + topic);
                }
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                    if (system.equals(record.key())) {
                        last = record.value();
                    }
                }
            }
            return Optional.ofNullable(last).map(Instant::parse);
        }
    }

    @Override
    public void save(String system, Instant cursor) {
        try {
            kafkaTemplate.send(topic, system, cursor.toString()).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("enregistrement du curseur interrompu", e);
        } catch (Exception e) {
            throw new IllegalStateException("enregistrement du curseur impossible: topic=" + topic, e);
        }
    }
}

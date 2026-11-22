// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.infrastructure.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instantaccessprovider.arcagentopenldap.domain.model.AttributeChange;
import com.instantaccessprovider.arcagentopenldap.domain.model.LdapChange;
import com.instantaccessprovider.arcagentopenldap.domain.port.RawEventPublisherPort;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Publie les changements sur le topic brut {@code iam.raw.ldap}. La clé du message est l'{@code entryUUID}
 * : tous les changements d'une même entrée restent ordonnés dans une partition.
 */
public class KafkaRawEventPublisher implements RawEventPublisherPort {

    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final String system;

    public KafkaRawEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                  String topic, String system) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.system = system;
    }

    @Override
    public void publish(LdapChange change) {
        try {
            String json = objectMapper.writeValueAsString(toMessage(change));
            kafkaTemplate.send(topic, change.entryUuid(), json).get(10, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialisation impossible: dn=" + change.dn(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("publication interrompue: dn=" + change.dn(), e);
        } catch (Exception e) {
            throw new IllegalStateException("publication Kafka impossible: dn=" + change.dn(), e);
        }
    }

    RawLdapMessage toMessage(LdapChange change) {
        String systemId = system.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "");
        return new RawLdapMessage(
                systemId + "_" + change.entryUuid() + "_" + ID_TIME.format(change.timestamp()),
                "ldap",
                system,
                change.type().name().toLowerCase(Locale.ROOT),
                change.dn(),
                change.previousDn(),
                change.entryUuid(),
                change.objectClasses(),
                change.timestamp(),
                change.modifiedBy(),
                change.origin(),
                change.changes().stream().map(RawLdapMessage.Change::of).toList(),
                change.entry());
    }

    /** Message JSON du topic brut (contrat décrit dans la documentation 12, section 4.5). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RawLdapMessage(
            String id,
            String source,
            String system,
            String changeType,
            String dn,
            String previousDn,
            @JsonProperty("entryUUID") String entryUuid,
            List<String> objectClasses,
            Instant timestamp,
            String modifiedBy,
            String origin,
            List<Change> changes,
            Map<String, List<String>> entry
    ) {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        record Change(
                String attribute,
                @JsonProperty("op") String operation,
                @JsonProperty("old") List<String> oldValues,
                @JsonProperty("new") List<String> newValues
        ) {
            static Change of(AttributeChange change) {
                return new Change(change.attribute(), change.operation(), change.oldValues(), change.newValues());
            }
        }
    }
}

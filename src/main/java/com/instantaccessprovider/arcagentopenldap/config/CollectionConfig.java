// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instantaccessprovider.arcagentopenldap.application.LdapCollectionService;
import com.instantaccessprovider.arcagentopenldap.domain.port.CursorStorePort;
import com.instantaccessprovider.arcagentopenldap.domain.port.LdapChangeSourcePort;
import com.instantaccessprovider.arcagentopenldap.domain.port.OwnWriteRegistryPort;
import com.instantaccessprovider.arcagentopenldap.domain.port.RawEventPublisherPort;
import com.instantaccessprovider.arcagentopenldap.infrastructure.kafka.KafkaCursorStore;
import com.instantaccessprovider.arcagentopenldap.infrastructure.kafka.KafkaRawEventPublisher;
import com.instantaccessprovider.arcagentopenldap.infrastructure.ldap.PersistentSearchChangeSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/** Assemblage du module de collecte ; actif uniquement si {@code arc.agent.collection.enabled=true}. */
@Configuration
@ConditionalOnProperty(prefix = "arc.agent.collection", name = "enabled", havingValue = "true")
public class CollectionConfig {

    @Bean
    public LdapChangeSourcePort ldapChangeSource(AgentProperties properties, CollectionProperties collection) {
        return new PersistentSearchChangeSource(properties.ldap(), collection.filter(),
                collection.timeout().toMillis());
    }

    @Bean
    public RawEventPublisherPort rawEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                                   ObjectMapper objectMapper, AgentProperties properties,
                                                   CollectionProperties collection) {
        return new KafkaRawEventPublisher(kafkaTemplate, objectMapper, collection.topic(),
                properties.targetSystem());
    }

    @Bean
    public CursorStorePort cursorStore(KafkaProperties kafkaProperties, KafkaTemplate<String, String> kafkaTemplate,
                                       CollectionProperties collection) {
        return new KafkaCursorStore(kafkaProperties.buildConsumerProperties(null), kafkaTemplate,
                collection.cursorTopic());
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public LdapCollectionService ldapCollectionService(LdapChangeSourcePort source,
                                                       RawEventPublisherPort publisher,
                                                       CursorStorePort cursorStore,
                                                       OwnWriteRegistryPort ownWrites,
                                                       AgentProperties properties,
                                                       CollectionProperties collection) {
        return new LdapCollectionService(source, publisher, cursorStore, ownWrites,
                properties.targetSystem(), collection.retryDelay());
    }
}

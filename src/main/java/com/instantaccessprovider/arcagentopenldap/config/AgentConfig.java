// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.instantaccessprovider.arcagentopenldap.domain.port.OwnWriteRegistryPort;
import com.instantaccessprovider.arcagentopenldap.infrastructure.ldap.InMemoryOwnWriteRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({AgentProperties.class, CollectionProperties.class})
public class AgentConfig {

    /** Mémoire des écritures de l'agent, consultée par la collecte pour marquer les échos. */
    @Bean
    public OwnWriteRegistryPort ownWriteRegistry(CollectionProperties collection) {
        return new InMemoryOwnWriteRegistry(collection.ownWriteTtl(), Clock.systemUTC());
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean
    public LdapContextSource ldapContextSource(AgentProperties properties) {
        AgentProperties.Ldap ldap = properties.ldap();
        LdapContextSource source = new LdapContextSource();
        source.setUrl(ldap.url());
        if (ldap.bindDn() != null && !ldap.bindDn().isBlank()) {
            source.setUserDn(ldap.bindDn());
            source.setPassword(ldap.bindPassword());
        }
        source.afterPropertiesSet();
        return source;
    }

    @Bean
    public LdapTemplate ldapTemplate(LdapContextSource ldapContextSource) {
        return new LdapTemplate(ldapContextSource);
    }
}

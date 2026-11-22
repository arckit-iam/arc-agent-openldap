// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Collecte événementielle des changements de l'annuaire.
 *
 * @param enabled      active la collecte (désactivée par défaut : l'agent ne fait alors que du provisioning)
 * @param topic        topic brut des changements
 * @param cursorTopic  topic compacté du curseur de reprise
 * @param filter       filtre LDAP des entrées suivies
 * @param retryDelay   délai avant de rouvrir le flux après une coupure
 * @param ownWriteTtl  durée pendant laquelle une écriture de l'agent est reconnue dans le flux
 * @param timeout      délai maximal d'une opération LDAP
 */
@ConfigurationProperties(prefix = "arc.agent.collection")
public record CollectionProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("iam.raw.ldap") String topic,
        @DefaultValue("iam.raw.ldap.cursor") String cursorTopic,
        @DefaultValue("(|(objectClass=inetOrgPerson)(objectClass=groupOfNames))") String filter,
        @DefaultValue("10s") Duration retryDelay,
        @DefaultValue("30s") Duration ownWriteTtl,
        @DefaultValue("30s") Duration timeout
) {}

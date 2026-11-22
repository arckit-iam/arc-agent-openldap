// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Ordre de provisioning publie par arc-publisher sur les topics {@code arc.provisioning.*}.
 * Contrat identique a celui d'arc-agent-ad.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProvisioningCommand(
        String orderId,
        @JsonProperty("eventType") String action,
        @JsonProperty("externalId") String identityId,
        @JsonProperty("displayName") String identityName,
        @JsonProperty("system") String targetSystem,
        String entitlementId,
        String entitlementName,
        String scope,
        @JsonProperty("changedAttributes") List<ChangedAttribute> attributes,
        @JsonProperty("timestamp") OffsetDateTime executeAt,
        String correlationId,
        String type,
        String accountType,
        NestedTarget identity,
        NestedTarget entitlement
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChangedAttribute(String name, Object oldValue, Object newValue) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NestedTarget(String name, String displayName, String externalId, String type, String accountType, String system) {}
}

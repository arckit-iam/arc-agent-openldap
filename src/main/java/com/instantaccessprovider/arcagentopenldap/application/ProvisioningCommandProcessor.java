// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instantaccessprovider.arcagentopenldap.config.AgentProperties;
import com.instantaccessprovider.arcagentopenldap.domain.ProvisioningCommand;
import com.instantaccessprovider.arcagentopenldap.domain.port.ProvisioningHandlerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ProvisioningCommandProcessor {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningCommandProcessor.class);

    private final ObjectMapper objectMapper;
    private final AgentProperties properties;
    private final ProvisioningHandlerPort handler;

    public ProvisioningCommandProcessor(
            ObjectMapper objectMapper,
            AgentProperties properties,
            ProvisioningHandlerPort handler
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.handler = handler;
    }

    public void process(String payload, String topic) {
        ProvisioningCommand command = readCommand(payload);
        if (command == null) {
            return;
        }

        if (!isForCurrentTarget(command.targetSystem())) {
            log.debug("Commande ignoree: targetSystem={} attendu={} topic={}",
                    command.targetSystem(), properties.targetSystem(), topic);
            return;
        }

        handler.execute(command, topic);
    }

    private ProvisioningCommand readCommand(String payload) {
        try {
            return objectMapper.readValue(payload, ProvisioningCommand.class);
        } catch (JsonProcessingException e) {
            log.warn("Payload provisioning invalide, message ignore: {}", e.getMessage());
            return null;
        }
    }

    private boolean isForCurrentTarget(String targetSystem) {
        if (targetSystem == null || properties.targetSystem() == null) {
            return false;
        }
        return targetSystem.trim().equalsIgnoreCase(properties.targetSystem().trim());
    }
}

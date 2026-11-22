// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instantaccessprovider.arcagentopenldap.config.AgentConfig;
import com.instantaccessprovider.arcagentopenldap.config.AgentProperties;
import com.instantaccessprovider.arcagentopenldap.domain.ProvisioningCommand;
import com.instantaccessprovider.arcagentopenldap.domain.port.ProvisioningHandlerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ProvisioningCommandProcessorTest {

    private ProvisioningHandlerPort handler;
    private ProvisioningCommandProcessor processor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        handler = mock(ProvisioningHandlerPort.class);
        objectMapper = new AgentConfig().objectMapper();
        AgentProperties properties = new AgentProperties(
                "ldap://LDAP01",
                new AgentProperties.Topics("arc.provisioning.identity", "arc.provisioning.assignment", "arc.provisioning.entitlement"),
                true,
                null
        );
        processor = new ProvisioningCommandProcessor(objectMapper, properties, handler);
    }

    @Test
    void shouldHandleCommandWhenTargetMatches() throws Exception {
        ProvisioningCommand command = command("job-1", "ldap://LDAP01");

        processor.process(objectMapper.writeValueAsString(command), "arc.provisioning.assignment");

        verify(handler).execute(argThat(cmd -> "job-1".equals(cmd.jobId())
                && "CREATE".equals(cmd.action())
                && "ldap://LDAP01".equals(cmd.targetSystem())), eq("arc.provisioning.assignment"));
    }

    @Test
    void shouldIgnoreCommandWhenTargetDoesNotMatch() throws Exception {
        ProvisioningCommand command = command("job-2", "ad://AD01");

        processor.process(objectMapper.writeValueAsString(command), "arc.provisioning.assignment");

        verify(handler, never()).execute(any(), any());
    }

    @Test
    void shouldIgnoreInvalidPayload() {
        processor.process("pas du json", "arc.provisioning.identity");

        verify(handler, never()).execute(any(), any());
    }

    private static ProvisioningCommand command(String jobId, String targetSystem) {
        return new ProvisioningCommand(
                jobId, "CREATE", "id-1", "jdupont", targetSystem, "ent-1", "GRP-FIN", "EMEA",
                List.of(new ProvisioningCommand.ChangedAttribute("email", null, "j@example.com")),
                OffsetDateTime.parse("2026-06-01T08:00:00+02:00"), "corr-" + jobId, null, null, null);
    }
}

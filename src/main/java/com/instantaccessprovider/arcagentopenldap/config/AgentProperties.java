// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "arc.agent")
public record AgentProperties(
        String targetSystem,
        Topics topics,
        boolean dryRun,
        Ldap ldap
) {
    public record Topics(String identity, String assignment, String entitlement) {}

    /**
     * @param usersOu                branche des identites, relative a baseDn (ex. {@code ou=Users})
     * @param groupsOu               branche des groupes, relative a baseDn (ex. {@code ou=Groups})
     * @param groupPlaceholderMember membre technique d'un groupe (groupOfNames impose au moins un member) ;
     *                               par defaut le compte de service, sinon {@code cn=arc-placeholder,<baseDn>}
     */
    public record Ldap(
            String url,
            String baseDn,
            String bindDn,
            String bindPassword,
            String usersOu,
            String groupsOu,
            String groupPlaceholderMember
    ) {}
}

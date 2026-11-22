// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "arc.agent")
public record AgentProperties(
        String targetSystem,
        Topics topics,
        boolean dryRun,
        Ldap ldap
) {
    public record Topics(String identity, String assignment, String entitlement) {}

    /**
     * @param usersOu                branche des identites par defaut (type de compte absent), relative a baseDn (ex. {@code ou=Users})
     * @param groupsOu               branche des groupes, relative a baseDn (ex. {@code ou=Groups})
     * @param groupPlaceholderMember membre technique d'un groupe (groupOfNames impose au moins un member) ;
     *                               par defaut le compte de service, sinon {@code cn=arc-placeholder,<baseDn>}
     * @param accountTypeOus         type de compte -> branche des identites, relative a baseDn
     *                               (ex. {@code standard -> ou=Staff}) ; un type non configure est refuse
     */
    public record Ldap(
            String url,
            String baseDn,
            String bindDn,
            String bindPassword,
            String usersOu,
            String groupsOu,
            String groupPlaceholderMember,
            Map<String, String> accountTypeOus
    ) {}
}

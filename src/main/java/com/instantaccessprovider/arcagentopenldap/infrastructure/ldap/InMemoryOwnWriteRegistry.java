// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.infrastructure.ldap;

import com.instantaccessprovider.arcagentopenldap.domain.model.LdapChangeType;
import com.instantaccessprovider.arcagentopenldap.domain.port.OwnWriteRegistryPort;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Registre en mémoire des écritures récentes de l'agent. Chaque écriture expire après {@code ttl} et est
 * consommée par la première notification correspondante. L'état est local au processus : un seul agent
 * actif par annuaire collecté.
 */
public class InMemoryOwnWriteRegistry implements OwnWriteRegistryPort {

    private final Duration ttl;
    private final Clock clock;
    private final ConcurrentHashMap<String, Queue<Instant>> expiries = new ConcurrentHashMap<>();

    public InMemoryOwnWriteRegistry(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public void recordWrite(String dn, LdapChangeType type) {
        expiries.computeIfAbsent(key(dn, type), k -> new ConcurrentLinkedQueue<>()).add(clock.instant().plus(ttl));
    }

    @Override
    public boolean consumeIfOwn(String dn, LdapChangeType type) {
        Queue<Instant> queue = expiries.get(key(dn, type));
        if (queue == null) {
            return false;
        }
        Instant now = clock.instant();
        Instant expiry;
        while ((expiry = queue.poll()) != null) {
            if (expiry.isAfter(now)) {
                return true;
            }
        }
        return false;
    }

    /** DN normalisé (casse et espaces entre composants) pour comparer le DN de l'agent et celui du serveur. */
    private static String key(String dn, LdapChangeType type) {
        String normalised;
        try {
            normalised = new LdapName(dn).getRdns().stream()
                    .map(Rdn::toString)
                    .map(rdn -> rdn.toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(","));
        } catch (InvalidNameException e) {
            normalised = dn.toLowerCase(Locale.ROOT);
        }
        return type + "|" + normalised;
    }
}

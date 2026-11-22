// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.infrastructure.ldap;

import com.instantaccessprovider.arcagentopenldap.domain.model.LdapChangeType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryOwnWriteRegistryTest {

    private static final String DN = "uid=alice,ou=People,dc=arc,dc=local";

    /** Horloge dont l'heure est reglee par le test. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-05-01T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void recordedWriteIsConsumedOnce() {
        InMemoryOwnWriteRegistry registry = new InMemoryOwnWriteRegistry(Duration.ofSeconds(30), new MutableClock());
        registry.recordWrite(DN, LdapChangeType.ADD);

        assertTrue(registry.consumeIfOwn(DN, LdapChangeType.ADD));
        assertFalse(registry.consumeIfOwn(DN, LdapChangeType.ADD));
    }

    @Test
    void sameDnWithAnotherChangeTypeIsNotOwn() {
        InMemoryOwnWriteRegistry registry = new InMemoryOwnWriteRegistry(Duration.ofSeconds(30), new MutableClock());
        registry.recordWrite(DN, LdapChangeType.ADD);

        assertFalse(registry.consumeIfOwn(DN, LdapChangeType.DELETE));
    }

    @Test
    void dnComparisonIgnoresCaseAndSpaces() {
        InMemoryOwnWriteRegistry registry = new InMemoryOwnWriteRegistry(Duration.ofSeconds(30), new MutableClock());
        registry.recordWrite(DN, LdapChangeType.MODIFY);

        assertTrue(registry.consumeIfOwn("UID=Alice, ou=people, dc=arc, dc=local", LdapChangeType.MODIFY));
    }

    @Test
    void expiredWriteIsNotOwn() {
        MutableClock clock = new MutableClock();
        InMemoryOwnWriteRegistry registry = new InMemoryOwnWriteRegistry(Duration.ofSeconds(30), clock);
        registry.recordWrite(DN, LdapChangeType.DELETE);

        clock.advance(Duration.ofSeconds(31));

        assertFalse(registry.consumeIfOwn(DN, LdapChangeType.DELETE));
    }
}

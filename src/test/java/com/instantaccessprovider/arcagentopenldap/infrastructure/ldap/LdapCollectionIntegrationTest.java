// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.infrastructure.ldap;

import com.instantaccessprovider.arcagentopenldap.application.LdapCollectionService;
import com.instantaccessprovider.arcagentopenldap.config.AgentProperties;
import com.instantaccessprovider.arcagentopenldap.domain.model.LdapChange;
import com.instantaccessprovider.arcagentopenldap.domain.model.LdapChangeType;
import com.instantaccessprovider.arcagentopenldap.domain.model.LdapEntry;
import com.instantaccessprovider.arcagentopenldap.domain.port.CursorStorePort;
import org.apache.directory.api.ldap.model.entry.DefaultEntry;
import org.apache.directory.api.ldap.model.entry.DefaultModification;
import org.apache.directory.api.ldap.model.entry.ModificationOperation;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.apache.directory.server.annotations.CreateLdapServer;
import org.apache.directory.server.annotations.CreateTransport;
import org.apache.directory.server.core.annotations.ContextEntry;
import org.apache.directory.server.core.annotations.CreateDS;
import org.apache.directory.server.core.annotations.CreatePartition;
import org.apache.directory.server.core.integ.AbstractLdapTestUnit;
import org.apache.directory.server.core.integ.ApacheDSTestExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Collecte bout en bout sur un Apache DS embarque : annuaire reel, Persistent Search reel, service de
 * collecte reel ; seuls Kafka et le curseur sont remplaces par des doubles en memoire.
 */
@ExtendWith(ApacheDSTestExtension.class)
@CreateDS(
        name = "arc-collection",
        partitions = @CreatePartition(
                name = "example",
                suffix = "dc=example,dc=com",
                contextEntry = @ContextEntry(entryLdif =
                        "dn: dc=example,dc=com\n" +
                        "dc: example\n" +
                        "objectClass: top\n" +
                        "objectClass: domain\n\n")))
@CreateLdapServer(transports = @CreateTransport(protocol = "LDAP"))
public class LdapCollectionIntegrationTest extends AbstractLdapTestUnit {

    private static final String SYSTEM = "ldap://LDAP01";
    private static final String FILTER = "(|(objectClass=inetOrgPerson)(objectClass=groupOfNames))";
    private static final String ALICE = "uid=alice,dc=example,dc=com";
    private static final String BOB = "uid=bob,dc=example,dc=com";

    private final List<LdapChange> published = new CopyOnWriteArrayList<>();
    private final Map<String, Instant> cursors = new ConcurrentHashMap<>();
    private final InMemoryOwnWriteRegistry ownWrites = new InMemoryOwnWriteRegistry(Duration.ofSeconds(30), Clock.systemUTC());

    @Test
    void realDirectoryChangesAreCollectedInRealTime() throws Exception {
        try (LdapNetworkConnection admin = connect()) {
            admin.add(person(ALICE, "alice", "alice@example.com"));

            PersistentSearchChangeSource source = newSource();
            LdapCollectionService service = newService(source);
            service.start();
            try {
                await().atMost(Duration.ofSeconds(20)).until(() -> cursors.containsKey(SYSTEM));
                assertTrue(published.isEmpty(), "le premier demarrage ne publie pas l'existant");

                // Silence plus long que le delai des recherches ordinaires (2 s) : le flux doit rester ouvert.
                Thread.sleep(4_000);

                // Modification faite par un tiers
                admin.modify(ALICE, new DefaultModification(
                        ModificationOperation.REPLACE_ATTRIBUTE, "mail", "alice@corp.example.com"));
                await().atMost(Duration.ofSeconds(10)).until(() -> published.size() == 1);
                LdapChange modified = published.get(0);
                assertEquals(LdapChangeType.MODIFY, modified.type());
                assertEquals(ALICE, modified.dn());
                assertNotNull(modified.entryUuid());
                assertEquals(LdapChange.ORIGIN_EXTERNAL, modified.origin(), "le flux ne doit pas avoir ete coupe");
                assertEquals("uid=admin,ou=system", modified.modifiedBy());
                assertTrue(modified.changes().stream().anyMatch(c ->
                        c.attribute().equalsIgnoreCase("mail")
                                && c.oldValues().contains("alice@example.com")
                                && c.newValues().contains("alice@corp.example.com")), modified.changes().toString());
                assertFalse(modified.entry().containsKey("userPassword"), "les secrets ne sont jamais publies");

                // Creation faite par l'agent : reconnue comme echo
                ownWrites.recordWrite(BOB, LdapChangeType.ADD);
                admin.add(person(BOB, "bob", "bob@example.com"));
                await().atMost(Duration.ofSeconds(10)).until(() -> published.size() == 2);
                LdapChange added = published.get(1);
                assertEquals(LdapChangeType.ADD, added.type());
                assertEquals(LdapChange.ORIGIN_ARC_AGENT, added.origin());

                // Suppression : dernier etat connu, auteur inconnu
                admin.delete(BOB);
                await().atMost(Duration.ofSeconds(10)).until(() -> published.size() == 3);
                LdapChange deleted = published.get(2);
                assertEquals(LdapChangeType.DELETE, deleted.type());
                assertEquals(added.entryUuid(), deleted.entryUuid());
                assertNull(deleted.modifiedBy());
                assertNull(deleted.entry());
            } finally {
                service.stop();
            }
        }
    }

    @Test
    void normalisedAuthorDnIsMadeReadable() {
        assertEquals("uid=admin,ou=system",
                PersistentSearchChangeSource.readableDn("0.9.2342.19200300.100.1.1= admin ,2.5.4.11= system "));
        assertEquals("uid=svc,dc=example,dc=com",
                PersistentSearchChangeSource.readableDn("uid=svc,dc=example,dc=com"));
        assertNull(PersistentSearchChangeSource.readableDn(null));
    }

    @Test
    void modifiedSinceFindsRecentEntriesOnly() throws Exception {
        try (LdapNetworkConnection admin = connect()) {
            admin.add(person(ALICE, "alice", "alice@example.com"));
            PersistentSearchChangeSource source = newSource();

            List<LdapEntry> recent = source.modifiedSince(Instant.now().minusSeconds(60));
            assertTrue(recent.stream().anyMatch(e -> e.dn().equals(ALICE)));

            List<LdapEntry> none = source.modifiedSince(Instant.now().plusSeconds(3600));
            assertTrue(none.isEmpty());

            LdapEntry alice = source.snapshot().stream().filter(e -> e.dn().equals(ALICE)).findFirst().orElseThrow();
            assertNotNull(alice.entryUuid());
            assertNotNull(alice.modifiedAt());
            assertFalse(alice.attributes().containsKey("userPassword"));
            assertTrue(alice.objectClasses().contains("inetOrgPerson"));
        }
    }

    // ---- Utilitaires -----------------------------------------------------------------------

    private PersistentSearchChangeSource newSource() {
        AgentProperties.Ldap ldap = new AgentProperties.Ldap(
                "ldap://localhost:" + getLdapServer().getPort(), "dc=example,dc=com",
                "uid=admin,ou=system", "secret", "ou=Users", "ou=Groups", "");
        return new PersistentSearchChangeSource(ldap, FILTER, 2_000);
    }

    private LdapCollectionService newService(PersistentSearchChangeSource source) {
        CursorStorePort cursorStore = new CursorStorePort() {
            @Override
            public Optional<Instant> load(String system) {
                return Optional.ofNullable(cursors.get(system));
            }

            @Override
            public void save(String system, Instant cursor) {
                cursors.put(system, cursor);
            }
        };
        return new LdapCollectionService(source, published::add, cursorStore, ownWrites, SYSTEM,
                Duration.ofMillis(500));
    }

    private LdapNetworkConnection connect() throws Exception {
        LdapNetworkConnection connection = new LdapNetworkConnection("localhost", getLdapServer().getPort());
        connection.bind("uid=admin,ou=system", "secret");
        return connection;
    }

    private static DefaultEntry person(String dn, String uid, String mail) throws Exception {
        return new DefaultEntry(dn,
                "objectClass: top", "objectClass: person", "objectClass: organizationalPerson",
                "objectClass: inetOrgPerson", "uid: " + uid, "cn: " + uid, "sn: " + uid,
                "mail: " + mail, "userPassword: secret-" + uid);
    }
}

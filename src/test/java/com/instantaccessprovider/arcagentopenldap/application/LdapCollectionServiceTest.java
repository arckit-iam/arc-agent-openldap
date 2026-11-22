// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.application;

import com.instantaccessprovider.arcagentopenldap.domain.model.LdapChange;
import com.instantaccessprovider.arcagentopenldap.domain.model.LdapChangeType;
import com.instantaccessprovider.arcagentopenldap.domain.model.LdapEntry;
import com.instantaccessprovider.arcagentopenldap.domain.model.LdapNotification;
import com.instantaccessprovider.arcagentopenldap.domain.port.CursorStorePort;
import com.instantaccessprovider.arcagentopenldap.domain.port.LdapChangeSourcePort;
import com.instantaccessprovider.arcagentopenldap.domain.port.OwnWriteRegistryPort;
import com.instantaccessprovider.arcagentopenldap.domain.port.RawEventPublisherPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LdapCollectionServiceTest {

    private static final Instant T0 = Instant.parse("2026-05-01T10:00:00Z");
    private static final String ALICE = "uid=alice,ou=people,dc=arc,dc=local";

    private final FakeSource source = new FakeSource();
    private final List<LdapChange> published = new CopyOnWriteArrayList<>();
    private final FakeCursorStore cursorStore = new FakeCursorStore();
    private final FakeOwnWrites ownWrites = new FakeOwnWrites();
    private LdapCollectionService service;

    @BeforeEach
    void setUp() {
        RawEventPublisherPort publisher = published::add;
        service = new LdapCollectionService(source, publisher, cursorStore, ownWrites,
                "ldap://LDAP01", Duration.ofMillis(100));
    }

    @AfterEach
    void tearDown() {
        service.stop();
    }

    @Test
    void firstStartPublishesNothingAndSetsCursorToLatestModification() {
        source.snapshot = List.of(
                entry(ALICE, "uuid-1", Map.of("mail", List.of("a@x.org")), T0),
                entry("uid=bob,ou=people,dc=arc,dc=local", "uuid-2", Map.of(), T0.plusSeconds(30)));

        service.start();

        await().untilAsserted(() -> assertEquals(T0.plusSeconds(30), cursorStore.saved.get("ldap://LDAP01")));
        assertTrue(published.isEmpty());
    }

    @Test
    void liveModifyPublishesAttributeDifferenceAndAdvancesCursor() {
        source.snapshot = List.of(entry(ALICE, "uuid-1", Map.of("mail", List.of("old@x.org")), T0));
        service.start();
        await().until(() -> source.listener != null && cursorStore.saved.containsKey("ldap://LDAP01"));

        Instant later = T0.plusSeconds(5);
        source.emit(new LdapNotification(LdapChangeType.MODIFY, null,
                entry(ALICE, "uuid-1", Map.of("mail", List.of("new@x.org")), later)));

        await().until(() -> published.size() == 1);
        LdapChange change = published.get(0);
        assertEquals(LdapChangeType.MODIFY, change.type());
        assertEquals("uuid-1", change.entryUuid());
        assertEquals(LdapChange.ORIGIN_EXTERNAL, change.origin());
        assertEquals(1, change.changes().size());
        assertEquals("replace", change.changes().get(0).operation());
        assertEquals(List.of("old@x.org"), change.changes().get(0).oldValues());
        assertEquals(List.of("new@x.org"), change.changes().get(0).newValues());
        assertEquals(later, cursorStore.saved.get("ldap://LDAP01"));
    }

    @Test
    void twoModificationsInTheSameSecondAreBothPublished() {
        source.snapshot = List.of(entry(ALICE, "uuid-1", Map.of("mail", List.of("a@x.org")), T0));
        service.start();
        await().until(() -> source.listener != null && cursorStore.saved.containsKey("ldap://LDAP01"));

        Instant same = T0.plusSeconds(1);
        source.emit(new LdapNotification(LdapChangeType.MODIFY, null,
                entry(ALICE, "uuid-1", Map.of("mail", List.of("b@x.org")), same)));
        source.emit(new LdapNotification(LdapChangeType.MODIFY, null,
                entry(ALICE, "uuid-1", Map.of("mail", List.of("c@x.org")), same)));

        await().until(() -> published.size() == 2);
    }

    @Test
    void modifyWithoutAttributeDifferenceIsIgnored() {
        source.snapshot = List.of(entry(ALICE, "uuid-1", Map.of("mail", List.of("a@x.org")), T0));
        service.start();
        await().until(() -> source.listener != null && cursorStore.saved.containsKey("ldap://LDAP01"));

        source.emit(new LdapNotification(LdapChangeType.MODIFY, null,
                entry(ALICE, "uuid-1", Map.of("mail", List.of("a@x.org")), T0.plusSeconds(1))));
        source.emit(new LdapNotification(LdapChangeType.MODIFY, null,
                entry(ALICE, "uuid-1", Map.of("mail", List.of("z@x.org")), T0.plusSeconds(2))));

        await().until(() -> published.size() == 1);
        assertEquals(List.of("z@x.org"), published.get(0).changes().get(0).newValues());
    }

    @Test
    void writeMadeByTheAgentIsMarkedAsEcho() {
        source.snapshot = List.of();
        service.start();
        await().until(() -> source.listener != null && cursorStore.saved.containsKey("ldap://LDAP01"));

        ownWrites.own.add(LdapChangeType.ADD + "|" + ALICE);
        source.emit(new LdapNotification(LdapChangeType.ADD, null,
                entry(ALICE, "uuid-1", Map.of("uid", List.of("alice")), T0)));

        await().until(() -> published.size() == 1);
        assertEquals(LdapChange.ORIGIN_ARC_AGENT, published.get(0).origin());
    }

    @Test
    void deletePublishesLastKnownStateWithoutAuthor() {
        source.snapshot = List.of(entry(ALICE, "uuid-1", Map.of("mail", List.of("a@x.org")), T0));
        service.start();
        await().until(() -> source.listener != null && cursorStore.saved.containsKey("ldap://LDAP01"));

        source.emit(new LdapNotification(LdapChangeType.DELETE, null,
                entry(ALICE, "uuid-1", Map.of(), T0)));

        await().until(() -> published.size() == 1);
        LdapChange change = published.get(0);
        assertEquals(LdapChangeType.DELETE, change.type());
        assertNull(change.modifiedBy());
        assertNull(change.entry());
        assertEquals("delete", change.changes().get(0).operation());
        assertEquals(List.of("a@x.org"), change.changes().get(0).oldValues());
    }

    @Test
    void restartCatchesUpFromCursorAndSkipsUnmodifiedEntries() {
        cursorStore.saved.put("ldap://LDAP01", T0);
        source.modifiedSince = List.of(entry(ALICE, "uuid-1", Map.of("mail", List.of("a@x.org")), T0.plusSeconds(10)));
        source.snapshot = List.of(
                entry(ALICE, "uuid-1", Map.of("mail", List.of("a@x.org")), T0.plusSeconds(10)),
                entry("uid=bob,ou=people,dc=arc,dc=local", "uuid-2", Map.of(), T0.minusSeconds(60)));

        service.start();

        await().until(() -> published.size() == 1);
        assertEquals(LdapChange.ORIGIN_CATCH_UP, published.get(0).origin());
        assertEquals("uuid-1", published.get(0).entryUuid());
        assertEquals(T0.plusSeconds(10), cursorStore.saved.get("ldap://LDAP01"));
    }

    @Test
    void entryDisappearedDuringOutageIsPublishedAsReconciledDeletion() {
        source.snapshot = List.of(entry(ALICE, "uuid-1", Map.of("mail", List.of("a@x.org")), T0));
        service.start();
        await().until(() -> source.listener != null && cursorStore.saved.containsKey("ldap://LDAP01"));

        source.snapshot = List.of();
        source.fail(new IllegalStateException("coupure"));

        await().until(() -> published.size() == 1);
        assertEquals(LdapChangeType.DELETE, published.get(0).type());
        assertEquals(LdapChange.ORIGIN_RECONCILE, published.get(0).origin());
        assertTrue(source.opened.get() >= 2, "le flux doit etre rouvert apres la coupure");
    }

    @Test
    void failedPublicationKeepsCursorAndChangeIsRecoveredByCatchUp() {
        source.snapshot = List.of(entry(ALICE, "uuid-1", Map.of("mail", List.of("a@x.org")), T0));
        AtomicInteger attempts = new AtomicInteger();
        LdapCollectionService flaky = new LdapCollectionService(source, change -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("kafka indisponible");
            }
            published.add(change);
        }, cursorStore, ownWrites, "ldap://LDAP01", Duration.ofMillis(100));
        try {
            flaky.start();
            await().until(() -> source.listener != null && cursorStore.saved.containsKey("ldap://LDAP01"));

            Instant later = T0.plusSeconds(5);
            LdapEntry changed = entry(ALICE, "uuid-1", Map.of("mail", List.of("b@x.org")), later);
            // Apres la coupure provoquee par l'echec, le rattrapage depuis le curseur retrouve la modification.
            source.modifiedSince = List.of(changed);
            source.emit(new LdapNotification(LdapChangeType.MODIFY, null, changed));

            await().until(() -> published.size() == 1);
            assertEquals(LdapChange.ORIGIN_CATCH_UP, published.get(0).origin());
            assertEquals(2, attempts.get());
            assertEquals(later, cursorStore.saved.get("ldap://LDAP01"));
        } finally {
            flaky.stop();
        }
    }

    // ---- Doubles ---------------------------------------------------------------------------

    private static LdapEntry entry(String dn, String uuid, Map<String, List<String>> attributes, Instant modifiedAt) {
        return new LdapEntry(dn, uuid, List.of("top", "inetOrgPerson"), attributes, "uid=admin,ou=system",
                modifiedAt, modifiedAt);
    }

    private static class FakeSource implements LdapChangeSourcePort {
        volatile List<LdapEntry> snapshot = List.of();
        volatile List<LdapEntry> modifiedSince = List.of();
        volatile Consumer<LdapNotification> listener;
        volatile Consumer<Throwable> failure;
        final AtomicInteger opened = new AtomicInteger();

        @Override
        public ChangeStream open(Consumer<LdapNotification> onNotification, Consumer<Throwable> onFailure) {
            this.listener = onNotification;
            this.failure = onFailure;
            opened.incrementAndGet();
            return () -> { };
        }

        @Override
        public List<LdapEntry> modifiedSince(Instant since) {
            return modifiedSince;
        }

        @Override
        public List<LdapEntry> snapshot() {
            return snapshot;
        }

        void emit(LdapNotification notification) {
            listener.accept(notification);
        }

        void fail(Throwable throwable) {
            failure.accept(throwable);
        }
    }

    private static class FakeCursorStore implements CursorStorePort {
        final Map<String, Instant> saved = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Optional<Instant> load(String system) {
            return Optional.ofNullable(saved.get(system));
        }

        @Override
        public void save(String system, Instant cursor) {
            saved.put(system, cursor);
        }
    }

    private static class FakeOwnWrites implements OwnWriteRegistryPort {
        final Set<String> own = new HashSet<>();

        @Override
        public void recordWrite(String dn, LdapChangeType type) {
            own.add(type + "|" + dn);
        }

        @Override
        public boolean consumeIfOwn(String dn, LdapChangeType type) {
            return own.remove(type + "|" + dn);
        }
    }
}

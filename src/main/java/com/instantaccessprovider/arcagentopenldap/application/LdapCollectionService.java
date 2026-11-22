// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.application;

import com.instantaccessprovider.arcagentopenldap.domain.model.AttributeChange;
import com.instantaccessprovider.arcagentopenldap.domain.model.LdapChange;
import com.instantaccessprovider.arcagentopenldap.domain.model.LdapChangeType;
import com.instantaccessprovider.arcagentopenldap.domain.model.LdapEntry;
import com.instantaccessprovider.arcagentopenldap.domain.model.LdapNotification;
import com.instantaccessprovider.arcagentopenldap.domain.port.CursorStorePort;
import com.instantaccessprovider.arcagentopenldap.domain.port.LdapChangeSourcePort;
import com.instantaccessprovider.arcagentopenldap.domain.port.OwnWriteRegistryPort;
import com.instantaccessprovider.arcagentopenldap.domain.port.RawEventPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Collecte événementielle d'un annuaire : suit les changements en continu, calcule les différences
 * d'attributs à partir d'un cache local des entrées, les publie puis avance le curseur de reprise.
 *
 * <p>Déroulement d'une session (relancée avec un délai après toute coupure) :
 * <ol>
 *   <li>ouverture du flux des changements, mis en file d'attente ;</li>
 *   <li>rattrapage des entrées modifiées depuis le curseur (reprise après arrêt ou coupure) ;</li>
 *   <li>lecture de l'état courant, qui alimente le cache sans rien publier ; après une coupure, les
 *       entrées du cache qui ont disparu sont publiées comme supprimées ;</li>
 *   <li>traitement continu de la file.</li>
 * </ol>
 *
 * <p>Un premier démarrage sans curseur ne publie rien : le curseur est positionné sur la dernière
 * modification existante. Une suppression survenue pendant l'arrêt de l'agent n'est pas détectable
 * (le cache est vide au redémarrage) ; elle l'est après une simple coupure de connexion.
 */
public class LdapCollectionService {

    private static final Logger log = LoggerFactory.getLogger(LdapCollectionService.class);

    private final LdapChangeSourcePort source;
    private final RawEventPublisherPort publisher;
    private final CursorStorePort cursorStore;
    private final OwnWriteRegistryPort ownWrites;
    private final String system;
    private final Duration retryDelay;

    private final Map<String, LdapEntry> cache = new ConcurrentHashMap<>();
    private final BlockingQueue<Item> queue = new LinkedBlockingQueue<>();
    private volatile boolean running;
    private volatile Instant cursor;
    private boolean cacheInitialised;
    private Thread thread;

    public LdapCollectionService(LdapChangeSourcePort source, RawEventPublisherPort publisher,
                                 CursorStorePort cursorStore, OwnWriteRegistryPort ownWrites,
                                 String system, Duration retryDelay) {
        this.source = source;
        this.publisher = publisher;
        this.cursorStore = cursorStore;
        this.ownWrites = ownWrites;
        this.system = system;
        this.retryDelay = retryDelay;
    }

    /** Démarre la collecte dans un thread dédié. */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "ldap-collector");
        thread.setDaemon(true);
        thread.start();
        log.info("Collecte LDAP demarree: system={}", system);
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Collecte LDAP arretee: system={}", system);
    }

    private void loop() {
        while (running) {
            try {
                session();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                log.warn("Collecte LDAP interrompue, nouvel essai dans {} : {}", retryDelay, e.toString());
            }
            try {
                Thread.sleep(retryDelay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void session() throws Exception {
        queue.clear();
        try (LdapChangeSourcePort.ChangeStream ignored = source.open(
                notification -> queue.add(new Item(notification, null)),
                failure -> queue.add(new Item(null, failure)))) {

            if (cursor == null) {
                cursor = cursorStore.load(system).orElse(null);
            }
            if (cursor != null) {
                catchUp();
            }

            List<LdapEntry> snapshot = source.snapshot();
            if (cacheInitialised) {
                reconcileDeletions(snapshot);
            }
            snapshot.forEach(entry -> cache.putIfAbsent(entry.entryUuid(), entry));
            if (cursor == null) {
                Instant start = snapshot.stream().map(LdapEntry::modifiedAt)
                        .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(Instant.now());
                saveCursor(start);
                log.info("Premier demarrage: {} entrees en cache, curseur initial {}", snapshot.size(), start);
            }
            cacheInitialised = true;

            while (running) {
                Item item = queue.take();
                if (item.failure() != null) {
                    throw new IllegalStateException("flux LDAP interrompu", item.failure());
                }
                handle(item.notification(), true, LdapChange.ORIGIN_EXTERNAL);
            }
        }
    }

    private void catchUp() {
        List<LdapEntry> modified = new ArrayList<>(source.modifiedSince(cursor));
        modified.sort(Comparator.comparing(LdapEntry::modifiedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        log.info("Rattrapage depuis {} : {} entree(s)", cursor, modified.size());
        for (LdapEntry entry : modified) {
            boolean created = entry.createdAt() != null && !entry.createdAt().isBefore(cursor);
            handle(new LdapNotification(created ? LdapChangeType.ADD : LdapChangeType.MODIFY, null, entry),
                    false, LdapChange.ORIGIN_CATCH_UP);
        }
    }

    private void reconcileDeletions(List<LdapEntry> snapshot) {
        Set<String> present = new LinkedHashSet<>();
        snapshot.forEach(entry -> present.add(entry.entryUuid()));
        for (LdapEntry known : List.copyOf(cache.values())) {
            if (!present.contains(known.entryUuid())) {
                handle(new LdapNotification(LdapChangeType.DELETE, null, known), false, LdapChange.ORIGIN_RECONCILE);
            }
        }
    }

    /**
     * Traite une notification : calcule le changement, le publie, puis met à jour le cache et le curseur.
     * Le cache et le curseur ne bougent qu'après une publication réussie, pour qu'un échec soit rejoué.
     */
    void handle(LdapNotification notification, boolean live, String defaultOrigin) {
        LdapEntry entry = notification.entry();
        LdapChangeType type = notification.type();
        // L'enregistrement des ecritures de l'agent est consomme meme si l'evenement est ensuite ignore.
        boolean own = live && ownWrites.consumeIfOwn(entry.dn(), type);
        String origin = own ? LdapChange.ORIGIN_ARC_AGENT : defaultOrigin;
        LdapEntry previous = cache.get(entry.entryUuid());

        if (type == LdapChangeType.DELETE) {
            if (previous == null) {
                log.debug("Suppression d'une entree inconnue ignoree: dn={}", entry.dn());
                return;
            }
            publish(new LdapChange(type, previous.dn(), null, previous.entryUuid(), previous.objectClasses(),
                    Instant.now(), null, origin, deletions(previous.attributes()), null));
            cache.remove(entry.entryUuid());
            return;
        }

        // Le modifyTimestamp n'a qu'une precision d'une seconde : deux modifications successives d'une meme
        // entree peuvent le partager. Un doublon (rattrapage qui recoupe le flux) se reconnait donc a l'absence
        // de difference avec l'etat connu, pas a la date.
        List<AttributeChange> changes = previous == null ? List.of() : diff(previous.attributes(), entry.attributes());
        if (previous != null && changes.isEmpty() && type != LdapChangeType.MODRDN
                && previous.dn().equalsIgnoreCase(entry.dn())) {
            cache.put(entry.entryUuid(), entry);
            return;
        }

        String previousDn = notification.previousDn() != null ? notification.previousDn()
                : (type == LdapChangeType.MODRDN && previous != null ? previous.dn() : null);
        Instant timestamp = entry.modifiedAt() != null ? entry.modifiedAt() : Instant.now();
        publish(new LdapChange(type, entry.dn(), previousDn, entry.entryUuid(), entry.objectClasses(),
                timestamp, entry.author(), origin, changes, entry.attributes()));
        cache.put(entry.entryUuid(), entry);
        if (entry.modifiedAt() != null && (cursor == null || entry.modifiedAt().isAfter(cursor))) {
            saveCursor(entry.modifiedAt());
        }
    }

    private void publish(LdapChange change) {
        publisher.publish(change);
        log.info("Changement publie: type={} dn={} origin={}", change.type(), change.dn(), change.origin());
    }

    private void saveCursor(Instant value) {
        cursorStore.save(system, value);
        cursor = value;
    }

    // ---- Différences -----------------------------------------------------------------------

    static List<AttributeChange> diff(Map<String, List<String>> before, Map<String, List<String>> after) {
        Map<String, List<String>> oldValues = caseInsensitive(before);
        Map<String, List<String>> newValues = caseInsensitive(after);
        List<AttributeChange> changes = new ArrayList<>();
        for (Map.Entry<String, List<String>> current : newValues.entrySet()) {
            List<String> old = oldValues.get(current.getKey());
            if (old == null) {
                changes.add(new AttributeChange(current.getKey(), "add", null, current.getValue()));
            } else if (!Set.copyOf(old).equals(Set.copyOf(current.getValue()))) {
                changes.add(new AttributeChange(current.getKey(), "replace", old, current.getValue()));
            }
        }
        for (Map.Entry<String, List<String>> gone : oldValues.entrySet()) {
            if (!newValues.containsKey(gone.getKey())) {
                changes.add(new AttributeChange(gone.getKey(), "delete", gone.getValue(), null));
            }
        }
        return changes;
    }

    private static List<AttributeChange> deletions(Map<String, List<String>> attributes) {
        return caseInsensitive(attributes).entrySet().stream()
                .map(e -> new AttributeChange(e.getKey(), "delete", e.getValue(), null))
                .toList();
    }

    private static Map<String, List<String>> caseInsensitive(Map<String, List<String>> source) {
        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (source != null) {
            map.putAll(source);
        }
        return map;
    }

    private record Item(LdapNotification notification, Throwable failure) {}
}

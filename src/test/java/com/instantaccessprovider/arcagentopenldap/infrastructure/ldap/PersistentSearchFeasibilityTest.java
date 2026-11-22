// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.infrastructure.ldap;

import org.apache.directory.api.ldap.model.entry.DefaultEntry;
import org.apache.directory.api.ldap.model.entry.DefaultModification;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.entry.ModificationOperation;
import org.apache.directory.api.ldap.model.message.Response;
import org.apache.directory.api.ldap.model.message.SearchRequest;
import org.apache.directory.api.ldap.model.message.SearchRequestImpl;
import org.apache.directory.api.ldap.model.message.SearchResultEntry;
import org.apache.directory.api.ldap.model.message.SearchScope;
import org.apache.directory.api.ldap.model.message.controls.EntryChange;
import org.apache.directory.api.ldap.model.message.controls.PersistentSearch;
import org.apache.directory.api.ldap.model.message.controls.PersistentSearchImpl;
import org.apache.directory.api.ldap.model.cursor.SearchCursor;
import org.apache.directory.api.ldap.model.name.Dn;
import org.apache.directory.api.ldap.model.schema.SchemaManager;
import org.apache.directory.api.ldap.schema.manager.impl.DefaultSchemaManager;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test de faisabilite de la collecte evenementielle : un client Persistent Search recoit-il en temps
 * reel les changements faits sur un Apache DS embarque ?
 */
@ExtendWith(ApacheDSTestExtension.class)
@CreateDS(
        name = "arc-feasibility",
        partitions = @CreatePartition(
                name = "example",
                suffix = "dc=example,dc=com",
                contextEntry = @ContextEntry(entryLdif =
                        "dn: dc=example,dc=com\n" +
                        "dc: example\n" +
                        "objectClass: top\n" +
                        "objectClass: domain\n\n")))
@CreateLdapServer(transports = @CreateTransport(protocol = "LDAP"))
public class PersistentSearchFeasibilityTest extends AbstractLdapTestUnit {

    private static final String BASE = "dc=example,dc=com";
    private static final SchemaManager SCHEMA = newSchemaManager();

    private static SchemaManager newSchemaManager() {
        try {
            return new DefaultSchemaManager();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Changement recu : type, dn, auteur et identifiant stable (entryUUID). */
    private record Change(String type, String dn, String author, String entryUuid) { }

    @Test
    void persistentSearchDeliversAddModifyDeleteInRealTime() throws Exception {
        BlockingQueue<Change> received = new LinkedBlockingQueue<>();
        List<Throwable> failures = new ArrayList<>();

        try (LdapNetworkConnection listener = connect()) {
            SearchRequest request = new SearchRequestImpl();
            request.setBase(new Dn(BASE));
            request.setFilter("(objectClass=*)");
            request.setScope(SearchScope.SUBTREE);
            request.addAttributes("*", "+");

            PersistentSearch persistentSearch = new PersistentSearchImpl();
            persistentSearch.setChangesOnly(true);
            persistentSearch.setReturnECs(true);
            persistentSearch.setChangeTypes(PersistentSearch.CHANGE_TYPES_MAX);
            request.addControl(persistentSearch);

            SearchCursor cursor = listener.search(request);
            Thread reader = new Thread(() -> {
                try {
                    while (cursor.next()) {
                        Response response = cursor.get();
                        if (response instanceof SearchResultEntry result) {
                            Entry entry = result.getEntry();
                            EntryChange change = (EntryChange) result.getControl(EntryChange.OID);
                            received.add(new Change(
                                    change == null ? "?" : change.getChangeType().name(),
                                    entry.getDn().getName(),
                                    author(entry),
                                    first(entry, "entryUUID")));
                        }
                    }
                } catch (Exception e) {
                    // La fermeture de la connexion en fin de test interrompt le curseur.
                    if (!Thread.currentThread().isInterrupted()) {
                        synchronized (failures) {
                            failures.add(e);
                        }
                    }
                }
            }, "persistent-search-reader");
            reader.setDaemon(true);
            reader.start();

            try (LdapNetworkConnection writer = connect()) {
                // Second compte, pour verifier qui est vu comme auteur d'une suppression.
                String svcDn = "uid=svc,dc=example,dc=com";
                writer.add(new DefaultEntry(svcDn,
                        "objectClass: top", "objectClass: person", "objectClass: organizationalPerson",
                        "objectClass: inetOrgPerson", "uid: svc", "cn: svc", "sn: svc",
                        "userPassword: svc-secret"));
                assertEquals(svcDn, next(received).dn());

                String dn = "ou=team,dc=example,dc=com";
                writer.add(new DefaultEntry(dn,
                        "objectClass: top", "objectClass: organizationalUnit", "ou: team"));
                Change added = next(received);
                assertEquals("ADD", added.type());
                assertEquals(dn, added.dn());
                assertNotNull(added.entryUuid(), "entryUUID attendu (identifiant stable)");
                assertTrue(sameDn("uid=admin,ou=system", added.author()),
                        "auteur de l'ajout : " + added.author());

                writer.modify(dn, new DefaultModification(
                        ModificationOperation.REPLACE_ATTRIBUTE, "description", "equipe"));
                Change modified = next(received);
                assertEquals("MODIFY", modified.type());
                assertEquals(added.entryUuid(), modified.entryUuid());
                assertTrue(sameDn("uid=admin,ou=system", modified.author()),
                        "auteur de la modification : " + modified.author());

                try (LdapNetworkConnection svc = new LdapNetworkConnection("localhost", getLdapServer().getPort())) {
                    svc.bind(svcDn, "svc-secret");
                    svc.delete(dn);
                }
                Change deleted = next(received);
                assertEquals("DELETE", deleted.type());
                assertEquals(dn, deleted.dn());
                assertEquals(added.entryUuid(), deleted.entryUuid());
                // Limite constatee : la notification de suppression porte l'entree telle qu'elle etait avant
                // d'etre supprimee. L'auteur reporte est donc l'ancien auteur (admin) et non celui qui supprime
                // (svc) : l'anti-echo ne peut pas reposer sur l'auteur pour les suppressions.
                assertTrue(sameDn("uid=admin,ou=system", deleted.author()),
                        "auteur reporte pour la suppression : " + deleted.author());
            }
        }

        synchronized (failures) {
            assertTrue(failures.isEmpty(), "erreur du lecteur : " + failures);
        }
    }

    private LdapNetworkConnection connect() throws Exception {
        LdapNetworkConnection connection = new LdapNetworkConnection("localhost", getLdapServer().getPort());
        connection.bind("uid=admin,ou=system", "secret");
        return connection;
    }

    private static Change next(BlockingQueue<Change> queue) throws InterruptedException {
        Change change = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(change, "aucun changement recu en 5 s");
        return change;
    }

    /**
     * Apache DS renvoie l'auteur sous forme normalisee (OID a la place des noms d'attributs, espaces) :
     * la comparaison passe par un DN normalise avec le schema.
     */
    private static boolean sameDn(String expected, String actual) throws Exception {
        SchemaManager schema = SCHEMA;
        return new Dn(schema, expected).equals(new Dn(schema, actual));
    }

    /** Auteur du changement : modifiersName apres une modification, creatorsName apres une creation. */
    private static String author(Entry entry) throws Exception {
        String modifier = first(entry, "modifiersName");
        return modifier != null ? modifier : first(entry, "creatorsName");
    }

    private static String first(Entry entry, String attribute) throws Exception {
        return entry.get(attribute) == null ? null : entry.get(attribute).getString();
    }
}

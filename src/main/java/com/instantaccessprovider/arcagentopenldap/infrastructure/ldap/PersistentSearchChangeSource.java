// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.infrastructure.ldap;

import com.instantaccessprovider.arcagentopenldap.config.AgentProperties;
import com.instantaccessprovider.arcagentopenldap.domain.model.LdapChangeType;
import com.instantaccessprovider.arcagentopenldap.domain.model.LdapEntry;
import com.instantaccessprovider.arcagentopenldap.domain.model.LdapNotification;
import com.instantaccessprovider.arcagentopenldap.domain.port.LdapChangeSourcePort;
import org.apache.directory.api.ldap.model.cursor.EntryCursor;
import org.apache.directory.api.ldap.model.cursor.SearchCursor;
import org.apache.directory.api.ldap.model.entry.Attribute;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.entry.Value;
import org.apache.directory.api.ldap.model.message.Response;
import org.apache.directory.api.ldap.model.message.SearchRequest;
import org.apache.directory.api.ldap.model.message.SearchRequestImpl;
import org.apache.directory.api.ldap.model.message.SearchResultEntry;
import org.apache.directory.api.ldap.model.message.SearchScope;
import org.apache.directory.api.ldap.model.message.controls.ChangeType;
import org.apache.directory.api.ldap.model.message.controls.EntryChange;
import org.apache.directory.api.ldap.model.message.controls.PersistentSearch;
import org.apache.directory.api.ldap.model.message.controls.PersistentSearchImpl;
import org.apache.directory.api.ldap.model.name.Dn;
import org.apache.directory.api.ldap.model.name.Ava;
import org.apache.directory.api.ldap.model.name.Rdn;
import org.apache.directory.api.ldap.model.schema.SchemaManager;
import org.apache.directory.api.ldap.schema.manager.impl.DefaultSchemaManager;
import org.apache.directory.api.util.GeneralizedTime;
import org.apache.directory.ldap.client.api.LdapConnectionConfig;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Source de changements basée sur le contrôle LDAP Persistent Search : une recherche reste ouverte et le
 * serveur pousse une notification à chaque ajout, modification, suppression ou renommage.
 *
 * <p>Le contrôle n'offre ni reprise ni ancienne valeur ; la reprise est assurée par {@link #modifiedSince}
 * et les anciennes valeurs par le cache du service de collecte. Les secrets ({@code userPassword}) ne
 * sont jamais lus dans les entrées renvoyées.
 */
public class PersistentSearchChangeSource implements LdapChangeSourcePort {

    private static final Logger log = LoggerFactory.getLogger(PersistentSearchChangeSource.class);

    private static final DateTimeFormatter GENERALIZED_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);

    /** Attributs demandés : tous les attributs utilisateur plus les attributs opérationnels utiles. */
    private static final String[] REQUESTED = {
            "*", "entryUUID", "createTimestamp", "modifyTimestamp", "creatorsName", "modifiersName"};

    /** Attributs absents de l'état publié (opérationnels, classes d'objets, secrets). */
    private static final Set<String> HIDDEN = Set.of(
            "entryuuid", "createtimestamp", "modifytimestamp", "creatorsname", "modifiersname",
            "objectclass", "userpassword", "unicodepwd");

    private final AgentProperties.Ldap ldap;
    private final String filter;
    private final long timeoutMillis;

    private static final SchemaManager SCHEMA = newSchemaManager();

    private static SchemaManager newSchemaManager() {
        try {
            return new DefaultSchemaManager();
        } catch (Exception e) {
            throw new IllegalStateException("schema LDAP embarque indisponible", e);
        }
    }

    public PersistentSearchChangeSource(AgentProperties.Ldap ldap, String filter, long timeoutMillis) {
        this.ldap = ldap;
        this.filter = filter;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public ChangeStream open(Consumer<LdapNotification> onNotification, Consumer<Throwable> onFailure) {
        LdapNetworkConnection connection = connect();
        // La recherche persistante reste silencieuse tant que l'annuaire ne change pas : le delai applique
        // aux recherches ordinaires la ferait echouer (le bind a deja eu lieu avec son delai).
        connection.setTimeOut(Long.MAX_VALUE);
        SearchCursor cursor;
        try {
            SearchRequest request = new SearchRequestImpl();
            request.setBase(new Dn(ldap.baseDn()));
            request.setFilter(filter);
            request.setScope(SearchScope.SUBTREE);
            request.addAttributes(REQUESTED);

            PersistentSearch persistentSearch = new PersistentSearchImpl();
            persistentSearch.setChangesOnly(true);
            persistentSearch.setReturnECs(true);
            persistentSearch.setChangeTypes(PersistentSearch.CHANGE_TYPES_MAX);
            request.addControl(persistentSearch);

            cursor = connection.search(request);
        } catch (Exception e) {
            closeQuietly(connection);
            throw new IllegalStateException("ouverture de la recherche persistante impossible", e);
        }

        AtomicBoolean closed = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try {
                while (cursor.next()) {
                    Response response = cursor.get();
                    if (response instanceof SearchResultEntry result) {
                        onNotification.accept(toNotification(result));
                    }
                }
                if (!closed.get()) {
                    onFailure.accept(new IllegalStateException("recherche persistante terminee par le serveur"));
                }
            } catch (Exception e) {
                if (!closed.get()) {
                    onFailure.accept(e);
                }
            }
        }, "ldap-persistent-search");
        reader.setDaemon(true);
        reader.start();
        log.info("Recherche persistante ouverte: base={} filtre={}", ldap.baseDn(), filter);

        return () -> {
            closed.set(true);
            closeQuietly(connection);
            reader.interrupt();
        };
    }

    @Override
    public List<LdapEntry> modifiedSince(Instant since) {
        String time = GENERALIZED_TIME.format(since.truncatedTo(ChronoUnit.SECONDS));
        return search("(&" + filter + "(|(createTimestamp>=" + time + ")(modifyTimestamp>=" + time + ")))");
    }

    @Override
    public List<LdapEntry> snapshot() {
        return search(filter);
    }

    private List<LdapEntry> search(String searchFilter) {
        LdapNetworkConnection connection = connect();
        try (EntryCursor cursor = connection.search(ldap.baseDn(), searchFilter, SearchScope.SUBTREE, REQUESTED)) {
            List<LdapEntry> entries = new ArrayList<>();
            while (cursor.next()) {
                entries.add(toEntry(cursor.get()));
            }
            return entries;
        } catch (Exception e) {
            throw new IllegalStateException("recherche LDAP impossible: " + searchFilter, e);
        } finally {
            closeQuietly(connection);
        }
    }

    private LdapNetworkConnection connect() {
        URI uri = URI.create(ldap.url());
        boolean ssl = "ldaps".equalsIgnoreCase(uri.getScheme());
        LdapConnectionConfig config = new LdapConnectionConfig();
        config.setLdapHost(uri.getHost());
        config.setLdapPort(uri.getPort() > 0 ? uri.getPort() : (ssl ? 636 : 389));
        config.setUseSsl(ssl);
        config.setTimeout(timeoutMillis);
        LdapNetworkConnection connection = new LdapNetworkConnection(config);
        try {
            if (ldap.bindDn() != null && !ldap.bindDn().isBlank()) {
                connection.bind(ldap.bindDn(), ldap.bindPassword());
            } else {
                connection.anonymousBind();
            }
            return connection;
        } catch (Exception e) {
            closeQuietly(connection);
            throw new IllegalStateException("connexion LDAP impossible: " + ldap.url(), e);
        }
    }

    private static LdapNotification toNotification(SearchResultEntry result) throws Exception {
        EntryChange change = (EntryChange) result.getControl(EntryChange.OID);
        LdapChangeType type = LdapChangeType.MODIFY;
        String previousDn = null;
        if (change != null) {
            ChangeType changeType = change.getChangeType();
            type = switch (changeType) {
                case ADD -> LdapChangeType.ADD;
                case DELETE -> LdapChangeType.DELETE;
                case MODDN -> LdapChangeType.MODRDN;
                default -> LdapChangeType.MODIFY;
            };
            if (change.getPreviousDn() != null) {
                previousDn = change.getPreviousDn().getName();
            }
        }
        return new LdapNotification(type, previousDn, toEntry(result.getEntry()));
    }

    static LdapEntry toEntry(Entry entry) throws Exception {
        Instant createdAt = timestamp(entry, "createTimestamp");
        Instant modifiedAt = timestamp(entry, "modifyTimestamp");
        String author = readableDn(first(entry, "modifiersName"));
        if (author == null) {
            author = readableDn(first(entry, "creatorsName"));
        }

        List<String> objectClasses = new ArrayList<>();
        Attribute classes = entry.get("objectClass");
        if (classes != null) {
            for (Value value : classes) {
                objectClasses.add(value.getString());
            }
        }

        Map<String, List<String>> attributes = new LinkedHashMap<>();
        for (Attribute attribute : entry) {
            String name = attribute.getUpId();
            if (HIDDEN.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            List<String> values = new ArrayList<>();
            for (Value value : attribute) {
                if (value.isHumanReadable()) {
                    values.add(value.getString());
                }
            }
            if (!values.isEmpty()) {
                attributes.put(name, values);
            }
        }
        return new LdapEntry(entry.getDn().getName(), first(entry, "entryUUID"), objectClasses, attributes,
                author, createdAt, modifiedAt != null ? modifiedAt : createdAt);
    }

    private static String first(Entry entry, String attribute) throws Exception {
        Attribute value = entry.get(attribute);
        return value == null ? null : value.getString();
    }

    /**
     * Apache DS renvoie les DN d'auteur sous forme normalisee (OID a la place des noms d'attributs, espaces
     * autour des valeurs). Cette methode retablit une forme lisible, par exemple {@code uid=admin,ou=system}.
     * Une valeur qui ne se laisse pas analyser est rendue telle quelle.
     */
    static String readableDn(String dn) {
        if (dn == null) {
            return null;
        }
        try {
            Dn parsed = new Dn(SCHEMA, dn);
            List<String> rdns = new ArrayList<>();
            for (Rdn rdn : parsed.getRdns()) {
                List<String> avas = new ArrayList<>();
                for (Ava ava : rdn) {
                    String type = SCHEMA.getAttributeType(ava.getType()).getName();
                    avas.add(type + "=" + ava.getValue().getString().trim());
                }
                rdns.add(String.join("+", avas));
            }
            return String.join(",", rdns);
        } catch (Exception e) {
            return dn;
        }
    }

    private static Instant timestamp(Entry entry, String attribute) throws Exception {
        String value = first(entry, attribute);
        return value == null ? null : new GeneralizedTime(value).getCalendar().toInstant();
    }

    private static void closeQuietly(LdapNetworkConnection connection) {
        try {
            connection.close();
        } catch (Exception e) {
            log.debug("Fermeture de la connexion LDAP: {}", e.toString());
        }
    }
}

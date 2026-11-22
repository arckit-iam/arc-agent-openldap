// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.domain.port;

import com.instantaccessprovider.arcagentopenldap.domain.model.LdapEntry;
import com.instantaccessprovider.arcagentopenldap.domain.model.LdapNotification;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * Source des changements d'un annuaire LDAP. Une implémentation s'appuie sur un mécanisme du serveur
 * (Persistent Search, Syncrepl, journal {@code accesslog}...) ; le domaine n'en dépend pas.
 */
public interface LdapChangeSourcePort {

    /**
     * Ouvre le flux des changements à venir (aucun état initial n'est renvoyé).
     *
     * @param onNotification appelée pour chaque changement, depuis un thread de la source
     * @param onFailure      appelée une fois si le flux est interrompu (coupure, erreur serveur)
     * @return le flux ouvert, à fermer pour libérer la connexion
     */
    ChangeStream open(Consumer<LdapNotification> onNotification, Consumer<Throwable> onFailure);

    /** Entrées modifiées ou créées à partir de {@code since} (inclus, précision à la seconde). */
    List<LdapEntry> modifiedSince(Instant since);

    /** État courant de toutes les entrées suivies. */
    List<LdapEntry> snapshot();

    /** Flux ouvert par {@link #open}. */
    interface ChangeStream extends AutoCloseable {
        @Override
        void close();
    }
}

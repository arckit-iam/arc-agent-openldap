// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.domain.port;

import com.instantaccessprovider.arcagentopenldap.domain.model.LdapChangeType;

/**
 * Mémoire des écritures récentes de l'agent, pour reconnaître dans le flux de collecte les changements
 * qu'il a lui-même provoqués (anti-écho). L'auteur rapporté par l'annuaire n'est pas fiable pour une
 * suppression, d'où cette corrélation par DN et type de changement.
 */
public interface OwnWriteRegistryPort {

    /** À appeler avant l'écriture, pour que la notification ne puisse pas arriver avant l'enregistrement. */
    void recordWrite(String dn, LdapChangeType type);

    /** Vrai (et consomme l'enregistrement) si une écriture récente de l'agent correspond. */
    boolean consumeIfOwn(String dn, LdapChangeType type);
}

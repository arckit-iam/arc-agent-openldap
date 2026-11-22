// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Changement prêt à être publié sur le topic brut.
 *
 * @param type          nature du changement
 * @param dn            DN de l'entrée après le changement (dernier DN connu pour une suppression)
 * @param previousDn    ancien DN (renommage), sinon {@code null}
 * @param entryUuid     identifiant stable de l'entrée
 * @param objectClasses classes d'objets de l'entrée
 * @param timestamp     horodatage du changement
 * @param modifiedBy    auteur du changement, {@code null} si inconnu (toujours le cas d'une suppression)
 * @param origin        {@code arc-agent} (écriture de l'agent), {@code external}, {@code catch-up}
 *                      (rattrapage après arrêt) ou {@code reconcile} (suppression constatée après coupure)
 * @param changes       différences par attribut ; vide si l'état précédent est inconnu
 * @param entry         état de l'entrée après le changement ; {@code null} pour une suppression
 */
public record LdapChange(
        LdapChangeType type,
        String dn,
        String previousDn,
        String entryUuid,
        List<String> objectClasses,
        Instant timestamp,
        String modifiedBy,
        String origin,
        List<AttributeChange> changes,
        Map<String, List<String>> entry
) {

    public static final String ORIGIN_ARC_AGENT = "arc-agent";
    public static final String ORIGIN_EXTERNAL = "external";
    public static final String ORIGIN_CATCH_UP = "catch-up";
    public static final String ORIGIN_RECONCILE = "reconcile";
}

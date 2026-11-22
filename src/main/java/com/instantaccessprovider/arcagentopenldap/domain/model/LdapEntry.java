// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * État d'une entrée de l'annuaire à un instant donné.
 *
 * @param dn            nom distingué de l'entrée
 * @param entryUuid     identifiant stable de l'entrée ({@code entryUUID}), inchangé en cas de renommage
 * @param objectClasses classes d'objets
 * @param attributes    attributs utilisateur (hors attributs opérationnels et secrets)
 * @param author        dernier auteur connu ({@code modifiersName}, à défaut {@code creatorsName})
 * @param createdAt     date de création ({@code createTimestamp})
 * @param modifiedAt    date de dernière modification ({@code modifyTimestamp}), à défaut la création
 */
public record LdapEntry(
        String dn,
        String entryUuid,
        List<String> objectClasses,
        Map<String, List<String>> attributes,
        String author,
        Instant createdAt,
        Instant modifiedAt
) {}

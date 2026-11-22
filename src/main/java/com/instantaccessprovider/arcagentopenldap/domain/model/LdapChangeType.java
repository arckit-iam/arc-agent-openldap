// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.domain.model;

/** Nature d'un changement observé dans l'annuaire. */
public enum LdapChangeType {
    ADD,
    MODIFY,
    DELETE,
    MODRDN
}

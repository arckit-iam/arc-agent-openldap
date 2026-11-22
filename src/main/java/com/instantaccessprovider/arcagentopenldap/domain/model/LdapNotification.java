// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.domain.model;

/**
 * Notification brute reçue de l'annuaire.
 *
 * @param type       nature du changement
 * @param previousDn ancien DN (renommage uniquement), sinon {@code null}
 * @param entry      entrée concernée ; pour une suppression, dernier état connu par le serveur
 */
public record LdapNotification(LdapChangeType type, String previousDn, LdapEntry entry) {}

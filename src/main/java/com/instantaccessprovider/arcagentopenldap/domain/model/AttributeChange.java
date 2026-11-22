// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.domain.model;

import java.util.List;

/**
 * Différence sur un attribut.
 *
 * @param attribute nom de l'attribut
 * @param operation {@code add}, {@code replace} ou {@code delete}
 * @param oldValues valeurs avant le changement ({@code null} si l'attribut n'existait pas)
 * @param newValues valeurs après le changement ({@code null} si l'attribut a disparu)
 */
public record AttributeChange(
        String attribute,
        String operation,
        List<String> oldValues,
        List<String> newValues
) {}

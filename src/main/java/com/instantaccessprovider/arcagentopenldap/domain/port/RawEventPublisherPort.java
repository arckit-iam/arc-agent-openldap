// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.domain.port;

import com.instantaccessprovider.arcagentopenldap.domain.model.LdapChange;

/** Publication d'un changement sur le topic brut. */
public interface RawEventPublisherPort {

    /**
     * Publie le changement et ne rend la main qu'une fois la publication acquittée.
     *
     * @throws RuntimeException si la publication échoue
     */
    void publish(LdapChange change);
}

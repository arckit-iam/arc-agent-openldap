// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.domain.port;

import java.time.Instant;
import java.util.Optional;

/** Persistance du curseur de reprise : date de modification du dernier changement publié. */
public interface CursorStorePort {

    Optional<Instant> load(String system);

    /** Enregistre le curseur ; ne rend la main qu'une fois l'écriture acquittée. */
    void save(String system, Instant cursor);
}

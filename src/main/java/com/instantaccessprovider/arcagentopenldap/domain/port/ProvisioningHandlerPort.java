// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.domain.port;

import com.instantaccessprovider.arcagentopenldap.domain.ProvisioningCommand;

/**
 * Port d'execution d'un ordre de provisioning sur le systeme cible.
 */
public interface ProvisioningHandlerPort {

    /**
     * Execute (ou simule en dry-run) la commande.
     *
     * @param command ordre de provisioning
     * @param topic   topic d'origine, qui determine le type d'objet vise (identite, droit, affectation)
     */
    void execute(ProvisioningCommand command, String topic);
}

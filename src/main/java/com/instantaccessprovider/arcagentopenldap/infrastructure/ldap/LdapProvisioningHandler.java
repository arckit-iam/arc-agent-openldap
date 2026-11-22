// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.infrastructure.ldap;

import com.instantaccessprovider.arcagentopenldap.config.AgentProperties;
import com.instantaccessprovider.arcagentopenldap.domain.ProvisioningCommand;
import com.instantaccessprovider.arcagentopenldap.domain.port.ProvisioningHandlerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.AttributeInUseException;
import org.springframework.ldap.NameAlreadyBoundException;
import org.springframework.ldap.NameNotFoundException;
import org.springframework.ldap.NoSuchAttributeException;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.support.LdapNameBuilder;
import org.springframework.stereotype.Component;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.ldap.LdapName;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Execute les ordres de provisioning sur un annuaire LDAP (schemas {@code inetOrgPerson} et
 * {@code groupOfNames}). Les valeurs inserees dans les DN sont echappees par {@link LdapNameBuilder} ;
 * les DN sont toujours construits sous les branches configurees ({@code usersOu}, {@code groupsOu}) et
 * seuls les attributs de la liste blanche sont ecrits.
 */
@Component
public class LdapProvisioningHandler implements ProvisioningHandlerPort {

    private static final Logger log = LoggerFactory.getLogger(LdapProvisioningHandler.class);

    /** Attributs ARC (en minuscules) et attributs LDAP natifs autorises pour une identite. */
    private static final Map<String, String> USER_ATTRIBUTES = Map.ofEntries(
            Map.entry("email", "mail"),
            Map.entry("mail", "mail"),
            Map.entry("firstname", "givenName"),
            Map.entry("givenname", "givenName"),
            Map.entry("lastname", "sn"),
            Map.entry("sn", "sn"),
            Map.entry("displayname", "displayName"),
            Map.entry("phone", "telephoneNumber"),
            Map.entry("telephonenumber", "telephoneNumber"),
            Map.entry("mobile", "mobile"),
            Map.entry("title", "title"),
            Map.entry("department", "departmentNumber"),
            Map.entry("departmentnumber", "departmentNumber"),
            Map.entry("company", "o"),
            Map.entry("description", "description")
    );

    /** Attributs autorises pour un groupe. */
    private static final Map<String, String> GROUP_ATTRIBUTES = Map.of(
            "description", "description",
            "displayname", "description"
    );

    private final AgentProperties properties;
    private final LdapTemplate ldapTemplate;

    public LdapProvisioningHandler(AgentProperties properties, LdapTemplate ldapTemplate) {
        this.properties = properties;
        this.ldapTemplate = ldapTemplate;
    }

    @Override
    public void execute(ProvisioningCommand command, String topic) {
        if (properties.dryRun()) {
            log.info("[DRY-RUN] jobId={} action={} topic={} identity={} targetSystem={} executeAt={}",
                    command.jobId(), command.action(), topic, command.identityName(),
                    command.targetSystem(), command.executeAt());
            return;
        }

        log.info("Execution LDAP jobId={} action={} topic={} identity={}",
                command.jobId(), command.action(), topic, command.identityId());

        AgentProperties.Topics topics = properties.topics();
        String action = command.action() != null ? command.action().toUpperCase(Locale.ROOT) : "";

        if (topic.equals(topics.identity())) {
            switch (action) {
                case "CREATE" -> createUser(command);
                case "MODIFY" -> modifyUser(command);
                case "DELETE" -> deleteUser(command);
                default -> warnUnknownAction("identity", command);
            }
        } else if (topic.equals(topics.assignment())) {
            switch (action) {
                case "CREATE", "ADD" -> addGroupMember(command);
                case "DELETE", "REMOVE" -> removeGroupMember(command);
                default -> warnUnknownAction("assignment", command);
            }
        } else if (topic.equals(topics.entitlement())) {
            switch (action) {
                case "CREATE" -> createGroup(command);
                case "MODIFY" -> modifyGroup(command);
                case "DELETE" -> deleteGroup(command);
                default -> warnUnknownAction("entitlement", command);
            }
        } else {
            log.warn("Topic inconnu ignore: topic={} jobId={}", topic, command.jobId());
        }
    }

    // ---- Identites -------------------------------------------------------------------------

    private void createUser(ProvisioningCommand command) {
        String uid = requireNonBlank(command.identityId(), "identityId", command);
        LdapName dn = userDn(uid);

        Attributes attrs = new BasicAttributes(true);
        attrs.put(objectClass("top", "person", "organizationalPerson", "inetOrgPerson"));
        attrs.put("uid", uid);
        String displayName = firstNonBlank(command.identityName(), uid);
        attrs.put("cn", displayName);

        Map<String, String> values = allowedValues(command, USER_ATTRIBUTES);
        // sn est obligatoire dans inetOrgPerson
        values.putIfAbsent("sn", uid);
        values.forEach(attrs::put);

        try {
            ldapTemplate.bind(dn, null, attrs);
            log.info("Utilisateur cree: dn={}", dn);
        } catch (NameAlreadyBoundException e) {
            log.info("Utilisateur deja present, creation ignoree: dn={}", dn);
        }
    }

    private void modifyUser(ProvisioningCommand command) {
        LdapName dn = userDn(requireNonBlank(command.identityId(), "identityId", command));
        Map<String, String> values = allowedValues(command, USER_ATTRIBUTES);
        if (values.isEmpty()) {
            log.info("Aucun attribut modifiable fourni, rien a faire: dn={}", dn);
            return;
        }
        ldapTemplate.modifyAttributes(dn, replaceItems(values));
        log.info("Utilisateur modifie: dn={} attributs={}", dn, values.keySet());
    }

    private void deleteUser(ProvisioningCommand command) {
        LdapName dn = userDn(requireNonBlank(command.identityId(), "identityId", command));
        try {
            ldapTemplate.unbind(dn);
            log.info("Utilisateur supprime: dn={}", dn);
        } catch (NameNotFoundException e) {
            log.info("Utilisateur deja absent, suppression ignoree: dn={}", dn);
        }
    }

    // ---- Groupes (entitlements) ------------------------------------------------------------

    private void createGroup(ProvisioningCommand command) {
        String cn = requireNonBlank(resolveEntitlementId(command), "entitlementId", command);
        LdapName dn = groupDn(cn);

        Attributes attrs = new BasicAttributes(true);
        attrs.put(objectClass("top", "groupOfNames"));
        attrs.put("cn", cn);
        attrs.put("member", placeholderMember());
        String description = firstNonBlank(command.entitlementName(), command.identityName());
        if (description != null) {
            attrs.put("description", description);
        }

        try {
            ldapTemplate.bind(dn, null, attrs);
            log.info("Groupe cree: dn={}", dn);
        } catch (NameAlreadyBoundException e) {
            log.info("Groupe deja present, creation ignoree: dn={}", dn);
        }
    }

    private void modifyGroup(ProvisioningCommand command) {
        LdapName dn = groupDn(requireNonBlank(resolveEntitlementId(command), "entitlementId", command));
        Map<String, String> values = allowedValues(command, GROUP_ATTRIBUTES);
        if (values.isEmpty()) {
            log.info("Aucun attribut modifiable fourni, rien a faire: dn={}", dn);
            return;
        }
        ldapTemplate.modifyAttributes(dn, replaceItems(values));
        log.info("Groupe modifie: dn={} attributs={}", dn, values.keySet());
    }

    private void deleteGroup(ProvisioningCommand command) {
        LdapName dn = groupDn(requireNonBlank(resolveEntitlementId(command), "entitlementId", command));
        try {
            ldapTemplate.unbind(dn);
            log.info("Groupe supprime: dn={}", dn);
        } catch (NameNotFoundException e) {
            log.info("Groupe deja absent, suppression ignoree: dn={}", dn);
        }
    }

    // ---- Affectations (membres de groupe) --------------------------------------------------

    private void addGroupMember(ProvisioningCommand command) {
        LdapName groupDn = assignmentGroupDn(command);
        String memberDn = assignmentUserDn(command).toString();
        ModificationItem mod = new ModificationItem(DirContext.ADD_ATTRIBUTE, new BasicAttribute("member", memberDn));
        try {
            ldapTemplate.modifyAttributes(groupDn, new ModificationItem[]{mod});
            log.info("Membre ajoute: user={} group={}", memberDn, groupDn);
        } catch (AttributeInUseException e) {
            log.info("Membre deja present, ajout ignore: user={} group={}", memberDn, groupDn);
        }
    }

    private void removeGroupMember(ProvisioningCommand command) {
        LdapName groupDn = assignmentGroupDn(command);
        String memberDn = assignmentUserDn(command).toString();
        if (memberDn.equalsIgnoreCase(placeholderMember())) {
            log.warn("Retrait du membre technique refuse: group={} jobId={}", groupDn, command.jobId());
            return;
        }
        ModificationItem mod = new ModificationItem(DirContext.REMOVE_ATTRIBUTE, new BasicAttribute("member", memberDn));
        try {
            ldapTemplate.modifyAttributes(groupDn, new ModificationItem[]{mod});
            log.info("Membre retire: user={} group={}", memberDn, groupDn);
        } catch (NoSuchAttributeException e) {
            log.info("Membre deja absent, retrait ignore: user={} group={}", memberDn, groupDn);
        }
    }

    private LdapName assignmentUserDn(ProvisioningCommand command) {
        ProvisioningCommand.NestedTarget target = command.identity();
        String uid = target != null
                ? firstNonBlank(target.externalId(), target.name(), target.displayName(), command.identityId())
                : command.identityId();
        return userDn(requireNonBlank(uid, "identity", command));
    }

    private LdapName assignmentGroupDn(ProvisioningCommand command) {
        ProvisioningCommand.NestedTarget target = command.entitlement();
        String cn = target != null
                ? firstNonBlank(target.externalId(), target.name(), target.displayName(), command.entitlementId())
                : command.entitlementId();
        return groupDn(requireNonBlank(cn, "entitlement", command));
    }

    // ---- Utilitaires -----------------------------------------------------------------------

    private LdapName userDn(String uid) {
        return LdapNameBuilder.newInstance(branch(properties.ldap().usersOu())).add("uid", uid).build();
    }

    private LdapName groupDn(String cn) {
        return LdapNameBuilder.newInstance(branch(properties.ldap().groupsOu())).add("cn", cn).build();
    }

    private String branch(String relativeOu) {
        return relativeOu + "," + properties.ldap().baseDn();
    }

    private String placeholderMember() {
        AgentProperties.Ldap ldap = properties.ldap();
        if (ldap.groupPlaceholderMember() != null && !ldap.groupPlaceholderMember().isBlank()) {
            return ldap.groupPlaceholderMember();
        }
        if (ldap.bindDn() != null && !ldap.bindDn().isBlank()) {
            return ldap.bindDn();
        }
        return "cn=arc-placeholder," + ldap.baseDn();
    }

    private static String resolveEntitlementId(ProvisioningCommand command) {
        // Pour les evenements entitlement plats, externalId est a la racine et mappe sur identityId
        return firstNonBlank(command.entitlementId(), command.identityId());
    }

    /** Valeurs non nulles de la commande, limitees a la liste blanche, indexees par attribut LDAP. */
    private static Map<String, String> allowedValues(ProvisioningCommand command, Map<String, String> allowed) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        if (command.attributes() == null) {
            return result;
        }
        for (ProvisioningCommand.ChangedAttribute attribute : command.attributes()) {
            if (attribute == null || attribute.name() == null || attribute.newValue() == null) {
                continue;
            }
            String ldapName = allowed.get(attribute.name().toLowerCase(Locale.ROOT));
            if (ldapName == null) {
                log.warn("Attribut ignore (hors liste blanche): name={} jobId={}", attribute.name(), command.jobId());
                continue;
            }
            result.put(ldapName, attribute.newValue().toString());
        }
        return result;
    }

    private static ModificationItem[] replaceItems(Map<String, String> values) {
        List<ModificationItem> items = new ArrayList<>();
        values.forEach((name, value) -> items.add(
                new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute(name, value))));
        return items.toArray(ModificationItem[]::new);
    }

    private static Attribute objectClass(String... classes) {
        Attribute attribute = new BasicAttribute("objectClass");
        for (String objectClass : classes) {
            attribute.add(objectClass);
        }
        return attribute;
    }

    private static String requireNonBlank(String value, String field, ProvisioningCommand command) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Champ obligatoire absent: " + field + " (jobId=" + command.jobId() + ")");
        }
        return value;
    }

    private static void warnUnknownAction(String type, ProvisioningCommand command) {
        log.warn("Action inconnue sur topic {}: action={} jobId={}", type, command.action(), command.jobId());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

// SPDX-FileCopyrightText: 2026 DÃ©dale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.arcagentopenldap.infrastructure.ldap;

import com.instantaccessprovider.arcagentopenldap.config.AgentProperties;
import com.instantaccessprovider.arcagentopenldap.domain.ProvisioningCommand;
import org.apache.directory.server.annotations.CreateLdapServer;
import org.apache.directory.server.annotations.CreateTransport;
import org.apache.directory.server.core.annotations.ContextEntry;
import org.apache.directory.server.core.annotations.CreateDS;
import org.apache.directory.server.core.annotations.CreatePartition;
import org.apache.directory.server.core.integ.AbstractLdapTestUnit;
import org.apache.directory.server.core.integ.ApacheDSTestExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.NamingException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests d'integration du provisioning contre un Apache DS embarque (partition en memoire, port ephemere).
 */
@ExtendWith(ApacheDSTestExtension.class)
@CreateDS(
        name = "arc-test",
        partitions = @CreatePartition(
                name = "example",
                suffix = "dc=example,dc=com",
                contextEntry = @ContextEntry(entryLdif =
                        "dn: dc=example,dc=com\n" +
                        "dc: example\n" +
                        "objectClass: top\n" +
                        "objectClass: domain\n\n")))
@CreateLdapServer(transports = @CreateTransport(protocol = "LDAP"))
public class LdapProvisioningHandlerTest extends AbstractLdapTestUnit {

    private static final String BASE = "dc=example,dc=com";
    private static final String TOPIC_IDENTITY = "arc.provisioning.identity";
    private static final String TOPIC_ASSIGNMENT = "arc.provisioning.assignment";
    private static final String TOPIC_ENTITLEMENT = "arc.provisioning.entitlement";

    private LdapTemplate ldapTemplate;
    private LdapProvisioningHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        LdapContextSource source = new LdapContextSource();
        source.setUrl("ldap://localhost:" + getLdapServer().getPort());
        source.setUserDn("uid=admin,ou=system");
        source.setPassword("secret");
        source.setPooled(false);
        source.afterPropertiesSet();
        ldapTemplate = new LdapTemplate(source);

        createOuIfAbsent("Users");
        createOuIfAbsent("Groups");

        handler = new LdapProvisioningHandler(properties(false), ldapTemplate);
    }

    @Test
    void createModifyDeleteUser() throws Exception {
        handler.execute(command("CREATE", "jdoe", "John Doe", List.of(
                attr("email", "john@example.com"),
                attr("unknownAttr", "x"))), TOPIC_IDENTITY);

        Attributes created = read("uid=jdoe,ou=Users");
        assertEquals("John Doe", created.get("cn").get());
        assertEquals("jdoe", created.get("sn").get());
        assertEquals("john@example.com", created.get("mail").get());
        assertTrue(values(created, "objectClass").contains("inetOrgPerson"));
        assertNull(created.get("unknownAttr"));

        handler.execute(command("MODIFY", "jdoe", null, List.of(
                attr("email", "john.doe@example.com"), attr("lastName", "Doe"))), TOPIC_IDENTITY);

        Attributes modified = read("uid=jdoe,ou=Users");
        assertEquals("john.doe@example.com", modified.get("mail").get());
        assertEquals("Doe", modified.get("sn").get());

        handler.execute(command("DELETE", "jdoe", null, null), TOPIC_IDENTITY);
        assertFalse(exists("uid=jdoe,ou=Users"));
    }

    @Test
    void createUserTwiceAndDeleteMissingUserAreIdempotent() throws Exception {
        handler.execute(command("CREATE", "twice", "Twice", null), TOPIC_IDENTITY);
        handler.execute(command("CREATE", "twice", "Twice", null), TOPIC_IDENTITY);
        assertTrue(exists("uid=twice,ou=Users"));

        handler.execute(command("DELETE", "twice", null, null), TOPIC_IDENTITY);
        handler.execute(command("DELETE", "twice", null, null), TOPIC_IDENTITY);
        assertFalse(exists("uid=twice,ou=Users"));
    }

    @Test
    void specialCharactersInIdentifierAreEscapedInDn() throws Exception {
        handler.execute(command("CREATE", "a,ou=evil", "Evil", null), TOPIC_IDENTITY);

        assertTrue(exists("uid=a\\,ou\\=evil,ou=Users"));
        assertFalse(exists("ou=evil,ou=Users"));

        handler.execute(command("DELETE", "a,ou=evil", null, null), TOPIC_IDENTITY);
    }

    @Test
    void groupLifecycleWithMembers() throws Exception {
        handler.execute(command("CREATE", "alice", "Alice", null), TOPIC_IDENTITY);
        handler.execute(command("CREATE", "bob", "Bob", null), TOPIC_IDENTITY);

        ProvisioningCommand createGroup = new ProvisioningCommand("job", "CREATE", "grp-fin", "Finance",
                "ldap://LDAP01", "grp-fin", "Finance", null, null, null, null, null, null, null);
        handler.execute(createGroup, TOPIC_ENTITLEMENT);

        Attributes group = read("cn=grp-fin,ou=Groups");
        assertTrue(values(group, "objectClass").contains("groupOfNames"));
        assertEquals(Set.of("cn=arc-placeholder," + BASE), values(group, "member"));

        handler.execute(assignment("CREATE", "alice", "grp-fin"), TOPIC_ASSIGNMENT);
        handler.execute(assignment("ADD", "bob", "grp-fin"), TOPIC_ASSIGNMENT);
        handler.execute(assignment("CREATE", "alice", "grp-fin"), TOPIC_ASSIGNMENT); // idempotent

        assertEquals(Set.of("cn=arc-placeholder," + BASE, "uid=alice,ou=Users," + BASE, "uid=bob,ou=Users," + BASE),
                values(read("cn=grp-fin,ou=Groups"), "member"));

        handler.execute(assignment("DELETE", "alice", "grp-fin"), TOPIC_ASSIGNMENT);
        handler.execute(assignment("REMOVE", "alice", "grp-fin"), TOPIC_ASSIGNMENT); // idempotent
        assertEquals(Set.of("cn=arc-placeholder," + BASE, "uid=bob,ou=Users," + BASE),
                values(read("cn=grp-fin,ou=Groups"), "member"));

        // le membre technique ne peut pas etre retire
        handler.execute(assignment("DELETE", "arc-placeholder", "grp-fin"), TOPIC_ASSIGNMENT);
        assertTrue(values(read("cn=grp-fin,ou=Groups"), "member").contains("cn=arc-placeholder," + BASE));

        handler.execute(new ProvisioningCommand("job", "DELETE", "grp-fin", null, "ldap://LDAP01",
                "grp-fin", null, null, null, null, null, null, null, null), TOPIC_ENTITLEMENT);
        assertFalse(exists("cn=grp-fin,ou=Groups"));

        handler.execute(command("DELETE", "alice", null, null), TOPIC_IDENTITY);
        handler.execute(command("DELETE", "bob", null, null), TOPIC_IDENTITY);
    }

    @Test
    void dryRunDoesNotWriteAnything() throws Exception {
        LdapProvisioningHandler dryRun = new LdapProvisioningHandler(properties(true), ldapTemplate);

        dryRun.execute(command("CREATE", "ghost", "Ghost", null), TOPIC_IDENTITY);

        assertFalse(exists("uid=ghost,ou=Users"));
    }

    @Test
    void missingIdentifierIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> handler.execute(command("CREATE", " ", "Nobody", null), TOPIC_IDENTITY));
    }

    // ---- aides -----------------------------------------------------------------------------

    private static AgentProperties properties(boolean dryRun) {
        return new AgentProperties(
                "ldap://LDAP01",
                new AgentProperties.Topics(TOPIC_IDENTITY, TOPIC_ASSIGNMENT, TOPIC_ENTITLEMENT),
                dryRun,
                new AgentProperties.Ldap("ldap://unused", BASE, "", "", "ou=Users", "ou=Groups", ""));
    }

    private static ProvisioningCommand command(String action, String uid, String displayName,
                                               List<ProvisioningCommand.ChangedAttribute> attributes) {
        return new ProvisioningCommand("job-" + uid, action, uid, displayName, "ldap://LDAP01",
                null, null, null, attributes, null, "corr", null, null, null);
    }

    private static ProvisioningCommand assignment(String action, String uid, String group) {
        return new ProvisioningCommand("job-assign", action, uid, null, "ldap://LDAP01",
                group, null, null, null, null, "corr", null,
                new ProvisioningCommand.NestedTarget(uid, uid, uid, "user", "ldap://LDAP01"),
                new ProvisioningCommand.NestedTarget(group, group, group, "group", "ldap://LDAP01"));
    }

    private static ProvisioningCommand.ChangedAttribute attr(String name, String value) {
        return new ProvisioningCommand.ChangedAttribute(name, null, value);
    }

    private void createOuIfAbsent(String ou) {
        if (exists("ou=" + ou)) {
            return;
        }
        Attributes attrs = new BasicAttributes(true);
        BasicAttribute objectClass = new BasicAttribute("objectClass");
        objectClass.add("top");
        objectClass.add("organizationalUnit");
        attrs.put(objectClass);
        attrs.put("ou", ou);
        ldapTemplate.bind(buildName("ou=" + ou), null, attrs);
    }

    private Attributes read(String relativeDn) {
        return ldapTemplate.lookup(buildName(relativeDn), (org.springframework.ldap.core.AttributesMapper<Attributes>) a -> a);
    }

    private boolean exists(String relativeDn) {
        try {
            ldapTemplate.lookup(buildName(relativeDn));
            return true;
        } catch (org.springframework.ldap.NameNotFoundException e) {
            return false;
        }
    }

    private static javax.naming.Name buildName(String relativeDn) {
        try {
            return new javax.naming.ldap.LdapName(relativeDn + "," + BASE);
        } catch (NamingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Set<String> values(Attributes attrs, String name) throws NamingException {
        Set<String> result = new HashSet<>();
        var all = attrs.get(name).getAll();
        while (all.hasMore()) {
            result.add(all.next().toString());
        }
        return result;
    }
}

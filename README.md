# arc-agent-openldap

Agent de provisioning LDAP (OpenLDAP, Apache DS, tout annuaire LDAPv3 de schéma `inetOrgPerson` / `groupOfNames`) pour InstantAccessProvider.

> **Statut : première version.** Seul le provisioning est implémenté. La collecte temps réel des changements vers Kafka (`iam.raw.ldap`) est en conception : voir [arc-docs, document 12](https://github.com/arckit-iam/arc-docs/blob/main/docs/12-agent-openldap.md).

## But

- Consommer les commandes publiées par `arc-publisher` sur `arc.provisioning.*`
- Filtrer strictement sur `targetSystem` (`ldap://LDAP01`, …)
- Exécuter l'opération LDAP correspondante, ou la simuler en mode dry-run

Ce design permet de lancer **1 pod par annuaire cible**. Le contrat d'entrée est identique à celui de `arc-agent-ad`.

## Opérations

| Topic | Action | Opération LDAP |
|---|---|---|
| `arc.provisioning.identity` | `CREATE` | ajout d'une entrée `inetOrgPerson` (`uid=<identityId>`) sous `ARC_AGENT_LDAP_USERS_OU` |
| | `MODIFY` | remplacement des attributs fournis |
| | `DELETE` | suppression de l'entrée |
| `arc.provisioning.entitlement` | `CREATE` / `MODIFY` / `DELETE` | idem pour une entrée `groupOfNames` (`cn=<entitlementId>`) sous `ARC_AGENT_LDAP_GROUPS_OU` |
| `arc.provisioning.assignment` | `CREATE` ou `ADD` | ajout de la valeur `member` au groupe |
| | `DELETE` ou `REMOVE` | retrait de la valeur `member` |

Les opérations sont idempotentes (création d'une entrée existante, retrait d'un membre absent… sont ignorés), car Kafka livre au moins une fois.

Particularités :

- `groupOfNames` impose au moins un `member` : un membre technique est ajouté à la création du groupe et ne peut pas être retiré (`ARC_AGENT_LDAP_GROUP_PLACEHOLDER_MEMBER`, par défaut le compte de service, sinon `cn=arc-placeholder,<base>`).
- `sn` est obligatoire dans `inetOrgPerson` : il vaut l'identifiant si aucun nom de famille n'est fourni.
- Seuls les attributs d'une liste blanche sont écrits (`email`, `firstName`, `lastName`, `displayName`, `phone`, `mobile`, `title`, `department`, `company`, `description`, ainsi que leurs noms LDAP) ; les autres sont ignorés avec un avertissement.
- Les valeurs insérées dans les DN sont échappées, et les DN sont toujours construits sous les branches configurées.

## Configuration

| Variable | Rôle | Défaut |
|---|---|---|
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Broker Kafka | `localhost:9092` |
| `TARGET_SYSTEM` | Annuaire cible géré par cette instance | `ldap://LDAP01` |
| `ARC_AGENT_CONSUMER_GROUP` | Groupe de consommateurs | `arc-agent-openldap` |
| `ARC_AGENT_DRY_RUN` | Simulation sans écriture | `true` |
| `ARC_TOPICS_PROVISIONING_IDENTITY` / `_ASSIGNMENT` / `_ENTITLEMENT` | Topics consommés | `arc.provisioning.identity` / `.assignment` / `.entitlement` |
| `ARC_AGENT_LDAP_URL` | URL de l'annuaire (`ldaps://` hors démonstration) | `ldap://localhost:10389` |
| `ARC_AGENT_LDAP_BASE_DN` | Racine de l'annuaire | `dc=example,dc=com` |
| `ARC_AGENT_LDAP_BIND_DN` / `ARC_AGENT_LDAP_BIND_PASSWORD` | Compte de service (vide : connexion anonyme) | vide |
| `ARC_AGENT_LDAP_USERS_OU` / `ARC_AGENT_LDAP_GROUPS_OU` | Branches des identités et des groupes, relatives à la racine | `ou=Users` / `ou=Groups` |
| `ARC_AGENT_LDAP_GROUP_PLACEHOLDER_MEMBER` | Membre technique des groupes | compte de service |

Fournir le mot de passe par secret d'exécution, jamais dans l'image ni dans le dépôt.

### Certificats (LDAPS)

L'image n'embarque aucune autorité de certification. Pour joindre un annuaire en LDAPS avec une CA privée, fournir son propre truststore JKS (monté en volume) :

```bash
docker run -v ./ca.jks:/certs/ca.jks:ro \
  -e JAVA_OPTS="-Djavax.net.ssl.trustStore=/certs/ca.jks -Djavax.net.ssl.trustStorePassword=changeit" \
  ghcr.io/arckit-iam/arc-agent-openldap:stable
```

Le truststore remplace celui de la JVM : il doit contenir la CA de l'annuaire et, si besoin, les CA publiques.

## Lancement local

```bash
mvn spring-boot:run
```

## Build et tests

```bash
mvn clean package
```

Les tests d'intégration démarrent un annuaire **Apache DS embarqué** (partition en mémoire, port éphémère) : aucun serveur à installer. Apache DS n'est utilisé que pour les tests et n'est pas livré dans l'image.

## Documentation

La documentation d'architecture de la plateforme est dans le dépôt [arc-docs](https://github.com/arckit-iam/arc-docs) :

- [Architecture globale](https://github.com/arckit-iam/arc-docs/blob/main/docs/02-architecture-globale.md).
- [Agent OpenLDAP et collecte LDAP temps réel](https://github.com/arckit-iam/arc-docs/blob/main/docs/12-agent-openldap.md).

Le déploiement GitOps (Helm, FluxCD) est géré dans un dépôt privé. L'intégration et la mise en production de la plateforme sont proposées par Dédale Solutions en **service payant** : pour en discuter, contactez-nous à [etiennsalle@gmail.com](mailto:etiennsalle@gmail.com).

## Licence

Copyright © 2026 Dédale Solutions

Ce projet est distribué sous la licence [EUPL-1.2](LICENSE) (European Union Public Licence v. 1.2).

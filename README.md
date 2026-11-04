# Lineward

[![Build](https://github.com/devAyyoub/Lineward/actions/workflows/build.yml/badge.svg)](https://github.com/devAyyoub/Lineward/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**Open source Identity Governance and Administration (IGA) platform, with governance for AI agents.**

Lineward aggregates accounts from heterogeneous systems, correlates them with identities, provisions access through approval workflows with segregation of duties control, produces audit evidence for recertification campaigns, and extends that same governance to the AI agents acting on people's behalf.

> **Status: work in progress. Phase 1 of 8.**
> Nothing provisions anything yet, and nothing is reconciled or reported. What works is the identity lifecycle and the access that follows from it. The platform reads the authoritative source over HTTP, page by page or only what changed, and stores what that source asserted separately from its own governed view of each person. Re-reading an unchanged extract writes nothing. Comparing two assertions classifies the change as a joiner, a mover or a leaver, the retroactive termination included, and from an identity's attributes it computes the access that identity is due by rule, recalculating whenever the lifecycle state moves. Two target system connectors read for real, and between them they are the test of whether the connector interface was an abstraction or wishful thinking. Against a Keycloak realm, over its admin API with a service account that can read and cannot write, it lists the accounts that exist, the permissions the realm can grant and which accounts hold them; it pages by asking for one row more than it needs, which is the only way to tell a finished read from an interrupted one, and it walks the group tree rather than listing it, because Keycloak's group endpoint serves only the top level; and it reads the accounts in two passes rather than one, because the user list does not return the service accounts of clients and reaching those needs a second endpoint and a wider role. A read that cannot see the clients refuses instead of reporting what it did see, since the alternative is a realm whose every machine account looks deleted. Against an OpenLDAP directory it reads the same three things over LDAP, and nothing about the interface had to change for a system that works differently in every respect: membership lives on the group rather than on the account, because `memberOf` does not exist in a directory until an overlay is configured to maintain it; paging is the cookie of RFC 2696, which cannot be reconstructed by the caller and does not survive its own connection, so a read is a session and a cursor from a closed one is refused rather than answered with the first page; and an account is identified by the identifier the server assigns, not by its distinguished name, which changes the day somebody is moved between organizational units. Under all of it sits the foundation: the schema with per-tenant isolation enforced by PostgreSQL row level security, tested against a real PostgreSQL. On top of those two connectors, aggregation now closes the loop: it reads the permissions a target system publishes and registers the ones the catalogue did not have, writes down every account the system reports along with what that system said about it, and says which identity each account belongs to, by employee number and then by email. Against the directory this repository ships that is twelve accounts, ten of them matched and two that belong to nobody, which is the finding an aggregation is run to produce rather than an error. Reading the same directory again links nothing and revokes nothing, and still reports those two. What is not built yet is the subtraction between the access a person is due and the access they have, so nothing is yet reconciled or reported as drift; correlation does not run against the accounts a system calls machines, and that is the design rather than a gap: a service account often carries the employee number of whoever created it, and the owner of an account is who answers for its access in a review, so who answers for a machine is a decision left to a person and the report counts those apart from the accounts whose owner no rule could find; a read that is interrupted starts again rather than resuming; and the Okta connector is still interface-only. This README tracks real progress, not intentions.

---

## The problem

In any organization above a few hundred people, nobody can answer three questions with confidence:

1. **Who has access to what?** Accounts live in dozens of disconnected systems: a directory, an identity provider, a payroll application, a dozen SaaS products.
2. **Why do they have it?** Access accumulates. People change teams and keep the permissions of their previous role, a pattern known as privilege creep. Somebody granted a permission by hand two years ago and nobody remembers why.
3. **Should they still have it?** Accounts outlive the people who owned them. Orphan accounts, the most common audit finding in access control, belong to nobody and often still work.

IGA is the discipline that answers those questions, and it is what regulators ask about first when they audit access control. Lineward implements it end to end.

And it adds the dimension that matters now: AI agents act with credentials, and they bring back every problem IGA already solved for people, multiplied by their speed and autonomy. Agents with shared API keys that never expire. Agents with more permissions than they need. Agents still running after the project that created them ended. No way to tell **on whose behalf** an agent acted.

**Lineward treats AI agents as first class identities, governed exactly like human ones.**

---

## What it does

| Module | Capability | Phase | Status |
|---|---|---|---|
| M1 | Identities and authoritative source, with joiner, mover and leaver lifecycle events | 1 | In progress: ingestion and lifecycle event derivation work; checkpoints and resumption do not |
| M2 | Aggregation, correlation and reconciliation, including orphan account and drift detection | 1 | In progress: a target system's permissions and accounts are read and stored, and every account a system does not call a machine is correlated to an identity by employee number or email; reconciliation, dormant account detection, drift and resumable reads are planned |
| M3 | Connectors and provisioning: Keycloak, LDAP, SCIM 2.0, plus a deliberately unreliable app to prove resilience | 1 reading, 2 writing | In progress: the Keycloak and OpenLDAP connectors read accounts and permissions from a real server of each; Okta and SCIM are interface-only, and nothing writes yet |
| M4 | Access model: entitlements, business roles, access lineage, basic role mining | 1 birthright, 3 the rest | In progress: rule based access is computed and recalculated on a lifecycle move, and a target system can register the permissions it publishes into the catalogue; everything else is planned |
| M5 | Access requests and configurable approval workflows | 3 | Planned |
| M6 | Segregation of duties, preventive and detective, with exceptions and a conflict simulator | 3 | Planned |
| M7 | Access certification campaigns, including rubber stamping detection | 4 | Planned |
| M8 | Hash chained audit log with an integrity verifier, and compliance reporting | 4 | Planned |
| M9 | Real time authorization: a policy decision point with ABAC policies as code | 5 | Planned |
| M10 | AI agent identity governance: delegation, permission intersection, kill switch | 6 | Planned |
| M11 | AI agents serving IAM: certification copilot, natural language queries, MCP server | 7 | Planned |

---

## The AI angle

Lineward works on both sides of the same problem.

**IAM for AI: governing agents.** An agent is an identity with a lifecycle, a mandatory human sponsor, its own access, its own certification campaigns and its own audit trail. Its tools are modeled as entitlements, so being allowed to call `request_access` is a permission that gets requested, approved and recertified like any other. When an agent acts for a person, delegation is explicit: the token carries the person as subject and the agent as actor, following RFC 8693 token exchange. **Effective permissions are the intersection of both**, so an agent acting for someone can never do something that person could not do. If the sponsor leaves the organization, the agent is suspended automatically. There is a kill switch, per agent and global.

**AI for IAM: agents that help.** Certification campaigns fail in practice because reviewers approve everything without looking, and they do that because they lack context. A copilot that summarizes usage, age, risk, SoD conflicts and comparison with peers in the same role turns a meaningless click into an informed decision. The agent proposes, the human decides. Queries run through typed read only tools, never free SQL, and always with the permissions of whoever is asking.

Every text coming from outside is treated as untrusted data, never as instructions, and the project ships an adversarial eval suite that runs in CI.

---

## Architecture

A modular monolith. Strict internal module boundaries verified by architecture tests, without the operational cost of microservices.

A module's base package is its public API and every nested package is internal, which a test enforces rather than a convention. So a flat base package is not an untidy one, it is the boundary. Each package carries its reading order in its `package-info.java`, starting with `com.lineward` itself, and a test fails the build when a package with types in it does not say where to begin.

**This diagram is the target architecture, not what exists today.** The console, the message broker, the MCP server and both demo applications are not built, and the status of each module is in the table above rather than here. It is drawn in full because the module boundaries are decided and enforced now, which is what makes the empty packages empty rather than absent: `provisioning` holds nothing but its `package-info.java`, and reading a target system is `connector`, which is the one with code in it.

```mermaid
flowchart LR
    HR[HR simulator<br/>REST API] -->|JML| CORE

    subgraph CORE[Lineward · modular monolith]
        ID[M1 Identity]
        AGG[M2 Aggregation]
        CONN[M3 Connectors<br/>reading]
        PROV[M3 Provisioning<br/>writing]
        ACC[M4 Access model]
        REQ[M5 Requests]
        SOD[M6 SoD]
        CERT[M7 Certification]
        AUD[M8 Audit]
        PDP[M9 PDP]
        AGT[M10 and M11 Agents]
    end

    CORE --> PG[(PostgreSQL)]
    CORE <--> MQ[[Message broker]]
    AGG --> CONN
    CONN --> KC[Keycloak]
    CONN --> LDAP[OpenLDAP]
    PROV --> KC
    PROV --> LDAP
    PROV --> SCIM[Demo SCIM app]
    PROV --> FLAKY[Flaky app]
    UI[Next.js console] -->|OIDC + API| CORE
    KC -.->|OIDC login| UI
    MCPC[MCP client] -->|OAuth| MCPS[MCP server]
    MCPS --> CORE
    AGT --> LLM[LLM provider]
    CORE --> OTEL[OpenTelemetry] --> OBS[Prometheus + Grafana]
```

### Standards

The project implements published specifications rather than inventing its own: OAuth 2.x, OpenID Connect, PKCE, SCIM 2.0 (RFC 7643 and RFC 7644), LDAP, Token Exchange (RFC 8693), DPoP (RFC 9449), Rich Authorization Requests (RFC 9396), CIBA and the MCP authorization specification. Compliance capabilities are mapped against DORA, NIS2, ISO/IEC 27001 and the Spanish ENS.

### Stack

Java, Spring Boot, PostgreSQL, Flyway, Keycloak, OpenLDAP, Next.js, Testcontainers, OpenTelemetry, Docker Compose, GitHub Actions.

Exact versions are pinned in [ADR-006](docs/adr/0006-stack-versions.md), with the reasoning for each one and the triggers that would justify revisiting it.

---

## Getting started

The environment comes up, both services run, and the platform ingests the simulated HR population. You need Docker and a JDK 25.

**A JDK 25 to run it, not only to build it.** A Maven toolchain compiles this project with 25 whatever JDK starts Maven, which is the decision in ADR-006, so `./mvnw verify` passes on a machine whose `java` is older while the jars it just built refuse to start there with `UnsupportedClassVersionError` and a class file version. Point `JAVA_HOME` at a 25 before running anything by hand.

```bash
# PostgreSQL 18, Keycloak 26 and OpenLDAP 2.6. On first start: the two database
# roles that tenant isolation depends on, the realm and the directory the
# connectors read, and the access rules that directory needs.
docker compose -f infra/docker-compose.yml up -d

# Build and test everything: the platform, the synthetic data generator and the
# simulated HR system. Includes the tenant isolation suite against a throwaway
# PostgreSQL, so a running Docker daemon is required.
./mvnw verify
```

```bash
# Install the modules locally once, so the simulated HR system can resolve the
# generator it depends on.
./mvnw install -DskipTests

# The platform. Applies the schema migrations and starts on http://localhost:8080
./mvnw -pl backend spring-boot:run

# The simulated HR system, on http://localhost:8090. This is the authoritative
# source the platform reads from, and it is a separate process on purpose: if it
# shared a database with the platform, aggregation would consist of Lineward
# reading its own tables.
./mvnw -pl demo-apps/hr-simulator spring-boot:run
```

The simulated HR system generates a 5.000 employee company from a fixed seed, so
it serves the same population on every start. It also publishes the anomalies
seeded into it, which is what lets a test or a demo assert on a specific finding
rather than hope one turns up:

```bash
curl 'localhost:8090/api/v1/employees?page=0&size=5'   # the extract, paged
curl localhost:8090/api/v1/anomalies                   # what was seeded, and where

# Provoke the three lifecycle events. The termination accepts a date in the
# past, which is the retroactive leaver case.
curl -X PATCH localhost:8090/api/v1/employees/E-0500 \
  -H 'Content-Type: application/json' -d '{"department":"Risk"}'
curl -X POST localhost:8090/api/v1/employees/E-0501/termination \
  -H 'Content-Type: application/json' -d '{"terminationDate":"2026-09-20"}'

# Then read only what changed, which is what an incremental ingest does.
curl 'localhost:8090/api/v1/employees?changedSince=2026-10-15T23:00:00Z'
```

To have the platform read that population into its own tables, start it with the
ingestion flag. It is off by default and there is no endpoint for it on purpose:
the platform has no authentication layer yet, and an unauthenticated endpoint
that writes identity data is a worse thing to add than a flag. The scheduled run
and the API arrive with the tasks that need them.

```bash
LINEWARD_INGEST_ON_STARTUP=true ./mvnw -pl backend spring-boot:run
```

It reports what it did, and the three counts are separate for a reason: a run
where everything is unchanged is the normal case and the one that proves the run
is idempotent, while a run where everything counts as updated means change
detection is broken.

```text
Ingested the whole population from source 'hr': 5000 created, 0 updated,
0 unchanged; managers 4974 linked, 0 unlinked, 0 unresolved, 0 preserved
```

4974 is the headcount minus the chief executive, who reports to nobody, minus
the 25 missing-manager anomalies the generator seeded. Running it again reports
5000 unchanged and writes nothing.

Reading the target systems is a second flag, off by default for the same reason.
It reads every application the catalogue has enabled and that configuration
names a connector for, which with the systems this repository ships is the
Keycloak realm and the OpenLDAP tree.

```bash
LINEWARD_AGGREGATE_ON_STARTUP=true ./mvnw -pl backend spring-boot:run
```

```text
Aggregated 'corporate-directory': 13 permissions and 12 accounts read;
12 accounts new, 0 seen again; 27 memberships appeared, 0 were taken away;
correlation linked 10, unlinked 0, left 0 ambiguous, 2 unmatched and 0 machines
no rule may claim
Aggregated 'intranet': 6 permissions and 1 accounts read; 1 accounts new,
0 seen again; 0 memberships appeared, 0 were taken away; correlation linked 0,
unlinked 0, left 0 ambiguous, 0 unmatched and 1 machines no rule may claim
```

The counts that do not go to zero are the point of the exercise, and they are
not errors. The directory ships two accounts nobody can claim, a contractor who
was never registered in HR and a service account whose name a 2011 payroll
migration truncated, and finding those is what aggregation is run for. The realm
ships one machine account, which is Lineward's own reader, and it is reported
rather than filtered out: a platform that governs non-human identities holds one,
and hiding it would be making an exception for the account most worth seeing.
Only `linked` and `unlinked` go to zero on a second run, because they are the
two that count changes; the other three count what is still there in the
morning.

Credentials come from the environment. The values in `application.yaml` are the
local docker-compose ones and exist as a default, not as a secret, and the
connector configuration prints `<redacted>` for every one of them so a
configuration dump does not leak what it was given.

PostgreSQL is published on port 5434, Keycloak on 8081 and OpenLDAP on 1389, because 5432, 8080 and 389 are commonly taken or privileged. All three are bound to localhost only. Override any of it with the variables documented in [`infra/.env.example`](infra/.env.example).

Keycloak comes up with the `lineward` realm already imported from [`infra/keycloak/import/lineward-realm.json`](infra/keycloak/import/lineward-realm.json). That file is what the connector reads, and two things in it are load bearing. It declares the user profile attributes correlation matches on, because Keycloak silently discards any attribute the realm does not declare and discards it when the account is written, so a realm configured afterwards does not recover them. And it defines the client the connector authenticates as, holding `view-users` and `view-clients` and nothing else, so the credential Lineward stores for this system can list accounts, groups and clients and cannot create, change or delete one. The second role is what reaches the service accounts of clients, which the user list does not return, and it is the narrowest role that does: there is no read permission in Keycloak for a client's service account alone. The client secret is not in the file: the file names an environment variable, which a realm import substitutes. Note that an import is `IGNORE_EXISTING`, so changing the realm file after the first start does nothing until the realm is deleted.

OpenLDAP comes up with the tree in [`infra/ldap/import`](infra/ldap/import) already loaded, and with the access rules in [`infra/ldap/init`](infra/ldap/init) applied. Both are part of what this repository delivers rather than scaffolding for a test, and the second one matters more than it looks. The database this image creates declares no access rules of its own, and `slapd`'s fallback when a database declares none is to let everybody read everything: measured on 2.6.10, an anonymous client with no credential at all was served the `userPassword` of every account in the tree. The rules say that nobody reads a password hash, that the account Lineward aggregates with reads the tree and nothing else, and that everybody else gets nothing. The tree itself carries no password anywhere; the reader's password reaches the directory from an environment variable. The group names in it are character for character the ones the catalogue migration already seeded, so that comparing what the directory has against what the platform expects does not report every permission as a difference.

Two database roles are not an accident: the application connects as a role that owns nothing, so PostgreSQL row level security applies to it. Connecting as the owner would make every isolation policy silently do nothing. The reasoning is in [ADR-011](docs/adr/0011-multi-tenancy-isolation.md).

---

## Security

An identity governance platform concentrates the answer to "who has access to what" and holds administrator credentials for every target system, so it is a high value target by construction. The posture is recorded in [ADR-014](docs/adr/0014-development-security-posture.md) and reporting is covered by [SECURITY.md](SECURITY.md). Two rules do most of the work:

**A control goes where it cannot be bypassed, not where someone remembers not to bypass it.** Tenant isolation is enforced by the database rather than by remembering to filter queries, and the audit trail is append-only by permissions rather than by convention.

**No control is accepted until it has been seen to fail when it should.** This is not a style preference, it is the lesson of two concrete incidents in this repository. ADR-011 claimed row level security applied to Flyway and was corrected after the claim was tested rather than assumed. And on the day dependency scanning was added it reported **no issues found** after silently discarding all sixteen packages it had located, because our versions come from a BOM and it could not resolve them. Given the resolved tree instead, the same scan found ten vulnerabilities, three of them in the embedded server including an authentication bypass.

That second one generalises past this project: **declaring dependency versions centrally is good practice, and it leaves naive tooling with nothing to read.** A correct engineering decision can silently disable a security tool, with no error and a clean report. So the number to read off a security tool is not its verdict, it is how many things it says it checked.

What runs today: dependency scanning that fails the build on every dependency the build actually resolves, continuous monitoring of the resolved tree, secret scanning with push protection, tenant isolation tested against a real PostgreSQL including deliberate negative tests, and module boundaries verified in code by architecture tests. Across the database those same boundaries are reviewed discipline rather than an enforced rule, which [ADR-001](docs/adr/0001-modular-monolith.md) states and which the referential integrity test of phase 1 is meant to close. What else is deliberately absent is listed in ADR-014, because a control described as present and only half built is worse than one that is missing.

---

## Documentation

- [`docs/adr/`](docs/adr/): architecture decision records, one file per decision. This is the public technical record of the project: every significant choice, the alternatives weighed and the consequences accepted.
- [`docs/brand/`](docs/brand/): the visual identity and its usage guide.

The day to day working document, which holds the collaboration rules, the product strategy and the session log, is kept private. ADRs written before that split may reference its sections; the reasoning each one needed is restated inside the ADR itself.

Working documentation is written in Spanish, which is deliberate and recorded in [ADR-002](docs/adr/0002-languages.md). Code, APIs and public facing material are in English.

---

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 0 | Foundations: repository, ADRs, domain model, skeleton, CI | Done |
| 1 | Identities and aggregation, and the connectors that read target systems | In progress |
| 2 | Provisioning: writing to target systems, SCIM, retries and idempotency | Planned |
| 3 | Access model, requests and segregation of duties | Planned |
| 4 | Certification and audit | Planned |
| 5 | Real time authorization | Planned |
| 6 | AI agent governance | Planned |
| 7 | AI agents serving IAM, and the MCP server | Planned |
| 8 | Hardening, performance and public demo | Planned |

---

## About this project

Lineward is an independent project built by Ayyoub Amjahed Abed. It is **not affiliated with, endorsed by or derived from any employer or client**, and it is designed entirely from public standards, open product documentation and industry literature.

All data in the project is **synthetic**, generated reproducibly from a fixed seed. No real personal data is used anywhere, and none should be.

## License

Licensed under the [Apache License 2.0](LICENSE). See [ADR-008](docs/adr/0008-license.md) for why.

## Author

Ayyoub Amjahed Abed, [ayyoub.dev](https://ayyoub.dev)

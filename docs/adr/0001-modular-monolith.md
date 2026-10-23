# ADR-001 · Modular monolith over microservices

- **Status:** Accepted
- **Date:** 2026-10-04

## Context

The shape of the deployment has to be decided before the backend skeleton is created, because that decision determines whether the project is one Maven POM with a package per business module or several separately deployable artefacts. Writing the skeleton first would mean taking the decision with one's fingers and documenting it afterwards.

**Domain forces.** An IGA has an unusually dense dependency graph. `identity` feeds `access`, which feeds `provisioning`, which feeds `audit`. And `sod` does not query one of them: it needs to see an identity's effective entitlements, the conflict rules and the standing exceptions at the same time in order to decide. A domain with strong dependencies is exactly the one that splits worst into services.

**Consistency forces.** The joiner flow of section 7.5 creates an identity, works out its birthright access, generates provisioning tasks and records audit events. That has to be atomic or reliably recorded as pending, never half done. With a single database it is one transaction. Spread across services it is distributed transactions and compensations from day one, before a single feature exists.

**Project forces.** One person builds it alongside a full time job (section 11). Section 2.2 requires everything to come up with one command. And section 1.1 requires Ayyoub to understand every piece, which on its own rules out any architecture whose operational cost eats the time that should go into learning the domain.

**Showcase force.** The project has to demonstrate senior level engineering (section 2.5). That raises the bar: choosing the comfortable option is not enough, it has to be chosen with an argument and, above all, **the boundaries have to be demonstrably real**. A monolith that calls itself modular with nothing verifying it is an ordinary monolith with good press.

**A new force, from 2026-10-04.** The product direction in section 17 adds two possibilities that did not exist before: an Open Core split (part of the code open, part commercial) and the extraction of an agent control plane. Neither requires microservices today, but both require the boundaries between modules to be real, because a future split would follow exactly those lines. This force reinforces the decision rather than changing it.

## Options considered

**1. A monolith with no internal boundaries**, with packages per technical layer (`controllers`, `services`, `repositories`).

It is the fastest way to start and what comes out by default if nobody decides anything. Its problem is well known and has a name: any class can call any class, so dependencies grow in every direction until moving one piece means touching half the application. In this project it also carries two specific costs: it would destroy the Open Core option, because there would be no lines to cut along, and it would remove one of the most valuable portfolio arguments, which is being able to show verified boundaries.

**2. A modular monolith with verified boundaries.** One deployable application, internal modules with explicit boundaries, and tests that fail when somebody crosses them.

**3. Microservices from the start.** One service per module or per group of modules.

Three costs, in order of severity. The first is that it turns the joiner flow into a distributed transaction and the access lineage query ("why does this identity hold this permission") into a fan out call across several services. The second is that `sod` needs a consistent view of several modules at once in order to decide, and in a distributed system that view does not exist without further work. The third is the cost of operation and observability, which for one person is time stolen from the domain. Rejected, and not for size: for the shape of the dependency graph.

**4. Hybrid: the monolith plus a separate PDP from day one.** Pulling M9 into its own service from the start, because it is the only one with a different latency profile.

It is the least bad of the rejected options and is probably the future, but today it is premature: the PDP does not exist until phase 5 and there is no measurement justifying separating it. Separating on intuition is exactly what principle 7 of section 3.3 forbids.

## Decision

**A modular monolith**, with the boundaries verified in continuous integration.

### Mechanics

- **Packages per business module, not per technical layer**, as section 10.4 already establishes. The structure is the one in section 7.3, under `dev.ayyoub.lineward`. **Note from 2026-10-22:** the root package is now `com.lineward`; the prefix changed and the reason is in ADR-007. The rest of this decision is unaffected, because the rule is the division into modules and not the prefix.
- **Spring Modulith 2.1.x** (version pinned in ADR-006) sets the convention: every direct subpackage of the main package is a module, and its base package is its public API.
- **The allowed dependencies are declared** with `@ApplicationModule(allowedDependencies = ...)` in each module's `package-info.java`. A module without the annotation may depend on any other, so declaring them is what turns the design into a constraint.
- **A test runs `ApplicationModules.of(LinewardApplication.class).verify()`** on every push. It is the test that makes the boundaries exist; without it, section 7.3 is a drawing.
- **`audit` listens to everybody's events and nobody depends on `audit`**, as section 7.3 already says. This is what makes the audit log credible: no module can alter what is audited about it.
- **`shared` stays flat**, with no subpackages, so that its contents remain reachable from the other modules. If it ever needs subpackages it will have to be declared an open module with `@ApplicationModule(type = Type.OPEN)`, and that is worth resisting: a growing `shared` is where the coupling the tests no longer see goes to hide.

### A finding that corrects the reading of section 7.3

Section 7.3 draws `connector` with four subpackages: `keycloak`, `ldap`, `scim` and `flaky`. Verified against the Spring Modulith documentation:

> "Any sub-package of the application module base package, is considered an _internal_ one. Code within those must not be referred to from other modules."

That is, **those four are not four modules: they are the inside of a single `connector` module**. The drawn structure is correct, but it means something different from what it might appear to, and it turns out to mean something better: the common connector interface (the SPI) lives in the base package and is the only thing visible, while the concrete implementations stay hidden. The consequence is that **no module can talk to the Keycloak connector directly**, neither by accident nor by shortcut. Everybody goes through the abstraction, and the test guarantees it instead of trusting code review. It is precisely the property M3 wants to demonstrate.

## Consequences

**What is gained**

- The atomicity of the joiner flow comes for free: one database transaction.
- One artefact to start, debug and deploy, which meets the one command requirement of section 2.2.
- Moving a boundary is cheap while the design is young. This is the most underestimated advantage: in the first months the boundaries **are going to move**, because the domain model is not settled yet, and moving them inside a monolith is a refactor, while between services it is a migration.
- The boundaries are demonstrated, not asserted. It is direct interview material and it is the line along which an Open Core split would be made (section 17.4).

**What is lost, or what has to be watched**

- **A single blast radius.** One serious failure brings everything down. Mitigated with tests, not with architecture.
- **Scaling in one block.** More machine cannot be given to the PDP alone. Until phase 5 nothing needs it.
- **A permanent temptation to cut corners.** The difference between a modular monolith and a monolith is one test. If that test is disabled "temporarily" to unblock something, the architecture disappears that same afternoon. Section 1.5 already forbids disabling tests; this is where keeping to it will hurt most.

**The limit of the verification, which is the most important part of this record**

Spring Modulith and ArchUnit analyse **Java types**. They do not see the database. Two modules can be perfectly decoupled in code and fused through SQL: it is enough for one to read the other's tables, or for a foreign key to cross the boundary. No test detects it, no tool warns, and the coupling is just as real and considerably harder to undo.

Hence three rules that accompany this decision and hold from the first Flyway migration:

1. **Every module owns its tables.** The others do not read them directly.
2. **No foreign keys crossing a module boundary.** The reference is stored as an identifier and the coherence is guaranteed by the domain.
3. **No queries joining tables from different modules.** If the data is needed, it is requested through the owning module's API or received by event.

All three are the real price of keeping the emergency exit open, and all three are invisible to the tests, so they are reviewed discipline rather than automation. They belong on the `/revisa` checklist.

**The emergency exit**

**M9, the PDP, is the first candidate for extraction**, being the only component with a profile unlike the rest: it decides on the critical path of every request, it is measured in milliseconds, and its load looks nothing like a nightly aggregation job.

The condition for extracting it, written now rather than when it hurts: that a **measurement** in phase 5 demonstrates the PDP's latency or scaling pattern cannot be solved inside the monolith (with a cache, with virtual threads or with indexes). With no measured number, it is not extracted. If that day comes, the three rules above are what make the extraction a matter of days rather than months.

**Relationship to other decisions**

- **ADR-011** decides that isolation between organisations lives in the database with row level security. It is independent of this record and compatible: it works the same with one deployment or twenty, and rule 1 above (every module owns its tables) does not get in the way of a per table policy.
- **ADR-003**, the message broker, is still pending and this record does not prejudge it. A modular monolith can have its modules communicate with in memory events, which is what Spring Modulith offers out of the box, and that is enough until the asynchronous provisioning of phase 2 forces a decision.

## Sources consulted

Verified on 2026-10-04:

- [Spring Modulith · Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html), for the module convention, the internal nature of nested subpackages, `@ApplicationModule(allowedDependencies = ...)`, open modules and `ApplicationModules.of(...).verify()`.
- ADR-006 for the Spring Modulith and Spring Boot versions, verified on 2026-09-25.

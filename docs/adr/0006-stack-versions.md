# ADR-006 · Stack versions: Java 25, Spring Boot 4.1, PostgreSQL 18, Keycloak 26.7

- **Status:** Accepted
- **Date:** 2026-09-25
- **Versions verified on:** 2026-09-25

## Context

Before the first line of code is written, the numbers have to be pinned. The Spring Boot skeleton, the `docker-compose.yml`, the first Flyway schema and CI all need the same answer, and if each of them answers on its own they end up diverging.

The decision is not "what is the latest version of each thing". It is twofold:

1. **Which support window the project enters.** The project is estimated at five to eight months of part time work (section 11) and the public demonstration has to stay alive afterwards. A piece whose support expires halfway through the roadmap forces an unplanned migration at the worst possible moment.
2. **Which combinations have been checked to work together.** The risk in a stack does not live in the pieces, it lives in the cartesian product. Each version on its own can be stable and the combination can fail to start. While verifying this record exactly that happened, described below.

Forces at play:

- **Section 7.2 already expressed a preference** ("the most recent Java LTS, 25 if everything is compatible, otherwise 21") and marked it with a warning to verify. This record carries out that verification instead of inheriting the hypothesis.
- **Phase 6 depends on specific IdP features**, not just on one existing: token exchange (RFC 8693), CIBA and DPoP. Choosing the Keycloak version without looking at the support status of those three would move a risk from month six to month zero without having looked at it.
- **The project is portfolio material.** A stack justified in an interview with "I put the latest in" subtracts. One justified with support windows and verified compatibilities adds.
- **The lesson is already in the failure log** (section 14, the Appendix A entry): writing in the document what one believes a tool supports is a hypothesis, not a fact. That is why this record carries a verification date and not only a decision date.

### Scope

This record covers **backend and infrastructure**: JDK, build tool, framework, modularity, database, migrations, IdP and test libraries.

It leaves out the **frontend** (Node, Next.js, TypeScript) and the pieces that depend on decisions still pending: the message broker (ADR-003), the policy engine (ADR-004) and the Java AI framework (ADR-005). Pinning the Next.js version today would mean pinning a number for a piece that is not touched until phase 1 and that will have expired by the time it is. Each one is pinned when it starts being used, with a verification of its own.

## Options considered

**1. The latest published version of each piece.** Attractive for being simple and for the "I am starting the project today, so I start with today's" argument.

Applied literally on 2026-09-25 it would have chosen **Java 27**, published ten days earlier. Java 27 is a **non LTS** release: it receives updates until March 2027, when Java 28 replaces it. That means a mandatory JDK version jump six months after starting, in the middle of phase 2 or 3. On top of that, Spring Boot 4.1.1 declares compatibility **up to and including Java 26**, so Java 27 is not covered by the framework that is going to be used.

The same logic applied to PostgreSQL would have meant waiting for version 19, in Beta 4 since 24 September 2026 with GA expected in October, that is, blocking the skeleton for several weeks in order to debut a major version on the day it is published.

Rejected: "the latest" and "the supported" are not the same thing, and confusing them is the mistake this record exists to avoid.

**2. Conservative LTS: Java 21 and Spring Boot 3.5.** The option for whoever puts stability above everything else. Java 21 is LTS and thoroughly proven, and Spring Boot 3.x is the line with the most accumulated documentation and third party answers.

It has two problems. The first is the calendar: open source support for Spring Boot 3.5 **ended on 30 June 2026**, that is, already. Staying on 3.x would require commercial Tanzu support, which does not apply here. Starting a new project on a line with no community support is starting in debt.

The second is about goals. Section 2.2 sets mastery of **virtual threads** and modern Java concurrency as a learning goal, and phase 2 includes a parallelism benchmark. Java 21 has them, but giving up two years of language and JVM improvements while gaining nothing in support, because 25 is also LTS and with a longer window, buys nothing.

**3. The most recent JDK LTS, the most recent stable framework line.** Java on its current LTS, Spring Boot on the line with the longest open source support ahead of it, the database on the latest major version with a year of accumulated maturity, and everything else inherited from the Spring Boot BOM unless there is a justified exception.

This is the option chosen, and it is the one section 7.2 expressed before it was verified.

### The finding that decided the Spring Boot line

Verification uncovered a real and documented failure: an application with **Spring Boot 4.0.2 and PostgreSQL 18.1 does not start**. It throws `Unsupported Database: PostgreSQL 18.1` during bean initialisation, because the Flyway version managed by the Boot 4.0.2 BOM (Flyway 11.14.1) predates PostgreSQL 18 and does not recognise it. Issue 49012 in the Spring Boot repository was closed as invalid, which means the fix belongs to whoever assembles the stack: move up a line or override the Flyway version property by hand.

Line 4.1 manages Flyway 12.4.0 and does not have the problem.

This matters beyond the number, for three reasons:

- It gives an argument for choosing line 4.1 over 4.0 that does not depend on support dates.
- It illustrates where new database versions really break: **first in the tools that inspect the database** (migrations, test drivers, administrative clients) and not in the engine or the JDBC driver. Flyway queries the server version and decides whether it supports it, so a freshly published major version breaks it before any SQL query does.
- It is the concrete reason why this record's PostgreSQL policy is not "move to the latest" but "move once the tools that inspect it declare it supported".

## Decision

### Pinned versions

| Piece | Version | Supported until | Reason |
|---|---|---|---|
| **JDK** | Java **25** (LTS), Eclipse Temurin distribution | Sept 2028 | GA on 16 Sept 2025, the current and most recent LTS. Spring Boot 4 declares first class support for 25. The next LTS is Java 29 in Sept 2027 |
| **Build** | Apache Maven **3.9.16**, via the Maven Wrapper | stable branch | Maven 4.0.0 is still a release candidate (rc-6) and its own documentation declares it unfit for production |
| **Framework** | Spring Boot **4.1.x** (4.1.1) | OSS support Jul 2027 | Line GA on 30 Jun 2026. 4.0 loses OSS support on 31 Dec 2026 and carries the Flyway failure with PostgreSQL 18 |
| **Modularity** | Spring Modulith **2.1.x** | aligned with Boot 4.1 | Line 2.0 moved its baseline to Boot 4 and Spring Framework 7. Consumed via its own BOM |
| **Database** | PostgreSQL **18** (18.6) | Nov 2030 | GA on 25 Sept 2025, a year of maturity. Five years of support ahead |
| **IdP** | Keycloak **26.7.x** (26.7.3) | see the 26.x policy | The latest stable line. 26.7.0 was released on 9 Jul 2026 |
| **Migrations** | Flyway **12.4.0** | inherited from the Boot 4.1 BOM | Covers PostgreSQL 18 |
| **JDBC driver** | PostgreSQL JDBC **42.7.13** | inherited from the BOM | |
| **Tests with infrastructure** | Testcontainers **2.0.5** | inherited from the BOM | |

The last three are not declared in the `pom.xml`: they are taken from the Spring Boot BOM and noted here only to record what was verified.

### Build tool: Maven

**Maven 3.9.16** over Gradle. It is the de facto standard of the Spring ecosystem, the Spring Boot BOM and parent POM fit directly, and the official documentation and third party answers show up in Maven form first. Gradle is better at incremental builds and at large multi module projects, but Lineward is a **modular monolith**, one deployable artefact with internal modules verified by architecture tests, so it never gets to collect that advantage.

Maven 4 is explicitly rejected: it is still a release candidate and its own documentation warns it is unfit for production. It will be revisited when it reaches GA, without hurry.

The **Maven Wrapper** (`mvnw`) is used so that the Maven version is pinned in the repository and the local build and the CI build are identical. A build that depends on which Maven each machine has installed is not reproducible.

### Policy: what is pinned explicitly and what is inherited

This part matters as much as the numbers, because it is what stops the stack drifting over time.

**Pinned explicitly**, because they are the pieces everything else depends on:

| What | Where the number lives |
|---|---|
| Java version | a `pom.xml` property, the Maven toolchain, the `Dockerfile` base image and `setup-java` in CI |
| Maven version | the Maven Wrapper (`.mvn/wrapper/maven-wrapper.properties`) |
| Spring Boot | the parent POM version |
| Spring Modulith | its BOM version property |
| PostgreSQL and Keycloak | image tags in `docker-compose.yml` and in the Testcontainers containers |

> **Added on 2026-10-21: Maven moves from 3.9.16 to 3.10.0.**
>
> Dependabot proposed it the same day it was enabled, in the repository's first pull request, and Ayyoub approved it. The table above and the decision still say 3.9.16, which is the number that no longer applies.
>
> **It does not contradict the reasoning of this decision, only its number.** The reason written for pinning 3.9.16 was staying on the stable branch because Maven 4.0.0 is a candidate and its documentation declares it unfit for production. That is still true, and more so: line 4 is still a candidate, today `4.0.0-rc-7`. 3.10.0 is the stable branch, it is the most recent of line 3, and it is not Maven 4, so it meets the original criterion.
>
> Verified by running the full reactor build with that version: 38 tests green, and the same in continuous integration, both on the pull request and on `main`. The robot proposing and continuous integration deciding is what makes an automatic update of the build tool safe, that being the piece everything else depends on.

**Inherited from the Spring Boot BOM** is everything else: Flyway, the JDBC driver, Testcontainers, Jackson and the rest of the managed tree. Overriding a managed version is an exception that requires a written reason, because it breaks the compatibility guarantee the BOM provides, which is precisely what it exists for. The legitimate case is the inverse of the finding: if the BOM manages a version incompatible with a piece we did choose ourselves, it is overridden and the reason is noted.

> **Added on 2026-10-21: the first exception, and a second legitimate reason this record had not foreseen.**
>
> The paragraph above allows a single case for overriding: the BOM managing a version incompatible with something we chose. Missing is the one that has now arisen, which is **a known vulnerability in a managed version, with no BOM version that fixes it**. It is recorded as a legitimate reason, with the same requirement of a written record.
>
> The dependency scanning added that day found ten vulnerabilities in the Spring Boot 4.1.1 tree:
>
> | Piece | BOM version | Findings | Raised to |
> |---|---|---|---|
> | `tools.jackson.core` (Jackson 3) | 3.1.5 | 7, among them denial of service by quadratic backtracking and `Path` deserialisation with no scheme allowlist | 3.1.8 |
> | `org.apache.tomcat.embed` | 11.0.24 | 3, among them an authentication bypass by capture and replay in the DIGEST authenticator | 11.0.27 |
>
> **Why they are overridden rather than moving up a line**, which is what this record prefers: 4.1.1 is the latest stable of its line and 4.2.0 only has milestones, so there is nowhere to advance to. Both bumps stay inside the same minor line, which is the smallest change that closes the findings.
>
> **Both are reverted** as soon as a Boot version manages those versions or later. An override that outlives its reason is how a project ends up anchored to an old dependency without anybody deciding to. The full reason lives in the comments of the root POM, beside the properties.
>
> And a new review trigger for this record's list: **dependency scanning flagging a managed version.** The original four were all about calendars or support, and none covered this.

**A concrete version is always pinned, never a moving tag.** Not `latest`, nor plain `postgres:18` where it can be more precise. A moving tag turns every `docker compose pull` into a silent version change, and it also breaks the reproducibility goal of section 2.2. This is the same **drift** problem as module M2, applied to the development environment: what you think you are running and what you are running diverge with nothing to warn you.

### On Keycloak and phase 6

Support status verified on 2026-09-25 in the Keycloak documentation, for the three features phase 6 depends on:

| Feature | Status | Reading |
|---|---|---|
| Standard token exchange V2 (RFC 8693) | **Supported**, enabled by default | The foundation exists and is stable |
| Legacy internal token exchange | **Preview and deprecated**, will be removed | Build nothing on it |
| Delegation semantics (actor claim) | **Experimental**, via "Token Exchange Delegation" with the `may_act` claim | It is the piece holding up principle 3 of section 6.2, and it requires pre-authorising the actor |
| Subject impersonation in V2 | **Not implemented yet** | Affects the design of delegation |
| CIBA | Documented as implemented, with no preview marking | Available for human approval |
| DPoP | **Preview** | Section 6.2 already treated it as conditional, and rightly so |

Conclusion: the chosen version **does not block** phase 6, but the warning in section 6.2 was well founded. What is supported and what is experimental do not match what the document took for granted. Thorough verification remains a phase 6 task, and the design of delegation will have to start from the pre-authorisation mechanism Keycloak actually offers, not the one that was assumed.

A separate note: Keycloak 26.7.0 introduced a **SCIM API in preview**. It does not replace the work of M3. Lineward's SCIM server and SCIM connector exist as a learning goal around the standard (sections 2.2 and 5, M3), not as a gap in Keycloak.

## Consequences

- **There will be at least one Spring Boot line jump during the project.** OSS support for 4.1 runs to July 2027 and the Spring cadence publishes a new line roughly every six months. The rule is to stay on the chosen line until its window nears its end, not to chase every release. Moving up a line mid phase spends time on something that teaches nothing new.
- **PostgreSQL 19 arrives in October 2026 and is not adopted at its GA.** PostgreSQL 18 is supported until November 2030, so there is no urgency at all. The criterion for moving up is not the publication date, it is Flyway and Testcontainers declaring the version supported, for the reason in the finding described above. When the move happens, it happens first in a CI job and afterwards in development.
- **Java 27 exists and is not used.** It is non LTS. The next LTS, Java 29, arrives in September 2027 and could fall within the life of the project: moving up will be optional rather than mandatory, because Java 25 has updates until September 2028.
- **Keycloak has no concept of LTS and publishes quarterly.** The minor version has to be pinned for reproducibility, and at the same time the security releases inside line 26.x have to be followed, which are cheap to apply. It is the piece of the stack that will ask for the most continuous attention.
- **The image tags live in two places and can diverge:** `docker-compose.yml` and the Testcontainers containers. If the tests use PostgreSQL 18 and the demonstration 19, the tests stop testing what is deployed, which is the worst possible failure in a suite with real infrastructure. When the skeleton is created they need a single source of truth, for example a Maven property read by the tests.
- **This record expires.** Everything here that says "the most recent" is true as of 2026-09-25 and false at some future moment with nothing to warn you. It is revisited when any of these conditions is met: the end of OSS support for Spring Boot 4.1 approaches (July 2027), PostgreSQL 19 is to be adopted, Maven 4 reaches GA, or phase 6 discovers that Keycloak's support status has changed.
- **Still pending, and not resolved by this record:** whether license headers go in every source file (inherited from ADR-008) and the message broker decision (ADR-003), which affects `docker-compose.yml` but not the skeleton.

## Sources

All consulted on 2026-09-25.

**Java**
- Oracle Java SE Support Roadmap: <https://www.oracle.com/java/technologies/java-se-support-roadmap.html>
- JDK 25: <https://openjdk.org/projects/jdk/25/>
- JDK 27: <https://openjdk.org/projects/jdk/27/>
- Releases available in Eclipse Temurin: <https://api.adoptium.net/v3/info/available_releases>

**Spring Boot and Spring Modulith**
- Release cycles and support windows: <https://endoflife.date/spring-boot>
- System requirements: <https://docs.spring.io/spring-boot/system-requirements.html>
- Versions managed by the BOM: <https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html>
- `spring-boot-dependencies` at tag v4.1.1, the authoritative source for the inherited versions: <https://github.com/spring-projects/spring-boot/blob/v4.1.1/platform/spring-boot-dependencies/build.gradle>
- Issue 49012, the failure with PostgreSQL 18: <https://github.com/spring-projects/spring-boot/issues/49012>
- Spring Modulith 2.0 GA: <https://spring.io/blog/2025/11/21/spring-modulith-2-0-ga-1-4-5-and-1-3-11-released/>
- Spring Modulith 2.2 M1, 2.1.1 and 2.0.8: <https://spring.io/blog/2026/08/26/spring-modulith-2-2-m1-2-1-1-2-0-8-and-1-4-13-released/>

**PostgreSQL and Flyway**
- Release cycles and support windows: <https://endoflife.date/postgresql>
- PostgreSQL 19 Beta 4: <https://www.postgresql.org/about/news/postgresql-19-beta-4-released-3386/>
- Databases and versions supported by Flyway: <https://documentation.red-gate.com/fd/supported-databases-and-versions-143754067.html>

**Keycloak**
- Keycloak 26.7.0: <https://www.keycloak.org/2026/07/keycloak-2670-released>
- Keycloak 26.7.3: <https://www.keycloak.org/2026/08/keycloak-2673-released>
- Token exchange and its support status: <https://www.keycloak.org/securing-apps/token-exchange>
- OIDC layers, CIBA and DPoP: <https://www.keycloak.org/securing-apps/oidc-layers>

**Maven**
- Release history: <https://maven.apache.org/docs/history.html>
- Download page, with the recommended version: <https://maven.apache.org/download.cgi>

# ADR-014 · Development security posture

- **Status:** Accepted
- **Date:** 2026-10-21

## Context

Lineward is an identity and access governance platform. That makes it an unusually high value target, for two reasons worth stating explicitly:

1. **It concentrates the answer to "who has access to what".** An attacker reading that database obtains the map of the entire organisation without touching a single target system.
2. **It holds the connector credentials**, which are administrator credentials in the corporate directory and in the identity provider. They are the most valuable credentials in the whole installation, which is why connector credential theft has been in the threat model since day one.

At the same time, the project has a learning and portfolio goal, and one person builds it part time. A security posture that demands constant ceremony does not get followed: it is abandoned in the third week and stays written as though it were followed, which is worse than never having written it.

There is also a real tension this decision has to resolve rather than hide: **convoluted code is itself a security problem.** Nobody reviews what they do not understand, and holes live where review does not reach. So "more controls" and "more secure" are not synonyms.

### What prompted this decision

Until 2026-10-21 there were serious security controls (verified per organisation isolation, an audit log the application cannot edit, least privilege in the database roles) and no statement of **how** security decisions get made. Three supply chain controls were deferred to the last phase with this argument, written on 2026-10-14:

> a backend of two classes produces no useful findings

The argument turned out to be false for two of the three, and the way it was discovered is the part worth recording. When dependency scanning was added, it found **ten vulnerabilities the same day**, seven in the library that parses every JSON input and three in the embedded server, including an authentication bypass. The reasoning error was confusing small code with small surface: two classes drag in 63 dependencies nobody on this side has audited, and that number does not grow with the code we write.

## Options considered

**1. Leave security to a hardening phase at the end.** That is what the roadmap did. A real advantage: it spends no time on controls that have nothing to protect yet, and static analysis of an empty project only produces noise. The drawback: it treats security as a layer applied on top, when the decisions that determine it most (where the authorisation boundary lives, whether a control can be bypassed) are taken at the start and cost a redesign afterwards. And, demonstrated empirically, it lets real third party vulnerabilities through for months.

**2. Harden everything from the start.** Static analysis, formal review, threat modelling per delivery. The decisive drawback for a one person project: the cost per delivery is high, the benefit in the early phases is low, and the predictable outcome is that it degrades into a formality. A control that is half followed informs worse than one that does not exist, because it shows up as being in place.

**3. Security takes priority, with the cost paid in design rather than in complexity.** The one chosen.

## Decision

**Security takes priority when it conflicts with something else, and it is paid for with design, not with added complexity.** The code has to stay clear, because clarity is part of the control.

Seven concrete rules, which are what this decision really decides:

1. **The control goes where it cannot be bypassed, not where somebody remembers not to bypass it.** A check at the boundary is a wall; a team convention, a note in the documentation or an instruction in a prompt are requests. PostgreSQL row level security makes the unfiltered query impossible; "remember to filter by organisation" prevents nothing.
2. **Every control arrives with its negative test performed at least once.** It has to have been seen failing when it should fail. If the negative test can be automated, it is automated. The reason is in the consequence below: there are controls whose failure is indistinguishable from success.
3. **Least privilege inwards too.** The application role owns no table, the continuous integration token is read only except in the job that needs to write, and every component gets just enough.
4. **No secrets in the code, in the configuration or in the history.** And only synthetic data, generated reproducibly.
5. **Before adding a check, look at whether the design can make the failure impossible.** A type that cannot hold the invalid state is worth more than a validation somebody has to remember to call.
6. **No privileged "trusted" path.** One shortcut of the form "if the component is internal we skip the check" is enough for the security property to disappear and never come back.
7. **What is missing gets said.** A half built control described as complete is worse than its absence, because nobody watches it. That is why this decision includes the list of what is **not** there.

### Supply chain controls, brought forward

Of the controls deferred to the last phase, the two that touch no line of code are brought forward, and the third stays deferred:

| Control | Status | Why |
|---|---|---|
| Dependency scanning that fails the build | Brought forward | It looks for published advisories about third party code, so it applies from the first dependency |
| Continuous monitoring and automatic version updates | Brought forward | A dependency becomes dangerous without anybody touching the repository |
| Static analysis of our own code | Still deferred | Here the original argument does hold: there is no domain logic to analyse yet |

### What this decision does not authorise

It does not authorise stopping the project to harden what does not exist yet, nor duplicating validations just in case, nor turning every delivery into an audit. It fixes the order of priority in a conflict; it is not permission to widen the scope.

## Consequences

**What is gained.** The security decisions get taken where they are cheap. The existing controls are tested the wrong way round rather than merely written down. And the supply chain is continuously watched instead of reviewed once at the end.

**What it costs.** Two versions managed by the Spring Boot BOM are overridden by hand, which locally breaks the compatibility guarantee the BOM provides. It is the exception ADR-006 allows, with the reason written beside each one and the obligation to revert it when a framework version manages them. An override that outlives its reason is how a project ends up anchored to an old dependency without anybody deciding to.

**What has to be watched, and it is the technical finding that justifies rule 2.** A control can fail for want of data and report that as success. It happened twice the same day, with two tools from different vendors:

- The dependency scanner reported "no problems" after silently discarding the sixteen packages it had found. It could not resolve their versions.
- The hosting platform's dependency graph held twenty five packages, fifteen of them with no version, and contained neither of the two libraries where the ten findings were.

The cause is the same and it is uncomfortable: **declaring versions centrally is good practice, and it is what ADR-006 argues for, and it leaves naive readers with nothing to read.** A correct engineering decision can silently disable a security tool, with no error, no warning and a report of normality.

The countermeasure is operational and applies to any future control: **from a control you read not the verdict but how many things it says it checked.** A coverage control with no coverage number is not a control. In this case, handing both tools the dependency tree already resolved by the build tool took the scan from 0 to 63 packages and the graph from 10 to 199 versions.

**What revisits this decision.** Two new triggers, on top of ADR-006's: dependency scanning flagging a version managed by the BOM, and any control appearing whose success report does not come with a count of what was checked.

# Security policy

Lineward is an identity governance platform. It is built in the open as an
engineering and learning project, it has no users and no production deployment,
and every piece of data in it is synthetic. That shapes what this policy can
honestly promise, so it is stated plainly rather than dressed up.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting on this repository: open the
**Security** tab and choose **Report a vulnerability**. That keeps the report
private until there is a fix, which is the point.

Please do not open a public issue for a security problem, and please do not
attack any deployment you did not set up yourself.

**What to expect.** This is a side project maintained by one person, so there is
no response time commitment. What there is: a report will be read, answered, and
either fixed or explained. If a report turns out to describe a real weakness,
the fix and the reasoning are documented the same way everything else here is,
in an architecture decision record or in the error diary.

## Scope

**In scope**, and genuinely useful to report:

- A way to read or write data belonging to another tenant. The schema isolates
  tenants with PostgreSQL row level security, and a hole there is the worst
  failure this project can have, because the data that leaks is precisely who
  has access to what. See [ADR-011](docs/adr/0011-multi-tenancy-isolation.md).
- A way to modify the audit trail from the application. It is append-only by
  database permissions, by design.
- A way to make an authorization check not run, or to reach a code path that
  skips one.
- A secret, token or credential committed anywhere in this repository or its
  history.
- A vulnerable dependency the automated scan does not catch. Knowing *why* it
  was missed matters as much as the dependency itself, for the reason in
  [ADR-014](docs/adr/0014-development-security-posture.md).

**Out of scope:**

- The simulated HR system (`demo-apps/hr-simulator`) has no authentication on
  purpose. It is a stand-in for a third party system used in development, and
  the connector credential problem belongs to a later phase. Its data is
  generated from a fixed seed and is entirely fictional.
- Anything that requires the attacker to already be a database superuser or to
  have shell access on the host.
- Missing hardening that this project already documents as absent. The list of
  what is and is not in place is in ADR-014, and an accurate "this is missing"
  is not a finding.

## What is in place

The reasoning behind each of these is in the linked record, not here.

| Control | Record |
|---|---|
| Per-tenant isolation enforced by the database, not by application discipline, with the two independent defences that make it hold | [ADR-011](docs/adr/0011-multi-tenancy-isolation.md) |
| Append-only audit trail, enforced by permissions | [ADR-013](docs/adr/0013-domain-model-phases-1-and-2.md) |
| Module boundaries verified by architecture tests in code. Across the database they are reviewed discipline, not an enforced rule, and the test that closes that gap is still open work | [ADR-001](docs/adr/0001-modular-monolith.md) |
| Dependency scanning that fails the build, on every dependency the build actually resolves, plus continuous monitoring | [ADR-014](docs/adr/0014-development-security-posture.md) |
| Every security control admitted only after being seen to fail when it should | [ADR-014](docs/adr/0014-development-security-posture.md) |

Two of those records exist because a control looked present and was not:
ADR-011 was corrected after its own claim about row level security turned out to
be untested, and ADR-014 exists because a dependency scanner reported "no issues
found" over an empty set. Both corrections are kept in place rather than edited
away.

## Supported versions

None. There is no release yet, so there is no supported version and no backport
policy. Fixes land on `main`.

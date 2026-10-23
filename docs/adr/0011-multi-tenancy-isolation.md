# ADR-011 · Multi-tenancy: an organisation discriminator and isolation with row level security

- **Status:** Accepted
- **Date:** 2026-10-04

## Context

Section 17.3 describes a possible evolution towards multi-tenant SaaS, that is, one installation serving several organisations with their data isolated. Section 2.3 makes clear that multi-tenancy **is not a functional goal**: there will be no organisation management and no billing. What is decided now is that the database schema must not make that evolution impossible.

The timing forces it. The first Flyway migration is a phase 0 task, and adding an organisation discriminator afterwards is one of the most expensive refactors there is: it touches every table, every index, every query, every test and every endpoint. Deciding it after the first schema means paying for it in full.

On 2026-10-04 it is decided that **every business table carries an `org_id` column from the first migration**. This decision knowingly accepts the cost section 17.3 calls "premature SaaS complexity", in exchange for eliminating the retrofit.

But the column on its own isolates nothing. It only makes isolation possible. The isolation depends on **every query** filtering by it, and that is the real problem this record solves:

```sql
-- What somebody who forgets the filter writes
SELECT id, email FROM identity WHERE department = 'Treasury';
-- Returns identities from every organisation

-- The correct version
SELECT id, email FROM identity WHERE department = 'Treasury' AND org_id = :org;
```

Three reasons why this will happen if there is no mechanism:

1. **Every query has to get it right**, and in an IGA there will be hundreds: screens, reports, counters, aggregation jobs, evidence export, SoD evaluation.
2. **The easiest ones to forget are not the lists**, but the counters, the reports and the scheduled jobs that run with no user signed in, where there is no obvious "current tenant" to draw on.
3. **The tests pass.** With a single organisation in the test data, a query that forgets the filter returns exactly what a correct one returns. The failure shows up with the second real client, in production, and the client is the one who finds it.

The severity is at its maximum in this particular domain: the data that leaks is who has access to what, that is, the map for attacking the other organisation. It is on record as a disclosure threat in section 9.

**The criterion of this record:** "remembering to filter" is not a security control. A mechanism that cannot be forgotten is needed.

## Options considered

**1. The column alone, filtering by discipline.** Every query adds its `AND org_id = :org` and code review watches for it.

It is the option that pays all of multi-tenancy's complexity and buys none of its security. A single omission in any one of the hundreds of queries there will be is a cross client leak, and point 3 of the context guarantees no test will catch it. Rejected.

**2. A repository base class.** A class of our own that every repository inherits from and that injects the filter.

It is the simplest and adds no dependencies. Its flaw is structural: it protects only what inherits from it, and nothing stops somebody writing a class that does not inherit, nor a native query, nor a report. It goes back to depending on the programmer using the right path, which is the original problem with one extra step.

**3. A global Hibernate filter.** Hibernate, the library that translates Java objects into SQL, allows declaring filters that are added automatically to the queries that go through it.

It covers more than option 2 and is configured in one place. But its boundary is the library: native SQL, reports, migration processes and any access that does not go through Hibernate bypass it silently. On top of that the filter has to be enabled per session, and a session where it was not enabled gives no warning, it simply does not filter.

**4. PostgreSQL row level security.** The database applies the filter itself to every query that arrives, whether from Hibernate, from native SQL or from a command line client.

It is the only one of the four that keeps protecting when the mistake is in the application code, which is where the mistakes are going to be. And it is the only one an auditor can verify by looking at the database instead of reviewing code.

## Decision

**PostgreSQL row level security (RLS)** as the isolation mechanism, with `org_id` as the discriminator in every business table.

### The concrete mechanics

**Enabled per table**, in the very migration that creates the table:

```sql
ALTER TABLE identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity FORCE ROW LEVEL SECURITY;

CREATE POLICY identity_tenant_isolation ON identity
    USING (org_id = current_setting('app.current_org')::uuid);
```

Without `current_setting`'s second argument, on purpose. Verified on 2026-10-05 against the real container: if nobody has set the tenant, the query fails with an explicit error instead of returning an empty list.

```
ERROR:  unrecognized configuration parameter "app.current_org"
```

Both forms fail closed, so both are safe. The difference is diagnostic: zero rows gets confused with "there is no data" and costs half an hour, while that error names the problem. The noisy failure is chosen.

**The current tenant is set per transaction**, not per session:

```sql
SELECT set_config('app.current_org', :orgId, true);  -- is_local = true
```

The third argument being `true` makes the value last only until the end of the transaction. It is essential because the connection pool reuses connections between requests: a session scoped value would stick to the connection and the next request, from another client, would inherit the previous tenant. That would turn the isolation mechanism into the source of the leak.

**Two database roles**, and this is the part that makes the rest work:

| Role | What for | Subject to RLS |
|---|---|---|
| `lineward_owner` | Owns the tables. Flyway uses it for the migrations | No, by being the owner |
| `lineward_app` | Used by the application at runtime. Owns nothing | Yes |

### The trap that invalidates RLS if you do not know about it

The PostgreSQL documentation is explicit:

> "Superusers and roles with the `BYPASSRLS` attribute always bypass the row security system when accessing a table. Table owners normally bypass row security as well, though a table owner can choose to be subject to row security with `ALTER TABLE ... FORCE ROW LEVEL SECURITY`."

That is: **in the default configuration, where the application connects as the same user that created the tables, RLS does absolutely nothing.** It is enabled, it raises no error, and it does not filter. It is the worst possible failure: a security control that looks like it is in place and is not.

That is why the decision includes both defences at once, and they are independent: `FORCE ROW LEVEL SECURITY` on every table, and an application role that owns nothing. Either one is enough; with both, a misconfiguration in one does not leave the system open.

### An immediate consequence for the schema design

The documentation also says:

> "Referential integrity checks, such as unique or primary key constraints and foreign key references, always bypass row security to ensure that data integrity is maintained."

This opens a leak channel RLS does not close and that has to be closed in the design of the tables. If `identity` had a global `UNIQUE (email)` constraint, inserting an identity with an email that already exists **in another organisation** would return a duplicate constraint error. The row is invisible, but the error confirms it exists. That is an information leak even though not a single row is seen.

**The rule, therefore, from the first migration:** every uniqueness constraint on a business table includes `org_id` as its first column.

```sql
-- Wrong: reveals the existence of rows in other organisations
UNIQUE (email)
-- Right
UNIQUE (org_id, email)
```

The same applies to foreign keys: a foreign key pointing at a row in another organisation would not be rejected by RLS. Coherence across organisations is the domain's responsibility, not the database's.

## Consequences

**What is gained**

- The isolation does not depend on remembering anything. A query that forgets the filter does not return another client's data: it returns fewer rows than expected.
- **Fails closed and noisily.** If nobody sets `app.current_org`, the query fails with `unrecognized configuration parameter`. The "I forgot the tenant" case behaves like "forbidden" and diagnoses itself. Checked on 2026-10-05 by running it.
- It also protects native SQL, the reports and any query that does not go through Hibernate.
- It is verifiable by an auditor without reading code.

**What is lost, or what has to be watched**

- **Debugging is more awkward.** When a query returns zero rows there is no error explaining it: the rows simply are not there. The first suspect for "nothing shows up" becomes the unset tenant.
- **The tenant has to be set in every transaction**, including every HTTP request, every consumed message and every scheduled job. The scheduled jobs are the delicate point, because they have no user to derive it from and the organisations they iterate over have to be decided explicitly.
- **The tests change shape, and this is what turns the mechanism into a guarantee.** The test data has to contain **always at least two organisations**, and a test has to exist asserting that from one of them nothing of the other is visible. Without that second organisation in the data, the tests are again incapable of detecting a leak, whatever mechanism sits underneath.
- **Performance.** The policy adds a predicate to every query, so `org_id` needs to be in the indexes. No problem is expected at demonstration scale (around 5,000 identities), but it is something to measure in phase 8 rather than assume.
- **Flyway works unchanged** because it runs as the owner, which is not subject to RLS. In exchange, migrations can see and touch data from every organisation, which is correct for a migration and dangerous for any other use of that role. The owner role is not used at runtime.
- **It does not make Lineward multi-tenant.** It stays what section 2.3 says: there is no organisation management and no isolation as a feature. Through phases 0 to 8 there will be a single organisation in use, and the mechanism will sit there serving nobody. That is exactly the cost that was accepted.

**Pending, and related**

- The audit log hash chain (M8) needs to decide whether it is one global chain or one per organisation. It depends on this decision and is reserved ground, so it is settled along with the domain model (section 16).

## Verification against the real container

Run on 2026-10-05 against PostgreSQL 18.6 with both roles already created by `infra/postgres/init/`:

| Test | Result |
|---|---|
| The owner role creates a table with RLS and inserts rows for two organisations | Fine |
| The application role queries without setting the tenant | `ERROR: unrecognized configuration parameter "app.current_org"` |
| The application role queries with an organisation set | Returns only that organisation's row, not the other's |
| The application role tries to create a table | `ERROR: permission denied for schema public` |

The third row is the isolation working and the fourth is what stops the application creating tables of its own, which would be owned by it and therefore exempt from the policies.

## Correction of 2026-10-14

> The text above is left as it is, by the rule that decision records are not rewritten. This section corrects a factual error that surfaced while writing the first migration.

**What this record claimed and is false.** The consequences say: "Flyway works unchanged because it runs as the owner, which is not subject to RLS". That contradicts its own mechanics section, which enables `FORCE ROW LEVEL SECURITY` on every table. `FORCE` exists precisely to subject the owner to the policies, so the two sentences cannot both be true.

**What was verified against the container** (PostgreSQL 18.6, role `lineward_owner`, a table with `ENABLE` and `FORCE`):

| Test | Result |
|---|---|
| The owner inserts without setting the tenant | `ERROR: unrecognized configuration parameter "app.current_org"` |
| The owner inserts with the tenant set | Fine |
| The owner inserts into another organisation while the first one is set | `ERROR: new row violates row-level security policy` |

**What remains true.** Flyway works unchanged for the **schema**: creating, altering and dropping objects does not go through the row policies. What does not work unchanged is **any migration that touches data**, which has to set the tenant explicitly with `set_config` before inserting or updating.

**The practical consequence, and it is not only an inconvenience.** A data migration is obliged to declare which organisation it acts on, which makes the migration that silently walks every client's rows impossible. The cost is that a migration that has to touch several organisations has to iterate them by hand, which is the same delicate point this record already notes for scheduled jobs with no user signed in.

**The underlying finding**, which is the one worth having in the log: the third test of the original verification table ("the application role queries with an organisation set") confirmed read isolation, and from there it was inferred that writes were covered too. They were, but for a different reason than the one written down, and the sentence about Flyway was put there without testing it. Verifying nine claims out of ten and writing the tenth from memory produces a document that looks verified throughout.

## Sources consulted

Verified on 2026-10-04 against the PostgreSQL 18 documentation:

- [Row Security Policies](https://www.postgresql.org/docs/18/ddl-rowsecurity.html), for the owner bypass, `FORCE ROW LEVEL SECURITY` and the bypass of referential integrity checks.
- [SET](https://www.postgresql.org/docs/18/sql-set.html), for the transaction scope of `SET LOCAL`.
- [Customized Options](https://www.postgresql.org/docs/18/runtime-config-custom.html), to confirm that PostgreSQL accepts compound names such as `app.current_org`.
- [System Administration Functions](https://www.postgresql.org/docs/18/functions-admin.html), for the signatures of `current_setting(setting_name, missing_ok)` and `set_config(setting_name, new_value, is_local)`.

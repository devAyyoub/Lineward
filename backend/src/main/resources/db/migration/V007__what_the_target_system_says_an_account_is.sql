-- What a target system was willing to say about the nature of an account, kept
-- because the alternative is asking it again.
--
-- An aggregation run already reports how many machine accounts it read. That
-- number answers "what happened last night" and not the question people
-- actually bring, which is which machine accounts in this organization nobody
-- answers for. The second is a query over what Lineward holds, so the kind has
-- to be here. Without the column the only way to answer would be to read every
-- target system again, which turns a question into an operation and makes the
-- answer depend on those systems being reachable at the moment somebody asks.
--
-- Two values, and the absence of a third is the design. There is no 'PERSON',
-- because no target system asserts that an account belongs to a human being: a
-- directory serves the robot a team created for a nightly job as the same
-- object class as everybody else, and Keycloak's user list does not
-- distinguish either. 'SERVICE' is the only claim a system can make and the
-- proof is structural, not textual: Keycloak serves a client's service account
-- from the clients endpoint rather than the user list, so an account that
-- arrived that way is one by construction. The reason this is not inferred
-- from a 'svc-' or 'service-account-' prefix is that a prefix is a convention
-- an administrator can change, and a platform that governs access must not
-- decide who is accountable for an account by reading somebody's naming
-- habits. AccountKind holds the same reasoning for whoever reads the code
-- first.
--
-- Not the same column as identity.type, which has HUMAN, SERVICE and AGENT.
-- That one records what Lineward decided somebody is; this one records only
-- what a connector read, and an account whose kind is SERVICE still has no
-- identity of its own. Who answers for a non-human identity is a decision a
-- person takes, which is why correlation refuses to guess an owner here and
-- counts these apart from the accounts whose owner it could not find.
--
-- The default is what makes this migration safe on a table that already holds
-- accounts: every row read before a connector could tell becomes UNKNOWN,
-- which is the truth about them. Marking them SERVICE or not-SERVICE would be
-- inventing a read that never happened.
ALTER TABLE account
    ADD COLUMN kind text NOT NULL DEFAULT 'UNKNOWN',
    ADD CONSTRAINT account_kind_check CHECK (kind IN ('SERVICE', 'UNKNOWN'));

-- The index the first report over this column needs: the machine accounts of
-- one organization that nobody answers for. Partial, because the rows worth
-- indexing are the few that are machines and not the many that are unknown,
-- and a full index here would be mostly one repeated value.
CREATE INDEX account_org_machine_idx ON account (org_id)
    WHERE kind = 'SERVICE' AND identity_id IS NULL;

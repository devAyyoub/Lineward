# ADR-003 · Event transport and message broker

- **Status:** Proposed
- **Date:** 2026-10-22
- **Decision deferred to:** phase 2, where it already has its box in the roadmap. It is accepted or rejected once real provisioning exists, not before
- **Reopened by:** any of the six triggers in the final section, and not by intuition

## Context

The question was framed as "Kafka, RabbitMQ or an outbox with polling", and that framing hides what turned out to be half the decision: **there is no one transport, there are three**, with different requirements and different timelines. Separating them is what turns one infrastructure choice into three questions, two of them already answered.

**1. Between modules in the same process.** The identity module detects a joiner and the access module works out what they are due for being one. Settled on 2026-10-22 and already built: the event is published **inside** the transaction that saves the change and the listener is synchronous, so either both are saved or neither is. There is no message in flight and nothing to lose. The condition that holds it up is written into the event itself: **no listener may do anything a rollback cannot undo.** The practical consequence is that this project does not use Spring Modulith's `@ApplicationModuleListener`, which is asynchronous and runs after the commit, and it is precisely that renunciation that makes its event publication registry unnecessary, that is, the table the framework uses to guarantee delivery.

**2. Towards the target systems.** Creating the account in Okta or in the directory. This cannot live inside a transaction, because the target is remote, slow and sometimes down. **It is already decided:** ADR-013 places `provisioning_task` and `outbox_message` in the provisioning module, with an organisation discriminator, and phase 2 has the boxes for the outbox pattern, idempotency, retries and the dead letter queue. So the outbox is not what is undecided.

**3. A message broker on top**, that is, a separate process holding the queue, such as Kafka or RabbitMQ. **This is the only thing this record leaves open.**

**A new factor since 2026-10-21**, the day the deployment platform appeared: Lineward runs in three environments, and single node Kafka asks for something like 1.5 to 2 GB per environment against a few hundred MB for RabbitMQ, on a 32 GB host. In a homelab that is a legitimate technical argument rather than a whim. **It is still unmeasured**, and measuring it is part of trigger 6.

**And what a broker would not solve**, worth saying before the options: it would not make outbound provisioning safe. A target being down requires retries, idempotency and a dead letter queue anyway, and those live in the task table. A broker moves messages; it does not make Okta answer.

## Options considered

| Option | For | Against |
|---|---|---|
| **Kafka** | Retention and replay of history, which fits audit evidence; the standard in event integration | 1.5 to 2 GB per environment, times three environments; operates badly on one node; solves a scale problem this project does not have |
| **RabbitMQ** | Far lighter; flexible routing; enough for work queues | One more operational component to maintain, upgrade and watch, with no consumer that needs it today; gives no replay of history |
| **Outbox and polling only** | Zero new infrastructure; the state lives in PostgreSQL, which has to be backed up and watched anyway; a table is inspected with SQL, which is the best diagnostic tool available | A latency floor set by the polling interval; periodic load on the database; a second independent consumer would force a rethink |

## Decision

**Provisionally the third: outbox and polling only, no broker.** And the status is Proposed rather than Accepted on purpose, because this is not accepted in writing but by building it: it closes once the phase 2 box is done and that phase's criterion is met, which is that taking a target system down in the middle of an operation loses nothing and duplicates nothing.

**The argument that makes deferral cheap is that the outbox is not the alternative to a broker, it is its prerequisite.** Writing the event in the same transaction as the data change is needed either way, with a broker or without one: it is the pattern that exists precisely so that a database and a messaging system cannot contradict each other. So building the outbox first is not betting on one of the three options, it is building the part all three share. If any of the triggers below is met, the broker is added **behind** the outbox and nothing already built is thrown away.

Of the three deliveries in the context, 1 needs no infrastructure and 2 needs exactly what ADR-013 already placed. Adding a broker today would mean operating a component in three environments for zero consumers.

## Reopening triggers

They are here because a deferred decision with no reopening conditions is not a deferred decision, it is a forgotten one. **None of the six is met today.**

| № | Trigger | How it is detected, so that it does not rest on intuition |
|---|---|---|
| 1 | **A module becomes a separate process** | By the explicit decision to extract it (section 17.3). As soon as the sender and the receiver are different processes, the central argument of this record stops holding |
| 2 | **A consumer outside Lineward wants the events**, for example a client's security monitoring system | By somebody's concrete request, not by assuming they will want them. If it arrives, the natural candidate is RabbitMQ rather than Kafka, unless they ask for history |
| 3 | **More than one independent consumer of the same event**, with different speeds and failure modes | By counting consumers. Today there are zero, and with only one polling is trivially correct |
| 4 | **Polling is the bottleneck** | By measuring two things in phase 2, alongside the virtual threads benchmark already on the list: the latency between detecting an event and provisioning it, and the load polling puts on PostgreSQL. **The threshold has to be set with the first number, not before** |
| 5 | **History has to be redelivered**, for example replaying six months of events for an audit | By an evidence requirement. A table can do it while the rows are kept; a broker's retention solves it differently and the comparison is worth making then |
| 6 | **The deployment target changes** and the homelab memory budget stops being an argument | When it changes. And then Kafka's consumption against RabbitMQ's has to be **measured for real**, because today's 1.5 to 2 GB is an unverified estimate and cannot be used to close anything |

**What does not reopen this**, written down so it is not argued again: an internal event being lost, because with the listener inside the transaction there is no event in flight; and provisioning to a target system failing, because that is the job of retries and idempotency, not of the transport.

## Consequences

- **No messaging component is operated** in the platform's three environments, and the state of pending deliveries lives in PostgreSQL, which has to be backed up and watched anyway. A queue that is inspected with SQL is also the best possible situation for diagnosing a provisioning run that never arrived.
- **There is a latency floor** set by the polling interval. For joiners, movers and leavers that is irrelevant, because the source is an extract read once a day. It would stop being irrelevant if access ever has to be revoked within seconds, which is the phase 6 emergency switch scenario and has to be looked at when that is designed.
- **One concrete obligation remains when phase 2 closes:** writing the measured latency here, so that trigger 4 has a number rather than a feeling.
- **The cost of being wrong is low and asymmetric in our favour**, which is what justifies deferring: adding a broker behind an existing outbox is new work, not redone work. The other way round, standing up a broker today that nobody consumes would be work that would have to be taken down.

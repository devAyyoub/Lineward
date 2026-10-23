# ADR-012 · Documentation visibility: the working document moves to a private repository

- **Status:** Accepted
- **Date:** 2026-10-05

## Context

The repository has been public from day one. That was a deliberate decision and its reasoning is on record: the full history is part of what the project demonstrates, and working in public forces secret hygiene on every commit.

What was not foreseen is that **a single document would end up doing two different jobs for two different audiences.** `docs/PROYECTO.md` grew to around 2,300 lines, and inside it were two things with nothing in common:

1. **An engineering log:** architecture, domain glossary, module sheets, implemented standards, threat model, testing strategy. Written to be read by a stranger.
2. **A lab notebook and a business document:** the rules for collaborating with the development agent, a per session record of what was delegated and what had to be corrected, a failure log, and a product and commercial strategy. Written for the author.

The second group **has no reader in a public repository.** It is not secret information: there are no credentials, no personal data, nothing belonging to third parties or to any employer. But publishing it has a cost and no benefit. Nobody arriving to evaluate the engineering is served by a record of what the author did not understand first time around, or by a commercial strategy that declares where the project would aim next.

**The timing forced the decision.** Deleting a file in a new commit does not remove it from the history: anybody can read the earlier versions forever. Removing it for real requires rewriting the history and force pushing, and that is only clean while the repository is small and nobody depends on it. At the time of deciding there were **21 commits, one author and zero forks, stars and watchers**. That window closes the moment somebody clones or forks.

## Options considered

**1. Leave everything public.** There is a real argument in favour: working in the open is itself a differentiator, and a document of this quality impresses the right reader. A senior engineer reading an honest failure log trusts the project more, not less.

The problem is that this reader is a minority, and part of the content has no upside at all. The product strategy gains nothing from being public and loses two things: it shows a competitor where the gap being aimed at is, and it shows an employer a plan that is none of their business.

**2. Take it out from here on, without rewriting the history.** It avoids the force push, which is always uncomfortable. But it does not solve the problem it means to solve: what is already published stays readable indefinitely. It is the option that feels like acting without having acted.

**3. Split the document in two.** What is showcase stays public as an architecture document; the rest leaves. It is the option with the best end result and the most expensive: it means maintaining two files and deciding in every session where each paragraph goes, which is precisely the kind of manual task that gets forgotten and makes the two diverge.

Rejected **for now, not forever**: it comes back as an open question, because the public repository does need an architecture document of its own at some point.

**4. Close the whole repository until a minimum dignified cut.** The simplest to execute. It reverses the decision to publish from day one and loses the visible history, which was precisely its reason for being. Rejected as disproportionate: the problem is not that the project is visible, it is that one particular file is.

## Decision

**Option 2 with a history rewrite**, carried out on 2026-10-05.

**What moved:** `CLAUDE.md` and `docs/PROYECTO.md`, to a private repository named `lineward-notes`, **keeping their history and their dates**. The document's history is the project's history, and losing it would have cost more than what was being avoided.

**What stays public:** the decision records, the README, the brand, the code and the infrastructure. And the six command files under `.claude/skills/`, by explicit decision: they are prompt templates, they contain no strategy, no session log and no weak points, and how a project is built with a development agent is part of what this project sets out to demonstrate.

**Mechanism:**

- The private repository is cloned into `docs/private/`, which the public one ignores. A git repository inside an ignored folder is invisible to the one outside, so the two live in the same working tree without getting in each other's way and without submodules.
- A `CLAUDE.md` of eight lines stays at the public root, with the single job of chaining the imports towards the private one. **Both imports are declared at the root rather than inside the private file**, because it was never verified whether a nested import resolves against the file that declares it or against the project root. Declaring them at the top makes the question irrelevant. Had they been chained and resolved the other way, the failure would have been silent.
- The public history was rewritten with `git filter-repo --invert-paths` and published with a force push, after a full backup.

## Consequences

**What is gained.** The public repository contains only what is written to be read by a stranger. The decision records plus the README are that log, and the records were already written with that intent.

**The real cost, and it has to be said.** The records predating this separation (002, 006, 007, 008 and 009) cite sections of a document that is no longer public: "see section 7.2", "see section 2.4". Each record restates the reasoning it needs, so they remain understandable, but they are incomplete for somebody arriving from outside. They are not corrected, because historical records are not rewritten. This is what justifies the open question about a public architecture document, which is option 3 deferred.

**A new source of divergence.** Two repositories mean a commit in one does not cover the other, and the private one falls behind **silently**, precisely because it is ignored. It is the same problem this project's own log has recorded three times in three different tools: a copy of the state presented with the confidence of the state. Mitigation: the task closing ritual explicitly recalls the commits of both repositories. It is a process mitigation, not a technical one, which means it can fail.

**A rewrite is not a cryptographic guarantee.** GitHub may keep orphaned objects reachable by their identifier for a while, and any clone made before the rewrite keeps the old history. That is acceptable here because the content was never secret. The criterion is worth writing down for next time: **had the leaked thing been a credential, the right answer would have been to rotate it, not to rewrite the history.** Rewriting serves to remove what should not have been there; it does not serve to undo a leak.

**The window is closed.** Repeating this operation later, with forks or outside contributions, would be far more expensive or impossible. This class of decision has an expiry date and is best taken early, just like the license.

**It changes nothing about the license.** The code stays public under Apache 2.0, and the door to dual licensing that ADR-008 left open stays open as long as there is a single copyright holder. This decision separates the lab notebook from the showcase; **it is not a step towards closing the code**, and it should not be read that way.

# ADR-016 · Everything published is in English

- **Status:** Accepted
- **Date:** 2026-10-23
- **Supersedes in part:** [ADR-002](0002-languages.md)

## Context

[ADR-002](0002-languages.md) split the two languages by function: English for whatever faces outwards, Spanish for whatever serves to learn, English always in the code. The decision rested on one premise, stated there in as many words: the working document, the decision records and the failure log are **learning material**, written so that their author understands and can defend every piece, and that work is done best in the language one thinks in.

That premise is gone. On 2026-10-22 Lineward stopped being a learning project, by its author's decision: it is a product first, a serious technical platform second and a professional showcase third, with no reserved pieces and no comprehension debt. What remains of the old regime is that every delivery carries its explanation, which is a requirement about review, not about language.

Two things follow. The decision records are not learning material any more: they are the **public technical log of the project**, and the first thing a reviewer opens after the README. And the barrier ADR-002 accepted on purpose, that "an international reader finds the cover page in English and the detail in Spanish", is now paid in exactly the audience the repository is for, because the repository is public and those records are one click from the front page.

The code was never the problem. It has been English from the start and stays that way.

## Options considered

**1. Keep ADR-002 as it is.** No work, and the records keep the nuance of the language they were written in. The cost is that the project reads as half translated to the only readers who were not there: eleven accepted decisions, the longest and most load bearing documents in the repository, in a language the standards they implement are not written in.

**2. English for everything published, Spanish only for what is not.** The line stops being "learning or showcase" and becomes "published or not", which is a line anybody can check: if it is in the public repository, it is in English. The cost is real and recurring, not one off: every decision is thought through in Spanish and then written in English, and the translation of the existing records has to be done once.

**3. Bilingual.** Rejected for the same reason ADR-002 rejected it, and the reason has not changed: in a one person project the translation is the first thing to fall out of date, and a stale translation is worse than none because the reader cannot tell which version governs.

## Decision

**Option 2.** Everything in the public repository is in English. The rule is the location, not the subject.

**In English, because it is published:**
- Everything the code already covered under ADR-002: identifiers, comments, database objects, API, log messages, commit messages, branch names.
- `README.md`, `SECURITY.md` and any file at the root of the public repository.
- The decision records in `docs/adr/`, **content and file name**.
- `docs/blog/` and `docs/brand/`.
- The `CLAUDE.md` at the root of the public repository and the session commands under `.claude/`. They are tooling rather than documentation, but they are published, and the rule is the location.

**In Spanish, because it is not published:**
- The working document and the collaboration instructions, which live in the private `lineward-notes` repository: `PROYECTO.md` and `docs/private/CLAUDE.md`. They are where the thinking happens, nobody else reads them, and [ADR-012](0012-documentation-visibility.md) already keeps them out of the public repository.
- The explanations their author is given while the work is being done.

**Never translated**, unchanged from ADR-002: the domain terms and acronyms (entitlement, joiner, birthright, drift, SoD, PDP, PEP) are used in English even inside Spanish text, because that is how the sector speaks.

## Consequences

- **The accepted records get translated, and that is the one thing allowed to touch them.** The rule that historical records are never rewritten stays exactly as it is: the translation carries the same reasoning, the same options, the same dates and the same decision, including the sentences that are now known to have been wrong. ADR-002 keeps saying that the records are written in Spanish, because that is what it decided on 2026-09-22, and this record is what changed it.
- **Renaming the files breaks any external link** to `docs/adr/0013-modelo-de-dominio-fases-1-y-2.md` and its siblings. No such link is known to exist, which is the only reason the rename is affordable, and it gets harder every month the repository stays public.
- **A translation cost lands on every future decision**, because the thinking happens in Spanish. It is the price of the audience, and it is paid at the moment of writing rather than accumulated as debt.
- **The console language, which ADR-002 left open, now has a default.** The console is published, so it is English unless a record of its own argues otherwise.
- The compliance mapping document ADR-002 described does not exist yet. When it does it is published, so it is in English, with the exception ADR-002 already granted: quotations from regulation keep the language of the source.

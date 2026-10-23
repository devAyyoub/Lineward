# ADR-002 · Languages: English in the code, Spanish in the working documentation, English on the public face

- **Status:** Accepted. Superseded in part by [ADR-016](0016-english-public-surface.md) on 2026-10-23, which moves everything published, the records in this directory included, to English
- **Date:** 2026-09-22

## Context

The project serves two purposes that pull in opposite directions.

As **learning material**, the master document, the decision records and the failure log are written so that Ayyoub understands and can defend every piece (section 1.1). That work is done best in the language one thinks in, and it feeds the blog posts directly.

As a **professional showcase**, the repository addresses technical recruiters, security architects and auditors (section 2.4), and the de facto language of both open source and the IAM literature is English. Every standard the project implements (the OAuth, SCIM, LDAP and token exchange RFCs) is written in English, and their terms have no established Spanish translation: nobody says "punto de decisión de política" in a real conversation, they say PDP.

Section 10.4 already fixed "names in English, documentation in Spanish", but that rule did not settle the case of **public** documentation, which is exactly what the open question in section 16 raised. Writing the README forces the matter.

## Options considered

**1. Everything in Spanish.** Consistent with `ayyoub.dev` and with the rest of the documentation, and authentic. But it forces the translation of technical terms nobody translates, and it narrows the reach of the repository in precisely the audience that matters most. A security architect arriving from LinkedIn and finding a README in Spanish assumes the project was not meant for them.

**2. Everything in English.** Maximum reach and consistency with the ecosystem. The cost is high and it falls where it hurts: writing the long reasoning of the decision records and the failure log in English slows the work down and impoverishes the nuance, which is precisely what makes those documents valuable. It would mean paying a permanent tax on the learning material for the benefit of a reader who may never arrive.

**3. Split by function.** The language is decided by the document's audience, not by the project. English in what is published outwards, Spanish in what serves to learn, English always in the code.

**4. Fully bilingual.** Every document in both languages. Maximum reach and maximum maintenance cost. In a one person project the translation is the first thing to fall out of date, and a stale translation is worse than none, because the reader cannot tell which of the two versions governs.

## Decision

**Option 3, split by function**, with these concrete rules.

**Always in English:**
- Code: identifiers, classes, methods, packages, code comments.
- Database: table, column and index names, and migrations.
- API: routes, DTO fields, error codes, domain events.
- Log messages.
- Commit messages, branch names and pull request titles.
- `README.md` and, once they exist, the project page, the published C4 diagrams and the subtitles of the demonstration video.

**Always in Spanish:**
- `docs/PROYECTO.md`, in full.
- The decision records in `docs/adr/`.
- The failure log and the session log.
- The compliance mapping document in `docs/compliance/`, with one exception: literal quotations from regulation keep the language of their source. The Spanish ENS is Spanish regulation, DORA and NIS2 have an official Spanish version, and the NIST and OWASP guidance exists only in English.

**Never translated:**
- The domain terms and acronyms (entitlement, joiner, birthright, drift, SoD, PDP, PEP) are used in English even inside Spanish text. Section 4 defines them once and from then on they are used as they are, which is how the sector speaks.

## Consequences

- The `README.md` ends up decoupled from the master document and has to be **synchronised by hand** every time a phase advances. It is accepted maintenance debt: the README must always carry the real state of the phases, and nothing checks that automatically.
- An international reader finds the cover page in English and the detail in Spanish. It is a real and deliberate barrier: the detail in Spanish is learning material, not product documentation. If the project ever looks for outside contributions, the master document will have to be translated or at least summarised in English, and a `CONTRIBUTING.md` added in English.
- The language of the **console interface** is left undecided. The public face rule would give it English, but it affects the demonstration experience and the screenshots on the website. It is decided in the phase that builds the console, and if it departs from this rule it is recorded as a record of its own.
- Section 10.4 of the master document is subsumed by this record, which is more specific. If the two ever contradict each other, the record governs.
- The language of the blog posts (section 15) is left open. This record does not decide it, because the blog belongs to `ayyoub.dev` rather than to the repository, but the decision is worth making consciously: the posts are the main vehicle for spreading the project.

# Blog drafts

Drafts of the [ayyoub.dev](https://ayyoub.dev) posts that come out of the work on Lineward. They live in the repository because they are written alongside the code and age with it: a post about an architecture decision that later changes has to be able to change in the same commit.

## Conventions

- **One file per post**, named in lowercase with hyphens.
- **A mandatory header** at the top of every draft: status, the audience it aims at, which phase of the project it comes from, and what is still to be decided.
- **Statuses:** `draft` (being written), `in review` (complete, pending a rewrite for voice), `published` (with a link), `dropped` (with a reason, not deleted).
- **Language: English**, since [ADR-016](../adr/0016-english-public-surface.md). Everything published in this repository is in English, the posts included, which replaces the exception they had under [ADR-002](../adr/0002-languages.md) for being an author's text rather than product documentation. If a Spanish version is ever wanted, it is a separate file and not a section inside the same one.
- **No third party material.** The same as the rest of the project: nothing internal to any employer or client, and no real personal data. Only public standards and what has been lived in this repository.

## How they get written

The material is not invented when sitting down to write: it comes out of the failure log and the session log of the working document, which get filled in while building. A failure that is solved and not written down is lost, and it is precisely the one that tells best afterwards.

| Post | Status | Comes from |
|---|---|---|
| [Drift is not an identity governance problem, it is an everyday one](drift-is-not-an-iam-problem.md) | `draft` | Phase 0 |

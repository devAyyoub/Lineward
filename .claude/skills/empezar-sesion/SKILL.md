---
description: Opens the session by placing the phase, the task and the next step from docs/private/PROYECTO.md.
disable-model-invocation: true
allowed-tools: Bash(git status *) Bash(git log *)
---

## Repository state

!`git status --short --branch`

## Latest commits

!`git log --oneline -5`

## Instructions

Read docs/private/PROYECTO.md, sections 1, 2, 3 and 12.
Tell me which phase and task we are in, what the recommended next step is and which pieces of the project it touches. Name the task with its Jira key, which is in Annex C. If you do not know which task is in progress, propose the next one in roadmap order with its key.
Take the repository state above into account: if there are uncommitted changes, tell me before starting, and do not mix them with the new work in one commit. That block only looks at the public repository, so check the private one as well with `git -C docs/private status --short`, which is where CLAUDE.md and PROYECTO.md live.
If there are unpublished commits, say so by asking the server with `git ls-remote origin` and not the local reference.
Tell me which branch I am on. Work goes on a branch cut from `develop` (section 1.4), so propose its name along with the scope.
Propose the scope of the session and wait for my confirmation before writing code. Once I confirm it, move the card to `En curso` with the Atlassian connector and tell me in one line. If the connector is not in the session, tell me and I will move it (section 1.2).

---
description: Closes the current task: summary, verification, document update and the commits of both repositories.
disable-model-invocation: true
---

We are closing the current task, following the protocol in section 1.2 of docs/private/PROYECTO.md:

1. Summarise what was done in plain language: what you did, why that way and what is worth reviewing.
2. Tell me what was verified **by running it**, not just which tests pass, and what was left unverified.
3. List the decisions you took that I have not approved yet.
4. Write the session log entry (section 12.3) and update the state (12.1 and the phase checklist).
5. Propose a decision record if there was a decision, and the full pass of section 14: one entry for every failure, surprise or unreliable source of the session.
6. Do the **claims pass of section 1.8**, and do it by looking rather than from memory. List what you have claimed during the session of these three kinds, and for each one say where you checked it: that something is decided, pending, built or absent; a property of the new code, such as it being idempotent or something not being able to get lost; and that a tool has failed. Anything with no source, check it before closing or correct the sentence, in the code and in the document. If you claimed nothing of those three kinds, say so explicitly instead of skipping the point.
7. Remind me of the commits of **both repositories**: the public one at the root and the private one in `docs/private/`. A `git add -A` at the root does not include the private one, because it is ignored, so the working document goes unpushed if nobody says so. And tell me which branch each one is left on: the work branch is merged into `develop` when I approve, and `develop` into `main` is my decision (section 1.4).
8. **Move the cards** of the task in Jira and tell me each movement in one line, with its key and its state. What you have built and verified goes to `En revisión` and not to `Finalizado`, because `Finalizado` is my approval rather than your closure: you move that one when I approve. If you worked on something with no card, or one task turned out to be several, propose the new card with a title, an epic and a two line description instead of creating it on your own. And if the Atlassian connector is not in the session, give me the list so I can move them myself (section 1.2).

Show me the document changes before writing them.

---
description: Reviews my changes as a senior security and IAM engineer. It points the problems out and explains them, it does not fix them.
disable-model-invocation: true
allowed-tools: Bash(git status *) Bash(git diff *) Bash(git show *)
---

## Repository state

!`git status --short`

## Uncommitted changes

!`git diff HEAD`

## Instructions

Review the changes above the way a senior security and IAM engineer would.
If the diff is empty, review the latest commit with `git show HEAD` and tell me before starting.

Assess: correctness, security, edge cases, concurrency, readability, tests and consistency with the architecture in docs/private/PROYECTO.md.

The report first, not the fix: point the problems out, order them by severity and explain why they are problems, with the concrete case that fails. At the end, ask which ones I want you to fix.

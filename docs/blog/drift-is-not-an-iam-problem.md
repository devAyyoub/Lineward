---
status: draft
audience: backend engineers and IAM practitioners. The hook works for anybody who uses git
phase: 0
alternative_title: "Git told me I had six unpushed commits. It was lying"
---

> **Draft.** It is written through so that it can be rewritten on top, not published as it stands. Three things are still to be decided and they are marked at the end.

# Drift is not an identity governance problem, it is an everyday one

I have spent three weeks building an identity governance platform. In that time I have believed something false four times, with total conviction: three of them a tool told me, and the fourth I told myself. All four are the same mistake, and it turns out to be the mistake that gives the category of software I am building its reason to exist. It is worth telling in order.

## The first time

I opened the project in the morning and git informed me that I had six unpushed commits.

```
$ git branch -avv
* main                 [origin/main: ahead 6] docs: ...
  remotes/origin/main  docs: ...        <- a commit from days ago
```

It was lying. All six had been on GitHub since the night before.

The explanation, once you see it, is obvious and also documented: `refs/remotes/origin/main` is not the state of the server. It is a local copy of where that branch was **the last time this clone spoke to the server**. If the push happened from somewhere else, or if the reference has not been refreshed, what git shows you is history.

The interesting part is not the failure. It is that no git command warns you that what it is showing may be stale. `git branch -avv` presents the old fact with exactly the same face it would have used for the good one. To actually ask the server there is another command:

```
$ git ls-remote origin
```

That one does open a connection and ask. Two commands that look like they answer the same question, and only one of them does.

## The second time

Days later I was pinning the stack versions. I chose Spring Boot and PostgreSQL 18, both stable, both inside their support window. And it turns out they do not start together:

```
Unsupported Database: PostgreSQL 18.1
```

It is not a warning you can ignore, it is a startup failure. The cause: the version of Flyway, which is the tool that applies the database migrations, carried inside it a list of supported versions written before PostgreSQL 18 existed. On connecting, Flyway queries the server version, compares it against its list and refuses.

The engine worked perfectly. So did the driver, because a driver only speaks the protocol and the protocol does not change between major versions. What was out of date was **the idea the tool had of the world**, and that idea travelled inside the jar.

Out of this came a rule I still use: a major database version breaks the tools that **inspect** it first (migrations, administrative clients, test containers) and not the ones that merely use it. And an uncomfortable corollary: verifying piece by piece gives false reassurance, because every piece is correct. The risk surface of a stack is the product of its versions, not the list.

## The third time

A week later I went to confirm those same versions and queried the Maven Central search API, which is where Java packages live. It told me the latest version of Spring Boot was 3.5.3.

Nine days earlier I had pinned 4.1.x, verified against the official documentation. One of the two things was wrong, and the comfortable hypothesis was that my decision rested on a version I had invented.

I was about to redo the work. What saved it was consulting the `maven-metadata.xml` of the repository itself, which is the real listing of what has been published:

```
$ curl -s https://repo1.maven.org/maven2/org/springframework/boot/spring-boot/maven-metadata.xml
```

There was 4.1.1 as the latest stable. **The search engine is an index over the repository; the repository is the repository.** When the two disagree, the second one governs.

## The fourth time

And this week's, which is the silliest and the most instructive. I created a commit, touched up its message with `git commit --amend` to match the project's style, and on pushing:

```
! [rejected]  main -> main (non-fast-forward)
```

Amending does not modify a commit, it creates another one with a different identifier. The original was already published, so the local repository and the server ended up telling the same story with different commits.

Here the stale copy was not a file or an index. It was **a belief**: taking for granted that the commit was still local only. No tool deceived me this time; I deceived myself with the same mechanism.

## The pattern

Four cases, three tools and one assumption of mine, one single mistake: **treating a copy of the state as though it were the state.**

And none of the four copies was corrupt. All of them were correct at the moment they were created. The problem is the one every cache in the world has: they do not know when they stopped being true, and nobody presents them with a warning.

The useful question is not "is this fact correct?", because the answer is almost always yes, and besides you cannot check it without doing the work you were trying to avoid. The useful questions are two:

1. Am I reading the state, or a copy of the state?
2. How much does being wrong cost?

The lie about the six commits cost me two minutes. The same confusion, in the place where I work, costs considerably more.

## And now the part I did not expect

It turns out I had spent three weeks tripping over the central problem of the software I was building.

Lineward is an **identity governance** platform, IGA in English, which is the discipline that answers who has access to what, why they have it and whether they should still have it. In an organisation of any size nobody can answer those three questions, because the accounts live scattered across dozens of disconnected systems.

A platform like that maintains what is called the **expected state**: who is entitled to which permission, by their job, by what was approved for them, by the role assigned to them. It is its database and it is very reliable, in the sense that it always answers quickly and without hesitating.

The problem is that the expected state **is not the state**. The actual state lives in the target systems: in the corporate directory, in the identity provider, in every application. And the two diverge the moment somebody grants a permission by hand, an automated process is skipped, or a change is undone directly in the system. The sector calls that difference **drift**.

Which is, exactly, my problem of the last three weeks. The platform's database will keep asserting what it wrote itself, with total conviction, just like `git branch -avv`. And it will not be corrupt: it will be describing precisely how the world looked the last time it looked at it.

Two design rules come out of that, and they are not matters of opinion:

**An identity governance platform that does not periodically read the target systems again does not govern, it recites what it wrote itself.** The process of reading them again is called **aggregation**, and it is not a maintenance task or an implementation detail: it is what separates a governance platform from a spreadsheet with a good interface.

**Recertification campaigns are run against the aggregated state, never against the expected one.** A recertification campaign is when a manager reviews their team's access and confirms or revokes each one. If what they are shown to sign is the expected state, they are signing a report about a report: they are certifying that the platform is consistent with itself, which is true and useless. What an auditor wants to know is what that person can actually do, today, in the payments system.

And then the classic finding appears, the one that shows up in almost every access control audit: the **orphaned account**. An account that cannot be tied to any person, usually because that person left and the leaver process never reached that system. It is not in the expected state, because as far as the platform is concerned that person has nothing. It is in the actual state, and it usually still works.

It is the lie about the six commits again, with the difference that this one is not fixed with `git ls-remote`.

## What I take from it

That this happens to a developer four times in three weeks, with tools they use daily and that have no defect at all, says something about how unavoidable the problem is. Caches exist because asking the source is expensive. The discipline is not about not using them, it is about knowing, for each fact, whether you are reading the state or a copy, and going to the source when the decision deserves it.

In access control, the decision always deserves it.

---

## To decide before publishing

1. **Attribution of the fourth case.** The commit amend was done by the development agent I work with, not by me. Telling it that way opens the subject of working with agents, which has a post of its own planned, and could derail this one. The options are telling it as it happened, telling it in the first person because the decision to delegate is mine either way, or dropping it and keeping three cases.
2. **Whether this is the first published post.** The plan was to open with a post introducing the project. This is a better technical hook and does not need anything to have been built, but it does not introduce Lineward.
3. **Links and figures.** Still to decide whether the repository's decision records get linked, and whether a diagram of expected against actual state is worth it. With a diagram drift is understood in three seconds, but the text already explains it and it may be redundant.

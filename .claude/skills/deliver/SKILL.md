---
description: Hands a finished draft from docs/blog/ over to the ayyoub.dev repository, without anyone carrying a document across by hand.
argument-hint: [draft slug]
disable-model-invocation: true
---

Hand the `docs/blog/` draft for $ARGUMENTS over to the blog.

The draft is the contract. Nothing new gets written here and nothing gets published: the blog repository holds the voice, the Spanish version and the decision to publish.

## Before handing it over

Check the draft against `docs/blog/README.md`. It crosses only if all of this holds:

1. **It is finished.** Written through, ending included. A draft that stops halfway is `draft`, not `in review`.
2. **Nothing private travels.** The material comes from the failure log and the session log of `docs/private/PROYECTO.md`, but it travels re-told, never pasted. No section of the working document, no roadmap that is not already in the README, no third party material.
3. **Nothing that does not exist is claimed.** No demo, no screenshots of a console that is not built, no usage metrics. If the draft promises any of them, that goes out before it crosses.
4. **The header is complete:** `status`, `audience`, `phase`, and `alternative_title` if there is a second title worth keeping.

If anything fails, say which one and stop. Do not fix it on your own.

## Handing it over

Set `status: in review` in the draft, which is what marks it as ready to cross, and run the blog's importer:

```bash
node /Users/ayyoub/Desktop/ayyoubdev2/ayyoub-dev/scripts/importar.mjs <slug> --clave <spanish-slug>
```

`--clave` is the file name the entry will have on the blog, and the blog names its files in Spanish: `el-escaner-que-aprobo-sin-mirar`, not `the-scanner-that-approved-without-looking`. Propose one from the title and ask Ayyoub before running it, because it ends up in the public URL and is not worth changing afterwards.

The importer writes the two language versions as drafts and prints what it left pending. Pass that report on as it came, without summarising away what it says is missing.

Then add the entry to the table in `docs/blog/README.md` if it is not there, and leave the commit for Ayyoub.

## Afterwards

When the entry is live on ayyoub.dev, its status here becomes `published` with the link. Until then it stays `in review`: the blog can rewrite it, and a status saying otherwise would be a lie in a public repository.

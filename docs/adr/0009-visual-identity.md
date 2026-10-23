# ADR-009 · Visual identity: the Ward mark, warm bone and brass

- **Status:** Accepted
- **Date:** 2026-09-24

## Context

ADR-007 chose the name and noted the direction for the logo, the ward of a lock or the cuts of a key, flat and monochrome. It decided nothing else: no concrete shape, no colour, no typeface.

That is enough while the project is only documentation, and it stops being enough as soon as visible surfaces appear. One already exists (the public README) and three more are coming: the Next.js console, the project page on `ayyoub.dev` and the demonstration video. With no written decision, each of them would end up with its own palette, which is why so many personal projects look like four different projects.

There is also a constraint specific to this case: the mark has to work at 16 pixels, because the favicon and the repository avatar are where most people will see it. That constraint governs over any aesthetic consideration.

## Options considered

**1. Decide nothing yet and take the framework defaults.** Tailwind or shadcn ship a reasonable palette and typeface, and cost nothing. The problem is that they are recognisable from a distance: a project with `slate-800`, `Inter` and cards with a coloured left border reads as a template, and this project is a showcase whose message is care for detail. It also defers a decision that will cost more once screens exist.

**2. The conventional security product palette.** Corporate blue, cool grey, system typeface. Nobody criticises it and nobody remembers it. It is exactly what the sector's software does, and for that very reason it does not distinguish.

**3. An identity of its own, and sober.** A warm bone background instead of white, nearly black and slightly cool ink, a single muted brass accent, a grotesque with character for headings and a monospace for data.

## Decision

**Option 3**, with these components.

**The mark.** A solid keyhole split by the slot of the ward. It was chosen among four proposals (split keyhole, key with ascending cuts, monogram with notches, and a pill with a cut out) and won on the size test: it is the only one that is mass rather than line, and therefore the only one that does not fall apart at 16 pixels. The key proposal told the name better, joining lineage and ward in the cuts, but its cuts disappear below 32 pixels.

A **simplified variant** without the slot is defined for sizes below 24 pixels, along with a **pill** carrying the keyhole as a cut out, for avatars and square surfaces.

**Colour.** Background `#F5F4F0`, ink `#15161A`, brass accent `#8A6229`, each with its dark theme equivalent. Brass goes with the idea of a key and a lock without falling into luxury gold.

**Type.** Space Grotesk for the name, headings and interface. IBM Plex Mono for labels, identifiers and data.

The concrete values live in [`docs/brand/README.md`](../brand/README.md), which is the single source. This record keeps the reasoning, not the hex values.

## Consequences

- **The monospace is not decoration.** Lineward is going to show account identifiers, native entitlement values, audit chain hashes and timestamps. All of that compares better in a fixed width, so the type choice solves a real reading problem in the console, not just a stylistic one.
- **Contrast is pinned from below.** `#5A5B61` is the lightest grey admissible for small text on the bone background if WCAG AA is to be met. It is written down because the classic mistake is lightening the greys until they "look refined" and leaving the interface below the threshold.
- **The accent is forbidden for small text.** Brass on bone does not reach 4.5:1 at small sizes. It is used in links, marks and large elements.
- **Two type families imply a deployment decision.** In the console they have to be self hosted or loaded with `next/font`, never linked from Google Fonts in production: that avoids depending on a third party in the critical path and avoids leaking every visitor's IP address to an external service. It is consistent with a project that talks about privacy and least privilege.
- **The dark theme is sketched, not settled.** The tokens and the reversed mark exist, but the full behaviour of the console in dark will be decided when the console exists.
- **The bitmap format is still pending.** A fallback `favicon.ico` and the social preview images will be generated once there is a website. SVG does not cover those cases.
- **The identity constrains future records.** Any component library chosen for the console will have to accept custom tokens without fighting them, which rules out the ones that ship a strong and inflexible aesthetic.

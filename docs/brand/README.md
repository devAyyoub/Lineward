# Lineward visual identity

The single source for the brand. If anything in the console, in the README or on the project page disagrees with this file, this file governs.

The reasoning behind each decision is in [ADR-009](../adr/0009-visual-identity.md). The direction of the logo was set in [ADR-007](../adr/0007-project-name.md).

## The mark

A solid keyhole split by a horizontal slot. The slot is the **ward**, the internal obstacle in a lock that lets only the right key through, which is the second half of the name.

The mark is mass, not line. It has no strokes, no outlines and no rounded corners. That is why it survives at 16 pixels, and it is not negotiable.

| File | When it is used |
|---|---|
| `lineward-mark.svg` | General use from 24 px up: headers, README, compositions with the name. |
| `lineward-mark-simple.svg` | Below 24 px. It closes the slot, which at that size no longer fits and turns to mud. |
| `lineward-tile.svg` | The repository avatar and anywhere that demands a square surface. A solid pill with the keyhole cut out. |
| `favicon.svg` | The browser tab. It is the simplified version, and it switches to light ink in a dark theme on its own. |

All of them carry the colour written into the file. To use them inline inside a component, replace the `fill` with `currentColor` and drive the colour from CSS.

## Colour

The names are the ones that will end up as CSS variables.

### Light theme

| Token | Value | Use |
|---|---|---|
| `ground` | `#F5F4F0` | Page background. Warm bone, never pure white. |
| `surface` | `#FFFFFF` | Cards and tables on top of the background. |
| `ink` | `#15161A` | Body text and the mark. Nearly black, slightly cool. |
| `ink-muted` | `#3A3B41` | Secondary text and descriptions. |
| `ink-subtle` | `#5A5B61` | Labels, captions and metadata. |
| `hairline` | `#DDDCD6` | Borders and separators. |
| `accent` | `#8A6229` | Muted brass. Links and occasional accents. |

### Dark theme

| Token | Value | Use |
|---|---|---|
| `ground` | `#15161A` | Page background. |
| `surface` | `#1D1E23` | Cards. |
| `ink` | `#F5F4F0` | Body text and the reversed mark. |
| `ink-muted` | `#C9CAD0` | Secondary text. |
| `ink-subtle` | `#A9AAB0` | Labels and metadata. |
| `hairline` | `#3A3B41` | Borders. |
| `accent` | `#C89A55` | Brass lightened so that it contrasts on a dark background. |

The accent is **not used for small text** in either theme. It is for links, underlines and marks, not for paragraphs.

## Type

| Role | Family | Settings |
|---|---|---|
| Name and headings | Space Grotesk 500 | `letter-spacing: -0.03em` on the name, `-0.02em` on headings |
| Body text and interface | Space Grotesk 400 | No adjustment |
| Labels, data, identifiers | IBM Plex Mono 400 and 500 | Uppercase with `letter-spacing: 0.14em` on labels |

The monospace is not decorative: Lineward shows identifiers, native entitlement values, audit hashes and timestamps, and all of that reads better at a fixed width.

⚠️ In the console, the fonts are self hosted or loaded with `next/font`. Google Fonts is not linked in production, so as not to depend on a third party and not to leak every visitor's IP address.

## Rules of use

- **Clear space:** half the height of the mark free on every side.
- **Minimum size:** 24 px for the version with the slot. Below that, the simplified one.
- **Horizontal composition:** the mark measures around 1.35 times the type size of the name, and the gap between the two is a third of the height of the mark.
- **One ink, always.** No gradients, no shadows, no outlines, no embossing.
- **It is not rotated, not stretched, not cropped** and not placed inside any shape other than the pill.

## Accessibility

The text values are chosen to meet WCAG AA, 4.5:1 for normal text.

`ink-subtle` (`#5A5B61`) is the lightest grey usable for small text on `ground`. A lighter grey falls below the threshold, which is the most common mistake when copying palettes from elsewhere.

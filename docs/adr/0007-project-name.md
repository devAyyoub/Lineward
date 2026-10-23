# ADR-007 · Project name: Lineward

- **Status:** Accepted
- **Date:** 2026-09-22

## Context

The project was born under the provisional name Cerbero, after the three headed dog that guards the gates of Hades. The metaphor is right for an access governance platform, but the name was still pending a collision check.

The project's real constraints are modest: it needs a repository inside a personal GitHub namespace, a page on `ayyoub.dev` and Maven coordinates under an owned domain. It does not need a `.com`, npm or PyPI. Two things do matter, though: that the name collides with nobody visible in IAM or security, because the project is the showcase for a future freelance IAM audit practice, and that the name does not promise something other than what the project does.

## Options considered

**1. Kerberos.** Rejected for two independent reasons, either of them sufficient. It is a trademark of MIT, which expressly forbids commercial use without written permission ("Kerberos... are trademarks of the Massachusetts Institute of Technology. No commercial use of these trademarks may be made without prior written permission of MIT"). And it names an authentication protocol, which is precisely what section 2.3 declares a non goal: authentication is delegated to Keycloak.

**2. Cerberus.** The English spelling is saturated in security: Cerberus Cyber Security, Cerberus Cyber Solutions, the Cerberus MDM app, Microsoft's Project Cerberus and Cerberus Capital Management. On GitHub, the most popular repository under that name is a set of email templates with more than 5,000 stars.

**3. Cerbero.** Better than Cerberus, but Cerbero Labs exists: it sells Cerbero Suite, a commercial malware analysis and forensics tool active since 2011, and it owns `cerbero.io`. The four main domains are taken. On top of that, the most visible security repository called `cerbero` is an attacker against the Kerberos protocol.

**4. Aldaba.** The Spanish word for a door knocker, the object one uses to ask permission to come in. An excellent metaphor, and the technical namespaces are free, but Aldaba is also a Galician technology consultancy, active since 2003 and accredited under the regional Empresa Cibersegura programme. Same category of service and same market as the planned freelance practice.

**5. Linaje.** Taken from principle 2 of section 3.3, the lineage of an access. No collision found, but it gives up the chance of a logo with an identity of its own, and the Spanish j is hard for an English speaker.

**6. Lineward.** A compound of *lineage* and *ward*.

Around 75 candidates were filtered in five rounds, checking domains, npm, PyPI, GitHub and company presence. The result was conclusive: no dictionary word with a usable meaning is free, in Spanish, English or Latin. That is why the industry itself uses invented compounds: SailPoint, CyberArk, ForgeRock, BeyondTrust, Saviynt, Okta.

## Decision

**Lineward.**

The name composes *lineage* and *ward*, and the second half carries three senses that all serve at once:

- *To ward*: to guard, to keep.
- *A ward*: a person under the care of a guardian, which is exactly the relationship between an AI agent and its human sponsor in module M10.
- *The wards of a lock*: the internal obstacles that let only the right key through. Real locksmithing vocabulary, and the origin of the logo.

The first half names what sets this project apart from any other IGA: being able to answer why an identity holds an access, who approved it and when it expires.

Checked on 2026-09-22: `lineward.dev`, `lineward.io`, npm and PyPI free, two repositories of the same name on GitHub with no relevant activity, and no company found under that name. The company check was a web search rather than a trademark registry query; verification at the EUIPO is still pending before any commercial use.

## Consequences

- The root package becomes `dev.ayyoub.lineward` and the repository becomes `lineward`.
  - **Corrected on 2026-10-22:** the root package is **`com.lineward`** and the Maven `groupId` moves from `dev.ayyoub` to `com.lineward`. The reason is that a person's name in the package of a platform ties the artefact to its author rather than to the project, and it gets in the way if Lineward ever has more than one contributor or a commercial dimension (sections 17.8 and 17.9 of the working document). `com` was chosen over `dev` or `io` for consistency with that direction. ⚠️ This record's domain check covered `lineward.dev` and `lineward.io`, **not `lineward.com`**: publishing to Maven Central under that `groupId` would require proving control of that domain, so it has to be checked before any artefact is published. None is published today.
- The logo is reconsidered: the ward of a lock or the cuts of a key, flat and monochrome, instead of the two headed creature. It gains legibility at 16 pixels.
- The *-ward* suffix reads in English as a direction (skyward, windward), so the guardian sense does not arrive on its own. It has to be told on the project page.
- Being an invented compound, the name does not explain itself and always needs a line of context. In exchange, it competes with nobody in search results.
- Verification at the EUIPO remains pending should the name ever be used to sell services.

# ADR-008 · License: Apache License 2.0

- **Status:** Accepted
- **Date:** 2026-09-22

## Context

Lineward is released as free software. The license is not an administrative formality: it is the contract with anybody who reads, uses or redistributes the code, and it has an uncomfortable property, it is **irrevocable for everything already published under it**. Whoever receives a version under a license today keeps those rights over that version forever, even if the license changes tomorrow.

Today the decision is cheap because Ayyoub is the **sole copyright holder**. The moment a first outside contribution is accepted, relicensing stops being a unilateral decision and starts requiring every contributor's permission. That is why it is decided now rather than later.

Forces at play:

- **The target audience** (section 2.4) includes security architects and compliance officers working at large companies, many of them with explicit internal policies about which licenses their engineers may touch.
- **The project is the showcase for a future freelance IAM audit practice** (section 2.2), so it is wise not to close the door on commercial use by its own author.
- **ADR-007** spent considerable effort choosing a name with no collisions. Giving that name away in the license afterwards would be incoherent.
- **Lineward integrates with Keycloak**, which is distributed under the Apache License 2.0. License compatibility with dependencies is not optional.

This record carries engineering judgement, not legal advice.

## Options considered

**1. MIT.** About fifteen lines that fit on one screen: do what you like, keep the copyright notice, there is no warranty. Its virtue is that it needs no interpretation, which is why it is the most widespread license there is.

Its gap is what it does not say. **It does not mention patents**, and there is legal debate over whether it grants a patent license implicitly. **It does not mention trademarks**, so nothing stops a third party redistributing the project unchanged and still calling it Lineward. For a project whose central subject is access control, choosing the license that leaves the flanks uncovered sends the wrong signal.

**2. Apache License 2.0.** It is MIT plus three pieces that matter here:

- **Section 3, an express patent grant**, with a retaliation clause: anybody starting patent litigation claiming the software infringes their patents automatically loses the patent license granted to them.
- **Section 6, trademarks**, which explicitly denies any grant over the holder's trade names and marks. It is exactly the protection ADR-007 left uncovered.
- **Section 4, redistribution conditions**, which requires keeping the notices and propagating the `NOTICE` file if one exists. Attribution ends up more structured than with MIT's single loose line.

Its only real drawback is length, two hundred lines of legal text against fifteen. That is not a practical problem: standard licenses are recognised by name and GitHub identifies them automatically.

**3. AGPL 3.0.** Network copyleft. The classic GPL triggers the obligation to publish the source when the software is **distributed**. A vendor offering the software as a cloud service never distributes anything, because the user only connects over a network, so the obligation never triggers. That gap was called the ASP loophole for years, and the AGPL exists to close it: whoever offers the software over a network must also offer its source code to those users.

It is an excellent license **when it protects a business model**, typically that of a company afraid a cloud provider will absorb its product and give nothing back. Lineward has no such business model. In exchange, its cost is concrete and runs against the main goal: many corporate policies forbid their engineers from incorporating, and sometimes even examining, AGPL code. Choosing it would reduce the number of people in the target audience willing to clone the repository.

## Decision

**Apache License 2.0.**

It covers the patent flank, protects the name ADR-007 worked to choose, creates no friction in corporate environments, and matches the license of Keycloak, a central dependency of the architecture. For a project whose message is rigour in access control, it is the coherent option.

The AGPL is rejected for solving a problem the project does not have, at a price paid precisely in the main goal. MIT is rejected by omission, not by fault: it is a good license that here leaves two things uncovered that matter.

The full text is applied unmodified, with the appendix filled in with the year and the holder, in a `LICENSE` file at the root of the repository.

## Consequences

- **Upward compatibility settled.** Apache 2.0, MIT and BSD dependencies can be incorporated without conflict.
- **Downward compatibility with a caveat.** According to the Free Software Foundation, Apache 2.0 is compatible with GPLv3 but **not with GPLv2**. If a GPLv2 dependency without the "or later" clause is ever considered, it has to be studied before, not after.
- **Section 6 registers no trademark.** It stops the license being read as an assignment of the name, which is different from protecting the name against third parties. That would need a EUIPO registration, which ADR-007 already noted as pending should the name ever be used to sell services.
- **Dual licensing stays possible while there is a single holder.** If there is ever a commercial version, the same code can be offered under another license to whoever pays for it. That door closes as soon as outside contributions arrive without a prior agreement, so **before accepting the first outside contribution a choice has to be made between a DCO and a CLA**, along with adding a `CONTRIBUTING.md`.
- **No `NOTICE` file for now.** Apache 2.0 does not require creating one, only propagating it if it exists. Creating one with no useful content only adds obligations for whoever redistributes. One will be created when there is something real to declare, for example third party code incorporated into the repository.
- **Whether to put license headers in every source file is still open.** The license appendix recommends it, many projects do not do it and some automate it in CI. It is decided when the backend skeleton is created, because it is cheaper to do from the first file than retroactively across hundreds.
- **The public demonstration is covered.** Apache 2.0 imposes no obligations for offering the software over a network, so deploying the demonstration in the homelab creates no additional obligation.

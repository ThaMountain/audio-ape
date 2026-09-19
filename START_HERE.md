# AUDIO APE — Codex handoff package

**Status:** implementation blueprint, not a completed Android application. **Prepared:** 2026-09-19. **Product owner:** Nick. **Intended consumer:** Codex or a human Android engineering team.

## Open these files in order

1. [`AGENTS.md`](AGENTS.md) — mandatory agent operating rules, scope, report format, stop conditions.
2. [`AUDIO_APE_MASTER_SPEC.md`](AUDIO_APE_MASTER_SPEC.md) — authoritative product requirements, UX states, stack, modules, algorithms, invariants and ship criteria. **This is the primary text document; it embeds all mockup images.**
3. [`docs/DESIGN_SYSTEM.md`](docs/DESIGN_SYSTEM.md) — implementation-oriented visual specs, animation, semantics and mockup discrepancy register.
4. [`docs/ARCHITECTURE_AND_CONTRACTS.md`](docs/ARCHITECTURE_AND_CONTRACTS.md) — storage, database, player, importer, background jobs, plugin grammar and security interfaces.
5. [`docs/EXECUTION_PLAN.md`](docs/EXECUTION_PLAN.md) — sequenced work tickets with acceptance tests and dependency gates.
6. [`docs/RESEARCH_AND_RISKS.md`](docs/RESEARCH_AND_RISKS.md) — primary-source URLs, findings, unresolved feasibility and compliance gates.
7. [`contracts/`](contracts/) — **illustrative, deliberately narrow** JSON schemas and test fixtures; these are starter contracts that must pass security review before external plugin publishing.
8. [`assets/mockups/`](assets/mockups/) — ten source mockups including the overview poster. They are **visual targets, not runnable screens**.

## The instruction to give Codex

> Read START_HERE.md, AGENTS.md, AUDIO_APE_MASTER_SPEC.md and all docs and contracts before changing code. Start in a **new repository/foundation**; do not silently reuse old experiments. Give me an inventory and a Phase 0 plan first. Implement **one ticket at a time** from docs/EXECUTION_PLAN.md and stop for review at each milestone gate. The mockups are visual references, but the *written decisions override mockup inconsistencies*. Keep all features legal and independently testable with public-domain/authorized media. Never claim a test passed without executing it. Never invent an external API contract, platform approval, or source data.

## Important deliverable boundaries

- Includes product design, engineering plan, example contracts, 10 generated design images, reference links and Codex instructions.
- Does **not** include app source code, a built APK, tested integration credentials, authorization to redistribute third-party content, the older original brand asset as an independently extracted transparent file, or evidence that the image generator's fictional book/plugin data corresponds to real services.
- A ZIP archive is the portable handoff; keep the folder structure intact so the Markdown image links work.
- `AUDIO_APE_MASTER_SPEC.txt` is a plain-text copy of the principal spec for tools that cannot read Markdown; use the Markdown version for images.

## Source of truth / precedence

1. Most recent explicit user choices in the conversation, transcribed in MASTER SPEC, outrank old files.
2. Master spec and approved change records outrank the supporting docs.
3. Design system describes implementation of the approved mockup *style*; if the generated image depicts a different product behavior, behavior in MASTER SPEC wins.
4. Existing 2026-09-18 engineering plan is historical context, **not** an authority; this package incorporates newer decisions (especially persistent user-accessible storage and uninstall deleting app history while preserving books).

## Implementation status

All tickets are **NOT STARTED** at handoff. Research is a design input, not proof that any complete provider workflow or signed plugin installer has been built or tested.

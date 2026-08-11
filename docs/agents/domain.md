# Domain Docs

How the engineering skills should consume this repository's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repository root, or
- **`CONTEXT-MAP.md`** at the repository root if it exists — it points at one `CONTEXT.md` per context. Read each one relevant to the topic.
- **`docs/adr/`** — read ADRs that touch the area about to be changed.

If any of these files do not exist, proceed silently. Domain-modeling skills create them lazily when terms or decisions are resolved.

## File structure

This is a single-context repository:

```text
/
├── CONTEXT.md
├── docs/adr/
└── src/
```

## Use the glossary's vocabulary

When output names a domain concept in an issue title, proposal, hypothesis, or test name, use the term defined in `CONTEXT.md`. Do not drift to synonyms the glossary explicitly avoids.

If a needed concept is absent, reconsider whether it belongs to the domain language or note the gap for domain modeling.

## Flag ADR conflicts

If output contradicts an existing ADR, surface the conflict explicitly instead of silently overriding it.

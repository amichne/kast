---
okf_version: 1.0
---

# Kast knowledge base

This checked-in Open Knowledge Format bundle explains Kast through stable concepts backed by repository sources. It is designed for progressive disclosure: choose a category, read one concept, and open the cited source only when implementation detail is necessary.

## Start here

- [Modules](modules/index.md) — ownership and dependency boundaries.
- [Runtime flows](flows/index.md) — how requests and state move through the system.
- [Contracts](contracts/index.md) — invariants that callers and adapters must preserve.
- [Glossary](glossary/index.md) — terms with repository-specific meaning.
- [Update log](log.md) — meaningful knowledge-base changes.

## Reading order

1. Read the governing `AGENTS.md` for durable engineering rules.
2. Use a category index to select one concept.
3. Follow concept links to adjacent concepts before opening implementation.
4. Use `code_sources` and inline source links for authoritative details.
5. Run the impact command after source changes to find concepts that may need review.

## Boundary

The bundle covers architectural ownership, public semantic contracts, principal runtime flows, installation configuration, and the proof vocabulary shared across modules. It does not replace source, tests, generated schemas, or the verified Gradle architecture report.

## Commands

```shell
./gradlew verifyKnowledgeBase
./gradlew knowledgeImpact
```

The first command strictly validates the OKF and every `code_sources` path. The second compares the working tree with source bindings and reports affected concepts.

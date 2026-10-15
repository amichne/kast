---
type: System Explanation
title: Knowledge upkeep and coverage assessment
description: Explains how scoped guides, concept Claims, package routes, source impact,
  and citation checks support wiki maintenance.
tags:
- kast
- development
code_sources:
- path: AGENTS.md
- path: .github/scripts/code_kb.py
- path: .github/workflows/openwiki-update.yml
- path: build.gradle.kts
- path: openwiki/architecture/functional-packages.md
verified:
  - by: openwiki/0.6.1
    at: 2026-10-15T00:33:05.932Z
sources:
  - id: openwiki-source-6c3c32c8e0fc1de5046d2785
    resource: repo://.github/scripts/code_kb.py
  - id: openwiki-source-6d4b4e707b8d60b6ccfa3425
    resource: repo://.github/workflows/openwiki-update.yml
  - id: openwiki-source-8037e2358a2c4f9b2c722a11
    resource: repo://AGENTS.md
  - id: openwiki-source-2a9daaac1604f238ef4c63fb
    resource: repo://build.gradle.kts
generated: { by: "codex", at: "2026-10-15T00:33:05.932Z" }
---

# Knowledge upkeep and coverage assessment

## Separate instructions, explanations, and evidence

Scoped `AGENTS.md` files own durable engineering rules and local source routes.
OpenWiki pages explain mechanisms and connect their source owners.
Claims bind material propositions to repository evidence under the OpenWiki lifecycle.
A generated index routes readers. It does not establish that a proposition remains true.

The package map covers the current production packages and their functional explanations.
It excludes experimental fixture packages from the production inventory.
Read the mapped topic for execution, state, failure, and test detail.
A package row alone does not prove complete behavioral coverage.

## Update through one page owner

Start or resume a native OpenWiki update before changing generated pages.
During planning, use current source and focused tests to choose topic paths and useful neighboring pages.
Keep one wiki with nested directories when those paths express the system structure.
Separate wikis are unnecessary for ordinary page depth.

Consume the persisted queue one assigned page at a time.
Read its current body before an update.
Keep accurate existing Claims without repeated submissions.
Reexamine stale Claims and explicitly confirm, revise, or retract each one.
Submit new material propositions with source evidence.
OpenWiki owns Claim identifiers, evidence versions, indexes, and final provenance.

Finish only after the queue completes and finalization succeeds.
If source drift invalidates the plan, start from the current native run state and create a replacement plan.
Do not repair a rejected lifecycle by editing sidecars or run metadata directly.

## Repository gates and their limits

`knowledgeImpact` reports concepts affected by changed cited sources.
`verifyKnowledgeBase` examines Markdown links, source paths, and exact declaration citations.
Kotlin declaration checks use an isolated PSI parser. Python declaration checks use the AST.
Declaration presence does not prove compiler identity or installed runtime behavior.

The checked-in OpenWiki workflow uses `workflow_dispatch`.
It requests a native update, preserves completed progress in a pull request, and reports failed runs explicitly.
Its CI credential is separate from a local subscription login.

The pinned prose assessment records metrics, diction findings, and glossary scope.
An `ASSESSED` result is not a claim of reader comprehension or factual completeness.
Keep the report and examine every substantive warning against the source meaning.

## Source owners

- [AGENTS.md](../../AGENTS.md)
- [code_kb.py](../../.github/scripts/code_kb.py)
- [openwiki-update.yml](../../.github/workflows/openwiki-update.yml)
- [build.gradle.kts](../../build.gradle.kts)
- [functional-packages.md](../architecture/functional-packages.md)

## Read next

- [Package coverage](../architecture/functional-packages.md)
- [Installed projection](../knowledge/installed-projection-and-retrieval.md)
- [Test evidence](testing-and-native-qualification.md)

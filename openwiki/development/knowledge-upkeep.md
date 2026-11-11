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
sources:
  - id: openwiki-source-e090e78423037e84d34cb024
    resource: repo://.github/scripts/ci/verification_plan.py
  - id: openwiki-source-eebbe85eaa5a49dadc0f9250
    resource: repo://.github/scripts/ci/verify_documentation.py
  - id: openwiki-source-6c3c32c8e0fc1de5046d2785
    resource: repo://.github/scripts/code_kb.py
  - id: openwiki-source-6d4b4e707b8d60b6ccfa3425
    resource: repo://.github/workflows/openwiki-update.yml
  - id: openwiki-source-8037e2358a2c4f9b2c722a11
    resource: repo://AGENTS.md
  - id: openwiki-source-c6c1ba497e026f49339a1117
    resource: repo://bin/kast-knowledge-refresh
  - id: openwiki-source-2a9daaac1604f238ef4c63fb
    resource: repo://build.gradle.kts
  - id: openwiki-source-6def96ae23af4541fb39b31d
    resource: repo://docs/tooling/claim_evidence.py
  - id: openwiki-source-c4a5ef7d5c7661ac58d253ba
    resource: repo://docs/tooling/knowledge_check.py
  - id: openwiki-source-7cbb86f93a654c4731d1a6a0
    resource: repo://docs/tooling/knowledge_refresh.py
  - id: openwiki-source-6506da7d406f804b7ef3a6df
    resource: repo://docs/tooling/markdown/evidence-assess.ts
  - id: openwiki-source-13b7644050eecaa683409f39
    resource: repo://docs/tooling/markdown/evidence-native.ts
  - id: openwiki-source-7177b22fefe1052541d9b757
    resource: repo://docs/tooling/okf_public.py
  - id: openwiki-source-1dc1f7665b2e88f8623231f1
    resource: repo://docs/tooling/publication.py
  - id: openwiki-source-7268f9708ec0a94462a131a0
    resource: repo://docs/tooling/reader_contracts.py
  - id: openwiki-source-36d4f4364ef6c0647be956fd
    resource: repo://docs/tooling/test_knowledge_refresh.py
  - id: openwiki-source-ed23841223cbb1f8ea2721ef
    resource: repo://docs/tooling/test_publication.py
generated: { by: "pi", at: "2026-11-11T06:28:10.019Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-11T06:28:10.019Z
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

The native TypeScript adapter uses OpenWiki 0.9.0 ClaimsStore, OpenWikiIgnore, RepositoryEvidenceResolver, and runClaimsPreflight.
Its read-only assessment never finalizes or changes verification timestamps.
Completed reports distinguish current, stale, and unresolved Claims.
Unresolved takes precedence over stale. Operational exceptions produce failed assessments.
Missing or empty Claim sets, orphaned sidecars, and canonical page-digest mismatches remain explicit failures of admission.
Public scope combines projected concepts with canonical owners referenced by authored associations.
The complete canonical corpus receives a separate assessment.
`verifyKnowledgeClaims` blocks source-only qualification and documentation CI on stale or unresolved canonical Claims.
Reports bind the source revision, producer, native inputs, pages, sidecars, and resolved evidence digests.
Input drift during assessment rejects the snapshot.

The update workflow examines page-to-sidecar agreement before native update.
A prose mismatch cannot enter a no-op or inherit verification through a metadata-only digest refresh.
Routine update pull requests include canonical knowledge and projector-owned outputs.
They exclude workflow definitions and engineering instructions.
Partial page progress remains reviewable without a claim of complete canonical or publication acceptance.

The publication manifest also owns reader contracts and reviewed authored associations.
Associations bind the full imported content to Claim identities and statement digests or exact generated contract bytes.
Edits to either side create a new review obligation.
Each published Claim also needs a source declaration citation or an executable test citation.
Kotlin PSI, Python AST, and the TypeScript parser establish declaration presence, not behavioral truth or a passing test.
A reviewed source revision can be at most five days older than the candidate HEAD commit timestamp.
This rule uses commit timestamps, not the wall clock.
Navigation parents derive from docs.json. Parsed outgoing edges determine incoming edges.
Reader measurements distinguish complete static footprints from unmeasured rendered expansion.
Author review, actual reader evaluation, and native task execution remain separate evidence.

`knowledgeImpact` reports concepts affected by changed cited sources.
`verifyKnowledgeBase` examines Markdown links, source paths, and exact declaration citations.
Kotlin declaration checks use an isolated PSI parser. Python declaration checks use the AST.
Declaration presence does not prove compiler identity or installed runtime behavior.

The checked-in OpenWiki workflow uses `workflow_dispatch`.
It requests a native update, preserves completed progress in a pull request, and reports failed runs explicitly.
Its CI credential is separate from a local subscription login.

`kast-knowledge-refresh` can run an explicit host-agent refresh in a separate owned clone and worktree.
It uses the locked OpenWiki runtime and retains an unverified candidate for review without automatic commits or publication.
Its launchd recipe is optional and is not installed by a refresh.
An ephemeral cloud host needs an authorized startup invocation or its own scheduler.

The pinned prose assessment records metrics, diction findings, and glossary scope.
An `ASSESSED` result is not a claim of reader comprehension or factual completeness.
Keep the report and examine every substantive warning against the source meaning.

## Source owners

- [AGENTS.md](../../AGENTS.md)
- [code_kb.py](../../.github/scripts/code_kb.py)
- [openwiki-update.yml](../../.github/workflows/openwiki-update.yml)
- [`build.gradle.kts`](../../build.gradle.kts)
- [functional-packages.md](../architecture/functional-packages.md)

## Read next

- [Package coverage](../architecture/functional-packages.md)
- [Installed projection](../knowledge/installed-projection-and-retrieval.md)
- [Test evidence](testing-and-native-qualification.md)

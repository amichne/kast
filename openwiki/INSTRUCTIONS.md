---
type: Engineering Guide
title: Kast knowledge maintenance instructions
description: Select the correct knowledge store, review source impact, and validate changes without weakening OpenWiki evidence.
resource: repo://kast/openwiki
tags: [kast, knowledge, maintenance]
timestamp: "2026-11-08T22:48:35Z"
source_revision: d43916783426e49e5e871820d4068fffbff93994
code_sources:
  - path: AGENTS.md
  - path: .github/scripts/code_kb.py
  - path: .knowledge/impact.json
  - path: .github/workflows/openwiki-update.yml
  - path: build.gradle.kts
  - path: docs/tooling/markdown/evidence-native.ts
---

# Maintain Kast knowledge

## Choose the owner

Follow [repository rules](../AGENTS.md) before changing knowledge.
`openwiki/` owns source-backed explanations and Claims.
`docs/` contains authored documentation, tooling, and public projections; it is not an OKF knowledge bundle.
Do not scan `docs/` with a generic OKF checker or add metadata to unrelated pages to satisfy that scan.
`.knowledge/impact.json` declares the OpenWiki backend for hooks that support repository-local selection.
Older installed hooks can ignore this file and still report the wrong store.
Update them through their marketplace release process, not by editing cached payloads.

Use [quickstart](quickstart.md) to find the relevant topic when retrieval tools are unavailable.
Read only the sections needed for the task.
Trace material claims to current source, contracts, and focused tests.
A matching source digest does not prove semantic accuracy or native behavior.

## Review source impact

From the repository root, run:

```sh
mise exec -- ./gradlew knowledgeImpact
```

This command examines the index, tracked edits, and untracked files.
It does not compare committed branch changes with `origin/main`.
A clean checkout can therefore report zero changes while a pull request changes cited source.
For committed changes, pass each repository-relative path to the checker:

```sh
mise exec -- uv run --locked --project docs/tooling python .github/scripts/code_kb.py \
  impact --repo . --docs openwiki --changed-file .github/workflows/ci.yml --format json
```

Repeat `--changed-file` for each relevant path, including both endpoints of a rename.
Use `git diff --name-status origin/main...HEAD` to inspect the pull-request scope.
The impact report lists citation matches, not proof that explanations remain correct.
Review those explanations against the diff before deciding whether to update them.

## Preserve the OpenWiki lifecycle

For generated concepts, use the native OpenWiki begin, plan, page, Claim, and finish sequence.
Read the current page before revising it.
Keep accurate Claims; confirm, revise, or retract changed Claims with source evidence.
Let OpenWiki own Claim identifiers, sidecars, indexes, source versions, and finalization.
Do not edit digests or verification dates merely to make checks pass.
This instructions file is authored guidance, not a generated system explanation.

The checked-in update workflow runs on manual dispatch, not a schedule.
It pins its own OpenWiki version and needs a separate CI credential.
A local tool version or login does not establish that CI can run.
Do not dispatch generation, install tooling, or publish changes without applicable authority.
See [knowledge upkeep](development/knowledge-upkeep.md) for the lifecycle and evidence limits.

## Validate and report

Run the repository citation guard after source or knowledge changes:

```sh
mise exec -- ./gradlew verifyKnowledgeBase
```

It checks Markdown links, source paths, and exact declaration citations.
It does not reconcile Claims or prove compiler resolution.
When Claim freshness matters, use the locked native assessment:

```sh
mise exec -- node docs/tooling/markdown/evidence-assess.ts --root "$PWD" --scope canonical
```

Keep its current, stale, unresolved, or failed result separate from citation validation.
Do not relabel stale Claims as current without reviewing their evidence.
Run Vale and the pinned prose assessment for changed guidance, as required by `AGENTS.md`.
Keep reports under `build/reports/`.

A backend-aware hook reports `NOT_ASSESSED` when OpenWiki requires its native assessment.
That result routes the next action here; it does not mean that drift is absent.
If a hook reports `outcome=invalid` or an error, treat drift as unverified.
An advisory exit code does not override the reported failure.
Inspect the selected store and checker before changing metadata.
For a hook owned by a marketplace, fix its canonical source; never patch installed caches.
Report the source revision, changed paths, exact checks, failures, and unverified evidence.

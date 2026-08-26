---
type: Playbook
title: Agent setup
description: Install one stable global Kast rule in the agent instruction file.
resource: kast://guide/agent-setup
tags:
  - kast
  - agent
  - setup
  - onboarding
timestamp: '2026-08-21T00:00:00Z'
code_sources:
  - path: install.sh
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/command/CliCommandGraph.kt
---

# Agent setup

Run this once for each coding-agent environment. The prompt delegates the instruction-file location to the agent, so the site does not need harness-specific setup pages.

## Prompt

```text
Configure Kast in this agent's user-level, always-on instructions.

Find the user-level instruction file that this agent loads for every
repository, such as its global AGENTS.md. Do not modify the current
repository.

Add or replace one `## Kast` section with exactly this content:

## Kast

For Kotlin or Gradle repository work, use `kast` for supported
compiler-grounded inspection and changes. Read `kast --help` and the
relevant command help before use; do not rely on remembered syntax.
Run Kast from the target repository. Preserve selectors and outcome
states returned by Kast. Do not reconstruct symbol identity. Do not
treat `Qualified` or `Rejected` as `Complete`. For a Kast change, do
not claim completion until Kast verifies it.

Make the update idempotent. Preserve all unrelated instructions.
Report the instruction file path and the final Kast section.
```

## Why the rule stays small

The installed CLI owns command syntax through `kast --help` and `kast --schema`. The global rule owns only stable behavior: use Kast, preserve returned identity, and preserve outcome state.

## First request

> Use Kast to inspect this repository, discover the declaration named `HealthController`, resolve the correct candidate, and describe the exact symbol.

See [Symbol identity](../concepts/symbol-identity.md) before writing a custom integration.

---
type: "query"
date: "2026-07-21T20:23:33.680615+00:00"
question: "Split Kast Codex tooling into a separately published fast-forward marketplace, remove plugin digest/version coupling, and delegate daemon/runtime concerns to the CLI"
contributor: "graphify"
outcome: "useful"
source_nodes: ["machine.rs", "hook.rs", "daemon.rs", "marketplace"]
---

# Q: Split Kast Codex tooling into a separately published fast-forward marketplace, remove plugin digest/version coupling, and delegate daemon/runtime concerns to the CLI

## Answer

Expanded from original query via graph vocab: [plugin, marketplace, receipt, digest, daemon, gradle, install, version, cli, hook, skill, sync]. The current coupling is in cli-rs/src/machine.rs: activation embeds resources/codex-marketplace and hashes it in machine.json; reconciliation installs that local marketplace. cli-rs/src/codex/hook.rs already delegates SessionStart daemon startup and post-write status/diagnostics to the running Kast CLI. The minimal split is therefore to publish the static marketplace separately, remove Codex bytes/digest from the machine manifest and release asset pipeline, and have reconcile replace/add the Git-backed marketplace while the thin plugin invokes the effective kast executable.

## Outcome

- Signal: useful

## Source Nodes

- machine.rs
- hook.rs
- daemon.rs
- marketplace
---
type: "query"
date: "2026-07-21T12:57:41.509982+00:00"
question: "Which files define the failing kast-action setup runtime contract?"
contributor: "graphify"
outcome: "useful"
source_nodes: ["verify-setup-kast-install.sh", "setup()", "setup_smoke.rs", "install.sh"]
---

# Q: Which files define the failing kast-action setup runtime contract?

## Answer

Expanded from original query via graph vocab: [action, setup, install, runtime, receipt, home, root, current, verify, contract]. The graph routes the failure through scripts/verify-setup-kast-install.sh, cli-rs/src/install/bundle_entrypoint.rs, cli-rs/tests/setup_smoke.rs, and install.sh.

## Outcome

- Signal: useful

## Source Nodes

- verify-setup-kast-install.sh
- setup()
- setup_smoke.rs
- install.sh
# GitHub integration guide

This file applies to `.github/` and descendants. This tree contains both
authored GitHub automation and repository-local Copilot package outputs, so
agents must identify which surface they are touching before editing.

## Authored surfaces

These files are hand-authored and may be edited directly when they own the
change:

- `.github/workflows/*.yml`
- `.github/scripts/*`
- `.github/ci/*.json`
- `.github/dependabot.yml`
- `.github/skill-shadowing.json`

Do not add provider-specific assistant trigger workflows. The V1 hosted-agent
path is the headless runtime plus the published `kast-action` contract smoke.

Run the narrowest script or workflow contract that covers the edit. For docs
contract changes, run both docs contract scripts and `zensical build --clean`.
For release workflow changes, run `.github/scripts/test-release-workflow-contract.sh`.
Homebrew publication owns only the CLI formula and must delete the retired
plugin cask from the tap; signed plugin ZIP, feed, signer, checksum, and
provenance verification remain release-owned JetBrains artifacts.
For CLI terminal command or executable example changes, run
`.github/scripts/test-terminal-command-contract.sh`.
The release-free local authority gate lives in
`.github/scripts/test-local-development-refresh-contract.sh`. Keep it wired in
the independent `local-authority-contracts` CI job whenever refresh
orchestration, source/artifact provenance, immutable
generation activation, rollback/removal, local readiness, or installed
skill/guidance routing changes. Its Gradle graph must remain headless and must
not include user JetBrains profile or release-configuration mutation.
The legacy developer install remains covered separately by
`.github/scripts/test-development-cli-install-contract.sh`. It is not the
revision-coherent local authority and must not run for every pull request.
Keep its real IDEA plugin task execution on integrated `main` pushes so
explicit-directory, configured-profile, running-profile, newest-profile,
missing-profile, and configuration-cache behavior cannot regress behind
dry-run task-graph coverage. Removing that documented compatibility surface
requires an explicit ADR decision.

Umbrella source contracts must not rerun focused owners. The CLI/plugin
cutover contract owns source presence, absence, and authority assertions only;
the runtime compatibility contract owns deterministic source and manifest
rendering only. Rust unit and integration tests run in `rust-cli`, Kotlin and
IDEA tests run in their Gradle owners, documentation rendering runs in the
documentation workflow, and installer, release, provenance, and asset
contracts run once in their named jobs. A focused Rust integration test must
not return success by skipping when an outer installer environment variable is
absent.

The `workflow-contracts` job is the static CI fanout gate. It must not install
Java, initialize Gradle, install Rust, or execute an installed-development
workflow. `.github/ci/issue-401-workflow-model.json` records the expanded DAG,
stable proof-output ownership, and timing samples. Keep output equivalence
blocking, keep timing provisional until five comparable successful candidate
runs exist, and list integrated non-PR proofs explicitly in `canaryTaskIds` so
they remain in the output inventory without inflating the required
pull-request critical path. Run `.github/scripts/test-ci-workflow-model.sh`
whenever jobs, `needs` edges, proof owners, canary classification, or timing
evidence change.
The separate `.github/scripts/test-local-development-semantic-e2e.sh` canary
must exercise the receipt-owned installed entrypoint, not checkout build
outputs. It owns refresh idempotence plus compiler-backed readiness, exact
symbol resolution, a known exhaustive nonzero reference, complete clean-file
diagnostics, plan-only mutation, explicit runtime shutdown, and receipt-owned
removal. Keep its authoritative job in
`.github/workflows/local-development-canary.yml`, outside ordinary pull-request
CI. The reusable workflow must run on integrated `main`, nightly, manually,
and from release preparation; release publication must fail closed when it
fails and preserve actionable runtime logs.

The signed JetBrains repository source lives at
`packaging/jetbrains/plugin-repository.json`. Runtime pair and IDEA build-range
truth lives separately at `packaging/jetbrains/runtime-compatibility.json`.
The authored renderers and GitHub Release adapters live in `.github/scripts/`;
release and docs workflows may
publish only their verified generated Pages output. A stable release may
advance the feed only after GitHub proves the release immutable and the
downloaded ZIP passes signer-bound cryptographic verification. A docs deploy
may only preserve the already-published feed. Both flows must materialize,
upload, and deploy under the shared `github-pages` concurrency lock. Never
hand-author `updatePlugins.xml` or `plugin-repository-manifest.json`, and never
derive the enrolled signer from a secret or mutable release variable.

The release workflow renders `kast-runtime-compatibility.json` from the
authored matrix using the exact release tag and commit, records a dedicated CI
artifact ledger and provenance entry, and uploads it immutably. Release
verification must validate that asset's source-owned IDEA range, positive
revisions, capability arrays, and same-release IDEA row. Run the dedicated
runtime compatibility contract whenever source, rendering, metadata parsing,
release wiring, or this ownership boundary changes.

Publishable CI artifacts are single-producer per commit. Producer jobs must
write a `scripts/verify-ci-artifact-ledger.py` receipt for the artifact they
built, and downstream packaging or publication jobs must verify that receipt
against the exact downloaded file before consuming it. Do not add a publishing
job that rebuilds a receipt-owned artifact; add a new producer receipt or make
the publisher consume an existing one.

## Copilot Package Source

Repository-local Copilot install outputs are not checked-in V1 sources. The
retained package source lives under `cli-rs/resources/plugin/` for LSP config
and prompt-time typed command guidance. Do not add generated `.github`
package copies or local installer shims here.

## Verify

For Copilot package source changes, run:

```console
.github/scripts/test-kast-copilot-plugin.sh
.github/scripts/test-lsp-config.mjs
```

For docs contract changes, run:

```console
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
```

For terminal commands and executable examples, run:

```console
.github/scripts/test-terminal-command-contract.sh
```

For local-development authority changes, run:

```console
.github/scripts/test-ci-workflow-model.sh
.github/scripts/test-local-development-refresh-contract.sh
.github/scripts/test-development-cli-install-contract.sh
.github/scripts/test-selector-handle-installed-workflow.sh
.github/scripts/test-local-development-semantic-e2e.sh
```

For signed JetBrains repository or Pages publication changes, run:

```console
.github/scripts/test-jetbrains-plugin-repository-contract.sh
.github/scripts/test-runtime-compatibility-contract.sh
.github/scripts/test-idea-plugin-signing-contract.sh
.github/scripts/test-release-workflow-contract.sh
```

For plugin-eval metric pack changes, run the script that owns the changed pack,
such as `.github/scripts/test-kast-routing-evals.sh` for routing checks or
`.github/scripts/run-kast-format-impact-report.sh` for the TOON format impact,
answer-request capture, and optional scored-answer pack.

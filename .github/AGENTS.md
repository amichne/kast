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
plugin cask from the tap. The release workflow owns the unsigned IDEA ZIP and
its adjacent `updatePlugins.xml` feed asset; neither participates in the
non-IDEA checksum or provenance system.
For CLI terminal command or executable example changes, run
`.github/scripts/test-terminal-command-contract.sh`.
The release-free processless machine authority gate lives in
`.github/scripts/test-local-development-refresh-contract.sh`. Keep it wired in
the independent `local-authority-contracts` CI job whenever refresh
orchestration, machine manifest activation, closed-IDE reconciliation, local
readiness, or installed skill/Codex routing changes. Its Gradle graph must build
the IDEA plugin, must not build or start `backend-headless`, and must not
install launchd state.
Umbrella source contracts must not rerun focused owners. The processless
machine authority contract owns source presence, absence, activation, and
reconciliation assertions; the runtime compatibility contract owns
deterministic source and manifest rendering only. Rust unit and integration tests run in `rust-cli`, Kotlin and
IDEA tests run in their Gradle owners, documentation rendering runs in the
documentation workflow, and installer, release, provenance, and asset
contracts run once in their named jobs. A focused Rust integration test must
not return success by skipping when an outer installer environment variable is
absent.

The Linux build-and-test job exclusively owns the JVM backend test suite and
its reports. The source-bound Linux backend job is the sole pull-request
portable-distribution producer and owns its no-fat-jar assertion, artifact,
and ledger. Do not add a platform build for an archive that is neither shipped
nor consumed. Production macOS authority remains the GitHub-hosted IDEA plugin
installed by JetBrains and the separate Homebrew CLI.

The `workflow-contracts` job is the static CI fanout gate. It must not install
Java, initialize Gradle, install Rust, or execute an installed-development
workflow. It captures and ledgers the immutable source snapshot consumed by
the existing Rust and Linux producers; this static identity step must not turn
into a second build owner. `.github/ci/issue-401-workflow-model.json` records
the expanded DAG, stable proof-output ownership, and timing samples. Keep
output equivalence blocking. A moved proof needs an explicit typed
`retiredProofOutputReplacements` entry naming its current owner. A proof for a
deliberately removed product property may be removed from both normalized
graphs only when an accepted ADR records that scope contraction; unexplained
loss remains a failure. Keep timing provisional until five comparable
successful candidate runs exist; historical baseline-only sampling gaps remain
explicit warnings rather than weakening the candidate gate. List integrated
non-PR proofs explicitly in `canaryTaskIds` so
they remain in the output inventory without inflating the required
pull-request critical path. Run `.github/scripts/test-ci-workflow-model.sh`
whenever jobs, `needs` edges, proof owners, canary classification, or timing
evidence change.
Developer-machine semantic proof runs through an open IDEA project and the
selected machine CLI. Do not add a local headless semantic fixture or canary.
Linux release headless packaging and action-runtime contracts remain separate
CI/release concerns and must not be described as developer-machine authority.

`packaging/jetbrains/updatePlugins.xml` is the hand-authored GitHub Release feed
template. The release job substitutes its tag and version and uploads the feed
beside the unsigned ZIP; docs workflows do not materialize or preserve a
JetBrains Pages repository. `packaging/jetbrains/runtime-compatibility.json`
separately owns typed runtime pairs and IDEA build ranges. It remains a source
and admission contract, not a generated release asset. Run the dedicated
runtime compatibility contract whenever that source or its metadata consumers
change.

Publishable CI artifacts are single-producer per commit. Producer jobs must
write a `scripts/verify-ci-artifact-ledger.py` receipt for the artifact they
built, and downstream packaging or publication jobs must verify that receipt
against the exact downloaded file before consuming it. Do not add a publishing
job that rebuilds a receipt-owned artifact; add a new producer receipt or make
the publisher consume an existing one. Pull-request Linux packaging is owned
by explicit release layers. `source-bound-cli` and
`source-bound-headless-backend` build the single release CLI and backend while
their required Rust and Kotlin validation jobs run independently.
Downstream Ubuntu/Debian and `kast-action` packaging consumes the verified
release components without creating a developer-machine generation.

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

For development-machine authority changes, run:

```console
.github/scripts/test-ci-workflow-model.sh
.github/scripts/test-local-development-refresh-contract.sh
.github/scripts/test-selector-handle-installed-workflow.sh
```

For IDEA GitHub Release distribution changes, run:

```console
.github/scripts/test-runtime-compatibility-contract.sh
.github/scripts/test-release-workflow-contract.sh
.github/scripts/test-macos-installer-contract.sh
```

For plugin-eval metric pack changes, run the script that owns the changed pack,
such as `.github/scripts/test-kast-routing-evals.sh` for routing checks or
`.github/scripts/run-kast-format-impact-report.sh` for the TOON format impact,
answer-request capture, and optional scored-answer pack.

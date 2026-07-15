# GitHub integration guide

This file applies to `.github/` and descendants. This tree contains both
authored GitHub automation and repository-local Copilot package outputs, so
agents must identify which surface they are touching before editing.

## Authored surfaces

These files are hand-authored and may be edited directly when they own the
change:

- `.github/workflows/*.yml`
- `.github/scripts/*`
- `.github/dependabot.yml`
- `.github/skill-shadowing.json`

Do not add provider-specific assistant trigger workflows. The V1 hosted-agent
path is the headless runtime plus the published `kast-action` contract smoke.

Run the narrowest script or workflow contract that covers the edit. For docs
contract changes, run both docs contract scripts and `zensical build --clean`.
For release workflow changes, run `.github/scripts/test-release-workflow-contract.sh`.
For CLI terminal command or executable example changes, run
`.github/scripts/test-terminal-command-contract.sh`.

The signed JetBrains repository source lives at
`packaging/jetbrains/plugin-repository.json`. The authored renderer and GitHub
Release adapter live in `.github/scripts/`; release and docs workflows may
publish only their verified generated Pages output. A stable release may
advance the feed only after GitHub proves the release immutable and the
downloaded ZIP passes signer-bound cryptographic verification. A docs deploy
may only preserve the already-published feed. Both flows must materialize,
upload, and deploy under the shared `github-pages` concurrency lock. Never
hand-author `updatePlugins.xml` or `plugin-repository-manifest.json`, and never
derive the enrolled signer from a secret or mutable release variable.

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

For signed JetBrains repository or Pages publication changes, run:

```console
.github/scripts/test-jetbrains-plugin-repository-contract.sh
.github/scripts/test-idea-plugin-signing-contract.sh
.github/scripts/test-release-workflow-contract.sh
```

For plugin-eval metric pack changes, run the script that owns the changed pack,
such as `.github/scripts/test-kast-routing-evals.sh` for routing checks or
`.github/scripts/run-kast-format-impact-report.sh` for the TOON format impact,
answer-request capture, and optional scored-answer pack.

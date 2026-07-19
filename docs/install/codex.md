---
title: Install The Codex Plugin
description: Install the release-matched Kast marketplace and activate kast@kast in Codex.
icon: lucide/blocks
---

# Install The Codex Plugin

Use this path after the Kast CLI and a semantic backend are installed. The
Codex plugin adds semantic routing and workflow guardrails; it does not install
Kast or prepare a workspace.

## Check The Prerequisites

Install the Kast release that matches the Codex plugin release. On macOS, open
the exact project or linked worktree in IntelliJ IDEA or Android Studio with
the GitHub-hosted Kast plugin active. On a supported Linux host, prepare the exact
root through the headless installation path.

The existing provider-neutral `kast` skill owns readiness, repair, and
workspace preparation. The `kast-codex` skill deliberately does not expose
those operations.

## Download The Marketplace

Download the Codex plugin archive and aggregate checksums from one Kast
release. Keep the extracted marketplace in a stable local directory because
Codex records that marketplace location.

```console
export KAST_CODEX_TAG="v1.2.3"
export KAST_CODEX_DOWNLOAD_DIR="/absolute/path/to/kast-codex-download"
export KAST_CODEX_MARKETPLACE_ROOT="/absolute/path/to/kast-codex-marketplace"
export KAST_CODEX_ASSET="kast-codex-plugin-${KAST_CODEX_TAG}.zip"

mkdir -p "$KAST_CODEX_DOWNLOAD_DIR" "$KAST_CODEX_MARKETPLACE_ROOT"
gh release download "$KAST_CODEX_TAG" \
  --repo amichne/kast \
  --dir "$KAST_CODEX_DOWNLOAD_DIR" \
  --pattern "$KAST_CODEX_ASSET" \
  --pattern SHA256SUMS

cd "$KAST_CODEX_DOWNLOAD_DIR"
grep "  ${KAST_CODEX_ASSET}$" SHA256SUMS | shasum -a 256 -c -
unzip -q "$KAST_CODEX_ASSET" -d "$KAST_CODEX_MARKETPLACE_ROOT"
```

The extracted root contains `marketplace.json`, a byte-identical
`.agents/plugins/marketplace.json` discovery manifest, and `plugins/kast/`.
It does not contain a Kast binary, MCP server, or app connector.

## Add The Plugin

Register the extracted non-default marketplace, then install the plugin by its
stable marketplace identity.

```console
codex plugin marketplace add "$KAST_CODEX_MARKETPLACE_ROOT"
codex plugin add kast@kast
codex plugin list
```

Confirm that `kast@kast` is listed. Then start a **new Codex task**. Existing
tasks do not reload plugin skills and hooks after installation.

## Update The Plugin

Verify and extract the newer release into a clean marketplace directory, then
replace the contents of the configured marketplace root with that verified
generation. Reinstall the same identity:

```console
codex plugin add kast@kast
codex plugin list
```

Start another new Codex task after the update. The CLI and plugin manifest
must have the same release version; do not combine a plugin archive from one
release with a Kast binary from another.

## Keep Existing Workspace Guidance

Do not remove a repository or workspace `.agents/skills/kast` installation.
It remains the provider-neutral readiness and preparation guide used outside
the Codex plugin.

If readiness reports a legacy global `~/.codex/skills/kast`, inspect the repair
plan before changing it. Kast may back up and remove only a receipt-owned copy,
and only after explicit apply authority. An unknown user-owned copy is reported
and left unchanged. See [Codex plugin recovery](../troubleshoot.md#recover-the-codex-plugin)
for the safe sequence.

Continue with [use Kast in Codex](../use/codex.md) for the semantic workflow.

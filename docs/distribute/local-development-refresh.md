---
title: Validate A Local Checkout
description: Build and exercise one revision-coherent Kast checkout without publishing a release.
icon: lucide/refresh-cw
---

# Validate A Local Checkout

Use this how-to when you are developing Kast itself and need agents to exercise
the current checkout before a release exists. The workflow creates a separate
headless `local-development` authority; it does not replace release `kast`, a
Homebrew receipt, or a JetBrains-installed plugin.

## Prerequisites

Run the workflow from the exact primary checkout or linked worktree you want to
test. The checkout needs JDK 21, Rust, and the repository Gradle wrapper's usual
build dependencies.

## Refresh The Local Generation

Build, attest, stage, and activate the current checkout with one non-interactive
command from the repository root.

```console title="Build and activate the current checkout"
./gradlew refreshDevelopmentLocal
```

The task captures the commit and current tracked plus non-ignored source bytes,
builds the Rust CLI and portable headless backend, attests both artifacts, and
activates them with the checkout's skill, guidance, and configuration. The
default authority is isolated under `.kast/local-development/`.

The command succeeds only after every component belongs to the same source
snapshot and its checksum matches. Repeating it without source or artifact
changes is idempotent. If staging or activation fails, the previously active
generation remains selected.

The CLI source digest is compiled into the local executable. The exact local
plugin JAR carries the backend source digest plus a producer manifest that
names and hashes every repo-built runtime JAR in the portable distribution.
Refresh rejects an ordinary Cargo binary, a relabeled backend, a stale sibling
JAR, an incomplete component set, or bytes changed after attestation.

## Prepare Once, Activate Without Rebuilding

Use the split tasks when the same source-attested generation must be reused or
when activation timing must exclude compilation and packaging.

```console title="Build and attest one immutable generation"
./gradlew prepareDevelopmentLocalGeneration
```

The task writes a prepared directory under
`build/local-development/prepared-generations/` and records its selected path
in `build/local-development/prepared-generation-path.txt`. Its strict
`generation.json` ledger binds the source snapshot, CLI, backend, producer
provenance, backend component manifest, skill, guidance inputs, and runtime
configuration to fixed relative paths and SHA-256 digests. The directory is
portable as a unit: component provenance does not retain the producer's
temporary absolute paths.

Activate the selected directory without running Cargo, Kotlin compilation,
Gradle packaging, or another attestation pass.

```console title="Verify and activate the prepared generation"
./gradlew activateDevelopmentLocal
```

To consume a relocated generation, select it explicitly.

```console title="Activate a relocated prepared generation"
./gradlew activateDevelopmentLocal \
  -PkastLocalPreparedGeneration="/absolute/path/to/prepared-generation"
```

Activation runs the exact prepared `bin/kast`, recomputes every source and
component digest, validates the embedded backend manifest, and rejects extra,
missing, linked, special, or renamed entries before it changes authority.
`refreshDevelopmentLocal` remains the one-command aggregate of preparation and
activation.

## Understand Repository Validation

Pull requests run focused proof at the boundary that owns each change. The
static workflow gate captures one source snapshot. Independent source-bound
CLI and backend jobs produce one CLI and backend from it while Rust and Kotlin
validation run in parallel. Linux owns the JVM backend tests. The macOS product
surface remains the GitHub-hosted IDEA plugin release boundary. The source-bound Linux
producer owns the only pull-request portable headless distribution, its
no-fat-jar layout assertion, and its artifact ledger; the former unconsumed
macOS archive proofs are explicitly replaced by those production-input proofs
in the workflow model. One prepared-generation job attests and packages that
generation. The required semantic fixture can consume it immediately; a pair
of parallel downstream owners derives the Ubuntu/Debian bundle and
published-action runtime inputs from the verified prepared bytes. The action
owner installs those inputs in the same focused job, avoiding another artifact
hop without rebuilding either component. One required
pull-request job activates the generation against a small
two-module Gradle fixture. It proves real headless import, selector-handle
reuse, main/test/test-fixture diagnostics, a
deliberate unresolved reference, plan-only rename, shutdown, and removal in a
single runtime cycle without installing Rust or refreshing the generation.

The complete Kast-on-Kast installed semantic scenario runs on
main, nightly, manual, and release paths so a cold full-repository import does
not delay ordinary pull-request feedback. The same fail-closed canary definition
exercises the receipt-owned CLI and headless backend in every path; release
publication cannot proceed after a failed canary. A failure preserves the
workflow log and uploads available runtime logs for diagnosis.

## Verify What Agents Will Use

Ask the receipt-owned launcher for machine-readable readiness before relying on
the local generation.

```console title="Inspect the active source and component authority"
./.kast/local-development/bin/kast \
  --output json \
  ready \
  --for machine \
  --workspace-root "$PWD"
```

The response identifies `local-development` authority, the canonical checkout,
commit, source SHA-256, active binary and backend, physical and effective skill
and guidance targets, and every component checksum. A mixed or modified
component fails readiness instead of falling back to release state.

Start the real semantic path through the refreshed headless generation for the
exact checkout. Runtime launch revalidates the full receipt, including backend
bytes, before it consumes the selected classpath.

```console title="Start the exact local headless backend"
./.kast/local-development/bin/kast developer runtime up \
  --workspace-root "$PWD" \
  --backend=headless
```

A first start performs a real Gradle import and waits until every Kotlin source
module has an SDK, valid dependencies, JDK and Kotlin runtime symbols, PSI, and
compiler diagnostics before reporting ready. The public runtime status and
semantic requests share that same compiler-admission state: status remains
`INDEXING` while admission is pending and becomes `DEGRADED` if it fails. If
IDEA already started an automatic sync, Kast waits for and adopts that sync
instead of racing it with a second import. Only a newly spawned local headless
runtime receives the five-minute cold-import allowance. Reusing an existing
runtime honors the caller's ordinary timeout, and release, demo, and normal
semantic request budgets remain unchanged. Later starts normally reuse
generation-scoped state. Source-index data is isolated with the generation
too, so a refreshed checkout cannot inherit semantic rows from older local
bytes.

Local runtime startup shares the prefix authority lock with refresh, rollback,
and removal. It revalidates the active receipt under that lock and retains the
lock until the exact spawned process ID registers for this workspace. A
concurrent generation transition therefore either finishes first and makes the
stale start fail before spawn, or observes the registered live runtime and
refuses the transition; it cannot orphan a process between those states. Two
concurrent start commands re-inspect under the lock, so the second reuses the
first registered process instead of spawning a duplicate. Lock-wait time does
not consume the post-spawn cold-import budget, and a child that exits before
registration is reaped and reported immediately.

The local headless process disables the release plugin's project-open setup
hook before IDEA starts. It does not read Homebrew release authority, install a
JetBrains profile, or rewrite workspace setup metadata.

Then verify backend identity, health, and compiler-backed workspace evidence.

```console title="Verify headless semantic capabilities"
./.kast/local-development/bin/kast agent verify \
  --workspace-root "$PWD" \
  --backend=headless \
  --explain
```

Require `state: READY`, `workspaceKind` matching the exact checkout, and
`evidenceQuality: COMPILER_BACKED` before using semantic results. Exact symbol
lookup and diagnostics then run through the installed generation, not a
checkout build output.

```console title="Exercise exact compiler evidence"
./.kast/local-development/bin/kast agent symbol \
  --query io.github.amichne.kast.headless.HeadlessWorkspaceKind \
  --workspace-root "$PWD" \
  --backend=headless \
  --explain

./.kast/local-development/bin/kast agent diagnostics \
  --file-path backend-headless/src/main/kotlin/io/github/amichne/kast/headless/HeadlessWorkspaceKind.kt \
  --workspace-root "$PWD" \
  --backend=headless \
  --explain
```

Exact references fall back to compiler/PSI search when the generation-scoped
source index is unavailable or has no evidence. Kast reports a reference page
as available only when it can prove the searched scope complete; partial
evidence remains explicitly degraded.

A plan-only rename is also a real semantic request. Kast resolves the symbol to
a compiler anchor, asks the refreshed backend for a dry-run rename, and reports
the resulting nonzero edits, affected files, and pre-edit file hashes without
writing source bytes. It is typed separately from an applied mutation, so only
`--apply` requires applied-mutation authority. A static command-shaped plan is
not accepted as proof.

The exact workspace receives an `AGENTS.local.md` symlink to the active
generation. The source-owned root `.gitignore` keeps that projection local;
refresh fails closed when the selected workspace does not ignore it. Both that
guidance and its installed skill teach the absolute receipt-owned startup and
verification sequence, so an agent cannot silently cross back to ordinary
`kast` or another generation's runtime. Refresh parses every taught invocation
against the staged CLI, including flags, required arguments, values, and the
small supported set of documentation placeholders. Bare command-path
references remain valid, but a stale flag or incomplete runnable example
fails before activation. Only the closed set of explicitly prohibited command
references is exempt as negative guidance; a positive invocation on that same
line is still parsed.

## Exercise The Local Generation In Codex

Generate a Codex marketplace only after the local generation is active. The
local launcher derives a generation-qualified output directory under its own
prefix and publishes the complete tree with one directory rename.

```console title="Generate the active worktree's Codex plugin"
./.kast/local-development/bin/kast \
  --output json \
  developer codex generate \
  --local
```

Record the returned `outputDirectory`. Repeating the command for the same
generation is an exact no-op. Kast refuses to overwrite a different or
user-owned output directory.

The generated `assets/kast-authority.json` declares the absolute stable
`bin/kast` path, generation identifier, CLI version, and
generation-qualified plugin version. Generated hook commands carry that exact
path and token to the launcher; the invoked CLI reads the manifest and
revalidates both against the active receipt before doing work. An executable
with the same basename at another path is not accepted.

Codex uses the stable `kast@kast` identity for both release and local
marketplaces. Before switching, inspect `codex plugin marketplace list --json`
and record the healthy released marketplace root as
`KAST_RELEASE_CODEX_MARKETPLACE_ROOT`. Then replace the marketplace snapshot
and reinstall the one plugin identity.

```console title="Select the generated local plugin for the next Codex task"
export KAST_LOCAL_CODEX_MARKETPLACE_ROOT="/absolute/outputDirectory/from/generate"

codex plugin marketplace remove kast
codex plugin marketplace add "$KAST_LOCAL_CODEX_MARKETPLACE_ROOT"
codex plugin add kast@kast
codex plugin list --json
```

`marketplace remove` also removes the installed `kast@kast`, so released and
local hooks are never enabled together. Finish the whole switch and confirm
the generation-qualified version before starting a new Codex task. The task
that performed the switch keeps its original plugin generation.

If local generation or installation fails, restore the recorded release
snapshot before starting another task. This is also the normal clean reset
after local testing.

```console title="Restore the released Codex plugin"
codex plugin marketplace remove kast
codex plugin marketplace add "$KAST_RELEASE_CODEX_MARKETPLACE_ROOT"
codex plugin add kast@kast
codex plugin list --json

./gradlew removeDevelopmentLocal
```

Confirm that `kast@kast` reports the released version, then start a new task.
If no healthy release marketplace was recorded, download and verify the latest
release first by following [Install the Codex plugin](../install/codex.md); do
not guess a cache path or keep a broken local plugin selected.

## Keep Linked Worktrees Isolated

Run the refresh inside each linked worktree. Its default prefix lives inside
that worktree, so source identity, runtime descriptors, caches, skill, and
guidance are not borrowed from another checkout.

Use explicit properties when a worker needs an external isolated prefix.

```console title="Select an exact workspace and prefix"
./gradlew refreshDevelopmentLocal \
  -PkastLocalWorkspaceRoot="$PWD" \
  -PkastLocalPrefix="/absolute/path/to/worker-kast-local"
```

Keep the prefix dedicated to that exact workspace. Refresh canonicalizes its
lock authority and rejects an attempt to switch an existing prefix to a
different workspace or reach an existing prefix through a final symlink.

## Roll Back Or Remove The Local Authority

Reactivate the validated previous generation when a new checkout build is not
usable.

```console title="Roll back one generation"
./gradlew rollbackDevelopmentLocal \
  -PkastLocalGeneration="<generation-id>"
```

Select the exact `generationId` reported by readiness or a preceding refresh.
Repeating the same targeted rollback is a no-op; an implicit second toggle is
never allowed. A refresh or rollback that would switch the active generation
refuses while any generation-owned backend PID is live and reports the exact
receipt-owned stop command. An unchanged idempotent refresh or rollback does
not disturb that runtime.

Remove the local prefix and its owned workspace guidance when testing is
finished.

```console title="Restore ordinary release authority"
./gradlew removeDevelopmentLocal
```

Removal preserves unrelated repository ignore rules, source files, Homebrew
state, release binaries, user configuration, and JetBrains plugin state. It
also removes its exact owned guidance projection even when the prefix was
already deleted, including a dangling symlink. Use the same
`kastLocalWorkspaceRoot` and `kastLocalPrefix` properties for rollback or
removal when the refresh used explicit values. Gradle uses the installed stable
controller while it exists; after the prefix is already gone, it uses a
surviving source-built checkout controller so cleanup remains reachable. If
that build output was moved, select it explicitly with
`-PkastLocalRecoveryController=/absolute/path/to/kast`. Missing-prefix removal
shares the same canonical namespace lock as refresh, so concurrent cleanup
cannot delete a newly activated projection. Removal also refuses while any
generation-owned backend PID is still live and reports the exact
receipt-owned `developer runtime stop` command to run first.

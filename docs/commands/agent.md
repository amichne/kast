---
title: Agent Automation Commands
description: Use the hidden `kast agent` command surface for scripts and agents.
icon: lucide/bot
---

# Agent Automation Commands

`kast agent` is the advanced CLI surface for scripts, CI steps, and coding
agents. It is hidden from the normal public command tree, but supported and
documented here because it is the preferred machine-oriented command path.

## JSON envelope

Every `kast agent` command emits one JSON object on stdout. Treat `ok: false`
as a failed operation even when the process exited cleanly.

```json title="Envelope shape"
{
  "ok": true,
  "method": "raw/resolve",
  "request": { "method": "raw/resolve" },
  "result": {}
}
```

Stderr may contain human-readable startup or progress messages. Scripts should
parse stdout and keep stderr for diagnostics.

## Alias commands

Use aliases for common shallow requests. They prepare the request object and
return the same envelope as `agent call`.

```console title="Resolve and trace from a file offset"
APP_FILE="$PWD/src/main/kotlin/App.kt"

kast agent raw-resolve --file-path "$APP_FILE" --offset 42
kast agent raw-references --file-path "$APP_FILE" --offset 42 --include-declaration
kast agent raw-call-hierarchy --file-path "$APP_FILE" --offset 42 --direction incoming --depth 3
```

Use name-based aliases when you know a Kotlin declaration name but not a file
offset.

```console title="Find and resolve by name"
kast agent workspace-symbol --pattern OrderService --max-results 20
kast agent resolve --symbol OrderService --kind class
kast agent references --symbol OrderService --kind class --include-declaration
```

## Structured calls

Use `kast agent call <method>` when a payload is too large for flags or when an
agent already has a structured request object.

```console title="Call a catalog method with a params file"
kast agent call raw/apply-edits --params-file /tmp/apply-edits.json
```

The input may be a params object, a full request, a prior agent envelope, or a
`nextRequest` object. Keep nested edit plans in files and pass them with
`--params-file`.

## File-backed workflows

`kast agent workflow` writes deterministic evidence files for multi-step
operations. Use `--dry-run` to create input and workflow files without calling
the backend.

```console title="Workflow evidence"
kast agent workflow verify --out-dir .kast-workflows/verify
kast agent workflow symbol --symbol OrderService --references --out-dir .kast-workflows/symbol
kast agent workflow rename-plan \
  --file-path "$PWD/src/main/kotlin/App.kt" \
  --offset 42 \
  --new-name processOrderSafely \
  --out-dir .kast-workflows/rename
```

Mutating workflow commands require explicit mutation opt-in. Do not treat a
dry-run workflow as proof that files changed.

## Raw RPC fallback

Use raw `kast rpc` only when debugging the low-level transport or reproducing a
protocol issue. Agent scripts should prefer `kast agent` because it normalizes
request inputs and returns a consistent envelope.

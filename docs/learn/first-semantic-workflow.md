---
title: First Semantic Workflow
description: Verify Kast readiness and run the first typed semantic commands.
icon: lucide/zap
---

# First Semantic Workflow

Use this guided workflow after installing Kast and preparing the repository.
The examples use placeholder Kotlin names such as `OrderService`; replace them
with symbols from your project. This page is not yet a fixture-backed tutorial,
so it teaches the command sequence rather than guaranteeing identical output.

## 1. Verify Install State

Start with read-only readiness. It reports whether Kast is ready for the task
surface you plan to use.

```console
kast ready --for agent --workspace-root "$PWD"
kast ready --for kotlin --workspace-root "$PWD"
```

If readiness reports drift, plan repair before applying it.

```console
kast repair --for agent --workspace-root "$PWD"
kast repair --for agent --workspace-root "$PWD" --apply
```

## 2. Verify The Backend

`agent verify` checks backend health, runtime state, capabilities, and the
workspace root the backend is serving.

```console
kast agent verify --workspace-root "$PWD"
```

Use the runtime status command when the backend is missing, stale, or still
indexing.

```console
kast developer runtime status --workspace-root "$PWD"
kast developer runtime up --backend=headless --workspace-root "$PWD"
kast agent verify --workspace-root "$PWD"
```

## 3. Resolve Symbol Identity

Run lookup before editing. Kast resolves compiler identity first, then can add
references or callers when you need usage evidence.

```console
kast agent symbol --query OrderService --workspace-root "$PWD"
kast agent symbol --query OrderService --references --workspace-root "$PWD"
kast agent symbol --query process --callers incoming --workspace-root "$PWD"
```

If the symbol query finds more than one candidate, refine with a file hint,
kind, or containing type from the [agent command reference](../reference/agent-commands.md).

## 4. Check Diagnostics

Diagnostics refresh the touched file first unless you opt out with
`--skip-refresh`.

```console
kast agent diagnostics \
  --file-path "$PWD/src/main/kotlin/App.kt" \
  --workspace-root "$PWD"
```

Run diagnostics before mutation commands when you need to know which file state
the backend sees.

## 5. Plan A Rename

Rename is identity-first and plan-first. Review the symbol identity, write set,
and conflicts before applying.

```console
kast agent rename \
  --symbol com.example.OrderService \
  --new-name Orders \
  --workspace-root "$PWD"
```

Apply only after the planned write set matches the intended change.

```console
kast agent rename \
  --symbol com.example.OrderService \
  --new-name Orders \
  --apply \
  --workspace-root "$PWD"
```

Local-variable rename is deferred until Kast has a typed non-offset selector
for locals. Use named declaration identities for public rename workflows.

## 6. Plan A Scope Mutation

Mutation commands read Kotlin content from files and plan the edit before
writing. This keeps shell quoting out of the code path and gives agents a
stable request to review.

```console
cat >/tmp/member.kt <<'KOTLIN'
fun newBehavior(): String = "ready"
KOTLIN

kast agent add-implementation \
  --inside-scope com.example.OrderService \
  --at body-end \
  --content-file /tmp/member.kt \
  --workspace-root "$PWD"
```

Add `--apply` only after reviewing the planned request and content file. Use
[mutation selectors](../reference/mutation-selectors.md) when you need exact
placement rules.

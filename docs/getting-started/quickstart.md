---
title: Quickstart
description: Set up repository guidance and run typed Kotlin semantic commands.
icon: lucide/zap
---

# Quickstart

## 1. Prepare The Workspace

```console
cd /path/to/your/repository
kast ready --for agent --workspace-root "$PWD"
```

On macOS, open the repository in IntelliJ IDEA or Android Studio with the
Homebrew-installed Kast plugin enabled. The plugin writes
`.agents/skills/kast/SKILL.md`, one managed `<kast>...</kast>` region, and
`.kast/setup/workspace.json`. On non-macOS headless/server hosts, run
`kast setup --workspace-root "$PWD"` before the readiness check.

## 2. Check Readiness

```console
kast ready --for agent --workspace-root "$PWD"
kast agent verify --workspace-root "$PWD"
```

If readiness asks for repair, plan first and apply explicitly:

```console
kast repair --for agent --workspace-root "$PWD"
kast repair --for agent --workspace-root "$PWD" --apply
```

## 3. Resolve Symbol Identity

```console
kast agent symbol --query OrderService --workspace-root "$PWD"
kast agent symbol --query OrderService --references --workspace-root "$PWD"
kast agent symbol --query process --callers incoming --workspace-root "$PWD"
```

## 4. Validate And Rename

```console
kast agent diagnostics --file-path "$PWD/src/main/kotlin/App.kt" --workspace-root "$PWD"
kast agent rename --symbol com.example.OrderService --new-name Orders --workspace-root "$PWD"
kast agent rename --symbol com.example.OrderService --new-name Orders --apply --workspace-root "$PWD"
```

Rename plans are identity-first; local-variable rename is deferred until Kast has
a typed non-offset selector for locals.

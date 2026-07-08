---
title: Quickstart
description: Set up repository guidance and run typed Kotlin semantic commands.
icon: lucide/zap
---

# Quickstart

Use this path when you want to prove Kast is installed, connected to the
workspace, and ready for compiler-backed Kotlin work. The same `kast agent`
commands work against the IDEA plugin backend and the Linux headless backend.

## 1. Prepare The Workspace

Start with the host you are on. Developer machines use the root installer for
the Homebrew and plugin path, then the JetBrains plugin prepares workspace
metadata. Linux servers prepare repository guidance from the CLI.

=== "macOS developer machine"

    ```console
    cd /path/to/your/repository
    curl --fail --location --remote-name https://raw.githubusercontent.com/amichne/kast/main/install.sh
    chmod +x install.sh
    ./install.sh install --workspace-root "$PWD"
    open .
    kast ready --for agent --workspace-root "$PWD"
    ```

    Use the explicit update command when the hidden Homebrew path should be
    refreshed:

    ```console
    ./install.sh update --workspace-root "$PWD"
    ```

    The plugin writes `.agents/skills/kast/SKILL.md`, one managed
    `<kast>...</kast>` region, and `.kast/setup/workspace.json`.

=== "Linux or hosted agent"

    Run setup once for the repository, then check readiness. This path is for
    headless hosts without an open developer IDE.

    ```console
    cd /path/to/your/repository
    kast setup --workspace-root "$PWD"
    kast ready --for agent --workspace-root "$PWD"
    ```

## 2. Check The Backend

Readiness verifies install state. `agent verify` verifies the semantic backend
that will answer symbol, diagnostics, impact, rename, and mutation commands.

```console
kast ready --for agent --workspace-root "$PWD"
kast agent verify --workspace-root "$PWD"
```

!!! success "Ready signal"
    A ready workspace reports backend health, runtime status, capabilities, and
    the active workspace root. If the command reports install drift, repair with
    an explicit plan/apply pair before running semantic commands.

```console
kast repair --for agent --workspace-root "$PWD"
kast repair --for agent --workspace-root "$PWD" --apply
```

## 3. Resolve Symbol Identity

Run lookup before editing. Kast resolves compiler identity first, then can add
references or callers when you need usage evidence.

```console
kast agent symbol --query OrderService --workspace-root "$PWD"
kast agent symbol --query OrderService --references --workspace-root "$PWD"
kast agent symbol --query process --callers incoming --workspace-root "$PWD"
```

## 4. Validate And Rename

Diagnostics and rename commands are plan-first. Review the plan, then rerun
with `--apply` when the target identity and write set are correct.

```console
kast agent diagnostics --file-path "$PWD/src/main/kotlin/App.kt" --workspace-root "$PWD"
kast agent rename --symbol com.example.OrderService --new-name Orders --workspace-root "$PWD"
kast agent rename --symbol com.example.OrderService --new-name Orders --apply --workspace-root "$PWD"
```

Rename plans are identity-first; local-variable rename is deferred until Kast
has a typed non-offset selector for locals.

## 5. Plan A Scope Mutation

Use mutation commands when you want Kast to place Kotlin content with semantic
scope evidence. Content always comes from a file so shell quoting never changes
the Kotlin being applied.

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

Add `--apply` only after reviewing the planned request and the content file.

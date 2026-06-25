---
title: Lifecycle Commands
description: Start, inspect, restart, and stop Kast workspace backends.
icon: lucide/activity
---

# Lifecycle Commands

Lifecycle commands manage the backend that owns Kotlin analysis state. They are
the first commands to run when a workspace is cold and the first commands to
inspect when a semantic request fails.

## Start or warm a backend

Use `kast up` from the repository root or any subdirectory. The first run may
index the workspace; later calls reuse the warm backend.

```console title="Start the selected backend"
kast up --backend=headless
kast up --backend=idea
```

`--backend=headless` starts the packaged headless runtime. `--backend=idea`
uses the IDEA or Android Studio plugin backend when the project is open and the
plugin is installed.

## Inspect runtime state

Use `kast status` for a human-readable state summary. Add `--output json` when
a script needs resolved paths, daemon state, logs, warnings, or backend details.

```console title="Check runtime state"
kast status
kast --output json status
```

Use `kast capabilities` when you need to know which semantic operations the
selected backend advertises.

```console title="Inspect capabilities"
kast capabilities --backend=headless
```

## Restart or stop

Use `kast restart` when a backend should be rebuilt from the current install
state. Use `kast stop` to shut it down and remove runtime state owned by that
workspace.

```console title="Restart or stop"
kast restart --backend=headless
kast stop --backend=headless
```

Restart is broader than a workspace refresh. Use it when runtime state is
suspect, the backend was upgraded, or path resolution changed.

## Common checks

These checks keep lifecycle troubleshooting concrete. Prefer them before
guessing about plugin state, runtime paths, or daemon readiness.

| Need | Command |
|------|---------|
| Confirm a backend is reachable | `kast agent health` |
| Read machine output for automation | `kast --output json status` |
| Check the selected runtime path model | `kast paths` |
| Verify managed install state | `kast doctor` |
| Repair managed install state | `kast doctor --repair` |

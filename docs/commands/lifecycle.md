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

Use `kast developer runtime up` from the repository root or any subdirectory. The first run may
index the workspace; later calls reuse the warm backend.

```console title="Start the selected backend"
kast developer runtime up --backend=headless
kast developer runtime up --backend=idea
```

`--backend=headless` starts the packaged headless runtime. `--backend=idea`
uses the IDEA or Android Studio plugin backend when the project is open and the
plugin is installed. When `runtime.ideaLaunch.enabled` is true, Kast first
reuses a matching open IDEA backend and otherwise launches the configured IDEA
command. On macOS, the default `idea` command falls back to the latest known
IntelliJ IDEA or Android Studio profile when the launcher is not on `PATH`.

## Inspect runtime state

Use `kast developer runtime status` for a human-readable state summary in an
interactive non-agent terminal. Captured and agent-run invocations default to
compact TOON. Add `--output json` when a JSON-only script needs resolved paths,
daemon state, logs, warnings, or backend details.

```console title="Check runtime state"
kast developer runtime status
kast --output json developer runtime status
```

Use `kast developer runtime capabilities` when you need to know which semantic operations the
selected backend advertises.

```console title="Inspect capabilities"
kast developer runtime capabilities --backend=headless
```

## Restart or stop

Use `kast developer runtime restart` when a backend should be rebuilt from the current install
state. Use `kast developer runtime stop` to shut it down and remove runtime state owned by that
workspace.

```console title="Restart or stop"
kast developer runtime restart --backend=headless
kast developer runtime stop --backend=headless
```

Restart is broader than a workspace refresh. Use it when runtime state is
suspect, the backend was upgraded, or path resolution changed.

## Common checks

These checks keep lifecycle troubleshooting concrete. Prefer them before
guessing about plugin state, runtime paths, or daemon readiness.

| Need | Command |
|------|---------|
| Confirm a backend is reachable | `kast agent verify --workspace-root "$PWD"` |
| Read machine output for automation | `kast --output json developer runtime status` |
| Check the selected runtime path model | `kast developer inspect paths` |
| Verify managed install state | `kast ready` |
| Repair managed install state | `kast repair --apply` |

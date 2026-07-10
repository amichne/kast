---
title: Troubleshoot
description: Diagnose install issues, backend state, indexing, semantic failures, and mutations.
icon: lucide/triangle-alert
---

# Troubleshoot

Start with the visible symptom. Most readers do not need to run the underlying
checks themselves; the agent or support workflow can run the read-only command
sequence when needed.

## Diagnostic Matrix

| Symptom | Likely cause | Next action |
| --- | --- | --- |
| Kast does not appear available after install | Machine install or plugin refresh did not complete | Close IntelliJ IDEA and Android Studio, then rerun the installer |
| `~/.local/bin/kast` runs instead of Homebrew Kast | An older managed local shim precedes Homebrew on `PATH` | Run read-only machine readiness and use its cleanup command only when one is offered |
| Install or update says a JetBrains IDE is running | IntelliJ IDEA or Android Studio still has a live process | Enter `y` to close the reported PID, or exit and run the printed `kill -TERM` command; do not use `sudo` |
| The agent cannot use Kast in a macOS project | The project has not been opened with the Kast plugin active | Open the project in the IDE and let the plugin prepare it |
| Hosted Linux agent cannot answer semantic questions | Headless bundle or backend is not active | Check the image/bootstrap flow and runtime state |
| Symbol lookup returns an unexpected target | The query is too broad | Narrow by kind, file, or containing type before editing |
| Diagnostics disagree with the file on disk | Backend source state may be stale | Refresh or restart the runtime before retrying |
| Rename or mutation plan selects the wrong scope | The selector or identity is too broad | Resolve identity first and use a narrower selector |

## Keep Fixes Plan-First

Repair and mutation flows should report what they intend to change before they
write. If a plan points at the wrong symbol, scope, or file, refine the request
instead of applying it.

??? info "Read-only checks for agents and support"
    These commands separate install readiness, backend state, and semantic
    capability without changing source files.

    ```console
    kast --output json ready --for agent --workspace-root "$PWD"
    kast --output json agent verify --workspace-root "$PWD"
    kast --output json status --workspace-root "$PWD"
    ```

    On macOS, machine readiness also reports the active install authority, the
    exact Homebrew binary from the trusted receipt, and any legacy PATH shadow.

    ```console
    kast --output human ready --for machine
    ```

    A cleanup command is intentionally absent unless Kast proves that the
    shadow is its own writable legacy shim and that Homebrew is the next PATH
    candidate. Unknown or administrator-owned files are left unchanged.

    If runtime state is stale, agents or support workflows can restart the
    selected backend and verify again.

    ```console
    kast developer runtime status --workspace-root "$PWD"
    kast developer runtime restart --backend=headless --workspace-root "$PWD"
    kast agent verify --workspace-root "$PWD"
    ```

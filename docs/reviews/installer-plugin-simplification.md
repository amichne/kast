---
type: Engineering Review
title: Installer and agent startup simplification
description: Records the recent setup changes, the removed dependencies, and the limits of local evidence.
resource: repo://docs/reviews/installer-plugin-simplification
tags: [kast, installation, codex, pi]
timestamp: "2026-10-16T06:50:12Z"
source_revision: c63215981504766de0d3b861c801414e05768f57
code_sources:
  - path: install.sh
  - path: agent-tools/hooks/hooks.json
  - path: agent-tools/hooks/session_start.py
  - path: pi/mcp-extension.ts
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/mcp/KastMcpServer.kt
  - path: cli/src/main/kotlin/io/github/amichne/kast/cli/installation/InstallationWorkflow.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/PluginInstallation.kt
  - path: distribution/cli/src/main/kotlin/io/github/amichne/kast/distribution/cli/ForceLifecycleStates.kt
---

# Installer and agent startup simplification

Codex setup now uses its plugin manager. Pi reads bundled context directly with Node and registers the existing native MCP server.
Installation success no longer needs a running Codex broker.
The existing MCP session still owns automatic IntelliJ preparation for the exact Kotlin Gradle root.

The enterprise incident has no recorded command, version, logs, or machine state in this review.
The local cases prove the changed behavior. They do not establish the cause or recurrence of that incident.

## Recent changes and their effects

| Change | Source-backed effect | Decision |
| --- | --- | --- |
| `#1021`, `a84bea422` | Fresh installation selected the standard Codex control socket. | Retire public App Server registration. |
| `#1033` and `#1034` | Replacement added stronger process and socket ownership before retirement. | Keep exact ownership and scoped cleanup. |
| `#1042`, `3e843eb6e` | Recovery separated Host receipts and admitted ordinary Codex sessions. | Keep those protections. |
| `#1045`, `53908b558` | Fresh installs selected private sockets, but ordinary upgrades kept saved shared settings. A background hook added uv and Pydantic setup. | Migrate saved shared settings and remove the duplicate preparation hook. |
| `#1047`, `a2ecf783e` | Pi invoked a fixed uv path. Context failure also rejected later agent turns. | Read local resources with Node and report one warning without blocking turns. |

These changes leave the reference graph sources and query contracts unchanged.

## Owners after the change

`kast plugin codex`, `kast connect codex`, and the compatibility form `kast connect codex mcp` select one plugin owner.
Codex owns its plugin cache, configuration, App Server, and control socket.
New App Server registration rejects before effects.
Plugin installation rejects a foreign standalone MCP registration before plugin effects.
After Codex admits the plugin, Kast removes only exact receipt-owned legacy registrations.

The installer uses private settings and does not enable a broker.
Saved shared settings migrate to private settings with typed provenance.
An explicit shared setting rejects before installation effects.
Native force reinstall returns `RESET_INSTALLED` with installation, version, and command, without a service generation.
The reset interface exposes no startup or readiness capability.
Exact fence admission, payload admission, replay rejection, and failure compensation remain.

`KastMcpServer` starts preparation once on legacy initialization or the first admitted modern request.
`McpWorkspaceOperationClient` shares the same preparation owner with later semantic calls and health requests.
The passive context hook neither opens projects nor calls semantic tools.
It uses Python 3.9 or later and the standard library.
Pi needs no Python or uv process.

## Executed focused evidence

- **Passed:** The existing MCP cases cover initialization, modern discovery, preparation rejection, and calls outside Gradle.
- **Passed:** All 121 CLI installation cases cover the changed installer owner.
- **Passed:** Seven passive-hook cases run on macOS Python 3.9 and current Python.
- **Passed:** Six Pi cases cover direct reads, context limits, resource rejection, and normal turns after context failure.
- **Passed:** Strict compilation examines the authored Pi adapter against installed Pi declarations with `skipLibCheck=true`.
- **Passed:** Independent schema cases accept six reset shapes and reject 67 invalid shapes.
- **Passed:** The projected plugin excludes the removed setup scripts and Python environment files.

The full task logs and prose reports reside in `build/reports/plugin-isolation/`.
The prose assessment reports `ASSESSED` separately from a clean report.
Readability advisories and findings in unchanged legacy explanations remain visible.

## Delivery limits

No live enterprise installation, broker replacement, shared-socket removal, or IntelliJ window exercise ran for this review.
The old broker code remains for scoped legacy retirement and recovery.
A failed replacement can restore the exact predecessor under its existing ownership rules.

Codex owns cached plugin files. After an upgrade, an existing plugin needs `kast plugin codex` to refresh those files.
A fresh client session must then load the new plugin.
Installation, registration, and compilation do not prove workspace readiness or a compiler-grounded query.

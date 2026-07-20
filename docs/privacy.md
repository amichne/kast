---
title: Kast Codex Plugin Privacy
description: Data handling and local storage for the Kast Codex plugin.
icon: lucide/shield-check
---

# Kast Codex Plugin Privacy

This notice describes data handling by the open-source `kast@kast` Codex
plugin. It applies to the plugin code distributed by the Kast project, not to
the separate data practices of Codex, GitHub, JetBrains products, or other
software you choose to use with Kast.

## Local Processing

The plugin resolves and executes the Kast CLI installed on your machine. It
does not include an app connector, remote Kast service, analytics client, or
MCP server. The plugin does not independently transmit workspace or session
state to a service operated by the Kast project.

Codex supplies hook event input to the local plugin process. The plugin may
inspect that input, local Git/worktree identity, Kotlin file state, and
structured Kast CLI results to enforce its semantic workflow.

## Data Stored

The plugin stores bounded recovery and guardrail evidence under Codex's local
plugin data root:

```text
$PLUGIN_DATA/sessions/<session-id>.json
```

The record may include:

- the plugin and Kast versions and resolved binary path;
- canonical workspace, Git, linked-worktree, and commit identity;
- paths and SHA-256 fingerprints for relevant Kotlin files;
- typed command outcomes, affected paths, in-flight mutation keys, and failures; and
- diagnostics evidence and explicitly reported blockers.

The plugin does not use this state as a source-code mirror or source index.
Session files are written atomically with owner-only permissions.

## Retention And Deletion

Session evidence remains in the Codex-managed plugin data directory until it
is removed by the local user or by Codex's plugin-data lifecycle. Removing a
session record prevents later evidence checks until the next `SessionStart`,
which establishes a new baseline.

Uninstalling or disabling the plugin stops its hooks from creating new session
records. Check the local plugin data directory separately if you also want to
remove retained state.

## Network And Third Parties

The plugin's launcher and hook engine do not add a network integration. The
Kast CLI communicates with the locally selected IDEA or headless semantic
backend according to the Kast runtime contract.

Codex itself may process task text, tool input, tool output, and files under
the terms and controls of the Codex product you use. That processing is not
performed by a Kast-operated service.

## Security And Sensitive Data

Do not place secrets in command-line arguments or prompts merely because the
plugin is local. Paths, diagnostics, and tool outcomes can be visible to Codex
and can appear in local session evidence. Use your existing repository access,
secret-management, and Codex data controls.

The plugin is open source. Security reports should follow the reporting path
in the [Kast repository](https://github.com/amichne/kast/security).

## Changes

Material changes to this notice require a source change to this page and a new
plugin release. The plugin manifest links to the published notice at
`https://kast.michne.com/privacy/`.

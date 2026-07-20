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
inspect that input, exact worktree identity, Kotlin paths, and structured Kast
CLI results to provide advisory context.

## Data Stored

The plugin stores no plugin session data. Each hook invocation reads only its
event input and current local Kast status, then returns advisory context to
Codex. It does not maintain baselines, changed-file ledgers, or diagnostics
evidence between events.

The global Kast config may retain the three Codex hook enablement booleans. It
does not store hook event contents or diagnostics.

## Retention And Deletion

Uninstalling or disabling the plugin removes its packaged skill and hooks.

## Network And Third Parties

The plugin's launcher and hook engine do not add a network integration. The
Kast CLI communicates with the locally selected IDEA or headless semantic
backend according to the Kast runtime contract.

Codex itself may process task text, tool input, tool output, and files under
the terms and controls of the Codex product you use. That processing is not
performed by a Kast-operated service.

## Security And Sensitive Data

Do not place secrets in command-line arguments or prompts merely because the
plugin is local. Paths, diagnostics, and tool outcomes can be visible to Codex.
Use your existing repository access, secret-management, and Codex data controls.

The plugin is open source. Security reports should follow the reporting path
in the [Kast repository](https://github.com/amichne/kast/security).

## Changes

Material changes to this notice require a source change to this page and a new
plugin release. The plugin manifest links to the published notice at
`https://kast.michne.com/privacy/`.

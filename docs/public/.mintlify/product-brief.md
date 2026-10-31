# Product brief

## Description

Kast gives coding agents source relationships from IntelliJ IDEA's compiler analysis.
The native `kast` command is the primary interface.

## Primary audience

Kotlin developers who already use Codex, Copilot, or Pi are the primary readers.
The setup path assumes that the Kast CLI is installed.
It selects the latest qualified development build with `kast upgrade --channel main`.

## Source authority

PR #1144 defines the installation and guidance contract for this documentation change.
Its reviewed source revision is `647fffc46f6247fac5b0fc632ae019d157bf9020`.
Keep the documentation aligned with that merged implementation.
Remove inherited setup commands and release comparisons.
Do not describe a public bootstrap that the implementation does not supply.

## Jobs to be done

1. Select a qualified development build.
2. Install CLI guidance for the selected agent.
3. Ask a useful question about the developer's own code.

## Navigation and ownership

Guides contains Overview, Set up, Examples, and Help.
Keep Architecture and Reference visible.
The setup page owns the path from build selection to a first useful answer.
The installation reference owns build policies and activation outcomes.
The integration reference owns CLI guidance, skill export, and optional MCP.
The CLI reference owns common command examples.
Keep existing routes and anchors. Edit generated contracts through their generator.

## Claims and limits

The default agent composition supplies CLI guidance.
MCP needs an explicit `--mcp` selection.
The persistent Host in IntelliJ IDEA supplies compiler analysis for CLI and MCP.
Keep installed files, loaded Host identity, agent resources, and workspace readiness distinct.
A successful resource installation does not prove content in an existing agent session.
An unfinished query does not prove absence. Source relationships do not prove runtime execution.

## Visuals

Keep the value explorer as an optional explanation.
Label authored illustrations and recorded results with their evidence scope.
Do not invent successful transcripts or treat playback duration as query latency.

# Product brief

## Description

Kast extends coding agents working on Kotlin Gradle projects with IntelliJ IDEA's compiler analysis.
The product shift is from inferring code meaning from text to working with compiler-grounded declaration identity and source relationships.
Users describe their work naturally. The agent handles Kast's operations.
The native `kast` command is the primary interface.

## Primary audience

Kotlin developers who already use Codex, Copilot, or Pi are the primary readers.
The setup path supports a first installation without a checkout or an existing Kast CLI.
The remote installer selects the latest qualified development build with `--developer-latest --skip-codex-plugin`.
An existing installation uses `kast upgrade --channel main --restart-ide`.

## Source authority

The content revision was checked against `2e3feb6b3afd50b0ee90b52b092684ac83e3a879` and the user's revised content draft.
`install.sh` owns the public bootstrap and developer-channel selection.
The management CLI owns upgrades and agent guidance.
`docs/public/start.mdx` owns the complete first-use procedure.
Keep commands and qualifications aligned with those source owners.

## Jobs to be done

1. Select a qualified development build.
2. Install CLI guidance for the selected agent.
3. Continue ordinary development with compiler-grounded evidence available to the agent.

## Navigation and ownership

Guides contains Overview, Set up, Capabilities, and Help.
Keep Architecture and Reference visible.
The setup page owns installation and agent connection.
Keep special prompts and a first-query exercise out of the default path.
Optional fixtures belong in evidence checks; technical request syntax belongs in reference.
The installation reference owns build policies and activation outcomes.
The integration reference owns CLI guidance, skill export, and optional MCP.
The CLI reference owns common command examples.
Keep existing routes and anchors. Edit generated contracts through their generator.

## Claims and limits

The product standard is never silently wrong: establish the fact with evidence or expose the gap.
State this standard confidently without claiming current universal perfection or guaranteeing the agent's entire answer.
Keep the current semantic model, completion policy, and consequential evidence limits beside their definitions.

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

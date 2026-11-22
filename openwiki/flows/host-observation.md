---
type: Runtime Flow
title: Host observation and semantic campaign
description: The semantic campaign drives one installed native MCP session, drains public query output, and records bounded advisory Host observations; Promptfoo suites evaluate agents separately.
resource: file://experiments/host-observation
tags: [observation, mcp, evaluation]
code_sources:
  - path: experiments/host-observation/mcp_replay.py
    symbols: [McpReplaySession, drain_workload, admit_mcp_wire]
  - path: experiments/host-observation/test_mcp_replay.py
  - path: experiments/host-observation/README.md
  - path: experiments/host-observation/promptfoo/README.md
  - path: build.gradle.kts
sources:
  - id: openwiki-source-2a9daaac1604f238ef4c63fb
    resource: repo://build.gradle.kts
  - id: openwiki-source-91a187e8f9b4ae08c1ab931b
    resource: repo://experiments/host-observation/mcp_replay.py
  - id: openwiki-source-1a61c49e0f5b1eee9e6f1a38
    resource: repo://experiments/host-observation/README.md
generated: { by: "codex", at: "2026-11-21T22:16:17.145Z" }
verified:
  - by: openwiki/0.9.0
    at: 2026-11-22T20:53:36.158Z
---

# Host observation and semantic campaign

The host-observation directory no longer drives a user's open IDEA.
The desktop observer script, its controller, and the hosted qualification harnesses are removed.
Two evaluation paths remain, and neither belongs to Kast runtime composition.

## Semantic campaign through native MCP

`mcp_replay.py` starts one installed native MCP stdio process in the selected workspace.
`McpReplaySession` sends `initialize`, then exchanges one framed JSON-RPC request at a time.
`admit_mcp_wire` admits each reply frame before the campaign records it.
A timeout, ended process, oversized frame, or invalid frame becomes a finite recorded outcome instead of a retry.
The replay never starts, scripts, or restarts an IDE.

`drain_workload` drains one public query workload within a call limit.
It follows issued output continuations with `RESUME`, which presents already-produced output without new semantic execution.
When no continuation remains, it reads retained pages with `READ_RESULT` and keeps the original output selection and execution budget.
It stops when a continuation repeats, the live basis moves, a call fails, or the call limit is reached.

The campaign keeps each call's appended Host log records and transport timing as advisory observations.
A joined observation proves the observational correlation and grants no semantic or freshness authority.
Pure and scripted collector checks do not establish installed-host latency.

## Agent evaluation with Promptfoo

The [Promptfoo suites](../../experiments/host-observation/promptfoo/README.md) compare agents that receive the Kast skill and Kast access with source-only controls.
They measure correctness, cost, context use, and task time. Their results do not qualify a product release.

## Verification

Root `check` depends on `hostObservationTest`, which runs the Python suites in `experiments/host-observation`, including MCP replay.
It does not run the Promptfoo Node suites; the Promptfoo guide lists their commands.

## Source owners

- [mcp_replay.py](../../experiments/host-observation/mcp_replay.py)
- [test_mcp_replay.py](../../experiments/host-observation/test_mcp_replay.py)
- [README.md](../../experiments/host-observation/README.md)

## Read next

- [Tests and native qualification](../development/testing-and-native-qualification.md)
- [Direct MCP](../transport/direct-mcp-and-tool-rpc.md)

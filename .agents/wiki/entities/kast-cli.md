# Kast CLI

The Kast CLI is the user-facing entry point into the system. It resolves which
binary to run, discovers or launches the daemon, and exposes the commands that
humans and agents use to talk to the resident analysis engine.

## Summary

The CLI matters because it is the control plane for everything else. It hides
installation layout, instance selection, transport details, and JSON output
shapes behind a single executable that agents can script and humans can run
directly.

## What the wiki currently believes

- The CLI is intentionally lightweight so the expensive Kotlin analysis state
  can live in a separate JVM daemon.
- Workspace and daemon lifecycle commands are part of the CLI surface, not an
  afterthought layered onto the backend.
- The CLI is the place where agent-oriented helpers such as skill installation,
  smoke checks, and JSON output contracts become operational.

## Evidence and sources

The sources below support the current model of the CLI.

- [[sources/getting-started]] - Explains first-run behavior, discovery, and
  daemon lifecycle basics.
- [[sources/cli-command-reference]] - Defines the public verbs and output
  shapes.
- [[sources/kast-cli-native-cli-module]] - Describes runtime management,
  process launching, and RPC calls.
- [[sources/installation-and-instance-management]] - Explains how the CLI is
  located and how side-by-side versions are selected.

## Related pages

The pages below explain the systems the CLI depends on.

- [[entities/analysis-server]]
- [[entities/backend-standalone]]
- [[concepts/client-daemon-architecture]]
- [[concepts/installation-and-instance-management]]
- [[analyses/operator-journeys]]

## Open questions

The current source set leaves a few CLI questions open.

- Which commands have the strongest stability guarantees for external tooling?
- How much CLI startup overhead remains after native-image packaging?

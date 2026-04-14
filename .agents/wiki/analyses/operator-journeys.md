# Operator journeys

This page answers a different question: what are the main journeys a human or
agent actually follows when using Kast?

## Short answer

Most journeys fall into three patterns: install and verify Kast, connect to or
prepare a workspace, and then run read or mutation commands against a warm
daemon. Agents follow the same path, but they add a symbol-identification stage
and prefer JSON wrappers.

## Analysis

The first journey is onboarding. The caller installs a published or local build,
verifies binary resolution, and checks the daemon status. The important outcome
is confidence that the discovery cascade will locate the right instance later.

The second journey is workspace preparation. The caller optionally prewarms the
workspace, lets Kast discover the module graph, and confirms that the daemon is
ready. In practice, this is where the first-command cost is paid.

The third journey is semantic reading. The caller resolves a symbol, finds
references, requests diagnostics, or expands a hierarchy. This is the shortest
path to value once the daemon is warm.

The fourth journey is mutation. The caller asks for a rename or edit plan,
reviews the proposed changes, and then applies them. This path depends on the
contract layer, validation logic, and test coverage more heavily than the read
path does.

The fifth journey is shutdown and maintenance. The caller refreshes a workspace,
stops a daemon, upgrades an instance, or runs smoke checks and CI when releasing
or debugging the system itself.

## Evidence used

The pages below define those operator journeys.

- [[sources/getting-started]]
- [[sources/cli-command-reference]]
- [[sources/installation-and-instance-management]]
- [[sources/using-kast-from-an-llm-agent]]
- [[concepts/client-daemon-architecture]]
- [[concepts/semantic-analysis-operations]]

## Follow-ups

These expansions would make the journeys more concrete.

- Add a task-oriented page that compares the human CLI path to the agent-wrapper
  path step by step.
- Add examples of the most common failure and recovery loops during onboarding.

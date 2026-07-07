# Agent Module Instructions

This directory owns pipe-friendly typed `kast agent` behavior and internal
agent workflow contracts.

Keep command dispatch, internal tool catalog projection, retained workflow
execution, package verification, request input normalization, response
envelopes, and alias expansion in separate part files. A new public agent
command must be typed and must not expose arbitrary method dispatch.

Do not add or route new behavior through a shell `kast rpc` surface.
Agent-facing flows should prefer typed commands such as `kast agent symbol`,
`kast agent diagnostics`, `kast agent impact`, and `kast agent rename`.

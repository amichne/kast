# analysis-server: JSON-RPC Transport Layer

This page summarizes the raw note
[[analysis-server-JSON-RPC-Transport-Layer]]. It is the main source for the
transport boundary.

## Source

This source is a server-layer note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[analysis-server-JSON-RPC-Transport-Layer]]

## Summary

This note explains server startup, request dispatch, transport selection,
descriptor persistence, configuration, and error handling. Its main
contribution is showing how Kast exposes a local analysis service rather than an
in-process-only library.

It also clarifies that the server layer owns local operational concerns such as
socket paths, stdio mode, and lifecycle cleanup.

## Key claims

- Kast uses JSON-RPC as the request envelope between client and backend.
- Descriptor storage is essential for daemon discovery and reuse.
- Transport concerns are separated from semantic backend logic.

## Connections

This source feeds the transport and lifecycle pages.

- Reinforces [[entities/analysis-server]]
- Reinforces [[concepts/client-daemon-architecture]]
- Supports [[analyses/end-to-end-request-lifecycle]]

## Open questions

This source does not quantify some operational edge cases.

- How often do stale descriptors become a practical issue?
- Which transport is the default in most real deployments?

## Pages updated from this source

The pages below now depend on this source.

- [[entities/analysis-server]]
- [[concepts/client-daemon-architecture]]
- [[analyses/end-to-end-request-lifecycle]]

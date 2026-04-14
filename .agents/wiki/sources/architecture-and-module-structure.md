# Architecture and Module Structure

This page summarizes the raw note [[Architecture-and-Module-Structure]]. It is
the source that names the main Gradle modules and their boundaries.

## Source

This source is an architecture note.

- Type: note
- Date: Unknown
- Author: Unknown
- Location: [[Architecture-and-Module-Structure]]

## Summary

This note maps Kast's multi-module Gradle layout and explains how the CLI,
contract layer, transport layer, backend, packaging, shared tests, and build
logic work together. Its main contribution is a codebase-level view of the
system rather than an operator view.

It also reinforces the client-daemon framing by tying concrete modules to that
high-level design.

## Key claims

- Kast is intentionally separated into functional Gradle modules.
- The module graph mirrors the client-daemon architecture.
- Shared testing and build logic are structural parts of the project.

## Connections

This source connects the module map to the synthesized pages.

- Reinforces [[concepts/client-daemon-architecture]]
- Adds detail to [[entities/analysis-api]], [[entities/analysis-server]], and
  [[entities/backend-standalone]]
- Supports [[analyses/end-to-end-request-lifecycle]]

## Open questions

This source is broad but not granular on operational details.

- Which module boundaries are expected to remain most stable over time?
- Which modules most often change together in practice?

## Pages updated from this source

The pages below now depend on this source.

- [[entities/analysis-api]]
- [[entities/analysis-server]]
- [[entities/backend-standalone]]

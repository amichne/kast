# Convention script guide

This directory owns precompiled convention plugins whose `package kast` declaration preserves the
public `kast.*` plugin IDs. Follow [the build-logic guide](../../../../AGENTS.md) for shared plugin
boundaries and verification.

## Local scope

- Keep each script below the repository line limit.
- Keep repository-specific PR gate wiring in the `pr633-*` scripts and reusable task types under
  `support/pr633`.

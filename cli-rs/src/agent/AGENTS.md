# Agent Module Instructions

This directory owns pipe-friendly `kast agent` behavior and agent workflow
contracts.

Keep command dispatch, tool catalog projection, workflow execution, package
verification, request input normalization, response envelopes, and alias
expansion in separate part files. A new agent command must land in the part that
owns its contract, with request and response shape kept typed and explicit.

Do not add or route new behavior through a shell `kast rpc` surface.
Agent-facing flows should prefer catalog-backed methods and stable JSON
envelopes through `kast agent call`.

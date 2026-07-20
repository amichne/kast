# Codex plugin source guide

This repository package is skill-only. Rust generates the marketplace, manifest, exposure asset, and presentation metadata from the authored skill.

The plugin must not contain hooks, scripts, MCP or app configuration, custom agents, or copied command catalogs. Mutations execute synchronously through typed `kast agent` commands and return terminal structured results.

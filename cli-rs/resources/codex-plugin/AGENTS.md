# Codex plugin source guide

This repository package is a CLI-only skill with advisory hooks. Rust generates the marketplace, manifest, exposure asset, hook configuration, and presentation metadata from the authored skill and launcher.

The plugin may contain only the default-discovered `SessionStart` and `PostToolUse` hooks plus one thin launcher. It must not contain session state, mutation gates, MCP or app configuration, custom agents, or copied command catalogs. Mutations execute synchronously through typed `kast agent` commands and return terminal structured results.

# Kast

Kast is a Kotlin analysis server with one HTTP/JSON contract and one supported
runtime path: a repo-local CLI manages a standalone JVM daemon for local
automation, editor integrations, and CI.

The repo is organized as a Gradle multi-module build:

- `analysis-api`: shared contract, models, errors, and edit validation
- `analysis-cli`: CLI control plane for workspace status, ensure, daemon
    lifecycle, and request dispatch
- `analysis-server`: Ktor transport, descriptor file handling, and HTTP routes
- `backend-standalone`: standalone runtime entrypoint plus Kotlin Analysis API
    integration
- `shared-testing`: fake backend fixtures used by server and backend tests

## Current state

The standalone-first migration is now the supported shape of the repository:

- the repo-local CLI can ensure a workspace runtime, report status, and dispatch
    analysis operations as JSON
- the generated `analysis-cli` wrapper is relocatable with its `libs/` directory,
    uses a packaged `runtime-libs/` classpath for detached daemon launches,
    supports `--help` and `--version`, and adds human-oriented daemon notes on
    `stderr` without changing the JSON contract on `stdout`
- the standalone backend provides symbol resolution, references, diagnostics,
    rename planning, and edit application through the shared HTTP contract
- the runtime registers itself through workspace-local descriptor files under
    `.kast/instances/`
- the standalone build fetches IntelliJ IDEA `2025.3` directly as a declared
    dependency for the Analysis API bridge instead of depending on another
    module's warmed Gradle cache

The main remaining production gap is `callHierarchy`. The standalone backend
also still carries a small compatibility JAR while the upstream standalone
Analysis API catches up with the stable IntelliJ `2025.3` runtime APIs.

---
title: Operator guide
description: Runtime defaults, descriptor lifecycle, and host-specific behavior
  for Kast.
icon: lucide/server
---

Kast has one transport surface and two runtime hosts. This page covers how each
host starts, where it registers itself, and which operational differences still
matter today.

## Runtime defaults

Both runtime hosts are local-first by default. They bind to `127.0.0.1`, use
an ephemeral port unless configured otherwise, and register themselves through a
descriptor file instead of a fixed socket address.

| Host | Startup trigger | Intended use | Current capabilities |
| --- | --- | --- | --- |
| IntelliJ plugin | Open a project in the plugin-enabled IDE | Local development | `RESOLVE_SYMBOL`, `FIND_REFERENCES`, `DIAGNOSTICS`, `RENAME`, `APPLY_EDITS` |
| Standalone process | Launch the wrapper script or fat JAR | CI and headless workflows | `RESOLVE_SYMBOL`, `FIND_REFERENCES`, `DIAGNOSTICS`, `RENAME`, `APPLY_EDITS` |

## Descriptor lifecycle

Both hosts write a `ServerInstanceDescriptor` JSON file under
`~/.kast/instances/` by default. Set `KAST_INSTANCE_DIR` to place descriptor
files somewhere else.

Kast deletes the descriptor on clean shutdown, so consumers can treat the
directory as a live registration surface instead of a permanent inventory.

Each descriptor contains these fields.

| Field | Meaning |
| --- | --- |
| `workspaceRoot` | Absolute path to the workspace the runtime serves |
| `backendName` | Runtime host identifier such as `intellij` or `standalone` |
| `backendVersion` | Runtime version string |
| `host` | Bound network interface |
| `port` | Resolved port after startup |
| `token` | Shared secret for protected routes, if configured |
| `pid` | Process identifier for the running host |
| `schemaVersion` | Wire schema version |

## IntelliJ plugin host

The IntelliJ module starts one Kast server per open workspace. The server is
project-scoped and uses the running IDE's PSI and indices as its analysis
engine.

Build the plugin from the repo root:

```bash
./gradlew :backend-intellij:buildPlugin
```

Run it in a sandbox IDE:

```bash
./gradlew :backend-intellij:runIde
```

The current IntelliJ limits are fixed at startup:

- `maxResults = 500`
- `requestTimeoutMillis = 30000`
- `maxConcurrentRequests = 4`

For repository-side verification of the current IntelliJ `2025.3` and bundled
Kotlin path, run these checks from the repo root:

```bash
./gradlew :backend-intellij:test \
  :backend-intellij:verifyPluginStructure \
  :backend-intellij:buildSearchableOptions
```

That sequence proves the plugin descriptor structure, searchable-options
generation, and project-scoped server startup path that writes a descriptor and
serves `/api/v1/health` during IntelliJ-hosted tests.

`DIAGNOSTICS` now reports Kotlin semantic diagnostics for Kotlin files and
falls back to PSI parse errors for non-Kotlin PSI. Call hierarchy support
remains future work.

## Standalone host

The standalone runtime builds a fat JAR plus a launcher script. It uses the
same HTTP surface and descriptor format as the IntelliJ host, and it now
resolves symbols, references, diagnostics, and rename plans through the Kotlin
Analysis API in headless mode.

When you point the standalone host at a real repository, it looks for
conventional `src/main` and `src/test` roots first. If the workspace contains
Gradle build files, it discovers source sets and inter-module dependencies from
the Gradle model instead of treating the repo as one flat source tree.

Build the standalone distribution:

```bash
./gradlew :backend-standalone:fatJar :backend-standalone:writeWrapperScript
```

Run it against a workspace:

```bash
./backend-standalone/build/scripts/backend-standalone \
  --workspace-root=/absolute/path/to/workspace
```

The standalone CLI accepts `--key=value` arguments.

| Flag | Default | Notes |
| --- | --- | --- |
| `--workspace-root` | Current working directory | Falls back to `KAST_WORKSPACE_ROOT` when omitted |
| `--source-roots` | unset | Comma-separated absolute paths that override source-root discovery |
| `--classpath` | unset | Comma-separated absolute paths added to the standalone classpath |
| `--module-name` | `sources` | Module label used only when you override source roots manually |
| `--host` | `127.0.0.1` | Local-only by default |
| `--port` | `0` | Uses an ephemeral port by default |
| `--token` | unset | Falls back to `KAST_TOKEN` |
| `--request-timeout-ms` | `30000` | Sets the per-request timeout |
| `--max-results` | `500` | Caps `references` and `diagnostics` output |
| `--max-concurrent-requests` | `4` | Limits parallel backend work |

The standalone backend currently advertises `RESOLVE_SYMBOL`,
`FIND_REFERENCES`, `DIAGNOSTICS`, `RENAME`, and `APPLY_EDITS`. Call hierarchy
remains unavailable in production hosts.

## Operational notes

These constraints affect clients regardless of which host you run.

- `health` and `capabilities` are always available.
- Protected routes require `X-Kast-Token` only when the runtime was started
  with a token.
- `references` and `diagnostics` responses can be truncated at the configured
  `maxResults` limit.
- The `/api/v1/call-hierarchy` route exists, but production hosts do not
  advertise `CALL_HIERARCHY` yet.

## Next steps

Use [Choose a runtime](choose-a-runtime.md) if you are deciding which host to
start. Use [Get started](get-started.md) for the bootstrap flow, and read
[HTTP API](api-reference.md) when you are implementing a client against the
contract.

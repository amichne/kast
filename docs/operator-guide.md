---
title: Operator guide
description: Runtime defaults, descriptor lifecycle, and command behavior for
  Kast's standalone runtime.
icon: lucide/server
---

Kast now has one supported runtime and one preferred operator surface. This
page covers the repo-local CLI, the standalone daemon it manages, and the
descriptor lifecycle that still makes direct HTTP possible when needed.

## Runtime defaults

The supported workflow is local-first by default. The standalone daemon binds to
`127.0.0.1`, uses an ephemeral port unless configured otherwise, and registers
itself through a descriptor file instead of a fixed socket address.

| Surface | Startup trigger | Intended use | Current behavior |
| --- | --- | --- | --- |
| `analysis-cli` | Run a repo-local command | Default operator and agent path | Detached daemon management, readiness checks, capability-gated JSON output |
| Standalone daemon | Started by the CLI or direct launcher | Kotlin analysis engine | `RESOLVE_SYMBOL`, `FIND_REFERENCES`, `DIAGNOSTICS`, `RENAME`, `APPLY_EDITS` |

## Descriptor lifecycle

The standalone runtime writes a `ServerInstanceDescriptor` JSON file under
`<workspace>/.kast/instances/` by default. Set `KAST_INSTANCE_DIR` to place
descriptor files somewhere else.

Kast deletes the descriptor on clean shutdown, so consumers can treat the
directory as a live registration surface instead of a permanent inventory.

When the workspace is inside Git and Kast uses the default workspace-local
directory, descriptor registration also adds `/.kast/` to the repo-local
`.git/info/exclude` file. That keeps the metadata untracked without requiring a
committed ignore rule.

Each descriptor contains these fields.

| Field | Meaning |
| --- | --- |
| `workspaceRoot` | Absolute path to the workspace the runtime serves |
| `backendName` | Runtime identifier; the supported value is `standalone` |
| `backendVersion` | Runtime version string |
| `host` | Bound network interface |
| `port` | Resolved port after startup |
| `token` | Shared secret for protected routes, if configured |
| `pid` | Process identifier for the running host |
| `schemaVersion` | Wire schema version |

## CLI control plane

The repo-local CLI is the supported operator surface. It is responsible for
workspace normalization, descriptor lookup, liveness checks, readiness waiting,
and starting a detached daemon when one is missing.

Build it from the repo root:

```bash
./gradlew :analysis-cli:fatJar :analysis-cli:writeWrapperScript
```

The generated wrapper is relocatable. It looks for its fat JAR in either a
sibling `libs/` directory or `../libs/`, so you can copy the wrapper and its
packaged directories together as one shareable unit. When you want CLI-managed
detached daemons, keep the generated `runtime-libs/` directory beside the
wrapper too; the CLI uses that ordered non-ZIP64 classpath for daemon launches.
Set `KAST_APP_JAR` when you need to override fat-JAR autodiscovery explicitly.

Common commands:

```bash
./analysis-cli/build/scripts/analysis-cli --help
./analysis-cli/build/scripts/analysis-cli --version
./analysis-cli/build/scripts/analysis-cli workspace status --workspace-root=/absolute/path/to/workspace
./analysis-cli/build/scripts/analysis-cli workspace ensure --workspace-root=/absolute/path/to/workspace
./analysis-cli/build/scripts/analysis-cli daemon start --workspace-root=/absolute/path/to/workspace
./analysis-cli/build/scripts/analysis-cli daemon stop --workspace-root=/absolute/path/to/workspace
```

The CLI now supports contextual help such as `help workspace` or
`workspace status --help`.

Analysis commands such as `capabilities`, `symbol resolve`, `references`,
`diagnostics`, `rename`, and `edits apply` all run through the same readiness
gate.

Successful operational commands continue to write machine-readable JSON to
stdout. When the workspace already has a daemon, or a command starts or stops
one, Kast also appends a short daemon-status note to stderr so operators can
see which runtime handled the request without breaking JSON consumers.

## Standalone daemon

The standalone runtime is still the analysis engine behind the CLI. It auto-
discovers conventional source roots and Gradle multi-module layouts when you do
not provide overrides.

The daemon defaults are fixed at startup:

- `maxResults = 500`
- `requestTimeoutMillis = 30000`
- `maxConcurrentRequests = 4`

If you need to launch it directly instead of through the CLI, build the runtime
distribution:

```bash
./gradlew :backend-standalone:fatJar :backend-standalone:writeWrapperScript
```

Then run it against a workspace:

```bash
./backend-standalone/build/scripts/backend-standalone \
  --workspace-root=/absolute/path/to/workspace
```

The direct launcher accepts `--key=value` arguments.

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

> **Warning:** If you bind the standalone host to a non-loopback address, you
> must also set a non-empty token. Kast rejects non-local binding without a
> token.

## Operational notes

These constraints affect clients regardless of whether they stay on the CLI or
call HTTP directly.

- `health` and `capabilities` are always available.
- Protected routes require `X-Kast-Token` only when the runtime was started
  with a token.
- `references` and `diagnostics` responses can be truncated at the configured
  `maxResults` limit.
- The `/api/v1/call-hierarchy` route exists, but production hosts do not
  advertise `CALL_HIERARCHY` yet.

## Next steps

Use [Runtime model](choose-a-runtime.md) if you are deciding between the CLI and
direct HTTP. Use [Get started](get-started.md) for the bootstrap flow, and read
[HTTP API](api-reference.md) when you are implementing a client against the
contract.

---
title: Direct CLI usage
description: When and how agents use the pipe-friendly Kast CLI fallback.
icon: lucide/terminal
---

# Direct CLI usage for agents

Most agents should prefer the packaged skill or native `kast_*` tools.
When the host needs a CLI fallback, use `kast agent`: it emits a stable JSON
envelope with `ok`, `method`, `request`, and either `result` or `error`.
`kast agent` auto-ensures the daemon for backend-owned methods. SQLite-backed
`database/metrics` and `symbol/query` are handled by the Rust CLI before daemon
passthrough.

Humans can still manage the daemon lifecycle explicitly with `kast up`,
`kast status`, and `kast stop`.

## Method families

Use flag aliases for shallow requests and `kast agent call <method>` for
structured payloads. Pick the family that matches the information you already
have.

- `raw/*` methods take explicit file paths, offsets, or file lists and
  are documented in the generated [API reference](../reference/api-reference.md)
- `symbol/*` methods are name-based orchestration helpers used by the
  packaged skill and native `kast_*` tools
- `database/*` methods read the local SQLite source index through the
  Rust CLI, such as `database/metrics`

Exact request and response shapes for the full catalog live in the
installed `references/commands.json` file. The OpenAPI reference covers
the raw backend projection.

## When to call the CLI directly

- The agent already has absolute paths or offsets from a previous response
- It's chaining operations in a script or pipeline
- It wants a catalog method the packaged skill does not expose directly
- It needs `--params-file` for a larger structured payload

## `raw/workspace-symbol` as the bridge when there's no offset

No offset? Use `kast agent workspace-symbol` instead of grepping. It's a
semantic declaration search.

=== "Basic search"

    ```console title="Find declarations by name"
    kast agent workspace-symbol --pattern HealthCheckService --max-results 100 --workspace-root "$PWD"
    ```

=== "Regex matching"

    ```console title="Pattern-based matching"
    kast agent workspace-symbol --pattern '.*Service$' --regex --max-results 100 --workspace-root "$PWD"
    ```

```json hl_lines="7-8" title="Response — symbol metadata for each match"
{
  "ok": true,
  "method": "raw/workspace-symbol",
  "result": {
    "symbols": [
      {
        "name": "HealthCheckService",
        "kind": "CLASS",
        "location": {
          "filePath": "/workspace/src/.../HealthCheckService.kt",
          "startOffset": 42, "startLine": 3,
          "preview": "class HealthCheckService"
        }
      }
    ],
    "page": { "truncated": false }
  }
}
```

Feed `location.filePath` and `location.startOffset` from a match
straight into `raw-resolve`, `raw-references`, or `raw-call-hierarchy` — no
intermediate text search.

## Flags, stdin, or params files

Small requests should use flags:

```console title="Resolve a file position"
kast agent raw-resolve --file-path /absolute/path/to/src/main/kotlin/App.kt --offset 42 --workspace-root "$PWD"
```

Pipe a params object when a script already has structured data:

```console title="Resolve by piped params"
printf '%s\n' '{"symbol":"HealthCheckService","kind":"class"}' |
  kast agent call symbol/resolve --workspace-root "$PWD"
```

Complex payloads — especially `raw/apply-edits`, which needs a structured edit
plan — go through `--params-file`:

```console title="Params file for structured payloads"
kast agent call raw/apply-edits --params-file=/path/to/params.json --workspace-root "$PWD"
```

`kast rpc` still exists as the raw transport escape hatch for compatibility and
debugging, but agent-facing scripts should prefer `kast agent`.

## Reading the JSON

Every `kast agent` call returns a single JSON object on stdout. Stderr is
human-readable noise (daemon startup, progress) that the agent can ignore.

Things to check before claiming an answer:

- **`ok`** — false means the operation failed even if transport succeeded
- **`result`** — successful backend payloads are wrapped here
- **`result.searchScope.exhaustive`** on `raw/references` — was the search complete?
- **`result.stats.truncatedNodes`** on `raw/call-hierarchy` — was the tree cut off?
- **`result.page.truncated`** on `raw/workspace-symbol` — were results capped?

## Next steps

- [Talk to your agent](talk-to-your-agent.md) — the skill-driven path
- [Understand symbols](../what-can-kast-do/understand-symbols.md) —
  identity operations in depth
- [API reference](../reference/api-reference.md) — raw backend schemas
  and examples

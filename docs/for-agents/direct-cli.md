---
title: Direct CLI usage
description: When and how agents call `kast rpc` directly instead of through the packaged skill.
icon: lucide/terminal
---

# Direct CLI usage for agents

Most agents should prefer the packaged skill or native `kast_*` tools.
When the host needs a CLI fallback, use `kast rpc`: it forwards a raw
JSON-RPC request and auto-ensures the daemon for backend-owned methods.
SQLite-backed `database/metrics` and `symbol/query` are handled by the
Rust CLI before daemon passthrough.

Humans can still manage the daemon lifecycle explicitly with `kast up`,
`kast status`, and `kast stop`.

## Method families

The CLI accepts the same line-delimited JSON-RPC envelope for every
family. Pick the family that matches the information you already have.

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
- It wants a JSON-RPC method the packaged skill does not expose directly
- It needs `--request-file` for a larger structured payload

## `raw/workspace-symbol` as the bridge when there's no offset

No offset? Use the `raw/workspace-symbol` JSON-RPC method instead of
grepping. It's a semantic declaration search.

=== "Basic search"

    ```console title="Find declarations by name"
    kast rpc '{"jsonrpc":"2.0","method":"raw/workspace-symbol","params":{"pattern":"HealthCheckService","maxResults":100,"regex":false,"includeDeclarationScope":false},"id":1}' --workspace-root="$PWD"
    ```

=== "Regex matching"

    ```console title="Pattern-based matching"
    kast rpc '{"jsonrpc":"2.0","method":"raw/workspace-symbol","params":{"pattern":".*Service$","maxResults":100,"regex":true,"includeDeclarationScope":false},"id":1}' --workspace-root="$PWD"
    ```

```json hl_lines="4-5" title="Response — symbol metadata for each match"
{
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
  },
  "id": 1,
  "jsonrpc": "2.0"
}
```

Feed `location.filePath` and `location.startOffset` from a match
straight into `raw/resolve`, `raw/references`, or `raw/call-hierarchy` — no
intermediate text search.

## Inline JSON or request files

Small requests fit inline as JSON-RPC payloads:

```console title="Inline JSON for ad hoc queries"
kast rpc '{"jsonrpc":"2.0","method":"raw/resolve","params":{"position":{"filePath":"/absolute/path/to/src/main/kotlin/App.kt","offset":42},"includeDeclarationScope":false,"includeDocumentation":false},"id":1}' --workspace-root="$PWD"
```

Complex payloads — especially `raw/apply-edits`, which needs a structured
edit plan — go through `--request-file`:

```console title="Request file for structured payloads"
kast rpc --workspace-root="$PWD" --request-file=/path/to/request.json
```

`request.json` should contain the full JSON-RPC envelope, including
`jsonrpc`, `method`, `params`, and `id`.

## Reading the JSON

Every `kast rpc` call returns a single JSON object on stdout. Stderr is
human-readable noise (daemon startup, progress) that the agent can
ignore.

Things to check before claiming an answer:

- **`result`** — every successful response wraps payload here
- **`searchScope.exhaustive`** on `raw/references` — was the search complete?
- **`stats.truncatedNodes`** on `raw/call-hierarchy` — was the tree cut off?
- **`page.truncated`** on `raw/workspace-symbol` — were results capped?

## Next steps

- [Talk to your agent](talk-to-your-agent.md) — the skill-driven path
- [Understand symbols](../what-can-kast-do/understand-symbols.md) —
  identity operations in depth
- [API reference](../reference/api-reference.md) — raw backend schemas
  and examples

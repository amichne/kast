---
title: Direct CLI usage
description: When and how agents call the Kast CLI directly instead of
  through the packaged skill.
icon: lucide/terminal
---

# Direct CLI usage for agents

Most agents go through the packaged skill — it turns conversational
references into the file path and offset `kast` needs. Sometimes the
agent has the position already, or wants behavior the skill doesn't
expose. Then it calls the CLI directly.

## When to call the CLI directly

- The agent already has a file path and offset from a previous response
- It's chaining operations in a script or pipeline
- It needs non-default traversal bounds on `call-hierarchy`
- It needs `--request-file` for structured payloads like `apply-edits`
- It needs an operation the skill doesn't surface

## `workspace-symbol` as the bridge when there's no offset

No offset? Use `workspace-symbol` instead of grepping. It's a semantic
declaration search.

=== "Basic search"

    ```console title="Find declarations by name"
    kast workspace-symbol \
      --workspace-root=$(pwd) \
      --pattern=HealthCheckService
    ```

=== "Filtered by kind"

    ```console title="Narrow results to classes only"
    kast workspace-symbol \
      --workspace-root=$(pwd) \
      --pattern=HealthCheckService \
      --kind=CLASS
    ```

=== "Regex matching"

    ```console title="Pattern-based matching"
    kast workspace-symbol \
      --workspace-root=$(pwd) \
      --pattern=".*Service$" \
      --regex=true
    ```

```json hl_lines="4-5" title="Response — symbol metadata for each match"
{
  "symbols": [
    {
      "name": "HealthCheckService",
      "kind": "CLASS",
      "filePath": "/workspace/src/.../HealthCheckService.kt",
      "location": {
        "startOffset": 42, "startLine": 3,
        "preview": "class HealthCheckService"
      }
    }
  ],
  "page": { "truncated": false }
}
```

Feed `filePath` and `startOffset` from a match straight into `resolve`,
`references`, or `call-hierarchy` — no intermediate text search.

## Inline flags or request files

Most operations take inline flags:

```console title="Inline flags for ad hoc queries"
kast resolve \
  --workspace-root=$(pwd) \
  --file-path=$(pwd)/src/main/kotlin/App.kt \
  --offset=42
```

Complex payloads — especially `apply-edits`, which needs a structured
edit plan — go through `--request-file`:

```console title="Request file for structured payloads"
kast apply-edits \
  --workspace-root=$(pwd) \
  --request-file=/path/to/edits.json
```

## Reading the JSON

Every command returns a single JSON object on stdout. Stderr is
human-readable noise (daemon startup, progress) that the agent can
ignore.

Things to check before claiming an answer:

- **`result`** — every successful response wraps payload here
- **`searchScope.exhaustive`** on `references` — was the search
  complete?
- **`stats.truncatedNodes`** on `call-hierarchy` — was the tree cut
  off?
- **`page.truncated`** on `workspace-symbol` — were results capped?

## Next steps

- [Talk to your agent](talk-to-your-agent.md) — the skill-driven path
- [Understand symbols](../what-can-kast-do/understand-symbols.md) —
  identity operations in depth
- [API reference](../reference/api-reference.md) — full schemas and
  examples

---
title: Direct CLI usage
description: When and how agents call the Kast CLI directly instead of
  through the packaged skill.
icon: lucide/terminal
---

# Direct CLI usage for agents

Most agents use the packaged Kast skill, which bridges conversational
references into the exact file and offset Kast needs. Sometimes an agent
needs to call the CLI directly — when it already knows the file position,
when it's building structured automation, or when it needs a capability
the skill doesn't expose.

## When to use direct CLI calls

Direct CLI calls make sense when your agent:

- Already has an exact file path and offset from a previous Kast result
- Needs to chain multiple operations in a script or pipeline
- Needs to pass non-default traversal bounds to `call-hierarchy`
- Needs to use `--request-file` for complex payloads like `apply-edits`
- Needs operations the skill doesn't directly surface

## Use workspace-symbol as an alternative bridge

When the agent doesn't have a file offset, `workspace-symbol` provides
a semantic alternative to text search for locating declarations.

=== "Basic search"

    ```console title="Find declarations by name"
    kast workspace-symbol \
      --workspace-root=/absolute/path/to/workspace \
      --pattern=HealthCheckService
    ```

=== "Filtered by kind"

    ```console title="Narrow results to classes only"
    kast workspace-symbol \
      --workspace-root=/absolute/path/to/workspace \
      --pattern=HealthCheckService \
      --kind=CLASS
    ```

=== "Regex matching"

    ```console title="Pattern-based matching"
    kast workspace-symbol \
      --workspace-root=/absolute/path/to/workspace \
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

The agent can then feed the `filePath` and `startOffset` from a match
directly into `resolve`, `references`, or `call-hierarchy` without an
intermediate text search step.

## Choose inline flags or request files

For most operations, the agent can pass parameters as inline flags:

```console title="Inline flags for ad hoc queries"
kast resolve \
  --workspace-root=/absolute/path/to/workspace \
  --file-path=/absolute/path/to/src/main/kotlin/App.kt \
  --offset=42
```

For complex payloads — especially `apply-edits` which requires a
structured edit plan — use `--request-file` to pass a JSON file:

```console title="Request file for structured payloads"
kast apply-edits \
  --workspace-root=/absolute/path/to/workspace \
  --request-file=/path/to/edits.json
```

## Read structured JSON output

Every Kast command returns a single JSON object on stdout. The agent
can parse this directly. Stderr carries human-readable notes (like
daemon startup messages) that the agent can safely ignore.

Key patterns for agents parsing Kast output:

- **Check `result` for the payload.** Every successful response wraps
  the data in a `result` field.
- **Check `searchScope.exhaustive`** on reference results before
  claiming the list is complete.
- **Check `stats.truncatedNodes`** on call hierarchy results before
  claiming the tree is complete.
- **Check `page.truncated`** on workspace-symbol results before
  treating the list as exhaustive.

## Next steps

- [Talk to your agent](talk-to-your-agent.md) — the conversational
  approach through the packaged skill
- [Understand symbols](../what-can-kast-do/understand-symbols.md) —
  all the identity operations in detail
- [API reference](../reference/api-reference.md) — full schemas and
  examples

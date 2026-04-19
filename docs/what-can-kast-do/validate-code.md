---
title: Validate code
icon: lucide/check-circle
description: >-
  Check code correctness from the command line with diagnostics,
  code actions, and completions.
---

# Validate code

These operations help you check code correctness without opening an
IDE. Run diagnostics to surface errors and warnings, request code
actions for suggested fixes, and query completions to discover
available symbols at a position.

## Diagnostics

Diagnostics analyze one or more Kotlin files and return compiler
errors, warnings, and informational messages with precise source
locations. Use them in CI gates, pre-commit hooks, or agent workflows
to catch problems before they reach review.

=== "Single file"

    Pass one file path to check a single source file:

    ```console title="Run diagnostics on one file"
    kast diagnostics \
      --workspace-root=/app \
      --file-paths=/app/src/main/kotlin/com/shop/OrderService.kt
    ```

=== "Multiple files"

    Pass a comma-separated list with `--file-paths` to check
    several files in one call:

    ```console title="Run diagnostics on multiple files"
    kast diagnostics \
      --workspace-root=/app \
      --file-paths=/app/src/main/kotlin/com/shop/OrderService.kt,/app/src/main/kotlin/com/shop/PaymentGateway.kt
    ```

The response contains a `diagnostics` array. Each entry includes the
file, severity, human-readable message, and exact source range:

```json title="Example diagnostics response" hl_lines="4 5 6"
{
  "diagnostics": [
    {
      "filePath": "/app/src/main/kotlin/com/shop/OrderService.kt",
      "severity": "ERROR",
      "message": "Unresolved reference: processOrdr",
      "range": {
        "startLine": 47,
        "startColumn": 5,
        "endLine": 47,
        "endColumn": 17
      }
    }
  ]
}
```

The highlighted fields — `filePath` and `severity` plus `range` —
give you everything you need to locate the problem and decide whether
it blocks a build.

!!! tip "Refresh before diagnosing"

    If you modified files outside the daemon, run
    `kast workspace refresh` first so diagnostics reflect the latest
    disk state.

## Code actions

Code actions return suggested fixes and refactorings available at a
specific file position. Pair them with diagnostics: first find the
error, then ask what kast can do about it.

=== "CLI example"

    ```console title="Request code actions at a position"
    kast code-actions \
      --workspace-root=/app \
      --file=/app/src/main/kotlin/com/shop/OrderService.kt \
      --offset=312
    ```

=== "JSON-RPC request"

    ```json title="code-actions JSON-RPC request"
    {
      "method": "code-actions",
      "params": {
        "position": {
          "filePath": "/app/src/main/kotlin/com/shop/OrderService.kt",
          "offset": 312
        }
      },
      "id": 1,
      "jsonrpc": "2.0"
    }
    ```

A typical response lists each available action with a title and the
edits it would apply:

```json title="Example code-actions response"
{
  "result": {
    "actions": [
      {
        "title": "Change to 'processOrder'",
        "kind": "quickfix"
      }
    ],
    "schemaVersion": 3
  },
  "id": 1,
  "jsonrpc": "2.0"
}
```

If no actions apply at the queried position, `actions` returns an
empty list. Filter to a specific diagnostic with `--diagnostic-code`
when you want only fixes for one error.

## Completions

Completions return the symbols, keywords, and snippets the compiler
suggests at a file position. This is a query-based lookup, not an
interactive editor sync — you send a position and receive a list of
candidates in one shot.

```console title="Query completions at a position"
kast completions \
  --workspace-root=/app \
  --file=/app/src/main/kotlin/com/shop/OrderService.kt \
  --offset=312
```

Use `--max-results` to cap the number of returned items and
`--kind-filter` to restrict candidates to specific symbol kinds.
The response includes an `exhaustive` flag that tells you whether
every candidate was returned or results were truncated.

```json title="Example completions response"
{
  "result": {
    "items": [
      {
        "name": "processOrder",
        "fqName": "com.shop.OrderService.processOrder",
        "kind": "FUNCTION",
        "type": "OrderResult"
      }
    ],
    "exhaustive": true,
    "schemaVersion": 3
  },
  "id": 1,
  "jsonrpc": "2.0"
}
```

## Next steps

- [Manage workspaces](manage-workspaces.md) — control the daemon
  lifecycle and workspace configuration.
- [Troubleshooting](../troubleshooting.md) — solutions for common
  issues when running kast.

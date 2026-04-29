---
title: Validate code
icon: lucide/check-circle
description: >-
  Check code correctness from the command line with diagnostics,
  code actions, and completions.
---

# Validate code

Check correctness without opening an IDE. Diagnostics surface
errors and warnings; code actions tell you what `kast` can fix;
completions show what the compiler thinks belongs at a position.

## Diagnostics

Diagnostics analyze one or more Kotlin files and return compiler
errors, warnings, and infos with exact source locations. Plug them
into CI gates, pre-commit hooks, or agent loops to catch problems
before review.

=== "Single file"

    Pass one file path:

    ```console title="Run diagnostics on one file"
    kast diagnostics \
      --workspace-root=$(pwd) \
      --file-paths=$(pwd)/src/main/kotlin/com/shop/OrderService.kt
    ```

=== "Multiple files"

    Pass a comma-separated list:

    ```console title="Run diagnostics on multiple files"
    kast diagnostics \
      --workspace-root=$(pwd) \
      --file-paths=$(pwd)/src/main/kotlin/com/shop/OrderService.kt,$(pwd)/src/main/kotlin/com/shop/PaymentGateway.kt
    ```

The response is a `diagnostics` array. Each entry carries the file,
severity, message, and exact range:

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

`filePath`, `severity`, and `range` give you everything to locate
the problem and decide whether it blocks the build.

!!! warning "Refresh before diagnosing"

    Diagnostics reflect the daemon's last view of disk. If you (or
    your agent, or `git checkout`) modified files outside the
    daemon's observation window, run `kast workspace refresh`
    first — otherwise you get a stale answer that looks correct.

### Use diagnostics as a CI gate

Diagnostics return structured JSON, so they drop into a CI pipeline
next to your normal Kotlin build. Bring up a daemon, diff for
changed `.kt` files, run diagnostics, fail on errors.

```bash title="Run kast diagnostics in CI"
kast workspace ensure --workspace-root=$(pwd)

kast diagnostics \
  --workspace-root=$(pwd) \
  --file-paths=$(git diff --name-only origin/main -- '*.kt' | sed "s|^|$(pwd)/|" | paste -sd, -) \
  > diagnostics.json

jq -e '[.diagnostics[] | select(.severity == "ERROR")] | length == 0' diagnostics.json
```

The `jq` line exits non-zero when any diagnostic is `ERROR`,
failing the step. Tighten the filter to `WARNING` for a stricter
gate.

## Code actions

Code actions return suggested fixes and refactorings available at a
file position. Pair them with diagnostics: find the error, then ask
what `kast` can do about it.

=== "CLI example"

    ```console title="Request code actions at a position"
    kast code-actions \
      --workspace-root=$(pwd) \
      --file=$(pwd)/src/main/kotlin/com/shop/OrderService.kt \
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

A typical response lists each available action with a title and
the edits it would apply:

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

Empty `actions` means nothing matched at that position. Filter to
a specific diagnostic with `--diagnostic-code` when you only want
fixes for one error.

## Completions

Completions return the symbols, keywords, and snippets the compiler
suggests at a position. One-shot lookup, not an editor sync — send
a position, get back a candidate list.

```console title="Query completions at a position"
kast completions \
  --workspace-root=$(pwd) \
  --file=$(pwd)/src/main/kotlin/com/shop/OrderService.kt \
  --offset=312
```

`--max-results` caps the list; `--kind-filter` narrows to specific
kinds. The response carries an `exhaustive` flag — `true` means you
got every candidate, `false` means results were capped.

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

- [Manage workspaces](manage-workspaces.md) — daemon lifecycle and
  workspace config
- [Troubleshooting](../troubleshooting.md) — fixes for common
  problems

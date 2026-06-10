---
title: Recipes
description: Copy-paste workflows for the things you actually want to do with Kast.
icon: lucide/book-open
---

# Recipes

Capability pages explain *what `kast` can do*. This page answers
the question one step earlier: *what do I run to do the thing I
want?*

Every recipe assumes you've started a backend with
`kast up --workspace-root="$PWD"`. Run that from the
root of your Kotlin project, open the recipe that matches your
task, and copy. Each one ends with a link to the deeper reference
if you want the full story.

## Read operations

??? example "Find all usages of a function"

    Two steps: identify the symbol, then ask who references it. The
    `searchScope.exhaustive` field on the response tells you whether the
    search was complete.

    ```console
    # 1. Resolve the symbol at the cursor (get its compiler identity)
    kast rpc '{"jsonrpc":"2.0","id":1,"method":"raw/resolve","params":{"position":{"filePath":"/absolute/path/to/src/main/kotlin/App.kt","offset":42}}}' \
      --workspace-root="$PWD"

    # 2. Find every reference to that same symbol
    kast rpc '{"jsonrpc":"2.0","id":2,"method":"raw/references","params":{"position":{"filePath":"/absolute/path/to/src/main/kotlin/App.kt","offset":42}}}' \
      --workspace-root="$PWD"
    ```

    Check `searchScope.exhaustive: true` on the response to confirm the
    list is complete. If it's `false`, compare `candidateFileCount` and
    `searchedFileCount` to see what was skipped.
    [Full reference →](what-can-kast-do/trace-usage.md)

??? example "See who calls a function"

    Resolve first, then walk incoming callers up to the depth you care
    about. Every node in the response carries truncation metadata, so you
    know whether the tree is complete or Kast stopped on purpose.

    ```console
    kast rpc '{"jsonrpc":"2.0","id":1,"method":"raw/resolve","params":{"position":{"filePath":"/absolute/path/to/src/main/kotlin/App.kt","offset":42}}}' \
      --workspace-root="$PWD"

    kast rpc '{"jsonrpc":"2.0","id":2,"method":"raw/call-hierarchy","params":{"position":{"filePath":"/absolute/path/to/src/main/kotlin/App.kt","offset":42},"direction":"INCOMING","depth":3}}' \
      --workspace-root="$PWD"
    ```

    Zero callers on something you know is called from outside?
    Probably an entry point — a `main`, a test, a framework
    callback, or a public API used by code outside this
    workspace. `kast` only sees what's inside the session.
    [Full reference →](what-can-kast-do/trace-usage.md#expand-the-call-hierarchy)

??? example "Find all implementations of an interface"

    Same resolve-first pattern. `implementations` returns every
    concrete subtype `kast` can see in the workspace.

    ```console
    kast rpc '{"jsonrpc":"2.0","id":1,"method":"raw/resolve","params":{"position":{"filePath":"/absolute/path/to/src/main/kotlin/Repository.kt","offset":120}}}' \
      --workspace-root="$PWD"

    kast rpc '{"jsonrpc":"2.0","id":2,"method":"raw/implementations","params":{"position":{"filePath":"/absolute/path/to/src/main/kotlin/Repository.kt","offset":120}}}' \
      --workspace-root="$PWD"
    ```
    [Full reference →](what-can-kast-do/understand-symbols.md)

??? example "Find a class by name when you don't have an offset"

    `raw/workspace-symbol` searches by name across the workspace. Use it as
    a bridge into the resolve-first flow when you only know what
    something is called.

    ```console
    kast rpc '{"jsonrpc":"2.0","id":1,"method":"raw/workspace-symbol","params":{"pattern":"OrderService"}}' \
      --workspace-root="$PWD"

    # Then feed the result's filePath + startOffset into resolve
    kast rpc '{"jsonrpc":"2.0","id":2,"method":"raw/resolve","params":{"position":{"filePath":"/absolute/path/from/previous/result.kt","offset":123}}}' \
      --workspace-root="$PWD"
    ```

    Default match is case-insensitive substring. Pass `--regex=true` if
    you need patterns. Always check `page.truncated` before assuming the
    result list is complete.
    [Full reference →](what-can-kast-do/understand-symbols.md)

??? example "Explore a file's structure"

    `raw/file-outline` returns a nested tree of named declarations —
    classes, objects, named functions, named properties. It skips
    lambdas, object literals, and locals inside function bodies.
    Use it as a map, not a complete index of identifiers.

    ```console
    kast rpc '{"jsonrpc":"2.0","id":1,"method":"raw/file-outline","params":{"filePath":"/absolute/path/to/src/main/kotlin/OrderService.kt"}}' \
      --workspace-root="$PWD"
    ```
    [Full reference →](what-can-kast-do/understand-symbols.md)

## Mutations

??? example "Rename a symbol safely"

    Fast path: start with `raw/rename` when you already have the
    file+offset. Do not pre-resolve or enumerate references just to
    plan scope; the compiler-backed rename computes the usage edits.

    Three steps: plan, review, apply. The plan response carries
    SHA-256 hashes of every file `kast` read. If anything changes
    on disk before you apply, the apply step rejects with a clear
    conflict error.

    ```console
    # 1. Plan the rename — nothing touches disk yet
    kast rpc '{"jsonrpc":"2.0","id":1,"method":"raw/rename","params":{"position":{"filePath":"/absolute/path/to/src/main/kotlin/App.kt","offset":42},"newName":"newSymbolName","dryRun":true}}' \
      --workspace-root="$PWD" > plan.json

    # 2. Review the returned `edits` array. When you're satisfied, apply.
    #    Create a raw/apply-edits request from the reviewed plan.
    kast rpc --request-file=apply-edits.json --workspace-root="$PWD"

    # 3. Verify by resolving the new name at the same position
    kast rpc '{"jsonrpc":"2.0","id":3,"method":"raw/resolve","params":{"position":{"filePath":"/absolute/path/to/src/main/kotlin/App.kt","offset":42}}}' \
      --workspace-root="$PWD"
    ```
    [Full reference →](what-can-kast-do/refactor-safely.md)

??? example "Clean up imports"

    Same direct-mutation flow as rename. `raw/optimize-imports`
    owns applicability for the files you name; do not run symbol
    discovery first. It returns the edits `kast` would make, and
    `apply-edits` writes them with conflict detection.

    ```console
    kast rpc '{"jsonrpc":"2.0","id":1,"method":"raw/optimize-imports","params":{"filePaths":["/absolute/path/to/src/main/kotlin/App.kt"]}}' \
      --workspace-root="$PWD" > plan.json

    kast rpc --request-file=apply-edits.json --workspace-root="$PWD"
    ```
    [Full reference →](what-can-kast-do/refactor-safely.md)

## Validation

??? example "Check if a file compiles"

    Run diagnostics on one or more files. The response is a
    structured list of errors and warnings with exact source
    ranges — easy to feed into a CI script or an agent.

    ```console
    kast rpc '{"jsonrpc":"2.0","id":1,"method":"raw/diagnostics","params":{"filePaths":["/absolute/path/to/src/main/kotlin/App.kt"]}}' \
      --workspace-root="$PWD"
    ```

    If you edited the file outside the daemon, run
    `kast rpc '{"jsonrpc":"2.0","id":1,"method":"raw/workspace-refresh","params":{}}' --workspace-root="$PWD"` first so diagnostics
    don't return a stale view.
    [Full reference →](what-can-kast-do/validate-code.md)

## Troubleshooting recipes

If a command returns an error or a result you didn't expect, the
[troubleshooting page](troubleshooting.md) has a section for each common
failure — daemon won't start, references look incomplete, apply-edits
rejected with a conflict, and so on.

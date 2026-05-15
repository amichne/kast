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
`kast workspace ensure --workspace-root=$(pwd)`. Run that from the
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
    kast resolve \
      --workspace-root=$(pwd) \
      --file-path=$(pwd)/src/main/kotlin/App.kt \
      --offset=42

    # 2. Find every reference to that same symbol
    kast references \
      --workspace-root=$(pwd) \
      --file-path=$(pwd)/src/main/kotlin/App.kt \
      --offset=42
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
    kast resolve \
      --workspace-root=$(pwd) \
      --file-path=$(pwd)/src/main/kotlin/App.kt \
      --offset=42

    kast call-hierarchy \
      --workspace-root=$(pwd) \
      --file-path=$(pwd)/src/main/kotlin/App.kt \
      --offset=42 \
      --direction=INCOMING \
      --depth=3
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
    kast resolve \
      --workspace-root=$(pwd) \
      --file-path=$(pwd)/src/main/kotlin/Repository.kt \
      --offset=120

    kast implementations \
      --workspace-root=$(pwd) \
      --file-path=$(pwd)/src/main/kotlin/Repository.kt \
      --offset=120
    ```
    [Full reference →](what-can-kast-do/understand-symbols.md)

??? example "Find a class by name when you don't have an offset"

    `workspace-symbol` searches by name across the workspace. Use it as
    a bridge into the resolve-first flow when you only know what
    something is called.

    ```console
    kast workspace-symbol \
      --workspace-root=$(pwd) \
      --pattern=OrderService

    # Then feed the result's filePath + startOffset into resolve
    kast resolve \
      --workspace-root=$(pwd) \
      --file-path=<filePath from previous result> \
      --offset=<startOffset from previous result>
    ```

    Default match is case-insensitive substring. Pass `--regex=true` if
    you need patterns. Always check `page.truncated` before assuming the
    result list is complete.
    [Full reference →](what-can-kast-do/understand-symbols.md)

??? example "Explore a file's structure"

    `outline` returns a nested tree of named declarations —
    classes, objects, named functions, named properties. It skips
    lambdas, object literals, and locals inside function bodies.
    Use it as a map, not a complete index of identifiers.

    ```console
    kast outline \
      --workspace-root=$(pwd) \
      --file-path=$(pwd)/src/main/kotlin/OrderService.kt
    ```
    [Full reference →](what-can-kast-do/understand-symbols.md)

## Mutations

??? example "Rename a symbol safely"

    Three steps: plan, review, apply. The plan response carries
    SHA-256 hashes of every file `kast` read. If anything changes
    on disk before you apply, the apply step rejects with a clear
    conflict error.

    ```console
    # 1. Plan the rename — nothing touches disk yet
    kast rename \
      --workspace-root=$(pwd) \
      --file-path=$(pwd)/src/main/kotlin/App.kt \
      --offset=42 \
      --new-name=newSymbolName

    # 2. Review the returned `edits` array. When you're satisfied, apply.
    #    Pass the plan back as a request file — kast apply-edits reads
    #    the same JSON shape `rename` returned.
    kast apply-edits --request-file=plan.json

    # 3. Verify by resolving the new name at the same position
    kast resolve \
      --workspace-root=$(pwd) \
      --file-path=$(pwd)/src/main/kotlin/App.kt \
      --offset=42
    ```
    [Full reference →](what-can-kast-do/refactor-safely.md)

??? example "Clean up imports"

    Same plan-then-apply flow as rename. `optimize-imports`
    returns the edits `kast` would make; `apply-edits` writes
    them with conflict detection.

    ```console
    kast optimize-imports \
      --workspace-root=$(pwd) \
      --file-path=$(pwd)/src/main/kotlin/App.kt

    kast apply-edits --request-file=plan.json
    ```
    [Full reference →](what-can-kast-do/refactor-safely.md)

## Validation

??? example "Check if a file compiles"

    Run diagnostics on one or more files. The response is a
    structured list of errors and warnings with exact source
    ranges — easy to feed into a CI script or an agent.

    ```console
    kast diagnostics \
      --workspace-root=$(pwd) \
      --file-paths=$(pwd)/src/main/kotlin/App.kt
    ```

    If you edited the file outside the daemon, run
    `kast workspace refresh --workspace-root=$(pwd)` first so diagnostics
    don't return a stale view.
    [Full reference →](what-can-kast-do/validate-code.md)

## Troubleshooting recipes

If a command returns an error or a result you didn't expect, the
[troubleshooting page](troubleshooting.md) has a section for each common
failure — daemon won't start, references look incomplete, apply-edits
rejected with a conflict, and so on.

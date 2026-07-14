---
title: Inspect Kotlin
description: Understand how agents resolve symbols, diagnostics, and impact before editing.
icon: lucide/search
---

# Inspect Kotlin

Use semantic inspection when a file, name, caller, diagnostic, or impact
question needs compiler-backed evidence. In normal use, the agent runs these
checks for you after Kast is installed and the project is open.

## Discover An Owned Kotlin File

Start with the exact admitted workspace rather than a recursive file or Git
search:

```console
kast agent workspace-files --workspace-root "$PWD"
```

The default compact page contains at most 20 compiler/project-model or Kotlin
source-index candidates. It groups consecutive, globally path-sorted files that
share identical typed evidence; each file path remains explicit at
`files[group].paths[i].filePath` and
`files[group].paths[i].relativePath`. Narrow the inventory before paging.
Filters combine with AND semantics:

```console
kast agent workspace-files \
  --workspace-root "$PWD" \
  --kind source \
  --module 'gradle:included/tools#:app' \
  --source-set integrationTest \
  --package named:com.example.orders \
  --path-prefix src/integrationTest/kotlin \
  --glob '**/*Service.kt'
```

Package and source-set filters match structured proof only. `root` means a
compiler/PSI-proven root package; `named:<fq-name>` means an exact proven
canonical package. A directory name, legacy source-set label, or text parser
guess does not match. Gradle module selectors include the workspace-relative
linked-build root and absolute project path, so root and included builds may
both own `:app` without being conflated.

If `nextPageToken` is present, repeat every result-affecting option and consume
the returned token once:

```console
kast agent workspace-files \
  --workspace-root "$PWD" \
  --kind source \
  --limit 20 \
  --page-token '<nextPageToken>'
```

Do not remove filters to make a continuation fit. A mismatched, malformed,
unknown, or replayed token fails as `INVALID_WORKSPACE_FILES_PAGE_TOKEN`. If
the bound backend, index, filesystem, or requested Git evidence changed, it
fails as `STALE_WORKSPACE_FILES_PAGE`; run a fresh unpaged query explicitly.

Check `cardinality`, `coverage`, and `limitations` before treating absence as
proof. `EXACT` means both candidate inventory and requested filter evidence are
complete. `KNOWN_MINIMUM` contains only proved matches. A stable partial result
may still continue its known matches. For scripts, the Kotlin source index is
not applicable: unrelated `.kt` indexing progress does not reduce a
script-only result, while mixed results keep source and script coverage
separate.

Use `files[group].paths[i].filePath` without translating its path dialect. For
a flat per-file projection, add `--fields path` to the discovery command:

```console
kast agent diagnostics \
  --workspace-root "$PWD" \
  --file-path '<filePath>'
kast agent symbol \
  --workspace-root "$PWD" \
  --query OrderService \
  --file-hint '<filePath>'
```

## Resolve Identity First

Start with exact lookup. A unique declaration resolves; zero or multiple exact
matches return typed outcomes instead of silently choosing a similar name.

```console
kast agent symbol --query OrderService --workspace-root "$PWD"
```

If the name is unknown, opt into fuzzy discovery and inspect its candidates:

```console
kast agent symbol --query order --mode discovery --workspace-root "$PWD"
```

Then rerun exact lookup with the selected simple or fully-qualified identity.
Use `--kind`, `--file-hint`, or `--containing-type` when the target requires a
hard constraint. Request references or callers only from that exact lookup.
This avoids treating every matching string as the same Kotlin symbol.

## Gather The Right Evidence

Different questions need different evidence:

| Question | Evidence |
| --- | --- |
| Which declaration is this? | Symbol identity |
| Where is this used? | References |
| Who calls this? | Caller evidence |
| What files might be affected? | Source-index impact |
| Does the backend see a clean file? | Diagnostics |

## Trace Usage And Impact Without Text Search

Resolve once, preserve the complete declaration anchor, and pass it unchanged
to each relationship command. This sequence uses compiler identity and the
source index only:

```bash
identity_json="$(kast --output json agent symbol \
  --query OrderService \
  --fields identity \
  --workspace-root "$PWD")"

fq_name="$(jq -r '.result.identity.fqName' <<<"$identity_json")"
declaration_file="$(jq -r '.result.identity.declarationFile' <<<"$identity_json")"
declaration_offset="$(jq -r '.result.identity.declarationStartOffset' <<<"$identity_json")"
kind="$(jq -r '.result.identity.kind | ascii_downcase' <<<"$identity_json")"

references_page_one="$(kast --output json agent references \
  --symbol "$fq_name" \
  --declaration-file "$declaration_file" \
  --declaration-start-offset "$declaration_offset" \
  --kind "$kind" \
  --workspace-root "$PWD")"

kast agent callers \
  --symbol "$fq_name" \
  --declaration-file "$declaration_file" \
  --declaration-start-offset "$declaration_offset" \
  --kind "$kind" \
  --workspace-root "$PWD"

reference_page_token="$(jq -r '.result.page.nextPageToken // empty' \
  <<<"$references_page_one")"
if [[ -n "$reference_page_token" ]]; then
  kast agent references \
    --symbol "$fq_name" \
    --declaration-file "$declaration_file" \
    --declaration-start-offset "$declaration_offset" \
    --kind "$kind" \
    --page-token "$reference_page_token" \
    --workspace-root "$PWD"
fi

kast agent impact \
  --symbol "$fq_name" \
  --declaration-file "$declaration_file" \
  --declaration-start-offset "$declaration_offset" \
  --kind "$kind" \
  --depth 3 \
  --workspace-root "$PWD"
```

Reference and compiler-traversal tokens are runtime-owned and single-use.
Impact tokens are stateless SQLite offsets. Every token is bound to the exact
selector and result-affecting options, so changing the subject, depth, or limit
fails before navigation. A function or property may return the typed
`IMPACT_OVERLOAD_GRANULARITY_UNAVAILABLE` limitation when the source index
cannot distinguish same-file overloads.

## Continue To Safe Edits

After the target identity and file state are clear, an agent can plan an edit.
Continue with [plan safe edits](plan-safe-edits.md) for the mutation flow.

??? info "Agent inspection commands"
    These commands are examples for agent authors and support workflows.

    ```console
    kast agent verify --workspace-root "$PWD"
    kast agent workspace-files --kind source --workspace-root "$PWD"
    kast agent symbol --query OrderService --workspace-root "$PWD"
    kast agent symbol --query order --mode discovery --workspace-root "$PWD"
    kast agent references \
      --symbol com.example.OrderService \
      --declaration-file "$PWD/src/main/kotlin/com/example/OrderService.kt" \
      --declaration-start-offset 42 \
      --kind class \
      --workspace-root "$PWD"
    kast agent diagnostics \
      --file-path "$PWD/src/main/kotlin/App.kt" \
      --workspace-root "$PWD"
    kast agent impact \
      --symbol com.example.OrderService \
      --declaration-file "$PWD/src/main/kotlin/com/example/OrderService.kt" \
      --declaration-start-offset 42 \
      --kind class \
      --workspace-root "$PWD" \
      --depth 3
    ```

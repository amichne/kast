---
title: Recipes
description: Copy-paste command sequences for common Kast CLI workflows.
icon: lucide/book-open
---

# Recipes

Recipes combine the command groups into short workflows. They assume Kast is
installed, the command runs inside a Kotlin workspace, and a backend can be
prepared with `kast setup`.

## Find usages of a symbol

Resolve first, then ask for references. Check the envelope and the search
scope before using the result as evidence.

```console title="Resolve and find references"
APP_FILE="$PWD/src/main/kotlin/App.kt"

kast setup --backend=headless --no-open-ide
kast agent call raw/resolve --params "{\"position\":{\"filePath\":\"$APP_FILE\",\"offset\":42}}" --backend=headless
kast agent call raw/references \
  --params "{\"position\":{\"filePath\":\"$APP_FILE\",\"offset\":42},\"includeDeclaration\":true}" \
  --backend=headless
```

Trust the usage list only after `ok` is true and
`result.searchScope.exhaustive` is true.

## Trace callers

Use call hierarchy when you need bounded caller or callee evidence. Increase
depth carefully; wide graphs can truncate by design.

```console title="Incoming callers"
APP_FILE="$PWD/src/main/kotlin/App.kt"

kast agent call raw/call-hierarchy \
  --params "{\"position\":{\"filePath\":\"$APP_FILE\",\"offset\":42},\"direction\":\"INCOMING\",\"depth\":3}"
```

Read `result.stats` to see whether depth, timeout, total node, or per-node
limits affected the tree.

## Find a declaration by name

Use `workspace-symbol` when you know the name but not the file offset. Resolve
the selected location before chaining further commands.

```console title="Search, then resolve the selected result"
kast agent call raw/workspace-symbol --params '{"pattern":"OrderService","maxResults":20}'

kast agent call raw/resolve \
  --params '{"position":{"filePath":"/absolute/path/from/the/result.kt","offset":123}}'
```

Use `--regex` for pattern matching and check `result.page.truncated` before
assuming the candidate list is complete.

## Plan a rename

Plan first and review the edit set before applying anything. A rename plan
contains file hashes so the apply step can reject stale edits.

```console title="Dry-run rename plan"
APP_FILE="$PWD/src/main/kotlin/App.kt"

kast agent call raw/rename \
  --params "{\"position\":{\"filePath\":\"$APP_FILE\",\"offset\":42},\"newName\":\"processOrderSafely\",\"dryRun\":true}" > rename-plan.json
```

When a script needs to apply a reviewed plan, build a `raw/apply-edits` params
file from the returned edits and hashes, then pass it through `agent call`.

```console title="Apply reviewed edits"
kast agent call raw/apply-edits --params-file /tmp/apply-edits.json
```

## Validate changed files

Refresh touched files when they changed outside the backend's observation
window, then run diagnostics for the exact files.

```console title="Refresh and diagnose"
APP_FILE="$PWD/src/main/kotlin/App.kt"

kast agent call raw/workspace-refresh --params "{\"filePaths\":[\"$APP_FILE\"]}"
kast agent call raw/diagnostics --params "{\"filePaths\":[\"$APP_FILE\"]}"
```

Use `kast agent workflow diagnostics` when you want deterministic evidence
files for a CI step or agent handoff.

```console title="Diagnostics workflow evidence"
kast agent workflow diagnostics \
  --file-path "$APP_FILE" \
  --out-dir .kast-workflows/diagnostics
```

## Inspect source-index impact

Use metrics when the question is about indexed relationships rather than live
cursor position.

```console title="Impact and coupling"
kast developer inspect metrics impact io.example.OrderService.process --depth 3
kast developer inspect metrics coupling
kast developer inspect metrics fan-in --limit 20
```

For scripts or agents, use the envelope-shaped metric command.

```console title="Agent metric envelope"
kast agent call database/metrics \
  --params '{"metric":"impact","symbol":"io.example.OrderService.process","depth":3}'
```

## Repair a stale repository package

When Copilot or an LSP host cannot find Kast files, verify the install, then
refresh the managed repository package.

```console title="Repair repository-local files"
kast ready
kast setup --force
kast ready
```

Use [Troubleshooting](troubleshooting.md) when readiness reports a missing binary,
plugin, manifest, or repository resource.

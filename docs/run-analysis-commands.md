---
title: Run analysis commands
description: Use the supported read and mutation commands once a workspace
  runtime is live.
icon: lucide/search
---

Once `kast workspace ensure` has prewarmed a daemon, or the first
runtime-dependent command has auto-started one, you can run the supported
analysis commands through the same CLI. This page focuses on the common tasks
people reach for during normal workspace analysis. If you are starting from a
human description of a class or property instead of a known file position, move
to the [human-first agent guide](use-kast-from-an-llm-agent.md) first.

Note: If you use the packaged `kast` skill or the repo-local launcher resolver,
you can bring local builds or explicit paths into the discovery cascade with
`KAST_CLI_PATH` (point at a specific executable) and `KAST_SOURCE_ROOT` (use
local build outputs or trigger `:kast:writeWrapperScript` when Java 21+ is
available). See the Get started guide for examples.

> **Note:** Analysis and refresh commands can attach while the daemon reports
> `INDEXING`. Early semantic results can still be partial or empty until the
> daemon reaches `READY`.

## Control startup behavior

Use the startup mode that matches the level of control you need before the
first semantic query.

- Run `kast workspace ensure --workspace-root=/absolute/path/to/workspace` when
  you want an explicit prewarm step that waits for `READY`.
- Add `--accept-indexing=true` to `workspace ensure` when a servable
  `INDEXING` daemon is enough for the next step.
- Add `--no-auto-start=true` to any runtime-dependent command when automation
  must fail instead of starting a daemon implicitly.

## Choose inline options or a request file

Most analysis commands support two ways to provide input. Inline flags are
fast for ad hoc work. Request files are better when the payload is richer or
you want to save it for repeatable automation.

| Command | Inline input | Request file input | Notes |
| --- | --- | --- | --- |
| `symbol resolve` | `--file-path` and `--offset` | `--request-file` | Use the inline form for a single lookup |
| `references` | `--file-path`, `--offset`, and optional `--include-declaration=true` | `--request-file` | Keep `--include-declaration` off unless you need the declaration in the result |
| `call hierarchy` | `--file-path`, `--offset`, `--direction`, and optional bound flags | `--request-file` | Use inline input when you want to tune depth or truncation limits directly |
| `diagnostics` | `--file-paths=/absolute/A.kt,/absolute/B.kt` | `--request-file` | Inline input is easiest for a small list of files |
| `rename` | `--file-path`, `--offset`, `--new-name`, and optional `--dry-run=true` | `--request-file` | Rename stays in planning mode unless you change `dryRun` in the query |
| `edits apply` | Not supported | `--request-file` | The request file must include edits plus expected file hashes |

## Check capabilities before you rely on a feature

Capabilities are the contract for what the current runtime is willing to do.
Check them first when you switch workspaces or when automation depends on a
specific operation being available.

```bash
kast \
  capabilities \
  --workspace-root=/absolute/path/to/workspace
```

If a capability is missing, Kast rejects the command instead of pretending the
runtime supports it.

## Resolve a symbol

Use `symbol resolve` when you know a file position and want the symbol details
at that exact offset.

```bash
kast \
  symbol resolve \
  --workspace-root=/absolute/path/to/workspace \
  --file-path=/absolute/path/to/src/main/kotlin/com/example/App.kt \
  --offset=123
```

Use a request file when the calling code already knows how to serialize a
`SymbolQuery` payload.

## Find references

Use `references` when you want usage sites for the symbol at a specific file
position.

```bash
kast \
  references \
  --workspace-root=/absolute/path/to/workspace \
  --file-path=/absolute/path/to/src/main/kotlin/com/example/App.kt \
  --offset=123 \
  --include-declaration=true
```

Keep `--include-declaration=true` for the cases where your consumer wants the
declaration returned alongside the reference list.

The result includes a `searchScope` object. Read `searchScope.exhaustive`
before treating the reference list as complete. Kast searches only the files
visible to the resolved symbol's visibility: private and local symbols produce
a `FILE`-scoped result, internal symbols produce a `MODULE`-scoped result, and
public or protected symbols produce a `DEPENDENT_MODULES`-scoped result using
the identifier index. When `searchScope.exhaustive` is `false`, results may
miss usages outside the searched scope.

## Expand a call hierarchy

Use `call hierarchy` when you want incoming callers or outgoing callees for the
declaration at a specific file position.

```bash
kast \
  call hierarchy \
  --workspace-root=/absolute/path/to/workspace \
  --file-path=/absolute/path/to/src/main/kotlin/com/example/App.kt \
  --offset=123 \
  --direction=incoming \
  --depth=2
```

`--direction` is required. Add `--max-total-calls`,
`--max-children-per-node`, or `--timeout-millis` when you need tighter
control over result shape.

Read the returned `stats` object and any per-node `truncation` metadata when
automation needs to know whether Kast cut the tree short.

## Run diagnostics

Use `diagnostics` when you want current diagnostics for one or more files in
the workspace.

```bash
kast \
  diagnostics \
  --workspace-root=/absolute/path/to/workspace \
  --file-paths=/absolute/path/to/src/main/kotlin/com/example/App.kt
```

Move to `--request-file` when you want the calling side to control the payload
shape directly.

## Plan a rename

Use `rename` when you want an edit plan for a symbol rename. The inline form is
usually enough for ad hoc work.

```bash
kast \
  rename \
  --workspace-root=/absolute/path/to/workspace \
  --file-path=/absolute/path/to/src/main/kotlin/com/example/App.kt \
  --offset=123 \
  --new-name=RenamedSymbol
```

The inline command defaults to `--dry-run=true`, so the result stays in
planning mode unless your request payload says otherwise.

## Apply a prepared edit plan

Use `edits apply` only after you already have a prepared edit plan that
includes the edits and expected file hashes.

```bash
kast \
  edits apply \
  --workspace-root=/absolute/path/to/workspace \
  --request-file=/absolute/path/to/query.json
```

This command does not support inline flags for the payload. It must read the
request from a file.

## Refresh workspace state manually

Kast refreshes `edits apply` results immediately and watches source roots for
most external `.kt` file changes. Use `workspace refresh` when you need the
manual recovery path.

1. Refresh the full workspace:

   ```bash
   kast \
     workspace refresh \
     --workspace-root=/absolute/path/to/workspace
   ```

2. Optional: Refresh only the files you know changed:

   ```bash
   kast \
     workspace refresh \
     --workspace-root=/absolute/path/to/workspace \
     --file-paths=/absolute/path/to/src/main/kotlin/com/example/App.kt,/absolute/path/to/src/main/kotlin/com/example/Use.kt
   ```

3. Inspect `refreshedFiles`, `removedFiles`, and `fullRefresh` in the JSON
   result when your calling code needs to react to the refresh scope.

## Understand bounded results

`call hierarchy` is part of the supported CLI, but it is intentionally bounded.
When you summarize results, report the direction you used and surface
truncation honestly if `stats` or node metadata show that Kast hit a limit.

## Next steps

Keep the reference page nearby when you want a smaller lookup-oriented summary
of the public commands.

- [Command reference](command-reference.md)
- [Use Kast from an LLM agent](use-kast-from-an-llm-agent.md)
- [LLM scaffolding reference](llm-scaffolding-reference.md)
- [Get started](get-started.md)

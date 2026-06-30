---
title: Metrics Commands
description: Query the source-index database for impact, coupling, search, and graph views.
icon: lucide/database
---

# Metrics Commands

Metrics commands read the local `source-index.db` through the Rust CLI. They do
not require a running JVM backend when the database already exists.

## Direct metrics

Use `kast developer inspect metrics ...` for human-readable summaries or `--output json` for
automation. Pass `--database` only when you need to read a specific index file
instead of the current workspace cache.

```console title="Source-index metrics"
kast developer inspect metrics fan-in --limit 20
kast developer inspect metrics fan-out --limit 20
kast developer inspect metrics dead-code --file-glob 'src/main/**/*.kt'
kast developer inspect metrics impact io.example.OrderService.process --depth 3
kast developer inspect metrics coupling
kast developer inspect metrics search OrderService --limit 25
```

These commands are useful after indexing has produced a source-index database.
Run `kast developer runtime up` first when the workspace has not been indexed yet.

## Agent metrics

Use `kast agent call database/metrics` when a script or agent needs the same
source-index answers in the JSON envelope shape.

```console title="Metrics through the agent envelope"
kast agent call database/metrics --params '{"metric":"fanIn","limit":20}'
kast agent call database/metrics --params '{"metric":"impact","symbol":"io.example.OrderService.process","depth":3}'
```

Check `ok` before trusting the payload. A missing database, stale workspace, or
invalid metric argument should fail explicitly.

## Scope filters

Metrics commands can be narrowed by workspace, database, file glob, folder
prefix, depth, and limit depending on the metric. Use filters to keep results
small enough to review.

| Need | Prefer |
|------|--------|
| Search declarations by name | `kast developer inspect metrics search <query>` |
| Rank highly used symbols | `kast developer inspect metrics fan-in --limit <n>` |
| Find files with broad dependencies | `kast developer inspect metrics fan-out --limit <n>` |
| Estimate blast radius | `kast developer inspect metrics impact <fq-name> --depth <n>` |
| Inspect module coupling | `kast developer inspect metrics coupling` |

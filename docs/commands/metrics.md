---
title: Metrics Commands
description: Inspect source-index metrics directly or through typed agent impact.
icon: lucide/bar-chart-3
---

# Metrics Commands

Use developer metrics commands for direct source-index inspection:

```console
kast developer inspect metrics fan-in --workspace-root "$PWD" --limit 20
kast developer inspect metrics fan-out --workspace-root "$PWD" --limit 20
kast developer inspect metrics impact com.example.OrderService --workspace-root "$PWD" --depth 3
```

Use the typed agent impact command when an agent needs the same impact view:

```console
kast agent impact --symbol com.example.OrderService --workspace-root "$PWD" --depth 3
```

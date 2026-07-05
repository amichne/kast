---
title: Troubleshooting
description: Diagnose readiness, backend, and semantic command failures.
icon: lucide/triangle-alert
---

# Troubleshooting

Start with read-only readiness:

```console
kast --output json ready --for agent --workspace-root "$PWD"
kast --output json agent verify --workspace-root "$PWD"
kast --output json status --workspace-root "$PWD"
```

If readiness reports install-state drift, plan repair first:

```console
kast --output json repair --for agent --workspace-root "$PWD"
kast --output json repair --for agent --workspace-root "$PWD" --apply
```

For semantic failures, resolve identity before retrying mutation:

```console
kast --output json agent symbol --query OrderService --workspace-root "$PWD"
kast --output json agent diagnostics --file-path "$PWD/src/main/kotlin/App.kt" --workspace-root "$PWD"
```

For daemon lifecycle issues, use developer runtime commands:

```console
kast developer runtime status --workspace-root "$PWD"
kast developer runtime restart --workspace-root "$PWD"
```

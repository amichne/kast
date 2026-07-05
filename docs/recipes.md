---
title: Recipes
description: Common typed Kast command sequences.
icon: lucide/list-checks
---

# Recipes

## Find References

```console
kast agent symbol --query OrderService --references --workspace-root "$PWD"
```

## Trace Callers

```console
kast agent symbol --query process --callers incoming --workspace-root "$PWD"
```

## Run Diagnostics

```console
kast agent diagnostics --file-path "$PWD/src/main/kotlin/App.kt" --workspace-root "$PWD"
```

## Plan And Apply Rename

```console
kast agent rename --symbol com.example.OrderService --new-name Orders --workspace-root "$PWD"
kast agent rename --symbol com.example.OrderService --new-name Orders --apply --workspace-root "$PWD"
```

## Inspect Impact

```console
kast agent impact --symbol com.example.OrderService --workspace-root "$PWD" --depth 3
```

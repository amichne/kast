---
title: Recipes
description: Common typed Kast command sequences.
icon: lucide/list-checks
---

# Recipes

These recipes are copy-paste starting points for common compiler-backed Kotlin
workflows. Keep them plan-first: inspect, review, then add `--apply` only when
the target and content are correct.

## Find References

Use this when a text search would include unrelated declarations with the same
spelling.

```console
kast agent symbol --query OrderService --references --workspace-root "$PWD"
```

## Trace Callers

Use caller tracing when the question is about execution relationships rather
than every textual mention.

```console
kast agent symbol --query process --callers incoming --workspace-root "$PWD"
```

## Run Diagnostics

Diagnostics refresh the touched file first unless you opt out with
`--skip-refresh`.

```console
kast agent diagnostics --file-path "$PWD/src/main/kotlin/App.kt" --workspace-root "$PWD"
```

## Plan And Apply Rename

Run the plan first. Apply only after the reported symbol identity and write set
match the intended change.

```console
kast agent rename --symbol com.example.OrderService --new-name Orders --workspace-root "$PWD"
kast agent rename --symbol com.example.OrderService --new-name Orders --apply --workspace-root "$PWD"
```

## Inspect Impact

Impact uses the source index, so run `kast agent verify` first when a workspace
was just opened or refreshed.

```console
kast agent impact --symbol com.example.OrderService --workspace-root "$PWD" --depth 3
```

## Add A Kotlin File

File creation reads complete content from a file and plans the write before it
touches the repository.

```console
cat >/tmp/NewType.kt <<'KOTLIN'
package com.example

class NewType
KOTLIN

kast agent add-file \
  --file-path "$PWD/src/main/kotlin/NewType.kt" \
  --content-file /tmp/NewType.kt \
  --workspace-root "$PWD"
```

## Insert Into A Scope

Use scope insertion when the target is a class, object, function, or other named
declaration that the Kotlin engine can resolve.

```console
cat >/tmp/member.kt <<'KOTLIN'
fun newBehavior(): String = "ready"
KOTLIN

kast agent add-implementation \
  --inside-scope com.example.OrderService \
  --at body-end \
  --content-file /tmp/member.kt \
  --workspace-root "$PWD"
```

## Replace A Declaration

Replacement resolves the named declaration and replaces that declaration scope,
not every occurrence of the text.

```console
cat >/tmp/replacement.kt <<'KOTLIN'
fun process(): String = "ready"
KOTLIN

kast agent replace-declaration \
  --symbol com.example.OrderService.process \
  --kind function \
  --content-file /tmp/replacement.kt \
  --workspace-root "$PWD"
```

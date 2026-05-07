# Quickstart

This quickstart gets you from a Kotlin module to a working typed namespace
with deterministic evaluation, stable ramp-up, and safe JSON loading. It stays
close to the public API surface that exists in the repo today.

## Prerequisites

You need a Kotlin/JVM project where you can add Gradle dependencies and write
one small namespace. You do not need a server, a control plane, or a JSON
pipeline to begin.

## Path through the quickstart

The pages are designed to be read in order.

1. [Install](install.md)
2. [Define your first namespace](define-first-namespace.md)
3. [Evaluate a typed flag](evaluate-a-typed-flag.md)
4. [Add ramp-up](add-ramp-up.md)
5. [Load JSON safely](load-json-safely.md)
6. [Verify behavior](verify-behavior.md)

## What you will end with

By the end of the quickstart, you will have a team-owned namespace, a typed
evaluation call site, one deterministic rollout example, and one strict JSON
load path that returns `ParseResult`.

## Next steps

Start with the dependency surface and keep the first module as small as
possible.

- [Install Konditional](install.md)


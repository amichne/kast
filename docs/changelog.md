---
title: Changelog
description: Release history and migration notes.
icon: lucide/file-text
---

# Changelog

## v1.0.0

Initial public release.

### Highlights

- **Two backends, one protocol** — standalone CLI daemon and IntelliJ
  IDEA plugin, both speaking JSON-RPC over Unix domain sockets.
- **Full Kotlin semantic analysis** — resolve, references, call
  hierarchy, type hierarchy, outline, workspace symbol, diagnostics,
  rename, apply-edits, optimize imports.
- **Completeness metadata** — `searchScope.exhaustive` on every
  reference result, `stats` and `truncation` on every call hierarchy
  node.
- **Plan-and-apply mutations** — SHA-256 hash-based conflict detection
  on rename and apply-edits.
- **Packaged skill** — `kast install skill` bundles agent-facing
  instructions, OpenAPI spec, and resolver script into a
  repository-local directory.
- **Native launcher** — GraalVM native-image entrypoint for fast
  startup with JVM daemon fallback.
- **Portable distribution** — `./build.sh cli-jvm` produces a
  self-contained bundle with launcher and runtime libs.

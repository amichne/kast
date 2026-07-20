---
name: kast
description: Use Kast for compiler-backed Kotlin and Gradle discovery, mutations, diagnostics, and validation.
metadata:
  kast-cli-dialect-revision: "3"
---

# Kast

Run `kast agent --help` and scoped `--help` to discover compiler-backed Kotlin and Gradle operations.

Mutations plan, apply, and validate diagnostics synchronously. Exit code 0 means the operation reached a green terminal result. On failure, preserve the structured error code and report only actionable details returned by Kast.

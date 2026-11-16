---
type: Engineering Report
title: "Linux MCP library classification"
description: "Records the pinned CFFI payload and the native inventory admission rule."
resource: "repo://docs/internal/reviews/native-library-classification"
tags: [kast, distribution, native]
timestamp: "2026-11-16T02:15:34Z"
source_revision: 4be6d202b911c7bd1553eada7a4714e848436707
code_sources:
  - path: packaging/mcp-requirements.txt
  - path: packaging/internal/mcp/mcp_dependency_artifact.py
  - path: build-logic/src/main/kotlin/support/tasks/verification/NativeElfLibrary.kt
  - path: build-logic/src/main/kotlin/support/tasks/verification/VerifyDistributionTasks.kt
  - path: build-logic/src/test/kotlin/VerifyControlDistributionTaskTest.kt
---

# Linux MCP library classification

The pinned CFFI library has a nonzero ELF entry address.
The previous classifier treated that address as proof of an executable.
The production classifier now examines ELF program headers and dynamic flags.
The distribution must still contain exactly one native executable.

## Evidence

[CI job 133007844821](https://github.com/amichne/kast/actions/runs/43777698249/job/133007844821) reported two native executables.
Its retained reports did not include the staged payload.
This investigation materialized the locked Linux x86-64 dependency payload with the existing producer and uv 0.12.9.
It did not compile or run a native image.

The producer selected `cffi-2.1.1-cp312-cp312-manylinux2014_x86_64.manylinux_2_17_x86_64.whl`.
Its SHA-256 matches the lock:

```text
c1453022f490d2459a11819d83ad1d586e9ff65a12ac3e705ffebd46d3685dcf
```

The wheel member and materialized `_cffi_backend.cpython-312-x86_64-linux-gnu.so` contain identical bytes.
The member has 348,856 bytes and this SHA-256:

```text
9bc1d08072255a5ec2db10fc75df1dd9e73097a75cd9150cfe875ebd6712f17c
```

Its ELF type is `ET_DYN`, its machine is x86-64, and its entry address is `0x9000`.
It has `PT_LOAD` and `PT_DYNAMIC` segments, no `PT_INTERP` segment, and no `DF_1_PIE` flag.
The other three native dependencies have a zero entry address.
The compiled production classifier admits all four materialized libraries.

## Admission and diagnostics

The [ELF header contract](https://gabi.xinuos.com/elf/02-eheader.html) defines `ET_DYN` as a shared object.
A nonzero entry address alone does not classify that object as PIE.
The classifier admits target-compatible ELF64 libraries only inside the MCP dependency directory.
It rejects `PT_INTERP`, `DF_1_PIE`, truncated tables, invalid ranges, duplicate flag entries, and incomplete dynamic metadata.
The dynamic table must match exactly one containing load mapping.

The task writes `build/reports/distribution/control-native-inventory.json` before the executable-count decision.
The report records relative paths, sizes, digests, header bytes, and classifications for at most 64 candidates.
Counts include every candidate, and the report states the omitted count.
The report does not contain file payloads or credentials.

## Proof and limits

The focused regression failed for the valid nonzero-entry library before the change and passed after it.
The complete `VerifyControlDistributionTaskTest` suite passed.
Its negative cases cover PIE, a zero-entry PIE, an interpreter segment, malformed offsets, wrong CPU, truncated metadata, and contradictory load mappings.

The pinned payload explains the old classification without proof that it matches the failed job's missing staged bytes.
A new complete Linux assembly remains necessary for end-to-end distribution evidence.
Release publication has a separate identity contract and separate tests.

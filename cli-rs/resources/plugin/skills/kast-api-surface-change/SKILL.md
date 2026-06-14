---
name: kast-api-surface-change
description: Use when a Kast task changes public API, JSON-RPC contracts, OpenAPI docs, command catalogs, or packaged agent surfaces.
---

# Kast API Surface Change

1. Confirm explicit user intent for the public API change.
2. Enumerate consumers: `docs/openapi.yaml`, command catalogs, packaged skills,
   extension resources, hooks, agents, and tests.
3. Update the narrowest contract owner first.
4. Run contract/docs validation plus focused Kotlin or Rust tests.
5. Report changed surfaces and any compatibility risk.

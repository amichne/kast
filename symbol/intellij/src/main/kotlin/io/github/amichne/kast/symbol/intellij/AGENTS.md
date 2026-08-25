# `symbol/intellij` production package guide

This directory owns request-local IntelliJ symbol adapters. Follow
[the module guide](../../../../../../../../../AGENTS.md) for scope compilation, K2 authority,
detachment, and verification.

## Local scope

- Keep Project, PSI, VFS, search-scope, and K2 values inside the request that created them.
- Issue exact selectors only after compiler-grounded identity and scope revalidation succeed.
- Return closed rejections for unsupported, ambiguous, stale, or incomplete native evidence.

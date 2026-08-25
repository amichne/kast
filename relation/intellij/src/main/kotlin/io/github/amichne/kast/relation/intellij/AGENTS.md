# `relation/intellij` production package guide

This directory owns the request-local IntelliJ relation adapter implementation. Follow
[the module guide](../../../../../../../../../AGENTS.md) for K2 authority, bounded enumeration,
detachment, and verification.

## Local scope

- Keep IntelliJ, PSI, K2, VFS, and search-scope values inside one request-local read.
- Derive detached endpoint identities from canonical compiler signatures before leaving K2.
- Derive a typed request-local confirmation plan after exact subject revalidation. Classlike
  callers use constructor-ownership confirmation; other references retain exact identity.
- Preserve qualified completion whenever native enumeration cannot prove an exact terminal result.

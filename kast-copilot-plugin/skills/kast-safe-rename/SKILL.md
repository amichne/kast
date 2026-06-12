---
name: kast-safe-rename
description: Use for Kotlin symbol rename work that must be backed by Kast LSP or raw/rename plans, reference enumeration, and diagnostics.
---

# Kast Safe Rename

1. Resolve the symbol and enumerate references.
2. Run `textDocument/prepareRename`; stop on ambiguity, generated code, stale
   index, unsupported kind, or invalid name.
3. Run `textDocument/rename` and review the returned `WorkspaceEdit`.
4. Apply only the returned edit set.
5. Run Kast diagnostics for changed Kotlin files before completion.

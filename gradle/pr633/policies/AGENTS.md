# PR 633 path policies

These policies admit only the cleanup and PR 633 delivery surfaces. Keep cleanup exclusions and
the PR diff policy synchronized with the corresponding typed task scopes.

The PR policy admits an `AGENTS.md` only when the guide is under an allowed guide prefix and is an
ancestor of another changed non-guide path. A guide alone never expands the product-write scope.

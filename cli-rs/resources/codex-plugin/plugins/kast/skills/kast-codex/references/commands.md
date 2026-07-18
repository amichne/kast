# Kast Codex command reference

Generated from the exhaustive Rust exposure contract. Do not edit.

| Command | Mode | Plan/apply | Evidence |
| --- | --- | --- | --- |
| `kast agent lease acquire` | `Lifecycle` | no | READY exact-root runtime and install-generation lease |
| `kast agent lease status` | `Lifecycle` | no | authenticated lease lifecycle and exact runtime identity |
| `kast agent lease release` | `Lifecycle` | no | idempotent release receipt and exact ownership cleanup |
| `kast agent workspace-files` | `Read` | no | typed workspace paths and coverage |
| `kast agent symbol` | `Read` | no | compiler-resolved symbol identity |
| `kast agent references` | `Read` | no | bounded reference identities |
| `kast agent callers` | `Read` | no | bounded incoming caller identities |
| `kast agent callees` | `Read` | no | bounded outgoing callee identities |
| `kast agent implementations` | `Read` | no | bounded implementation identities |
| `kast agent hierarchy` | `Read` | no | bounded hierarchy identities |
| `kast agent impact` | `Read` | no | source-index impact evidence |
| `kast agent diagnostics` | `Read` | no | diagnostics bound to current file contents |
| `kast agent rename` | `PlanFirstMutation` | yes | typed plan or applied operation with idempotency evidence |
| `kast agent add-file` | `PlanFirstMutation` | yes | typed plan or applied operation with idempotency evidence |
| `kast agent add-declaration` | `PlanFirstMutation` | yes | typed plan or applied operation with idempotency evidence |
| `kast agent add-implementation` | `PlanFirstMutation` | yes | typed plan or applied operation with idempotency evidence |
| `kast agent add-statement` | `PlanFirstMutation` | yes | typed plan or applied operation with idempotency evidence |
| `kast agent replace-declaration` | `PlanFirstMutation` | yes | typed plan or applied operation with idempotency evidence |
| `kast agent operation status` | `OperationControl` | no | latest retained operation state |
| `kast agent operation cancel` | `OperationControl` | no | cooperative cancellation outcome |

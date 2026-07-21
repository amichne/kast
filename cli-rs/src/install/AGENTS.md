# Setup transaction boundary

This directory owns the single `kast setup` transaction.

- `bundle_validation.rs` parses the untrusted bundle and produces `ValidatedBundle` only after every required artifact and digest is proven.
- `bundle_install.rs` writes a complete release in `KAST_HOME/staging`, renames it into `releases/<digest>`, atomically switches `current`, and verifies the active CLI.
- `bundle_entrypoint.rs` owns locking, legacy Kast backup, idempotence, rollback, and the structured result.

No other command may persist installation or configuration state.

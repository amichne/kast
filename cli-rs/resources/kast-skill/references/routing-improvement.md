# Kast routing improvement

`evals/catalog.json` is the canonical routing and behavior suite for the Kast
skill. Keep routing cases centered on the Rust CLI: `expected_route` should be
`rust-kast-cli`, `allowed_ops` should name `kast` subcommands or `kast rpc`
methods, and `forbidden_ops` should cover generic Kotlin tools such as `grep`,
`rg`, and `view`.

Use `scripts/build-routing-corpus.py` to regenerate or extend routing cases from
maintenance inputs. The historical maintenance assets remain under
`fixtures/maintenance/` for reference, but the durable CI-scored corpus lives in
`evals/` and `history/`.

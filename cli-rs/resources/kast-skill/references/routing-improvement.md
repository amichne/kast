# Kast routing improvement

The packaged skill, `references/commands.json`, and
`references/requests/` are the canonical shipped routing surface for the Kast
skill. Keep maintenance routing cases centered on the Rust CLI:
`expected_route` should be `rust-kast-cli`, `allowed_ops` should name `kast`
subcommands or `kast rpc` methods, and `forbidden_ops` should cover generic
Kotlin tools such as `grep`, `rg`, and `view`.

Use `scripts/build-routing-corpus.py` to regenerate or extend routing
candidates from maintenance inputs when evaluating the skill locally. Keep
session exports, benchmark runs, and generated candidate corpora outside the
installed skill tree unless a release intentionally promotes them to a shipped
reference.

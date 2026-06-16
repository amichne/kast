# Kast routing improvement

The packaged skill, `references/commands.json`, and
`references/requests/` are the canonical shipped routing surface for the Kast
skill. Keep maintenance routing cases centered on the Rust CLI:
`expected_route` should be `rust-kast-cli`, `allowed_ops` should name `kast`
subcommands or `kast rpc` methods, and `forbidden_ops` should cover generic
Kotlin tools such as `grep`, `rg`, and `view`.

Keep session exports, benchmark runs, generated candidate corpora, and any
local routing-analysis tools outside the installed skill tree unless a release
intentionally promotes sanitized outputs to a shipped reference.

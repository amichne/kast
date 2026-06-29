# Kast routing improvement

The packaged skill, `references/commands.json`, `references/requests/`, and
`fixtures/maintenance/evals/routing.json` are the canonical shipped routing
surface for the Kast skill. Validate routing cases against
`fixtures/maintenance/evals/routing.schema.json`. Keep cases centered on the
public agent surface: `expectedPrimitive.name` should be `kast`,
`allowedActions` should name catalog methods, named tools, or `kast agent` /
`kast inspect metrics` commands, and `forbiddenActions` should cover generic
Kotlin tools such as `grep`, `rg`, and generic file reads.

Keep session exports, benchmark runs, generated candidate corpora, and any
local routing-analysis tools outside the installed skill tree unless a release
intentionally promotes sanitized outputs to a shipped reference.

The package smoke tests validate the checked corpus against the catalog and
agent tool metadata so promoted cases cannot drift away from the public
navigation surface.

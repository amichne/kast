# Kast routing improvement

The installed skill entrypoint, source catalog, and
`fixtures/maintenance/evals/routing.json` are the canonical routing surface for
the Kast skill. Validate routing cases against
`fixtures/maintenance/evals/routing.schema.json`. Keep positive cases centered
on the public agent surface: `expectedPrimitive.name` should be `kast`,
`allowedActions` should name typed `kast ...` commands, and
`forbiddenActions` should cover generic Kotlin tools such as `grep`, `rg`, and
generic file reads plus removed helper commands such as `kast agent tools`,
`kast agent call`, and `kast agent workflow`.
Negative over-trigger cases should set `expectedPrimitive.name` to `none`, use
only generic allowed actions, and forbid Kast semantic actions that should not
run for unrelated work.

Keep session exports, benchmark runs, generated candidate corpora, source-only
catalog fixtures, and any local routing-analysis tools outside the installed
skill tree unless a release intentionally promotes sanitized output into
`SKILL.md`.

The package smoke tests validate the checked corpus against the typed command
surface so promoted cases cannot drift away from the public navigation surface.
The repository routing gate also captures the removed `kast agent tools`
envelope and verifies it points agents back to the typed v1 dialect.

Run `.github/scripts/test-kast-routing-evals.sh` after routing changes. When
`plugin-eval` is available, run
`plugin-eval analyze cli-rs/resources/kast-skill --metric-pack .github/plugin-eval/kast-routing/manifest.json`
to include the Kast routing metric pack in the skill report.

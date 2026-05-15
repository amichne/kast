# Log

This log is append-only. Each entry records what the compiled layer changed and
which pages now hold the durable synthesis.

## [2026-04-14] skill uplift | kast skill refinement from wiki

- `kast-rename.sh` rewritten: now accepts `--symbol` (symbol mode) in addition
  to `--file-path`+`--offset` (offset mode); emits `ok`-keyed wrapper JSON
  consistent with all other wrappers — `edit_count`, `affected_files`,
  `apply_result`, `diagnostics`, `log_file`.
- `kast-callers.sh` updated: added `--max-total-calls`, `--max-children-per-node`,
  `--timeout-millis` tuning flags (pass-through to CLI); query block in output
  now includes those fields.
- `kast-impact.sh` updated: added `--caller-depth` flag (default 2) so the
  embedded call-hierarchy depth is configurable; `call_hierarchy` output block
  now includes `depth`.
- `SKILL.md` rewritten: bootstrap section no longer references the non-existent
  `.agents/hooks.json`; added workspace lifecycle section (status, stop, ensure
  options, daemon states table); added smoke/capabilities commands; added
  `page.truncated` rule; completed error table; updated integration table.
- `agents/openai.yaml` fixed: `kast-rename` tool parameters now match the
  actual script interface; `kind`, `include-declaration`, `include-callers`,
  `caller-depth`, `max-total-calls`, `max-children-per-node`, `timeout-millis`
  added to the relevant tools; `direction` made optional with default.
- `references/command-reference.md` updated: `kast-rename.sh`, `kast-callers.sh`,
  and `kast-impact.sh` sections reflect the new interfaces.
- Pages updated from this session: [[concepts/llm-agent-workflows]],
  [[analyses/operator-journeys]], [[entities/kast-cli]].

## [2026-04-14] ingest | Kast source corpus

- Added [[index]] and [[overview]] as the main entry points for the Kast wiki.
- Added source summaries under [[sources/kast-overview]] through
  [[sources/glossary]] for the current `Kast/*.md` corpus.
- Added entity pages for [[entities/kast-cli]], [[entities/analysis-api]],
  [[entities/analysis-server]], and [[entities/backend-standalone]].
- Added concept pages spanning architecture, operations, discovery, caching,
  traversal, installation, telemetry, testing, and agent workflows.
- Added cross-source syntheses in [[analyses/end-to-end-request-lifecycle]],
  [[analyses/operator-journeys]], and
  [[analyses/safety-and-correctness-story]].
- Open question: ingest upstream repo docs or release notes if you want this
  wiki to capture version-by-version changes rather than the current structural
  picture.

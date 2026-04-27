# Kast Skill — Phoenix Evaluations

Runnable Phoenix eval suite for the kast skill, built from observed conversation
history failures.

## What it evaluates

| Evaluator | Type | Failure mode it catches |
|-----------|------|------------------------|
| `uses_kast_commands` | code | trigger_miss, routing_bypass |
| `no_grep_for_kotlin_identity` | code | routing_bypass, schema_response |
| `no_sed_for_kotlin_edit` | code | mutation_abandonment |
| `no_maintenance_reads` | code | maintenance_thrash |
| `acknowledges_ok_false` | code | failure_response_ignored, mutation_abandonment |
| `routing_correct` | LLM | trigger_miss, routing_bypass |
| `recovery_quality` | LLM | initialization_friction |
| `schema_correct` | LLM | schema_request (filePaths→targetFile, workspaceRoot) |
| `failure_handling_correct` | LLM | failure_response_ignored, mutation_abandonment |

## Failure taxonomy (from session history)

Derived from sessions `803057da`, `dbda65ca`, `fe3aa9ad`, `431e7b1e`:

- **trigger_miss** — generic Kotlin prompts don't route to kast
- **routing_bypass** — skill loaded but agent uses grep/rg/cat for Kotlin identity
- **initialization_friction** — `KAST_CLI_PATH` empty; agent searches filesystem instead of running bootstrap
- **maintenance_thrash** — agent reads `.kast-version` / `fixtures/maintenance/` / `wrapper-openapi.yaml` before any useful work
- **schema_request** — wrong request fields: `filePaths` instead of `targetFile`, missing `workspaceRoot`, probes `{}`
- **schema_response** — abandons kast after jq projection fails (snake_case wrapper vs camelCase nested model)
- **mutation_abandonment** — falls back to `sed`/manual edit after `write-and-validate` returns `ok=false`
- **failure_response_ignored** — treats `ok=false` as success or abandons kast entirely

## Dataset

30 examples total:
- 13 from `evals/evals.json` (behavior evals)
- 17 from `evals/routing.json` (routing evals)

The checked-in native corpora are now the source of truth for both `kast eval skill`
and the Phoenix experiment. The Phoenix script no longer carries a separate
`_SESSION_CASES` shadow dataset.

## Quickstart

```bash
# 1. Install dependencies
pip install arize-phoenix openai

# 2. Start Phoenix (or point at cloud)
python -m phoenix.server.main &         # local, or:
export PHOENIX_HOST=https://app.phoenix.arize.com
export PHOENIX_API_KEY=your-key

# 3. Set your LLM key
export OPENAI_API_KEY=sk-...

# 4. Dry-run (3 examples)
cd .agents/skills/kast/fixtures/maintenance/phoenix
python kast_evals.py

# 5. Full run
DRY_RUN=0 python kast_evals.py
```

## Interpreting results

- **`uses_kast_commands` < 0.7** → kast skill description needs stronger trigger wording
- **`no_grep_for_kotlin_identity` < 0.8** → "never use grep" rule not prominent enough
- **`recovery_quality` < 0.8** → bootstrap recovery instructions need clarification
- **`schema_correct` < 0.8** → quickstart.md request shapes need reinforcing
- **`failure_handling_correct` < 0.8** → ok=false handling rules need reinforcing

## Anti-patterns to avoid

Per `fundamentals-anti-patterns.md`:
- **Do not automate rare issues** — only categories with ≥3 observed instances are covered
- **Calibrate judges** — run with human labels on 20 samples; target >80% TPR/TNR
- **Keep capability evals at 50–80%** — 100% pass means the dataset has no signal

## Adding new cases

Add durable prompts to the checked-in corpora instead of editing the Phoenix
script directly:

- `fixtures/maintenance/evals/evals.json` for behavior-oriented cases
- `fixtures/maintenance/evals/routing.json` for routing/trigger cases

Each promoted case should include a `failure_mode` field so the native
`kast eval skill` corpus checks and the Phoenix breakdown use the same
taxonomy.

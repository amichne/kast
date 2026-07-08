# Routing improvement workflow

Use this playbook when the Kast skill is loading too rarely, when generic
Kotlin requests fall back to raw grep-style exploration, or when enterprise
teams need a repeatable process for tightening skill routing over time.

## Principles

Keep raw session exports and Copilot process logs immutable. Treat them as
evidence, not as a place to edit or normalize history.

Promote only sanitized, durable prompts into checked-in routing evals. Do
not commit sensitive code snippets, full command output, or local absolute
paths from team transcripts.

Judge routing with the most concrete signal available:

1. The user prompt from the shared session export.
2. The tool and skill sequence in the export.
3. The agent and hook events from `process-*.log`.

## Source material

The routing workflow works best with these inputs:

- Markdown exports created with `/share`
- Optional HTML exports when Markdown is missing
- Copilot process logs such as `process-*.log`

Prefer Markdown exports whenever possible. They contain stable headings for
user prompts, loaded skills, and tool usage.

## Build a routing corpus

Build routing corpora outside the installed skill tree and commit only
sanitized, durable outputs. The installed skill intentionally ships no corpus
builder or runtime scripts; local analysis tools should write to ignored
`build/skill-routing/` paths or another scratch directory.

Useful derived artifacts are:

- `routing-cases.jsonl` with one sanitized case per prompt or agent turn
- `routing-summary.md` with high-level counts and systemic misses
- `promotion-candidates.json` shaped like the checked-in eval corpus

Classifications should remain stable and concrete, such as `trigger-miss`,
`loaded-but-bypassed`, `semantic-abandonment`, `schema-friction`,
`mutation-validation-friction`, `initialization-friction`,
`maintenance-thrash`, `route-via-subagent`, and `config-drift`.

## Review the output

Start with `routing-summary.md`. It shows the high-level counts, the most
common classifications, and the systemic issues that need attention before
prompt tuning.

Then inspect `routing-cases.jsonl` to see the sanitized evidence for each
case. This is where you decide whether a miss is durable enough to become a
checked-in eval.

Finally review `promotion-candidates.json`. These are suggested additions to
`fixtures/maintenance/evals/routing.json`, not auto-approved changes.

## Promote durable misses

When a prompt pattern recurs, add a sanitized entry to
`fixtures/maintenance/evals/routing.json`.
Validate it against `fixtures/maintenance/evals/routing.schema.json` and use
the existing examples in that file as the canonical case shape.
The packaged content smoke test validates that this corpus exists, routes every
case to the `kast` skill, forbids text-search fallbacks for Kotlin semantics,
and references catalog methods that are present in the public agent surface.

Good routing evals:

- keep the prompt phrasing realistic
- state the expected skill and route
- encode recovery expectations when the first Kast attempt hits setup friction
  or a noisy JSON result
- distinguish top-level wrapper response fields from nested API model fields
  when the observed failure was schema/projection friction
- preserve failed mutation responses such as validation/hash errors as failed
  edits instead of turning them into success-shaped manual edits
- forbid raw `grep` / `rg` for semantic Kotlin work
- stay generic enough to survive codebase churn

## Improve the skill after promotion

Once the eval corpus captures the recurring miss, update the narrowest
surface that explains the behavior:

1. `SKILL.md` for portable, standards-based skill behavior
2. `.github/instructions/*.md` for repository-local Copilot routing hints
3. `.github/extensions/kast/*` for package tool behavior
4. optional vendor-specific metadata only when a host actually requires it

Do not change several of these at once unless the evidence says they all
need to move together.

## Re-measure

After any routing change:

1. Re-run `.github/scripts/test-kast-routing-evals.sh`, which validates the
   static routing corpus and public typed-command boundaries.
2. When `plugin-eval` is available, run
   `plugin-eval analyze cli-rs/resources/kast-skill --metric-pack .github/plugin-eval/kast-routing/manifest.json`.
3. Compare against the previous baseline.
4. Re-run the corpus builder on fresh sessions.

The goal is not only a better static score. The goal is fewer fresh cases in
`trigger-miss` and `loaded-but-bypassed`.

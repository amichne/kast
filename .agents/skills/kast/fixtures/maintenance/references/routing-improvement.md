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

Run the corpus builder on one or more directories of exports and logs:

```console title="Build routing cases"
python3 .agents/skills/kast/fixtures/maintenance/scripts/build-routing-corpus.py \
  --session-dir=/absolute/path/to/session-exports \
  --logs-dir=/absolute/path/to/copilot/logs \
  --output-jsonl=build/skill-routing/routing-cases.jsonl \
  --output-markdown=build/skill-routing/routing-summary.md \
  --output-promotions=build/skill-routing/promotion-candidates.json
```

The script:

- prefers Markdown exports over sibling HTML files
- extracts visible prompts, skill loads, and tool traces from HTML-only exports
- redacts absolute paths and session identifiers
- classifies cases such as `trigger-miss`, `loaded-but-bypassed`,
  `semantic-abandonment`, `route-via-subagent`, and `config-drift`
- emits promotion candidates in the same shape as the checked-in eval corpus

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
Use the existing examples in that file as the canonical schema.

Good routing evals:

- keep the prompt phrasing realistic
- state the expected skill and route
- encode recovery expectations when the first Kast attempt hits setup friction
  or a noisy JSON result
- forbid raw `grep` / `rg` for semantic Kotlin work
- stay generic enough to survive codebase churn

## Improve the skill after promotion

Once the eval corpus captures the recurring miss, update the narrowest
surface that explains the behavior:

1. `SKILL.md` for portable, standards-based skill behavior
2. `.github/agents/*.md` for GitHub Copilot-specific routing and invocation hints
3. `.github/hooks/*` for enforcement and compatibility drift
4. optional vendor-specific metadata only when a host actually requires it

Do not change several of these at once unless the evidence says they all
need to move together.

## Re-measure

After any routing change:

1. Re-run `kast eval skill`
2. Compare against the previous baseline with
   `kast eval skill --compare=baseline.json`
3. Re-run the corpus builder on fresh sessions

The goal is not only a better static score. The goal is fewer fresh cases in
`trigger-miss` and `loaded-but-bypassed`.

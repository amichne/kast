---
name: skill-creator
description: Create new skills, revise existing skills, and build the eval scaffolding that proves they help. Use when a user wants to turn a repeated workflow into a skill, bootstrap SKILL.md/resources, ingest Copilot session logs into candidate evals, benchmark a skill against baselines or alternate models, add new pain points to a shared suite, or promote changes only when core and holdout coverage do not regress.
---

# Skill Creator

A skill for creating new skills and iteratively improving them — scoped to this repo's
Copilot CLI conventions.

The overall process:

1. **Interview** — gather intent with `ask_user`, then call `skill_creator_interview` to record answers and unlock editing
2. Understand what the skill should do and how it should work
3. Write a draft of the skill in `.github/skills/<name>/SKILL.md`
4. Create a few test prompts, run the skill (via sub-agents), and evaluate results
5. Revise based on feedback; repeat until satisfied

Jump in wherever the user is in this process. If they say "I want a skill for X", start from
the interview. If they hand you an existing draft, run the interview then go to testing.
**Never skip step 1** — the `skill_creator_interview` tool is a hard gate on file editing.
Create or improve a skill without binding the workflow to one model vendor or one host runtime.

## Core principles

### Keep the core lean

Assume the model is already strong. Put only durable, non-obvious guidance in `SKILL.md`. Move long references, schemas, and reusable utilities into `references/` and `scripts/`.

### Match freedom to fragility

- Use prose guidance when many approaches can work.
- Use scripts when the same code keeps being rewritten.
- Use explicit checklists only when the task is easy to get subtly wrong.

### Prefer progressive disclosure

Keep `SKILL.md` focused on the workflow. Put detailed schemas, platform adapters, and evaluation mechanics in reference files that can be loaded only when needed.

### Treat runtime integration as an adapter

> **Before writing any file**, use `ask_user` to gather intent, then call
> `skill_creator_interview` (mode: `"create"`) with the answers. The extension hook will
> block any edit to a `SKILL.md` file until this tool has been called.

Start by understanding the user's intent. If the current conversation already contains a
workflow they want to capture (e.g., "turn this into a skill"), extract answers from the
conversation history first. Then fill in the gaps:
Do not make one provider's CLI or one model family the center of the skill. The portable core is:

1. define what the skill should do
2. create reusable resources
3. evaluate with and without the skill
4. collect pain points
5. promote only after non-regression gates pass

Host-specific metadata and automation are optional adapters around that core.
Once you have answers, call `skill_creator_interview` before proceeding.

### Interview and Research

## Skill anatomy

Every skill should start from this minimal shape:

```text
skill-name/
├── SKILL.md
├── agents/        # optional runtime-specific UI or helper prompts
├── scripts/       # optional deterministic utilities
├── references/    # optional docs and schemas
├── assets/        # optional templates, icons, fixtures
├── evals/         # optional shared eval suite
├── history/       # optional progression ledger
├── eval-viewer/   # optional review UI for eval workspaces
└── fixtures/      # optional maintained corpora or maintenance fixtures
```

Use `scripts/init_skill.py` to bootstrap a new skill. If the user wants UI-facing metadata for platforms that support it, generate it separately with `scripts/generate_ui_metadata.py` instead of baking it into the core workflow.
Treat this as the canonical durable layout. `scripts/quick_validate.py` enforces the eval contract, uses collection-aware overlap scoring for the target skill, and flags tree-scoped skills that likely belong in `AGENTS.md` instead. Use `scripts/audit_skill_overlap.py` when you need to inspect existing sibling overlap across the whole skills root instead of assuming only the proposed addition is risky.

## Workflow

### 1. Capture intent from real work

Start with concrete user examples, not abstractions.

- Extract repeated steps from the current conversation first.
- If the user already has session logs, ingest them with `scripts/ingest_copilot_events.py`.
- Look for repeated prompts, follow-up corrections, tool failures, and manual workarounds. Those are often the seeds of the first eval cases.

Questions to answer before writing:

1. What should this skill enable another agent to do?
2. When should it trigger?
3. What should success look like?
4. Is the outcome objective enough to justify a persistent eval suite?

### 2. Plan reusable resources

For each representative example, decide whether the repeated value belongs in:

- `scripts/` for deterministic work
- `references/` for domain knowledge, schemas, policies, or workflows
- `assets/` for templates or boilerplate

If a step keeps getting rediscovered in transcripts, it probably belongs in the skill.

### 3. Bootstrap the skill

Use the initializer instead of hand-rolling the folder:

```bash
python3 scripts/init_skill.py my-skill --path /path/to/skills --resources scripts,references --with-evals
```

Then validate the result before you start filling in content:

```bash
python3 scripts/quick_validate.py /path/to/skills/my-skill --skills-root /path/to/skills
```

Only create the directories the skill truly needs.

### 4. Write the skill itself

Follow these rules:

- Keep frontmatter to the portable minimum: `name` and `description`.
- Put trigger guidance in the `description`, not in a "when to use" section later in the file.
- Write the body in imperative form.
- Explain why a step matters when that improves judgment.
- Keep the body short enough that another agent can load it without wasting context.

### 5. Build the eval scaffold early

For skills with objective outcomes, keep the suite beside the skill:

```text
evals/
├── catalog.json         # canonical cases with stage and source metadata
├── pain_points.jsonl    # raw candidate issues from logs, reviews, or bugs
└── files/               # reusable input fixtures

history/
└── progression.json     # accepted benchmarks, gates, promotions, regressions
```

This layout is now a contract, not just a suggestion:

- durable eval inputs live only in `evals/` and `history/`
- `cases[].files` must point at files under `evals/files/`
- benchmark workspaces and scratch review artifacts do **not** belong in the packaged skill
- `scripts/quick_validate.py` rejects malformed eval JSON and incomplete eval scaffolds

> ⛔ **Interview required before touching any skill file.**
>
> Do NOT read the SKILL.md, make edits, or run evals until you have completed these steps:
>
> 1. Use `ask_user` to ask the user:
>    - What output did you see that felt wrong? *(paste an example if possible)*
>    - Are there specific patterns being generated incorrectly?
>    - What does "ideal output" mean to you here?
>    - Should we run evals before/after to verify the improvement?
> 2. Call `skill_creator_interview` (mode: `"improve"`) with their answers.
>
> The `skill_creator_interview` tool unlocks SKILL.md editing for this session. The
> extension hook will surface a confirmation prompt if you attempt to edit a skill file
> without calling it first.
>
> If the user says "just fix it" without answering, push back and ask at minimum what
> output felt wrong — you cannot improve a skill without knowing what's broken.

This is the core loop. You've run test cases, the user has reviewed results — now make the
skill better based on their feedback.

Use `references/schemas.md` for the exact shapes.

Recommended stages:

- `candidate` - newly added pain point; not trusted yet
- `holdout` - stable enough to gate changes, but not yet part of the permanent core
- `core` - never knowingly regress
- `retired` - obsolete or replaced

### 6. Run the evaluation matrix

The execution mechanism depends on the host runtime, but the stored artifacts should stay consistent.

For each eval case, capture:

- with-skill run
- baseline run (`without_skill`, `old_skill`, or another explicit comparator)
- optional alternate-model runs when the user cares about cross-model coverage

Store outputs in an iteration workspace and keep the layout stable so the shared viewer and benchmark scripts can reuse it.

Use the existing evaluation helpers:

- `agents/grader.md` for assertion grading
- `agents/comparator.md` for blind A/B review
- `agents/analyzer.md` for benchmark pattern analysis
- `scripts/aggregate_benchmark.py` for summary statistics
- `scripts/prove_consolidation.py` when a merged skill needs to prove it matched or beat the legacy sibling envelope
- `eval-viewer/generate_review.py` for human review

See `references/evaluation_scaffold.md` for the full layout and operating loop.

### 7. Integrate pain points continuously

Do not wait for a big rewrite to update the suite.

- Ingest Copilot event logs with `scripts/ingest_copilot_events.py`
- Merge the resulting pain points into `evals/catalog.json` with `scripts/merge_pain_points.py`
- Keep new cases as `candidate` until they have actually passed the progression gate

`scripts/merge_pain_points.py` should fail fast on malformed pain-point intake instead of silently weakening the suite.

Good pain points usually come from:

- user follow-up corrections
- tool failures
- repeated manual cleanup
- benchmark comments about missing coverage
- regressions found after a rollout

### 8. Prove progression before promotion

Use `scripts/progression_gate.py` after each benchmark to update `history/progression.json` and, when justified, promote cases through the suite.

Default policy:

1. core cases must not regress
2. holdout aggregate coverage must not regress
3. candidate cases must pass their required threshold for enough accepted benchmarks before promotion

This keeps the suite growing without letting a new change "win" by dropping hard cases.

### 9. Package only the durable artifacts

Share the skill with its durable eval assets, but exclude transient benchmark outputs and scratch workspaces. `scripts/package_skill.py` already follows that rule.
If you see root-level artifacts such as `session.html`, benchmark summaries, or review feedback in the skill directory, treat them as misplaced workspace outputs and move them out before promotion.

## Practical guidance

### When to skip the full scaffold

Skip the persistent suite only when the skill's output is mostly subjective and the user clearly prefers lightweight iteration.

### When to insist on the scaffold

Use the full scaffold when any of these are true:

- the skill transforms files or structured data
- the workflow is operationally important
- multiple teammates will maintain the skill
- a regression would be expensive
- the user wants to compare versions or models

### When to consolidate instead of creating a new skill

Prefer consolidation or `AGENTS.md` guidance when any of these are true:

- the proposed skill mostly encodes one repo subtree's local conventions
- the scope is so narrow that it only helps in one module or one file family
- another sibling skill already covers nearly the same trigger space

Do not assume the existing sibling set is already healthy. Use `scripts/quick_validate.py` for the skill you are actively editing, then run `scripts/audit_skill_overlap.py /path/to/skills --output overlap_report.json` to find existing overlap clusters across the collection. When you choose to merge overlapping skills, benchmark the consolidated candidate against each legacy sibling on the union of their evals and keep the resulting `consolidation_report.json` as proof that overlap reduction did not weaken outcomes.

### Team-scale default

For a team-sized workflow:

1. keep `evals/catalog.json` in version control
2. treat `pain_points.jsonl` as the intake queue
3. use Copilot session logs to seed new cases
4. record every accepted benchmark in `history/progression.json`
5. promote only through the gate, not by intuition

That makes the suite additive, auditable, and hard to quietly weaken.

## Reference files

- `references/evaluation_scaffold.md` - workspace layout, review loop, and promotion flow
- `references/interface_metadata.md` - optional UI metadata adapters
- `references/schemas.md` - catalog, benchmark, pain-point, and progression schemas
- `agents/grader.md` - grade expectations against outputs
- `agents/comparator.md` - blind compare two outputs
- `agents/analyzer.md` - analyze why a version won or lost

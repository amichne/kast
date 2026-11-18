# Paired Pi agent experiments

Use [the token POC](AGENT_EFFICIENCY_TOKEN_POC.md) for the current ten-task comparison and token report.
Use [the reference experiment](AGENT_EFFICIENCY_REFERENCES.md) for later chain instructions and provider timing.

This local experiment compares native Pi sessions with and without Kast.
The existing host-observation controller owns process cancellation and cleanup.
Pi owns MCP discovery, tool execution, permissions, and session shutdown.
The experiment does not publish a plugin or install a discoverable skill.

## Run a comparison

Use Python 3.12 or 3.13, uv 0.12.9 or later, Node, Pi 1.3.0, and the installed Kast MCP command.
The current runner supports macOS because its holdout isolation uses `sandbox-exec`.
Use an existing credential file with enough token validity for the run.
The runner uses read-only credential access.

Run the focused local gates:

```sh
uv run --locked --project experiments/host-observation ruff format --check \
  experiments/host-observation
uv run --locked --project experiments/host-observation ruff check \
  experiments/host-observation
uv run --locked --project experiments/host-observation pyright
uv run --locked --project experiments/host-observation pytest \
  experiments/host-observation/test_agent_efficiency_metrics.py \
  experiments/host-observation/efficiency-tests
node --test experiments/host-observation/agent_efficiency_audit.test.mjs
node --test experiments/host-observation/agent_efficiency_session.test.mjs
node --test experiments/host-observation/agent_efficiency_limits.test.mjs
```

Create an environment file with absolute paths for `workspace`, `pi_package`, `auth`, and `mcp_command`.
Include the exact `pi_version`.
Set `producer_workspace` to an isolated checkout at `5937ff1f46fbb0914c36226afb52426a4271c29c`.
The runner pins that producer revision for the measured corpus and its bundled references.
Set `subject` to the exact admitted workspace revision or source overlay.
The runner pins `openai-codex/gpt-6.1-sol` with `high` effort.
The corpus curator supplies source annotations before inference.
Keep the curator source and its tests with the private corpus archive.

Prepare a new benchmark directory:

```sh
uv run --locked --project experiments/host-observation python \
  experiments/host-observation/agent_efficiency.py prepare \
  --workspace /absolute/pinned/checkout \
  --output /private/tmp/new-benchmark \
  --curator /absolute/independent/agent_efficiency_corpus.py \
  --environment /absolute/environment.json
```

The preparation step needs at least 120 unique tasks across all 11 operation families.
It examines source snippets and setup preimages before the split.
It reserves about one quarter of each family for the holdout.
The manifest records actual scope and difficulty counts.
Scope does not establish repository scale.

Create a JSON plan for a development comparison:

```json
{
  "benchmark": "/private/tmp/new-benchmark",
  "configuration": "/absolute/kast/experiments/host-observation/agent-efficiency-configs/token-poc-compact.json",
  "output": "/private/tmp/new-development-run",
  "repeats": 2
}
```

Set `tasks` to a list of task IDs for a focused run.
Set `holdout` to `true` only for a sealed configuration.
Use a new output directory for each run.

Run the suite from `experiments/host-observation/promptfoo`:

```sh
npm ci
KAST_EFFICIENCY_PLAN=/absolute/comparison.json npm run eval -- \
  -c agent-efficiency.config.mjs -o /private/tmp/development-promptfoo.json
```

Each Promptfoo row starts a fresh Pi process and session for one arm.
The scenario alternates the control and treatment arms.
Use the plan to select tasks and repeats for the complete cohort.
Both arms use SSE, disabled retries, disabled compaction, and disabled cache warming.
Provider caches remain shared and warm.
The manifest declares this cache policy without a claim of a cold-cache comparison.

## Interpret the evidence

The session journal records finalized usage, provider requests, tool calls, output bytes, and stages.
Private transcripts preserve answers and tool evidence.
The runner compares exact JSON answers with independent source annotations.
Mutation tasks also need their expected file content.
Unqualified oracles cannot grant a correct completion.
The runner restores source snapshots after each trial.
The provider guard aborts denied requests because Pi catches hook exceptions.

Every failed trial consumes elapsed time and cost in the comparison.
Unknown cost remains unknown.
Pi reports catalog-priced estimates for subscription inference.
Those estimates do not establish billed dollars.
`comparison.json` uses billed cost and rejects dollar acceptance when that cost is absent.
`catalog-comparison.json` gives a separate diagnostic comparison with its price qualification.

The metric owner draws paired task clusters for 95% confidence intervals.
Each cluster keeps both arms and every repeat.
Acceptance needs both lower interval limits to reach 5, plus the accuracy gates.
Missing pairs, unknown costs, undefined samples, and degenerate intervals cannot establish acceptance.
Independent transcript review must examine `semantic_misselections` and false-completeness claims before promotion.
Exact answer equality alone does not establish the truth of every intermediate claim.

## Candidate changes

The versioned configurations pin the current instruction bundles.
The measured runs used the briefs and hashes from revision `d04b765c1`.
The current briefs use explicit terminal objects after revision `20cd1065e`.
Canonical tool names, operation identities, input contracts, and evidence qualifications stay under their production owners.
Each Kast participant receives a private copy of the canonical skill and its CLI guide. Its prompt points to that copy.
A changed configuration always starts new sessions.
For an experimental brief, use a `file` descriptor with its catalog filename and SHA-256 digest.
The catalog is `agent-efficiency-configs/` beside the runner.
[The compact configuration](agent-efficiency-configs/token-poc-compact.json) supplies an example.
Each trial reads the file once and rejects changed bytes before it starts Pi.
The runner also rejects missing files, symbolic links, and invalid UTF-8.
The configuration digest includes the brief digest.
Keep independent implementation candidates in separate worktrees at the same source revision.
Keep one coordinator responsible for corpus custody, oracles, and promotion.

For candidate optimization, use the same Promptfoo configuration with an optimization plan:

```json
{
  "champion": {
    "benchmark": "/private/tmp/champion-benchmark",
    "configuration": "/absolute/champion-configuration.json"
  },
  "candidates": [{
    "benchmark": "/private/tmp/candidate-benchmark",
    "configuration": "/absolute/candidate-configuration.json"
  }],
  "development_task_ids": ["first-task", "second-task"],
  "repeats": 2,
  "output": "/private/tmp/new-optimization"
}
```

The comparison rejects different models, budgets, corpus identities, and source revisions.
Each candidate must use a separate workspace and change one configuration field.
The completed run writes cohort and candidate comparisons.
Promotion needs billed cost and independent semantic audits.

The measured corpus covers ten source questions in one repository.
It does not establish performance across repository scales or live freshness cases.
The source oracles and raw runs remain in the private archive described by the reports.
A complete target assessment also needs a holdout and independent intermediate-claim audits.

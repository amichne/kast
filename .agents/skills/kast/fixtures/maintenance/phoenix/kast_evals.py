#!/usr/bin/env python3
"""Phoenix evaluations for the kast skill.

Runs LLM-as-judge + code evaluators against prompts drawn from:
  - evals.json
  - routing.json

Usage:
    pip install arize-phoenix openai
    export OPENAI_API_KEY=sk-...
    export PHOENIX_HOST=http://localhost:6006   # optional, default
    python kast_evals.py

Set DRY_RUN=0 to run all examples (default dry-run tests 3 first).
"""

import asyncio
import json
import os
import re
from pathlib import Path

import pandas as pd
from phoenix.client import Client
from phoenix.client.experiments import evaluate_experiment, run_experiment
from phoenix.evals import (
    ClassificationEvaluator,
    LLM,
    bind_evaluator,
    create_evaluator,
)

# ── Paths ─────────────────────────────────────────────────────────────────────

_HERE = Path(__file__).parent
_MAINTENANCE = _HERE.parent
_SKILL_ROOT = _MAINTENANCE.parent.parent  # .agents/skills/kast/

EVALS_JSON = _MAINTENANCE / "evals" / "evals.json"
ROUTING_JSON = _MAINTENANCE / "evals" / "routing.json"
SKILL_MD = _SKILL_ROOT / "SKILL.md"
QUICKSTART_MD = _SKILL_ROOT / "references" / "quickstart.md"

# ── Axial-coded failure taxonomy (derived from session history) ───────────────
#
# From session 803057da ("collate transcripts"):
#   "loaded but bypassed" — kast skill context provided, agent still uses grep/rg
#
# From session dbda65ca ("improve kast skill reliability"):
#   "failures of skill initialization" (KAST_CLI_PATH empty)
#   "mismatched schema specifications in input/output shapes"
#   "reduce friction when skill is selected" (maintenance thrash)
#
# From session fe3aa9ad ("improve kast skill evaluations"):
#   env-var confusion (KAST_WORKSPACE_ROOT), since removed from the skill
#
# From session 431e7b1e ("port skill wrappers to Kotlin"):
#   contract surface misses: wrong filePaths→targetFile, missing workspaceRoot
#
# Resulting taxonomy:
#   trigger_miss          - generic Kotlin prompt, kast not chosen
#   routing_bypass        - kast loaded, agent uses grep/rg for Kotlin identity
#   initialization_friction - KAST_CLI_PATH empty, wrong recovery path
#   maintenance_thrash    - reads .kast-version / fixtures/maintenance / wrapper-openapi first
#   schema_request        - wrong request fields (filePaths, no workspaceRoot, probes {})
#   schema_response       - abandons kast after jq/snake_case/camelCase confusion
#   mutation_abandonment  - falls back to sed/manual edit after write-and-validate ok=false
#   failure_response_ignored - treats ok=false as success or abandons kast entirely

_REQUIRED_FAILURE_MODES = {
    "trigger_miss",
    "routing_bypass",
    "initialization_friction",
    "maintenance_thrash",
    "schema_request",
    "schema_response",
    "mutation_abandonment",
    "failure_response_ignored",
}

# ── Dataset builder ───────────────────────────────────────────────────────────


def _require_failure_mode(ev: dict, source: str) -> str:
    failure_mode = ev.get("failure_mode")
    if failure_mode not in _REQUIRED_FAILURE_MODES:
        raise ValueError(
            f"{source} case {ev.get('id', '<unknown>')} has invalid failure_mode={failure_mode!r}. "
            f"Expected one of {sorted(_REQUIRED_FAILURE_MODES)}"
        )
    return failure_mode


def build_dataframe() -> pd.DataFrame:
    rows: list[dict] = []

    with open(EVALS_JSON) as f:
        for ev in json.load(f)["evals"]:
            rows.append({
                "id": f"behavior-{ev['id']}",
                "prompt": ev["prompt"],
                "category": "behavior",
                "failure_mode": _require_failure_mode(ev, "behavior"),
                "expected_behavior": ev["expected_output"],
                "expectations": json.dumps(ev["expectations"]),
            })

    with open(ROUTING_JSON) as f:
        for ev in json.load(f)["evals"]:
            rows.append({
                "id": f"routing-{ev['id']}",
                "prompt": ev["prompt"],
                "category": "routing",
                "failure_mode": _require_failure_mode(ev, "routing"),
                "expected_behavior": (
                    f"Routes to @kast. "
                    f"Allowed: {ev['allowed_ops']}. "
                    f"Forbidden: {ev['forbidden_ops']}."
                ),
                "expectations": json.dumps(ev["allowed_ops"]),
            })

    return pd.DataFrame(rows)


# ── LLM task (simulates an agent given the kast skill) ───────────────────────

_SKILL_CONTEXT = ""
if SKILL_MD.exists():
    _SKILL_CONTEXT += SKILL_MD.read_text()
if QUICKSTART_MD.exists():
    _SKILL_CONTEXT += "\n\n" + QUICKSTART_MD.read_text()

_SYSTEM_PROMPT = f"""You are a developer assistant. You have the kast skill active.
Follow its instructions exactly. When working with Kotlin code, always use kast
skill commands. Never use grep/rg for Kotlin identity.

--- KAST SKILL ---
{_SKILL_CONTEXT}
--- END SKILL ---
"""


def run_kast_task(example) -> str:
    import openai

    client = openai.OpenAI()
    resp = client.chat.completions.create(
        model="gpt-4o",
        messages=[
            {"role": "system", "content": _SYSTEM_PROMPT},
            {"role": "user", "content": example.input["prompt"]},
        ],
        max_tokens=800,
    )
    return resp.choices[0].message.content or ""


# ── Code evaluators ───────────────────────────────────────────────────────────

_KAST_CMD = re.compile(
    r"kast skill (workspace-files|scaffold|resolve|references|callers|rename|write-and-validate|diagnostics)"
)
_GREP_RG_KOTLIN = re.compile(
    r"(grep|rg)\s+.*\.(kt|kts)|"
    r"(grep|rg)\s+.*\b(class|interface|fun|val|var|object)\b",
    re.IGNORECASE,
)
_SED_KOTLIN = re.compile(r"sed\s+.*\.(kt|kts)", re.IGNORECASE)
_MAINTENANCE = re.compile(
    r"\.kast-version|fixtures[/\\]maintenance|wrapper-openapi\.yaml", re.IGNORECASE
)
_OK_FALSE = re.compile(r'"ok"\s*:\s*false|ok=false', re.IGNORECASE)
_FAILURE_ACK = re.compile(
    r"fail|error|did not succeed|not succeed|blocker|hash|validation|ok.*false",
    re.IGNORECASE,
)


@create_evaluator(name="uses_kast_commands", kind="code")
def uses_kast_commands(output: str) -> bool:
    """Response contains at least one kast skill command."""
    return bool(_KAST_CMD.search(output))


@create_evaluator(name="no_grep_for_kotlin_identity", kind="code")
def no_grep_for_kotlin_identity(output: str) -> bool:
    """Response does not use grep/rg for Kotlin identity lookup."""
    return not bool(_GREP_RG_KOTLIN.search(output))


@create_evaluator(name="no_sed_for_kotlin_edit", kind="code")
def no_sed_for_kotlin_edit(output: str) -> bool:
    """Response does not use sed to edit Kotlin files."""
    return not bool(_SED_KOTLIN.search(output))


@create_evaluator(name="no_maintenance_reads", kind="code")
def no_maintenance_reads(output: str) -> bool:
    """Response does not open maintenance fixtures early."""
    return not bool(_MAINTENANCE.search(output))


@create_evaluator(name="acknowledges_ok_false", kind="code")
def acknowledges_ok_false(output: str, input: dict) -> bool:
    """When prompt contains ok=false, response must acknowledge the failure."""
    prompt = input.get("prompt", "") if isinstance(input, dict) else str(input)
    if not _OK_FALSE.search(prompt):
        return True  # not applicable to this prompt
    return bool(_FAILURE_ACK.search(output))


# ── LLM evaluators ────────────────────────────────────────────────────────────

_llm = LLM(provider="openai", model="gpt-4o")

routing_evaluator = ClassificationEvaluator(
    name="routing_correct",
    prompt_template="""\
You are evaluating whether a developer assistant correctly chose to use kast
(a Kotlin semantic analysis tool) given a user prompt.

kast should be used for ANY Kotlin identity work: symbols, files, call tracing,
safe edits, renames. Never use grep/rg for Kotlin identity.

<prompt>{{input}}</prompt>
<response>{{output}}</response>

Did the response use kast skill commands for Kotlin work, without falling back to
grep/rg/sed for Kotlin identity?

"correct"        – kast commands used appropriately
"incorrect"      – grep/rg used for Kotlin identity, or kast bypassed for Kotlin work
"not_applicable" – task does not involve Kotlin identity

Answer (correct/incorrect/not_applicable):""",
    llm=_llm,
    choices={"correct": 1, "incorrect": 0, "not_applicable": 1},
)

recovery_evaluator = ClassificationEvaluator(
    name="recovery_quality",
    prompt_template="""\
You are evaluating how a developer assistant recovered from a kast initialization
failure (KAST_CLI_PATH empty / command not found).

Correct recovery:
  eval "$(bash .agents/skills/kast/scripts/kast-session-start.sh)"
  → retry the same kast command once
  → continue semantically; never fall back to grep/rg

<prompt>{{input}}</prompt>
<response>{{output}}</response>

"good_recovery"    – used bootstrap script, retried kast, stayed semantic
"partial_recovery" – attempted bootstrap but made other mistakes
"bad_recovery"     – broad filesystem search, read maintenance files, or fell back to grep
"not_applicable"   – no initialization failure in the prompt

Answer (good_recovery/partial_recovery/bad_recovery/not_applicable):""",
    llm=_llm,
    choices={"good_recovery": 1, "partial_recovery": 0.5, "bad_recovery": 0, "not_applicable": 1},
)

schema_evaluator = ClassificationEvaluator(
    name="schema_correct",
    prompt_template="""\
You are evaluating whether a developer assistant used correct kast JSON shapes.

Rules:
- scaffold uses `targetFile` (singular absolute path), NOT `filePaths` array
- Always include `workspaceRoot` in scaffold requests
- One scaffold call per file; no batch variant
- Request JSON is camelCase
- Top-level wrapper response metadata is snake_case
- Nested API model fields are camelCase (e.g. symbol.fqName, location.filePath)
- Never probe schema with empty `{}` or `--help`
- Paths ending in filePath/filePaths/contentFile must be absolute

<prompt>{{input}}</prompt>
<response>{{output}}</response>

"correct"        – all kast request/response handling follows the rules
"incorrect"      – wrong field names, batched scaffold, omitted workspaceRoot,
                   or misinterpreted snake_case/camelCase boundaries
"not_applicable" – no kast request JSON shown or constructed

Answer (correct/incorrect/not_applicable):""",
    llm=_llm,
    choices={"correct": 1, "incorrect": 0, "not_applicable": 1},
)

failure_handling_evaluator = ClassificationEvaluator(
    name="failure_handling_correct",
    prompt_template="""\
You are evaluating how a developer assistant handled a kast failure response.

kast failure responses carry: ok=false, type (e.g. WRITE_AND_VALIDATE_FAILURE),
stage, message, optional error_text and log_file.

Correct behavior:
1. Check `ok` and `type` BEFORE presenting results
2. Summarize the failure stage and message
3. Choose: targeted retry, narrowed query, diagnostics, or explicit blocker report
4. NEVER switch to grep/sed/manual edits after a kast failure
5. NEVER claim success when ok=false

<prompt>{{input}}</prompt>
<response>{{output}}</response>

"correct"        – checks ok/type, surfaces failure details, picks appropriate next step
"incorrect"      – ignores ok=false, treats failure as success, or switches to text tools
"not_applicable" – no failure scenario in the prompt

Answer (correct/incorrect/not_applicable):""",
    llm=_llm,
    choices={"correct": 1, "incorrect": 0, "not_applicable": 1},
)

# ── Experiment runner ─────────────────────────────────────────────────────────

_EVALUATORS = [
    uses_kast_commands,
    no_grep_for_kotlin_identity,
    no_sed_for_kotlin_edit,
    no_maintenance_reads,
    bind_evaluator(acknowledges_ok_false, {"input": "input"}),
    bind_evaluator(routing_evaluator, {"input": "input.prompt", "output": "output"}),
    bind_evaluator(recovery_evaluator, {"input": "input.prompt", "output": "output"}),
    bind_evaluator(schema_evaluator, {"input": "input.prompt", "output": "output"}),
    bind_evaluator(failure_handling_evaluator, {"input": "input.prompt", "output": "output"}),
]


def main() -> None:
    phoenix_host = os.getenv("PHOENIX_HOST", "http://localhost:6006")
    dry_run = int(os.getenv("DRY_RUN", "3"))  # set DRY_RUN=0 for full run

    client = Client(base_url=phoenix_host)

    df = build_dataframe()
    print(f"Dataset: {len(df)} examples")
    print(df["category"].value_counts().to_string())

    dataset = client.datasets.create_dataset(
        dataframe=df,
        name="kast-skill-evals-v1",
        input_keys=["prompt"],
        output_keys=["expected_behavior"],
        metadata_keys=["id", "category", "failure_mode", "expectations"],
    )
    print(f"\nDataset uploaded to Phoenix: {dataset.name}")

    run_kwargs: dict = {
        "dataset": dataset,
        "task": run_kast_task,
        "evaluators": _EVALUATORS,
        "experiment_name": "kast-skill-eval-run-v1",
    }
    if dry_run > 0:
        run_kwargs["dry_run"] = dry_run
        print(f"\nDry run: testing {dry_run} examples. Set DRY_RUN=0 for full run.\n")

    experiment = run_experiment(**run_kwargs)

    print("\n=== Aggregate scores ===")
    for name, score in experiment.aggregate_scores.items():
        bar = "█" * int(score * 20)
        print(f"  {name:<35} {score:.2f}  {bar}")

    print("\n=== Failures by failure_mode ===")
    failure_counts: dict[str, list[str]] = {}
    for run in experiment.runs:
        failing = [
            k for k, v in run.scores.items()
            if isinstance(v, dict) and v.get("score", 1.0) < 0.5
        ]
        if failing:
            mode = run.example.metadata.get("failure_mode", "unknown")
            failure_counts.setdefault(mode, []).extend(failing)

    for mode, evals in sorted(failure_counts.items(), key=lambda x: -len(x[1])):
        from collections import Counter
        counts = Counter(evals)
        print(f"\n  {mode}:")
        for ev, n in counts.most_common():
            print(f"    {ev}: {n} failures")

    print(f"\nView full results in Phoenix: {phoenix_host}")


if __name__ == "__main__":
    main()

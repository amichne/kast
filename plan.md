## Repository: `michne/kast`

All new files go under `.agents/skills/kast/value-proof/`. This keeps the value-proof suite separate from the existing behavior/routing evals in `.agents/skills/kast/evals/`.

---

### 1. Create the codebase bindings schema and default bindings

**File: `.agents/skills/kast/value-proof/bindings.schema.json`**

Define a JSON schema for the pluggable slots. Each slot maps an abstract role to a concrete symbol in the target codebase:

```json
{
  "target_repo": "path or name of the target Kotlin project",
  "workspace_root": "/absolute/path/to/target/repo",
  "slots": {
    "SEALED_HIERARCHY": {
      "symbol": "Konstrained",
      "fqName": "io.amichne.konditional.core.types.Konstrained",
      "file": "konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt",
      "description": "A sealed interface with 3+ subtypes, ideally cross-module"
    },
    "DISAMBIGUATE_MEMBER": {
      "symbol": "key",
      "containingType": "Feature",
      "fqName": "io.amichne.konditional.core.features.Feature.key",
      "file": "konditional-engine/src/main/kotlin/io/amichne/konditional/core/features/Feature.kt",
      "description": "A property name that appears on many unrelated types — grep returns false positives"
    },
    "CROSS_MODULE_CLASS": {
      "symbol": "FlagValue",
      "fqName": "io.amichne.konditional.internal.serialization.models.FlagValue",
      "file": "konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt",
      "description": "A class referenced across module boundaries"
    },
    "OVERLOADED_OR_COMMON_FUNCTION": {
      "symbol": "resolve",
      "containingType": "ConditionalValue",
      "fqName": "io.amichne.konditional.rules.ConditionalValue.resolve",
      "file": "konditional-engine/src/main/kotlin/io/amichne/konditional/rules/ConditionalValue.kt",
      "description": "A function name that appears in many classes — disambiguation required"
    },
    "RENAME_TARGET": {
      "symbol": "NamespaceRegistry",
      "fqName": "io.amichne.konditional.core.registry.NamespaceRegistry",
      "newName": "FeatureRegistry",
      "file": "konditional-engine/src/main/kotlin/io/amichne/konditional/core/registry/NamespaceRegistry.kt",
      "description": "An interface referenced across multiple modules — rename touches many files"
    },
    "LARGE_CLASS": {
      "symbol": "EvaluationDiagnostics",
      "fqName": "io.amichne.konditional.internal.evaluation.EvaluationDiagnostics",
      "file": "konditional-engine/src/main/kotlin/io/amichne/konditional/internal/evaluation/EvaluationDiagnostics.kt",
      "description": "A 200+ line class with nested types — scaffold vs raw read shows token savings"
    },
    "MODULE_LIST": {
      "modules": ["konditional-types", "konditional-engine", "konditional-json"],
      "description": "The modules in the target project"
    }
  }
}
```

**File: `.agents/skills/kast/value-proof/bindings/konditional.json`**

The default bindings file pre-filled for the `konditional` repo (using the slots above). Create a second empty template file `bindings/template.json` with placeholder values and comments so users can plug in their own codebase.

---

### 2. Create the value-proof eval catalog

**File: `.agents/skills/kast/value-proof/catalog.json`**

Follow the same schema as `.agents/skills/kast/evals/catalog.json` (see `.agents/skills/skill-creator/references/schemas.md` lines 16-51 for the schema). Set `skill_name` to `"kast-value-proof"`. All cases start at stage `"candidate"`.

The catalog should contain these 10 eval cases. Each prompt uses `{{SLOT_NAME}}` template variables that the prompt renderer (step 3) will hydrate from bindings.json:

**Category 1: Symbol Disambiguation (identity precision)**

Case `vp-disambiguate-member`:
- prompt: `"Find all usages of the {{DISAMBIGUATE_MEMBER.symbol}} property on {{DISAMBIGUATE_MEMBER.containingType}}, not every {{DISAMBIGUATE_MEMBER.symbol}} in the repo. List each call site with file path and line number."`
- expectations:
  1. "Resolves the member with containingType or fileHint before scanning usages"
  2. "Result set is scoped to {{DISAMBIGUATE_MEMBER.containingType}}.{{DISAMBIGUATE_MEMBER.symbol}} — does not include unrelated types"
  3. "Does not use raw text search (grep/rg) as the primary identity mechanism"
  4. "Reports at least 3 distinct usage sites with file paths"

Case `vp-disambiguate-function`:
- prompt: `"Find callers of {{OVERLOADED_OR_COMMON_FUNCTION.containingType}}.{{OVERLOADED_OR_COMMON_FUNCTION.symbol}}(), not every function named {{OVERLOADED_OR_COMMON_FUNCTION.symbol}} in the project. Show the call hierarchy."`
- expectations:
  1. "Disambiguates the function using containingType, kind, or fileHint"
  2. "Does not silently pick one of multiple candidates without disambiguation"
  3. "Reports callers specific to the target class, not unrelated resolve() calls"

**Category 2: Exhaustive Evidence (completeness proof)**

Case `vp-exhaustive-references`:
- prompt: `"Find every reference to {{CROSS_MODULE_CLASS.symbol}} across the entire workspace. Tell me whether the search was exhaustive — did it cover every candidate file, or was it sampled/truncated?"`
- expectations:
  1. "Reports searchScope.exhaustive status or equivalent completeness metadata"
  2. "Lists references grouped by file"
  3. "Does not claim completeness without structural proof from the tool"
  4. "Finds references in at least 2 different modules"

Case `vp-sealed-hierarchy-trace`:
- prompt: `"List every implementation of the sealed interface {{SEALED_HIERARCHY.symbol}}. For each implementation, show its file location and which module it belongs to."`
- expectations:
  1. "Uses semantic resolution (not grep for 'class.*Konstrained') to find implementations"
  2. "Lists all sealed subtypes with their file paths"
  3. "Correctly identifies which module each implementation lives in"
  4. "Does not miss implementations in other modules"

**Category 3: Safe Mutations (edit correctness)**

Case `vp-multi-file-rename`:
- prompt: `"Rename {{RENAME_TARGET.symbol}} to {{RENAME_TARGET.newName}} across the entire workspace. Show me the edit plan before applying. After applying, confirm no compile errors were introduced."`
- expectations:
  1. "Uses kast_rename (not find-and-replace or sed)"
  2. "Shows an edit plan listing all affected files before applying"
  3. "Updates import statements, not just the declaration"
  4. "Runs diagnostics or reports compile status after the rename"
  5. "Does not leave broken references in any module"

Case `vp-edit-and-validate`:
- prompt: `"Add a @Deprecated annotation with message 'Use {{RENAME_TARGET.newName}} instead' to the {{RENAME_TARGET.symbol}} interface declaration. Confirm the file still compiles after the edit."`
- expectations:
  1. "Uses kast_write_and_validate (not raw edit/create tool)"
  2. "Runs diagnostics atomically as part of the write"
  3. "Reports clean or dirty compile state after the edit"
  4. "Does not claim success without validation evidence"

**Category 4: Structural Understanding (token efficiency)**

Case `vp-scaffold-large-class`:
- prompt: `"Summarize the public API of {{LARGE_CLASS.symbol}} — list every public type, interface, enum, and data class it contains, with their member signatures."`
- expectations:
  1. "Uses kast_scaffold (not raw file read) as the primary information source"
  2. "Lists all nested sealed interfaces and enums accurately"
  3. "Does not hallucinate members that don't exist"
  4. "Produces the summary in fewer tokens than reading the raw file would require"

Case `vp-workspace-discovery`:
- prompt: `"List every module in this workspace and how many Kotlin source files each contains."`
- expectations:
  1. "Uses kast_workspace_files (not recursive ls/find)"
  2. "Reports the correct module names: {{MODULE_LIST.modules}}"
  3. "Reports file counts for each module"
  4. "Completes in a single tool call (not iterative directory traversal)"

**Category 5: Multi-Step Workflow (compound advantage)**

Case `vp-impact-analysis`:
- prompt: `"I want to delete {{OVERLOADED_OR_COMMON_FUNCTION.containingType}}.{{OVERLOADED_OR_COMMON_FUNCTION.symbol}}(). Show me every direct caller, then for each caller show its callers (depth 2). Identify which of those callers are in test files vs production code."`
- expectations:
  1. "Resolves the exact function before tracing callers"
  2. "Shows a 2-level call hierarchy"
  3. "Distinguishes test files from production files"
  4. "Reports truncation metadata if the hierarchy was bounded"

Case `vp-cross-module-flow`:
- prompt: `"Trace how {{CROSS_MODULE_CLASS.symbol}} flows from its definition in {{CROSS_MODULE_CLASS.file}} to its consumers in other modules. Show the cross-module dependency chain."`
- expectations:
  1. "Uses scaffold + references + callers in sequence (not grep)"
  2. "Identifies consumers in at least one other module"
  3. "Shows concrete file-to-file relationships, not just module names"
  4. "Does not miss cross-module references"

---

### 3. Create the prompt renderer

**File: `.agents/skills/kast/value-proof/scripts/render_prompts.py`**

A Python script that:
1. Reads `catalog.json` and a bindings file (e.g., `bindings/konditional.json`)
2. For each case, replaces `{{SLOT.field}}` template variables with concrete values from bindings
3. Outputs a `rendered-catalog.json` with hydrated prompts ready for execution
4. Validates that all referenced slots exist in the bindings file

Usage: `python render_prompts.py --catalog catalog.json --bindings bindings/konditional.json --output rendered-catalog.json`

---

### 4. Create the run orchestrator

**File: `.agents/skills/kast/value-proof/scripts/run_value_proof.py`**

A Python script that:
1. Reads `rendered-catalog.json`
2. Creates the workspace layout per the evaluation_scaffold.md contract:
   ```
   kast-value-proof-workspace/
   └── iteration-001/
       ├── eval-vp-disambiguate-member/
       │   ├── eval_metadata.json
       │   ├── with_skill/
       │   │   ├── run-1/
       │   │   │   ├── outputs/
       │   │   │   │   └── transcript.md
       │   │   │   ├── grading.json  (placeholder — grader fills this)
       │   │   │   └── timing.json
       │   │   ├── run-2/ ...
       │   │   └── run-3/ ...
       │   └── without_skill/
       │       ├── run-1/ ...
       │       ├── run-2/ ...
       │       └── run-3/ ...
       ├── ...more eval dirs...
       ├── benchmark.json
       └── benchmark.md
   ```
3. For each case × configuration × run:
   - Writes `eval_metadata.json` with the hydrated prompt and expectations
   - Creates the `outputs/` directory
   - Prints instructions for the human operator (or agent) to execute the run and save the transcript
4. After all runs are graded, calls `aggregate_benchmark.py` to produce `benchmark.json` and `benchmark.md`

The script should support `--runs-per-config 3` (default 3) and `--configs with_skill,without_skill`.

For the actual execution of each run, the script should generate a `run_instructions.md` per run that says:
- For `with_skill`: "Open a Copilot Chat session with the Kast skill loaded. Paste this prompt: [hydrated prompt]. Save the full transcript to outputs/transcript.md."
- For `without_skill`: "Open a Copilot Chat session WITHOUT the Kast skill (or with Kast tools disabled). Paste this prompt: [hydrated prompt]. Save the full transcript to outputs/transcript.md."

This keeps the orchestrator tool-agnostic — it works with any agent runtime.

---

### 5. Create the executive summary generator

**File: `.agents/skills/kast/value-proof/scripts/generate_executive_summary.py`**

Reads `benchmark.json` (produced by `aggregate_benchmark.py`) and generates:

1. **`executive-summary.md`** — a one-page enterprise-facing document:
   - Title: "Kast Value Proof: [target repo name]"
   - Headline metrics table (pass rate, tokens, tool calls, time — with_skill vs without_skill vs delta)
   - Per-category breakdown (Disambiguation, Completeness, Safe Mutations, Token Efficiency, Multi-Step)
   - "Key findings" section auto-generated from analyzer notes (e.g., "Assertion 'no compile errors after rename' passes 100% with Kast, 0% without")
   - "What this means" section mapping each category to enterprise value (correctness → fewer bugs shipped, completeness → audit confidence, safe mutations → no broken builds, token efficiency → lower API cost)

2. **`executive-summary.html`** — a standalone HTML version using the same data, suitable for sharing via email or embedding in a slide deck. Can reuse the viewer.html template pattern from `generate_review.py`.

Usage: `python generate_executive_summary.py --benchmark benchmark.json --bindings bindings/konditional.json --output executive-summary.md`

---

### 6. Create the end-to-end runner README

**File: `.agents/skills/kast/value-proof/README.md`**

Document the full workflow:

```markdown
# Kast Value Proof Suite

## Quick start (using konditional as target)

1. Render prompts:
   python scripts/render_prompts.py \
     --catalog catalog.json \
     --bindings bindings/konditional.json \
     --output rendered-catalog.json

2. Scaffold the workspace:
   python scripts/run_value_proof.py \
     --catalog rendered-catalog.json \
     --workspace ../kast-value-proof-workspace \
     --runs-per-config 3

3. Execute each run (follow run_instructions.md in each run directory)

4. Grade each run using the grader agent (agents/grader.md from skill-creator)

5. Aggregate results:
   python ../../skill-creator/scripts/aggregate_benchmark.py \
     ../kast-value-proof-workspace/iteration-001 \
     --skill-name kast-value-proof

6. Analyze patterns:
   Use agents/analyzer.md from skill-creator against the benchmark.json

7. Generate the enterprise deliverable:
   python scripts/generate_executive_summary.py \
     --benchmark ../kast-value-proof-workspace/iteration-001/benchmark.json \
     --bindings bindings/konditional.json

8. Open the interactive viewer:
   python ../../skill-creator/eval-viewer/generate_review.py \
     ../kast-value-proof-workspace/iteration-001 \
     --benchmark ../kast-value-proof-workspace/iteration-001/benchmark.json

## Using your own codebase

1. Copy bindings/template.json to bindings/my-project.json
2. Fill in each slot with a representative symbol from your codebase
3. Run the same workflow with --bindings bindings/my-project.json
```

---

### 7. Create history/progression.json

**File: `.agents/skills/kast/value-proof/history/progression.json`**

Initialize an empty progression ledger per the schema in `.agents/skills/skill-creator/references/schemas.md` (lines 156-195):

```json
{
  "skill_name": "kast-value-proof",
  "updated_at": "2026-05-06T00:00:00Z",
  "benchmarks": [],
  "case_history": {}
}
```

This enables the progression gate to track results over time as the suite matures.

---

### Summary of files to create

```
.agents/skills/kast/value-proof/
├── README.md
├── catalog.json                          (10 parameterized eval cases)
├── bindings.schema.json                  (slot definitions)
├── bindings/
│   ├── konditional.json                  (pre-filled for konditional repo)
│   └── template.json                     (empty template for other repos)
├── scripts/
│   ├── render_prompts.py                 (hydrate templates with bindings)
│   ├── run_value_proof.py                (scaffold workspace + run instructions)
│   └── generate_executive_summary.py     (enterprise-facing output)
└── history/
    └── progression.json                  (empty ledger)
```

All grading, aggregation, analysis, and viewer infrastructure is reused from `.agents/skills/skill-creator/` — no duplication needed.

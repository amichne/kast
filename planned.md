Create a unified evaluation framework at the repository root level in michne/kast:

1. **Create new top-level directory structure:**
   - Create `evaluation/` directory
   - Create `evaluation/bindings/` directory
   - Create `evaluation/scripts/` directory
   - Create `evaluation/fixtures/` directory

2. **Migrate scripts from value-proof:**
   - Move all files from `.agents/skills/kast/value-proof/scripts/` to `evaluation/scripts/`
   - Keep `value_proof_aggregate.py` as the primary aggregator (it has paired Wilcoxon and applicability-aware logic)
   - Remove or deprecate `.agents/skills/skill-creator/scripts/aggregate_benchmark.py` (it lacks value-proof specific features)
   - Create `evaluation/scripts/run_evaluation.py` that orchestrates the full value-proof workflow (render, dispatch, grade, aggregate)

3. **Create unified catalog:**
   - Create `evaluation/catalog.json` based on `.agents/skills/kast/value-proof/catalog.json`
   - Use the value-proof catalog schema as the base (with applicability, outcome/process expectations, oracle refs)
   - Remove progression gate concepts (stage, promotion requirements) - focus on value demonstration
   - Create `evaluation/catalog.schema.json` that defines the schema for value-proof evaluation

4. **Update skill references:**
   - Update `.agents/skills/kast-value-proof-runner/SKILL.md` to point to new `evaluation/` location instead of `.agents/skills/kast/value-proof/`
   - Update `.agents/skills/skill-creator/SKILL.md` to reference the consolidated evaluation framework for value justification
   - Update any references in `.agents/skills/kast/value-proof/` to point to the new `evaluation/` location

5. **Update CI workflows:**
   - Update `.github/workflows/` files that reference value-proof scripts to use `evaluation/scripts/` instead
   - Update any workflow steps that reference `.agents/skills/kast/value-proof/` to use `evaluation/`

6. **Update AGENTS.md:**
   - Update the contract surface inventory in AGENTS.md (around lines 172-182) to reflect the new evaluation structure
   - Replace references to `.agents/skills/kast/value-proof/` with `evaluation/`

7. **Create documentation:**
   - Create `evaluation/README.md` as the single source of truth for:
     - How to run value-proof evaluations
     - How to add new eval cases focused on value demonstration
     - How to interpret results (outcome_pass_rate, Wilcoxon significance, integrity checks)
     - The value proposition framework (why this evaluation proves value)
   - Include migration guide from the old scattered structure
   - Emphasize that this is for value justification, not iterative progression

8. **Clean up old structure:**
   - Remove `.agents/skills/kast/value-proof/` directory after migration (or leave as deprecated with pointer to new location)
   - Do NOT move `.agents/skills/kast/value-proof/history/` - do not preserve progression data
   - Leave `parity-tests/` in place as a separate Gradle module for backend development (not part of value justification)

The goal is to have one place (`evaluation/`) where anyone can go to understand, run, and extend the evaluation framework for value justification, with clear separation between the evaluation infrastructure and the agent-facing skills.

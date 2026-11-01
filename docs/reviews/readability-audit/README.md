# Documentation readability evidence

The [reader review](reader-review.md) describes the page changes, technical evidence, and remaining limits.
This record compares one frozen corpus before and after the reader-journey edits.
The baseline source revision is `dac99b39a0b7b497d5c0e0d5c2a604f5817e5397`.
A score does not establish technical accuracy or reader comprehension.

## Corpus and baseline

`inventory.json` lists all 308 tracked Markdown and MDX files at the baseline revision.
Each entry names its reader, purpose, category, source digest, and inclusion decision.
`corpus.txt` freezes 105 authored files for the comparison.
Generated pages remain in the inventory with their source owners and exclusion reasons.
The author audits assess their place in the reader journey separately.

The review later identified `experiments/host-observation/agent-payload/evidence.md` as historical evidence.
The comparison keeps that file so a scope change cannot remove its three suggestions.
The report does not rewrite its recorded results.

| Stage | Errors | Warnings | Suggestions | Exit |
| --- | ---: | ---: | ---: | ---: |
| Before: prose | 0 | 0 | 74 | 0 |
| Before: terminology | 2 | 0 | 0 | 1 |
| After: prose | 0 | 0 | 54 | 0 |
| After: terminology | 0 | 0 | 0 | 0 |

`after/` preserves the final raw findings, command outcomes, source hashes, and policy hashes.
`after-generated-introductions/` separately assesses four generated shells with newly authored introductions.
Those four pages have zero findings in either stage.
The frozen comparison does not count these additions as removed baseline findings.

The public pages produced no starter findings before the edits.
Their material problems concern orientation, prerequisites, explanation order, and evidence interpretation.
The separate page audits assess those problems.

`before-prose.json` and `before-terminology.json` preserve every Vale finding with its original file and location.
`before-run.json` records both commands and exit codes.
`compatibility-initial-*` preserves the first run with the existing spelling vocabulary.
That vocabulary incorrectly treated lowercase common words as capitalization rules.
The accepted terminology profile uses a separate product-name vocabulary and the existing glossary alias rules.

## Two independent profiles

The [supplied starter](../../../config/vale/readability/starter-provenance.json) supplies the six unchanged prose rules and their native tests.
Stage A has no vocabulary, spelling rule, or terminology style.
It covers narrow repetition and punctuation defects, sentence length, paragraph density, section length, and reading-grade suggestions.
Section length supplies a structural signal. It does not assess the logical order of a page.

Stage B uses `Kast.Terminology`, generated from `docs/glossary.json`, and the `KastNames` product vocabulary.
It enables `Vale.Terms` and `Vale.Avoid` without spelling or prose rules.
The product vocabulary preserves established names from current repository documentation.
It does not define new domain terms or equate distinct concepts.

Both profiles omit frontmatter metadata, code spans, and code blocks.
Vale's native MDX parser examines component children and omits JavaScript expressions, imports, and attributes.
The compatibility cases exercise these distinctions.

The audit uses Vale 3.25.1.
`mise install vale@3.25.1` downloaded the macOS arm64 release and passed its checksum stage.
The repository and shared prose assessor keep their separate Vale 3.24.0 pin.
This audit does not change their policy or replace their reports.
The installed shared assessor emits schema v1 and accepts `--glossary`, despite the repository description of a newer schema-v2 procedure.
The cache changed from plugin 4.0.4 to 4.0.5 during the audit.
Both runs report the same Vale engine and policy digest.
`pinned-baseline-summary.json` and `pinned-after-summary.json` record that distinction and the complete local report paths.

## Reproduce the comparison

From the repository root, install the audit engine:

```shell
mise install vale@3.25.1
```

Run both profiles and save a new report directory:

```shell
mise exec vale@3.25.1 -- uv run --locked --script config/vale/readability/audit.py \
  --output build/reports/documentation/readability-current
```

The runner reads the explicit corpus and rejects missing, duplicate, unsupported, or external paths before Vale starts.
It strips `VALE_*` overrides and passes `--no-global` to both invocations.
It saves raw output, exit codes, severity counts, source hashes, and policy hashes.
It rejects corpus changes during the run and refuses to overwrite an existing report directory.
Use a new output directory for each run.

To reproduce the baseline, restore the listed files from the recorded source revision in a separate checkout.
Use the same audit configuration and compare their hashes with `inventory.json` before the run.
Do not replace current documentation to reproduce the baseline.

## Compatibility and runner tests

The supplied prose cases passed with coverage: 27 passed, zero failed.
The supplied terminology cases passed with coverage: 10 passed, zero failed.
The Kast terminology cases passed: eight passed, zero failed.
They cover aliases, product case, literal code, frontmatter, native MDX, and separation from prose mechanics.

```shell
mise exec vale@3.25.1 -- vale --no-global \
  --config=config/vale/readability/prose.ini test --coverage \
  config/vale/readability/styles/Prose config/vale/readability/tests/prose

mise exec vale@3.25.1 -- vale --no-global \
  --config=config/vale/readability/starter-compatibility/terminology.ini test --coverage \
  config/vale/readability/starter-compatibility/styles/Terminology \
  config/vale/readability/starter-compatibility/tests/terminology

mise exec vale@3.25.1 -- vale --no-global \
  --config=config/vale/readability/terminology.ini test \
  config/vale/readability/tests/terminology
```

The report runner passed strict Pyright, Ruff, and eight focused pytest cases.
A real two-page Markdown/MDX run passed both profiles with zero findings.
The input cases reject absent files, duplicate paths, an empty corpus, and paths outside the repository.
The output cases reject unknown severity values and malformed report shapes.

```shell
uv run --locked --project docs/tooling ruff format --check \
  config/vale/readability/audit.py config/vale/readability/test_audit.py
uv run --locked --project docs/tooling ruff check --config docs/tooling/pyproject.toml \
  --config 'include=["*.py"]' config/vale/readability/audit.py config/vale/readability/test_audit.py
uv run --locked --project docs/tooling pyright --project config/vale/readability/pyrightconfig.json
uv run --locked --project docs/tooling pytest -q -c config/vale/readability/pytest.ini \
  config/vale/readability/test_audit.py
```

Initial runner development found an import-order error, a redundant type assertion, and a pytest selector that selected unrelated suites.
The corrected commands passed. Those development failures were tool defects, not documentation or product failures.

## Interpretation limits

The native tests establish rule behavior on their fixtures. They do not establish comprehension.
Length and grade findings remain suggestions. The review keeps necessary technical names, exact contracts, and historical qualifications.
The rule-specific review records explain the remaining findings by file and location.
The two terminology corrections use "previously received reply" because those contexts describe captured bytes, not necessarily a live retained result.
No rule was disabled to get a passing result.

The engine behavior follows the official [Vale 3.25.1 release](https://github.com/vale-cli/vale/releases/tag/v3.25.1)
and [native MDX documentation](https://docs.vale.sh/formats/mdx).
The starter's provisional thresholds remain unchanged.

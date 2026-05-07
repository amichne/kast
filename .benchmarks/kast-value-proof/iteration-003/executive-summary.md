# Kast Value Proof: konditional

## Headline metrics

| Metric | with_skill | without_skill | Delta |
| --- | ---: | ---: | ---: |
| Pass rate | 90% | 60% | +0.31 |
| Tokens | 4630 | 10516 | -5887 |
| Tool calls | 0 | 3 | -2 |
| Time | 138.2s | 41.3s | +97.0 |

## Per-category breakdown

| Category | Pass rate | Enterprise value |
| --- | ---: | --- |
| Disambiguation | 88% | correctness -> fewer bugs shipped from symbol mix-ups |
| Completeness | 100% | completeness -> audit confidence and fewer missed call sites |
| Safe Mutations | 90% | validated edits -> fewer broken builds after refactors |
| Token Efficiency | 75% | structural summaries -> lower API cost and faster reviews |
| Multi-Step | 100% | compound workflows -> clearer blast-radius analysis before changes |

## Key findings

- Assertion 'Uses scaffold + references + callers in sequence (not grep)' passed in 1/1 with-skill runs.
- Assertion 'Identifies consumers in at least one other module' passed in 1/1 with-skill runs.
- Assertion 'Shows concrete file-to-file relationships, not just module names' passed in 1/1 with-skill runs.
- Assertion 'Does not miss cross-module references' passed in 1/1 with-skill runs.
- Assertion 'Disambiguates the function using containingType, kind, or fileHint' passed in 1/1 with-skill runs.
- Assertion 'Does not silently pick one of multiple candidates without disambiguation' passed in 1/1 with-skill runs.
- Assertion 'Reports callers specific to the target class, not unrelated resolve() calls' passed in 1/1 with-skill runs.
- Assertion 'Resolves the member with containingType or fileHint before scanning usages' passed in 1/1 with-skill runs.
- Assertion 'Result set is scoped to Feature.key — does not include unrelated types' passed in 1/1 with-skill runs.
- Assertion 'Does not use raw text search (grep/rg) as the primary identity mechanism' passed in 0/1 with-skill runs.
- Assertion 'Reports at least 3 distinct usage sites with file paths' passed in 1/1 with-skill runs.
- Assertion 'Uses kast_write_and_validate (not raw edit/create tool)' passed in 1/1 with-skill runs.
- Assertion 'Runs diagnostics atomically as part of the write' passed in 1/1 with-skill runs.
- Assertion 'Reports clean or dirty compile state after the edit' passed in 1/1 with-skill runs.
- Assertion 'Does not claim success without validation evidence' passed in 1/1 with-skill runs.
- Assertion 'Reports searchScope.exhaustive status or equivalent completeness metadata' passed in 1/1 with-skill runs.
- Assertion 'Lists references grouped by file' passed in 1/1 with-skill runs.
- Assertion 'Does not claim completeness without structural proof from the tool' passed in 1/1 with-skill runs.
- Assertion 'Finds references in at least 2 different modules' passed in 1/1 with-skill runs.
- Assertion 'Resolves the exact function before tracing callers' passed in 1/1 with-skill runs.
- Assertion 'Shows a 2-level call hierarchy' passed in 1/1 with-skill runs.
- Assertion 'Distinguishes test files from production files' passed in 1/1 with-skill runs.
- Assertion 'Reports truncation metadata if the hierarchy was bounded' passed in 1/1 with-skill runs.
- Assertion 'Uses kast_rename (not find-and-replace or sed)' passed in 1/1 with-skill runs.
- Assertion 'Shows an edit plan listing all affected files before applying' passed in 1/1 with-skill runs.
- Assertion 'Updates import statements, not just the declaration' passed in 0/1 with-skill runs.
- Assertion 'Runs diagnostics or reports compile status after the rename' passed in 1/1 with-skill runs.
- Assertion 'Does not leave broken references in any module' passed in 1/1 with-skill runs.
- Assertion 'Uses kast_scaffold (not raw file read) as the primary information source' passed in 1/1 with-skill runs.
- Assertion 'Lists all nested sealed interfaces and enums accurately' passed in 1/1 with-skill runs.
- Assertion 'Does not hallucinate members that don't exist' passed in 1/1 with-skill runs.
- Assertion 'Produces the summary in fewer tokens than reading the raw file would require' passed in 1/1 with-skill runs.
- Assertion 'Uses semantic resolution (not grep for 'class.*Konstrained') to find implementations' passed in 1/1 with-skill runs.
- Assertion 'Lists all sealed subtypes with their file paths' passed in 1/1 with-skill runs.
- Assertion 'Correctly identifies which module each implementation lives in' passed in 1/1 with-skill runs.
- Assertion 'Does not miss implementations in other modules' passed in 1/1 with-skill runs.
- Assertion 'Uses kast_workspace_files (not recursive ls/find)' passed in 1/1 with-skill runs.
- Assertion 'Reports the correct module names' passed in 1/1 with-skill runs.
- Assertion 'Reports file counts for each module' passed in 0/1 with-skill runs.
- Assertion 'Completes in a single tool call (not iterative directory traversal)' passed in 0/1 with-skill runs.

## What this means

- **Disambiguation**: correctness -> fewer bugs shipped from symbol mix-ups.
- **Completeness**: completeness -> audit confidence and fewer missed call sites.
- **Safe Mutations**: validated edits -> fewer broken builds after refactors.
- **Token Efficiency**: structural summaries -> lower API cost and faster reviews.
- **Multi-Step**: compound workflows -> clearer blast-radius analysis before changes.

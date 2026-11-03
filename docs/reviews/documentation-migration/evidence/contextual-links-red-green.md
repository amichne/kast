# Generated contextual links

The source baseline is `7de04069e2a4a781a603ff51b3f9016b66108790`.
The same focused assertions compare the missing links before and after the generator changes.

| Command | Outcome | Decisive result |
| --- | --- | --- |
| `mise exec -- uv run --locked --project docs/tooling pytest docs/tooling/test_glossary.py -k public_glossary_keeps_canonical_definitions_and_sources` | Failed before the change | The assertion for `[How to understand a Kast answer](/explanation)` failed at line 999. |
| `mise exec -- ./gradlew :cli:test --tests 'io.github.amichne.kast.cli.MintlifyCallableReferenceTest' --console=plain` | Failed before the change | Seven tests ran. The missing `[Schemas and samples](/reference/schemas)` link failed at line 122. |
| The same glossary selector after regeneration | Passed | One selected test passed. Canonical definitions and source URLs remain in the projection. |
| `mise exec -- ./gradlew :cli:test --tests 'io.github.amichne.kast.cli.MintlifyCallableReferenceTest' :cli:generateMintlifyCallableReference --console=plain` | Passed | The focused tests and generated artifact completed. `callable-links-green.txt` preserves the command output. |

The original failures were assertion failures for the absent edges. They were not runner or dependency failures.
The callable tests also compare request and response schemas with their canonical input types.
These results establish generated navigation and schema preservation. They do not establish rendered layout or native task execution.

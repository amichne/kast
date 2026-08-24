# CLI projection test guide

This directory owns exact output-contract tests for the typed CLI projectors.

## Test invariants

- Construct projector inputs from refined protocol values and closed `OperationOutcome` variants.
- Compare the complete emitted JSON document, including discriminators, field order, explicit
  unavailable values, qualifications, and operation-specific rejection evidence.
- Keep topology coverage evidence complete and deterministically ordered. Include delimiter-shaped
  text in ordering regressions so concatenated sort keys cannot pass.
- Do not use maps or `JsonElement` trees to bypass the generated serialization path under test.

## Focused verification

Run `./gradlew :cli:test --tests '*GeneratedCliProjectionTest' --tests
'*TopologyBuildCliProjectorTest'`.

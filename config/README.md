# Kotlin quality baselines

Module `check` runs Spotless, Detekt, and Kotlin file-length checks. Existing
structural findings are recorded so the gates can reject new debt immediately.
Formatting has no baseline.

`detekt/baselines/<module>/baseline.xml` records native Detekt finding identities.
The `baseline-main.xml` and `baseline-test.xml` siblings record type-resolved
source-set findings and take precedence for their corresponding tasks. The
generic baseline covers the separate `detekt` task. New finding identities fail
the gate; Detekt does not measure growth inside an already recorded finding.
Remove entries as their findings are resolved. Generating baselines with
`detektBaseline`, `detektBaselineMain`, or `detektBaselineTest` is an explicit
debt-policy change, never a routine formatting or CI step.

`kotlin/file-length-baseline.tsv` records repository-relative Kotlin paths and
their existing line counts, separated by one tab. Blank lines and `#` comments
are allowed. Paths must be normalized and unique; limits must be canonical
positive integers. Invalid entries fail the gate.

Production files retain a default limit of 400 lines and test files 600 lines.
Each recorded oversized file has its current size as a fixed ceiling: growth
fails, and unlisted files receive only the default limit. Lower a recorded
ceiling when reducing a file; remove its entry once it fits the default. The
check does not rewrite baselines automatically. Do not regenerate or raise
ceilings to accommodate new violations.

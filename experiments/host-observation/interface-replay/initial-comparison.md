# Initial interface comparison

The first scope remains incomplete. The offline harness and its scoped tests pass.
A3 fails the production-encoding equality proof. Expired provider credentials leave A9 `NOT_RUN`.
No live-model result selects a format.

## Acceptance

| Criterion | Status | Evidence or unmet condition |
| --- | --- | --- |
| A1 | PASS | Committed source, artifact digests, SDK dependencies, model settings, and fixture identities appear in `initial-screen.json`. |
| A2 | PASS | Six independent graders accept positive controls and reject target, scope, reference, action, argument, and coverage mutations. |
| A3 | FAIL | `JToon` decodes nested empty arrays as strings. C1 through C4 fail the guarded equality proof. |
| A4 | PASS | Real restricted Bash commands cross the fixture driver and production Kotlin admission. Raw effects remain recorded. |
| A5 | PASS | Replay composes no Host, MCP connection, or native semantic executor. |
| A6 | PASS | Deterministic pool tests cover eight workers, isolation, caps, cancellation, and complete records. Existing owned-worker tests cover cleanup. |
| A7 | PASS, offline | All 24 complete inputs survive real SDK wrapping and byte limits. Synthetic transport proves exact serialized delivery once. |
| A8 | PASS | Every trial has a receipt. Every exclusion has a reason. The complete effect ledger records zero actual sends. |
| A9 | NOT_RUN | The configured `OAuth` credential expired. No refresh or provider substitution occurred. |
| A10 | PASS | Focused tests, the host-observation gate, JSON contracts, knowledge checks, graph checks, and scoped prose checks pass. |

The A7 evidence uses synthetic transport. It supplies no live-provider or model-behavior evidence.
Eight pairs fail encoding equality before either arm can send.
The four remaining pairs stop before inference because of expired credentials.
All twelve planned pairs lack measured model outcomes.
The report lists all 24 invalid arms separately.

## Comparison

| Measure | TOON | JSON |
| --- | --- | --- |
| Development cases | 6 | 6 |
| Planned trials | 12 | 12 |
| Actual provider sends | 0 | 0 |
| Eligible model outcomes | 0 | 0 |
| Decision correctness | unavailable | unavailable |
| Provider tokens | unavailable | unavailable |
| Paired model latency | unavailable | unavailable |
| Measured misuse | unavailable | unavailable |

The result is `INCONCLUSIVE`.
No correctness percentage, token reduction, or speed claim follows from this run.
The two repetitions do not create more independent tasks.
Zero sends establish zero provider work. They do not supply provider usage measurements.

## Identities and records

The tested implementation commit is `9b587586704b348a6310190d2e258f48e95df3b5`.
Its working tree was clean when the final plan was prepared.
The empty-diff digest is `4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945`.
The refreshed base matches the specification: `0decbfa874a21fc70592992bc12eaa6939980af2`.

The manifest digest is `3173b89379cdd1bf975c55c5ba6727be569f8bdc724479ec627351e44ce25651`.
The aggregate artifact digest is `278755cf6cc25ba14b6ae631faec93418fb8deeccd7a50b4566ca74c9b0dcba9`.
The model remains `openai-codex/gpt-6.1-sol`, high effort, through Pi 1.4.0.
The plan pins the SDK files and dependencies with separate digests.

- `initial-screen.json` contains the declared schedule and identities.
- `initial-input-proof.json` contains complete projections and equality outcomes for all arms.
- `initial-trials.jsonl` contains one machine-readable receipt per trial.
- `initial-comparison.json` contains the aggregate ledger, exclusions, and missing pairs.
- `source-assumptions.json` records the source comparison and current process guidance.

Private local records reside in `build/interface-replay-committed-plan.json.run/`.
The exact executable plan resides in `build/interface-replay-committed-plan.json`.
Earlier local runs remain intact. An artifact update invalidated one plan before execution.
The final run uses the stable manifest and committed source.
Every local run records zero live provider sends.

## Executed checks

| Command | Status | Result |
| --- | --- | --- |
| `node --test experiments/host-observation/pi_evaluation*.test.mjs` | PASS | 227 passed and 9 optional cases skipped. |
| `KAST_REPLAY_ADAPTER_MANIFEST="$PWD/cli/build/replay-adapter/manifest.json" KAST_REPLAY_TEST_PLAN="$PWD/build/interface-replay-plan.json" node --test experiments/host-observation/pi_evaluation_replay{_adapter,_sdk}.test.mjs` | PASS | All six explicit integration tests passed with no skips. |
| `mise exec -- ./gradlew :cli:test --tests 'io.github.amichne.kast.cli.publicshell.*' :distribution:output:test verifyJsonContracts verifyVerificationGraph knowledgeImpact verifyKnowledgeBase` | PASS | Public CLI and renderer cases passed. JSON contracts and knowledge citations report zero issues. |
| `mise exec -- ./gradlew hostObservationTest verifyDocumentationGlossary verifyScopedProse -PkastProsePaths=experiments/host-observation/interface-replay/README.md` | PASS | All 332 host-observation tests passed. Scoped prose has no error findings. |
| `mise exec -- uv run --locked --script .github/scripts/ci/fast_checks.py` | PASS | CI decisions and 23 portable suites passed. |
| `git diff --check` | PASS | The authored changes contain no whitespace errors. |
| Live inference and native qualification | NOT_RUN | Expired credentials block inference. This scope excludes native runs. |

The ordinary Node command skips opt-in integration tests. The explicit command runs the six new adapter and SDK tests.
The three other optional SDK checks remain skipped. They do not supply evidence for this implementation.
The synthetic SDK suite includes correct, malformed, incomplete, transport-failed, and real-shell outcomes.
It proves that an automatic second SDK request stops before transport and preserves first-request usage.
The existing fresh and received-result tests still pass.

The first JSON guard exposed manual JSON in new Kotlin tests. Typed fixtures and external negative fixtures resolved those findings.
The first fast gate rejected the changed Gradle fingerprint. Regeneration changed only the CLI build-file digest in the checked graph.
The first prose gate rejected an unmarked technical identifier. Code markup resolved that spelling error.
The reports preserve these failures separately from the final passed checks.
The pinned prose result is `ASSESSED`, with a grade advisory.
Technical names and exact acceptance details keep their necessary precision. Reader comprehension remains `NOT_ASSESSED`.

## Failure reproductions and next scope

The smallest nested-array input is `{"items":[{"connections":[]}]}`.
The production encoder emits `items[1]:` with the list item `connections: []`.
`JToon` decodes that array as the string `"[]"`.
The exact Kotlin regression is `nested empty array codec mismatch invalidates the pair without output repair`.

The unrestricted-integer input is `{"n":1234567890123456789012345678901234567890}`.
`JToon` decodes that integer as a string. The guarded adapter reports `REPLAY_ENCODING_MISMATCH`.
The exact Kotlin regression is `unsupported unrestricted integer decoding invalidates the encoding pair`.
The `Long.MAX_VALUE`, Unicode, escaping, omission, null, and exact-reference corpus still passes its supported-case assertions.

The source fixtures remain unchanged between format arms.
The encoder syntax agrees with the [TOON specification](https://github.com/toon-format/spec/blob/main/SPEC.md#91-inline-primitive-arrays).
The current evidence identifies decoder defects. It does not prove model-visible corruption or poor model behavior.

A later scope can assess a replacement for `JToon`.
Its acceptance corpus must include these regressions, the complete fixtures, exact integers, and production output contracts.
This task implements no library migration or default change.
A valid complete screen still needs an equality proof for all six cases and current access to the configured provider.

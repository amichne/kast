# Hosted review repairs

The hosted review found three gaps in replay enforcement.
The repairs preserve command intent, workspace selection, and blocked tool proposals.
They change no task, canonical result, model setting, or product default.

The test adapter now keeps `EXECUTE` and `PLAN` as distinct operation facts.
It also keeps the raw workspace selection and a finite fixture, foreign, or invalid outcome.
The adapter resolves paths without access to another workspace.
Production admission still owns argument validity.
The independent grader rejects planning as a read and a foreign workspace as the requested scope.
Neither case receives a successful fixture transition.

The worker counts each SDK tool proposal before tool effects.
A second proposal stays blocked and appears in the persistent ledger.
The first invocation can complete, but the final grade records `RESOURCE_MISUSE`.
The worker and report share one pure rule for known resource misuse.
A partial ledger keeps that misuse when the final grade is unavailable.
Aggregate accounting keeps the failed work and its complete provider usage.
The target gate rejects a correct label that contradicts recorded excess proposals.
Missing historical counters remain unavailable.

## Recorded evidence

The [offline audit](hosted-review-regrade.json) rechecks all 96 recorded decisions through the production parser and current grader.
It keeps the original receipts, usage, latency, source identities, and outcomes.
Raw assistant messages and recorded SDK events agree on proposal counts.
Every admitted invocation uses execution mode and the fixture workspace.
All 96 grades remain unchanged. The audit sends zero provider requests.
The campaign stays at 96 actual sends and 90,596 provider tokens.
The 3× target remains `NOT_MET`.

## Controls and checks

These valid commands cannot satisfy C3:

```sh
kast query plan --name OrderService --in services/orders --source-set main
kast query --name OrderService --in services/orders --source-set main --workspace /another-worktree
```

The SDK control supplies two Bash proposals in one synthetic response.
Its first proposal is the correct C6 repair. Its second proposal asks for another declaration.
The result keeps one shell call, one Kast invocation, zero native calls, complete usage, and an unsuccessful grade.
This control supplies no model measurement.

- **PASSED:** 94 public-shell Kotlin tests, including three new controls for command facts.
- **PASSED:** 111 deterministic Node tests. Fourteen optional integrations skip without explicit SDK and adapter variables.
- **PASSED:** All 34 opt-in integration tests, with zero skips.
- **PASSED:** JSON contracts, Kotlin format, file length, and `:cli:detekt` checks.
- **FAILED before repair:** Command-fact, planning-grade, proposal-accounting, and target-contradiction controls.
- **ASSESSED:** Pinned prose reports keep grade-8 advisories for necessary parser, accounting, and source identities. Reader comprehension remains unmeasured.
- **NOT RUN:** More inference, live Host qualification, workflow confirmation, publication, or deployment.

Exact focused commands:

```sh
GRADLE_USER_HOME="$PWD/build/interface-gradle-home" mise exec -- ./gradlew :cli:test --tests 'io.github.amichne.kast.cli.publicshell.*' :cli:detekt :cli:spotlessCheck :cli:checkTestKotlinFileLength :cli:prepareReplayAdapter verifyJsonContracts --console=plain
node --test experiments/host-observation/pi_evaluation_replay*.test.mjs
KAST_REPLAY_TEST_PLAN="$PWD/build/interface-session-test-plan.json" KAST_REPLAY_ADAPTER_MANIFEST="$PWD/cli/build/replay-adapter/manifest.json" node --test experiments/host-observation/pi_evaluation_replay_sdk.test.mjs experiments/host-observation/pi_evaluation_replay_adapter.test.mjs experiments/host-observation/pi_evaluation_replay_adapter_session.test.mjs experiments/host-observation/pi_evaluation_replay_codex_sdk.test.mjs
node build/interface-hosted-review-regrade.mjs
```

The audit and its raw inputs remain in the task checkout's private `build/` directory.
The JSON audit pins its source, grader, adapter, artifacts, and original receipt hashes.
The full pre-push gate and hosted checks must pass for the final commit before merge.

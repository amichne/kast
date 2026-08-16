# Kast IntelliJ substrate handoff

**Prepared:** 2026-08-13  
**Audience:** the next implementation agent working in `/Users/amichne/code/kast`  
**Immediate objective:** finish KIP-035 without weakening its semantic proof, push it to PR #609, then implement KIP-036 and publish the first snapshot that exposes verified add-declaration.

## Start here

The repository is intentionally between atomic delivery nodes. KIP-034 is pushed and green. KIP-035 is implemented locally across several modules but is not committed, pushed, or complete.

Do not discard, reset, clean, or replace the working tree. Preserve every existing change until it has been classified against `.agent/TASK.md` and the KIP-035 diff.

Before editing:

1. Read the repository `AGENTS.md`, every nearer `AGENTS.md` for a touched path, and `.agent/TASK.md`.
2. Run `git status --short`, `git diff --check`, `git rev-parse HEAD`, and `git ls-remote origin refs/heads/codex/intellij-native-kip001`.
3. Confirm local `HEAD`, the remote PR head, and the state described below have not moved.
4. Treat this handoff as a snapshot. Live source, `.agent/TASK.md`, typed Kotlin architecture policy, and current remote state remain authoritative.

## Exact delivery state at handoff

| Surface | State | Evidence |
| --- | --- | --- |
| `origin/main` | `60ca538fd00d6c75c4c40140ec719bc531c9651e` | Original program baseline; PR work is unmerged |
| Local branch | `codex/intellij-native-recovery-20260813` at `c7467075a0db542373f93964f3ec851496002229` | `git rev-parse HEAD` |
| Remote PR branch | `origin/codex/intellij-native-kip001` at the same SHA | `git ls-remote` and PR #609 readback |
| PR #609 | Open; all 14 current checks succeeded; merge state reported `BLOCKED` | Re-read before publication or merge work |
| KIP-033 | Pushed as `1c0319049` | Durable recovery preparation |
| Shape-script fix | Pushed as `a502cd0ba` | Avoids repository-shape pipe deadlock |
| KIP-034 | Pushed as `c7467075a` | Recovery-prepared IntelliJ apply and write-set closure |
| KIP-035 | Local, uncommitted, not pushed | 40 tracked modifications plus 26 untracked files; focused lanes are green; final integration proof is incomplete |
| KIP-036 | Not started | Owns public verified add-declaration cutover and bypass closure |
| Published program snapshot | None for the PR branch | Latest program-baseline snapshot is `snapshot-60ca538fd00d` |

The attached `kast-intellij-first-actionable-tasks.zip` is historical execution material, not the current queue. Its S00–S12 tranche maps to the architecture/read work already represented on the branch through KIP-018. Do not copy one of those task files over the active KIP-035 contract.

## What KIP-035 currently implements locally

### Resulting-generation publication

- `:evidence:sqlite` is active and production-depends on `:index-store`.
- `IndexStoreWorkspaceGenerationPublication` owns the existing atomic source-index publication adapter; the indexer bridge only composes and delegates.
- Publication comparison is a typed `Current | Moved` result. It does not expose a Boolean validation protocol.
- No parallel SQL schema or second generation authority was introduced.

### Compiler-backed verification

- `:change:verify:spi`, `:change:verify:intellij`, and `:change:verify:service` are materialized and active.
- Verification is designed as one scoped `smartReadAction` at the exact published G1 generation.
- The observed identity retains the typed target path and exact UTF-16 PSI source range.
- Diagnostics are bounded to the appended declaration.
- Callable collisions use K2 semantic type equality, not rendered signatures or `toString()` values.
- Outbound preservation is admitted only for proven zero-to-zero cardinality. A nonzero lane fails closed until typed per-reference target identity exists.
- Expected failures are closed typed states; nullable, Boolean, sentinel, exception-message, and string-shape protocols were removed from the new verification path.

### Durable terminal receipt

- Terminal v5 completion consumes the exact `ObservedAddDeclarationVerification` capability. A caller cannot complete verification by enumerating obligation tokens.
- The durable receipt retains the complete `PublishedWorkspaceGeneration` and typed observed identity.
- Reload uses journal-owned typed admission rather than recreating compiler proof.
- A transaction whose rollback or commit disposition is not known returns `CommitOutcomeUnknown`.
- The terminal v5 state does not expose recovery/apply authority, while earlier recovery records remain durable audit evidence.

## Proof already obtained

These checks passed in their owning lanes. They are evidence of the slices, not a substitute for the final combined proof:

```shell
./gradlew :change:verify:spi:test :change:verify:service:test \
  --tests '*AddDeclaration*Verif*' --no-daemon

./gradlew :change:verify:intellij:check --no-daemon

./gradlew :change:journal:sqlite:test :change:verify:spi:test \
  :change:verify:service:test --tests '*AddDeclaration*Verif*' --no-build-cache

./gradlew verifyKastArchitecture generateKastArchitectureProjection \
  --configuration-cache --no-build-cache
```

`git diff --check` passed after the parallel lanes. Touched governed production files were at or below 400 physical lines; several are exactly 399 or 400, so add no lines without reflow or extraction.

IntelliJ MCP was used, but its imported project model does not include the newly split change/evidence modules. Targeted diagnostics either reported files outside content roots or hung. Do not report MCP as green evidence. Pinned Gradle compilation currently provides the reliable compiler proof; use MCP again only after the project model is demonstrably current.

## Remaining KIP-035 work, in order

### 1. Add a real IntelliJ executor test

This is the main missing behavioral proof. `:change:verify:intellij` currently has protocol tests, but no test executes `IntellijAddDeclarationVerificationExecutor` against real PSI/K2 state.

Add the smallest fixture-backed test that:

- runs with the pinned IntelliJ test application and a real Kotlin `KtFile`;
- supplies the exact applied postimage and published generation;
- executes the verifier under its real smart-read boundary;
- proves a positive `KtNamedFunction` observation with typed target path, PSI range, kind, and generation;
- proves at least one semantic negative as a sealed limitation, preferably a compiler diagnostic or collision;
- compares typed identities and result variants only.

Do not assert source fragments, rendered type/signature text, class names, exception messages, diagnostic names/messages, serialized JSON substrings, or `toString()` output as change detection.

While adding this test, reconcile the verifier's runtime admission. The current implementation admits only product `IC` at build `261.25134.95`, while root policy advertises IntelliJ IDEA 2026.2/build 262 and Android Studio 2026.1.2/build 261 as supported hosts. Establish whether the isolated runtime is intentionally pinned independently of the supplying host, then encode and test the actual supported matrix. Do not silently narrow public support.

### 2. Prove the indexer composition boundary

KIP-036 owns the public route. KIP-035 must not cut it over early. It still needs a narrow direct-consumer proof that the indexer can compose the exact publication/environment authorities with the verifier without restoring aggregate backend or mutation authority.

Prefer a test-only composition if that is sufficient to execute the production adapters. Add production wiring only when required for the KIP-035 behavior and still unreachable from the public registry. Do not create a parallel workspace-generation or SQLite authority.

### 3. Resolve the evidence-to-index-store policy edge

The local KIP-035 diff currently admits `EVIDENCE_SQLITE -> INDEX_STORE` through `ModulePolicyValidator.exactLegacyImplementationBridges`. That is a bare validator exception and is not represented in the generated JSON projection.

This conflicts with the attached S02/KIP-004 rule that every temporary legacy-host edge must be exact, retirement-bound, and projected. Decide this from the intended final architecture—not convenience:

- if `:index-store` is a temporary legacy host, represent the edge through the existing typed `LegacyMigrationEdgePolicy` with a named retirement task and executable negative proof;
- if `:index-store` is the intended permanent owner of the atomic source-index transaction, stop calling the edge a legacy exception and model/document the permanent dependency direction explicitly.

Do not leave an unprojected special-case set that allows Kotlin authority and generated policy to disagree.

### 4. Freeze and audit the combined diff

Review every changed production Kotlin file for:

- discarded refinements or reconstructed proof;
- public factories that can mint physical, compiler, journal, or terminal authority from data alone;
- nullable or Boolean validation/control protocols;
- primitive domain fields that cross inward without a boundary type;
- string-, renderer-, enum-name-, or exception-message-based semantic decisions;
- exception paths that can escape after mutation or durable admission;
- broad `Exception` catches around suspend effects that can swallow coroutine cancellation;
- dependency edges that point from contracts toward adapters or restore legacy aggregate authority.

Verify that every changed path is allowed by `.agent/TASK.md`. Preserve unrelated user work and stop if ownership cannot be established.

The original KIP-035 program text asks fault tests to prove `Verified`, `RolledBack`, `Rejected`, and `RecoveryRequired`, while the active KIP-035 task explicitly excludes rollback/recovery execution. Do not expand into rollback silently. Reconcile the acceptance language with the live architecture owner and require every post-apply failure to retain the strongest recovery authority that the active scope can honestly prove.

### 5. Run the exact final Green Proof

Read `.agent/TASK.md` immediately before verification, then run its command without weakening or splitting the final acceptance claim:

```shell
./gradlew :change:verify:spi:test :change:verify:intellij:test \
  :change:verify:service:test :indexer:test --tests '*AddDeclaration*Verif*' && \
./gradlew :change:contract:check :change:journal:contract:check \
  :change:journal:sqlite:check :change:recovery:contract:check \
  :change:recovery:service:check :change:apply:spi:check \
  :change:apply:intellij:check :change:apply:service:check \
  :change:verify:spi:check :change:verify:intellij:check \
  :change:verify:service:check :workspace:service:check \
  :evidence:sqlite:check :indexer:test && \
./gradlew generateKastArchitectureProjection verifyKastArchitecture \
  --configuration-cache && \
python3 .github/scripts/check-repository-shape.py --root . && \
git diff --check
```

If shared IntelliJ fixtures fail in the widened parallel run, rerun the exact affected target in isolation before diagnosing product behavior. Do not use a green isolated rerun to conceal a deterministic widened failure.

### 6. Record review evidence and publish KIP-035

- Complete the required Kotlin correctness scorecard under the `.agent-turn` path named by the active task.
- Update only `.agent/TASK.md` Execution State.
- Commit the atomic node, with a candidate message such as `feat(change): verify applied add declarations`.
- Push without force to `origin/codex/intellij-native-kip001`.
- Read back the exact remote SHA and PR head.
- Babysit all required checks to terminal success for that exact SHA. A successful push or one green job is not completion.

## KIP-036 and release handoff

After KIP-035 is pushed and exact-head CI is green, replace `.agent/TASK.md` with a new KIP-036 contract derived from the program document. KIP-036 owns:

- the public verified add-declaration route;
- removal or mechanical blocking of legacy/raw bypasses;
- public typed receipt/failure projection;
- end-to-end user reachability through the packaged runtime.

### Snapshot boundaries

The snapshot workflow publishes a full product; it has no per-feature or per-module scope input. Therefore release scope must be stated and verified externally.

- A backfill snapshot at `c7467075a` could honestly prove only the already reachable native-read and plan-only add-declaration scopes. It must not claim KIP-034 apply or KIP-035 verification as user-reachable.
- Do not publish KIP-035 alone as a verified-add-declaration snapshot. Its new behavior remains unreachable until KIP-036.
- Publish the next verified-add-declaration snapshot only after KIP-036 exact-head CI is green.
- Before manual dispatch, require the PR head, remote branch SHA, and selected workflow source SHA to be identical.
- After publication, verify the immutable `snapshot-<sha12>` target, all four platform setup archives and four SHA-256 sidecars, and an isolated installation.
- From the isolated installation, run the public journey—not an internal test helper—and verify `kast up` reaches semantic readiness before exercising add-declaration.

The manual snapshot workflow runs its own validation and does not consume a PR CI run. Do not infer exact-head CI provenance from successful snapshot dispatch alone.

## Stop conditions

Stop and report rather than improvising if:

- the working tree contains changes outside the active task that cannot be attributed safely;
- a real IntelliJ test cannot bind the same G1 publication used by verification;
- semantic success would require string rendering, JSON parsing in core verification, or equal-count inference;
- terminal receipt construction can bypass the compiler-issued observation;
- the publication adapter would require a second database, schema, or generation authority;
- a release can package the code but the declared public function is not reachable.

## Sources of truth

- `AGENTS.md` and nearer module guides
- `.agent/TASK.md`
- `.agents/arch/kast-architecture-firewall-and-mutation-workflow.md`
- `build-logic/src/main/kotlin/support/architecture/KastPlatformModules.kt`
- `gradle/architecture/kast-architecture-policy.json` as generated projection only
- `.github/workflows/snapshot.yml`
- PR #609 and exact remote SHA readback
- `kast-intellij-substrate-program-2026-08-13.html` for the updated program view

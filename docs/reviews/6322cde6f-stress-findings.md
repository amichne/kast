# Stress findings at 6322cde6f

Tested source: `6322cde6fe6859f6df04d599f8a32ac7f617afdf`, checked out detached on 2026-10-03. The original clean detached HEAD was `f58c2277bdce9eb2b9aaf2abf795d31c5fb0cf20`; the remote was fetched before switching. The exact baseline control and host were assembled and installed before making the separate build configuration fix described below. The initial build fix and baseline catalog were committed and pushed as `fa9b751e5`. The implementation and live follow-up are recorded below.

The bounded checks passed, including native compiler-backed searches, 150-path finding drains at grants 1, 37 and 100, seven linked-path expansions, concurrent searches and typed failure probes. IDEA was killed through the shell and restarted, as requested. No finding identity or ordering regression was observed. The investigation remains qualified because it exceeded checkpoint capacity and encountered unmodeled flow. These checks do not establish native allocation improvement, a baseline latency comparison or release qualification.

## Findings

### F1 — Public small-page requests still construct and encode a larger window

Status: fixed in `189808732`; production-budget regression and installed drain/expansion checks passed. Native allocation and latency improvement remain unmeasured.

At the tested baseline, [RetainedQueryPresentation](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/RetainedQueryPresentation.kt) selects `minOf(cursor + 100, sectionCount)` for finding reads. The `READ_RESULT` branch in [CanonicalQueryProtocol](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt) does not pass the request's admitted execution budget into retained presentation. [HostedQueryResponse](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryResponse.kt) encodes the original page before checking the result grant and fitting a smaller prefix.

Consequently, a public `READ_RESULT` for `FINDINGS` with `executionBudget.maxResults: 1` can construct up to 100 findings before fitting its returned page. The fix bounds construction by the internal presentation window, rather than the caller's smaller result grant. Its factory-only one-row allocation measurement must not be presented as allocation for that public request.

Native check: a retained 150-path investigation drained at grants 1, 37 and 100 with identical finding values, original ordinals, linked row IDs and unresolved qualification. Seven linked paths expanded correctly across the 100-row boundary. Byte fitting reduced the larger grants to five public calls each; grant 1 required 150 calls. Timings are observations, not a controlled comparison.

Implemented follow-up: retained presentation receives the admitted result grant and constructs at most `min(100, maxResults, remaining)` entries. Original section counts, ordinals, linked row IDs and closure survive. Result-limit qualifications remain explicit, and hosted byte fitting still applies. The new production protocol test failed against the old implementation (`[2, 1]` items instead of `[1, 1]`) and passed after the fix. The separate full-path-order equality check in `originalWitnessRowIds` remains outside the factory allocation measurement.

### F2 — Native assembly inherits a shell JDK without Native Image

Status: reproduced and fixed locally; native compilation and required guards passed.

[README](../../README.md) and [development guide](../development.md) previously said Java 25 or newer without naming the native prerequisite. With Temurin `25.0.2+10-LTS`, assembling the exact baseline control failed at `:distribution:cli:nativeCompile`: `native-image` was missing and `gu` was unavailable. Switching the shell to Oracle GraalVM 25 completed baseline assembly.

The local fix sets a Java 25 `nativeImageCapable` launcher for all management CLI native binaries in [distribution/cli/build.gradle.kts](../../distribution/cli/build.gradle.kts). [gradle.properties](../../gradle.properties) registers `GRAALVM_HOME` as a Gradle discovery input. Native compilation can therefore select its toolchain independently of the shell JDK and use the existing Foojay resolver when no matching installation is detected. This follows the [Gradle Native Image toolchain contract](https://docs.gradle.org/current/userguide/toolchains.html#sec:selecting_toolchains_native_image) and [Native Build Tools launcher selection](https://graalvm.github.io/native-build-tools/latest/gradle-plugin).

Verified from a Temurin shell: native compilation succeeded with `GRAALVM_HOME` unset and an explicit Gradle installation path, then succeeded with the new `GRAALVM_HOME` discovery input. Both used Oracle GraalVM `25.0.2`. The final control archive layout and component-release guards also passed under separate local version `0.20261003.6322-buildfix`; that artifact was not installed. Automatic download was not exercised. An older auto-detected SDKMAN GraalVM `25+37` rejected the reachability metadata schema; discovery alone does not upgrade an existing matching installation. The development guide now explains selecting a current patch or supplying its path. Ordinary JVM checks retain their general Java prerequisite.

Logs: `/tmp/kast-6322-stress-build.log`, `/tmp/kast-6322-stress-build-graal.log`, `/tmp/kast-6322-stress-native-toolchain-fix.log`, `/tmp/kast-6322-stress-native-toolchain-path.log`, `/tmp/kast-6322-stress-native-toolchain-env.log`.

### F3 — Dense impact reaches the default checkpoint capacity

Status: baseline checkpoint stops reproduced; removed for this 150-branch workload in the installed follow-up. Larger workloads remain bounded and may still reach capacity.

The fixture's single producer feeds 150 `display(value)` calls. The investigation retained all 150 original paths, 25 observations and five read rejections, but required native-flow and execution-boundary obligations stayed unresolved. Expanded checkpoint stops report `requiredBytes: 8487400` against `availableBytes: 8388608`. Increasing a public result grant does not enlarge that checkpoint budget. The public result preserved `checkpoint-capacity-exceeded`, selected-subset qualifications and the original closure through every drain and expansion.

Implemented follow-up: stable authority and declaration identities retain their immutable proof objects; native reads reuse the admitted enclosing endpoint, and fully equal callable proofs share physical storage. Equality includes authority, scope, constraints and compiler evidence. Independent invocation ranges, paths, branches, observations and obligations remain distinct. The existing graph still charges physically distinct equal objects separately with its unchanged conservative factor. The dense test measured a ledger charge of 28,871,104 bytes; the checkpoint default is now 32 MiB. Small-cap rejection remains covered. No native-flow or semantic model was widened.

## Setup observations and recovery

- Direct `cli/build/install/kast/bin/kast-tool-rpc catalog` succeeded, but calls from that incomplete Gradle layout returned `OBSERVATION_UNAVAILABLE`. The required `share/kast/one-shot-observation-v1` marker is supplied by the assembled control product. Repeating the probes through `build/control-product/bin/kast-tool-rpc` returned the expected failures in all cases. This is a packaging/readiness distinction, not evidence that invalid requests are admitted.
- The initial staged health check rejected with `HOST_UNAVAILABLE`; preparation recorded `OPENING`, then `SELECTED_IDE_UNAVAILABLE`. After installing the exact artifacts and restarting IDEA, the first actual declaration query automatically opened and imported the owned Kotlin fixture (`OPENING` → `IMPORTING` → `ADMISSION` → `COMPLETED`, about 17.9 seconds). Subsequent health reported ready/indexed. A cold health observation alone did not predict the semantic query's outcome.
- The ordinary control installation was replaced from `0.1.0` with `0.20261003.6322` using the regular installer, preserving registration and workspace state. Only the verified IDEA executable was signaled through the shell; it exited after SIGTERM. IDEA `2026.2.3`, build `262.10968.63`, was then restarted. Live `DESCRIBE` independently reported hosted plugin `0.20261003.6322`, host `bcbe149a-1c3b-46e8-bb4f-7b427f772fce`, PID 17720, exact fixture root and native Kotlin stub index authority. The host release record binds the artifacts to the full source SHA.
- An older retained result later returned `execution-rejected/result-unavailable` with `next_action: restart_read`. A fresh investigation restored the drains and expansions. The source's default retained-state TTL is ten minutes; unavailable handles must be reacquired rather than reused. The recovery check did not manufacture a clock transition or claim the precise eviction cause.
- Temporary expansion assertions initially assumed `rowId` instead of `row_id` and compared full terminals directly with compact finding terminals. Corrected checks assert original row identity, producer, destination and variant-specific obligations independently. Those harness errors are retained in local receipts and are not product findings.
- Supplied thread instructions referred to retired session installations and the previous `knowledge/` tree. This checkout's authored instructions use persistent installation and `openwiki/`. The mismatch is outside the tested production change.

## Implemented follow-up and live qualification

Production fixes: `189808732fbbcbf4140c90cc1cb1c011f9364d26` and
`78e84e776e433d8b3b89c88f5ed3cab5893ab15c`.
Installed candidate: `0.20261003.6324`; its host release record binds the artifacts
to the second commit. Both candidate testing rounds killed the verified IDEA
process through shell SIGTERM and restarted the application. Final live `DESCRIBE`
reported plugin `0.20261003.6324`, IDEA build `262.10968.63`, PID 31134,
host `b48e034f-085b-4f63-a68c-5c980ec2851a` and the exact fixture root.

### F4 — Retention scaled an already conservative checkpoint charge again

Status: discovered while qualifying F3; fixed in `78e84e776` and verified natively.

The first follow-up candidate completed the dense native investigation but returned
`retention.kind: capacity_exceeded`. Its complete ledger had 302 observations and
no read rejections. A 100-result producer page still retains a checkpoint for its
remaining output. `QueryRetainedEvidence` multiplied that checkpoint's already
conservative byte charge again, then added the original ledger separately.

The service regression with a 100-result grant measured a 30,376,960-byte
checkpoint, a 271,895,416-byte retained-result charge and a 302,272,376-byte paired
reservation before the fix. It now proves that capture adds the checkpoint's
existing charge once, preserves that exact checkpoint object, and retains all 150
original paths within the configured total budget. The total query retention
default is 128 MiB, covering independently charged checkpoints, retained results
and fitted output pages; the checkpoint cap is 32 MiB. Capacity rejection and
configuration cross-bounds remain enforced. These are conservative reservations,
not measured JVM heap use. The larger default permits more retained memory per
workspace; operators can retain smaller limits and receive typed capacity outcomes.

| Follow-up boundary | Verified result |
| --- | --- |
| Affected module checks | 482 cases across query protocol/service/contract, relation contract/IntelliJ, workspace contract and kernel; zero failures, errors or skips. Focused RED/GREEN cases cover admitted one-finding construction and paired checkpoint/result retention. |
| Required guards | JSON contracts, configuration ingress, architecture and knowledge validation passed. Knowledge impact identified source-bound concepts; generated OpenWiki pages were left for the scheduled refresh. |
| Exact candidate assembly and install | Control and host assembled with Oracle GraalVM 25.0.2; regular persistent installer succeeded. Live `DESCRIBE` independently confirmed the candidate host after restart. |
| Dense native investigation | Retained all 150 original paths and 302 observations, with zero read rejections or checkpoint stops. Original closure is `UNRESOLVED[NATIVE_FLOW]`; all 150 findings carry `UNRESOLVED_FLOW/UNMODELED_CALL`. |
| Public findings and links | Grants 100, 37 and 1 drained all 150 findings in 6, 6 and 150 calls. Full finding values, original ordinals, row IDs and closure matched. Seven linked-path expansions passed at 0, 24, 25, 74, 99, 100 and 149. Byte fitting still bounds larger pages. |
| Rejections and concurrency | Past-end cursor rejected with `correct_request`; unknown and previous-host results rejected with `restart_read`. One earlier concurrency-3 round returned typed `BUSY` at `REQUEST_ADMISSION` with `wait_for_capacity`, consistent with two configured read slots. A subsequent round completed all six root-scoped searches at concurrency 3. No automatic retry behavior is claimed. |

The final native receipts are under `build/stress-6322/final-native/`: the impact
reply, host description, drain/expansion summary, findings, rejection summary and
concurrency summary. Original baseline assets remain in `assets/`; the intermediate
candidate remains in `candidate-assets/`; final assets and checksum sidecars are in
`final-assets/`. Native heap, allocation improvement and controlled latency
comparison remain unmeasured. Draining findings does not discharge unmodeled flow.

Final candidate checks:

```sh
./gradlew --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g \
  :query:protocol:check :query:service:check :query:contract:check \
  :relation:contract:check :relation:intellij:check :workspace:contract:check :kernel:check \
  verifyJsonContracts verifyKastArchitecture verifyConfigurationIngress \
  knowledgeImpact verifyKnowledgeBase
build/python-tests/env/bin/python3 build/stress-6322/final_probe.py
build/python-tests/env/bin/python3 build/stress-6322/final_drain.py
build/python-tests/env/bin/python3 build/stress-6322/final_extra.py
```

Logs: `/tmp/kast-6322-fixes-guards.log`, `/tmp/kast-6322-retention-guards.log`,
`/tmp/kast-6322-paired-retention-red.log`, `/tmp/kast-6322-paired-retention-green.log`,
`/tmp/kast-6322-final-candidate-build.log`, `/tmp/kast-6322-final-install.log`,
`/tmp/kast-6322-final-probe.log`, `/tmp/kast-6322-final-drain.log`,
`/tmp/kast-6322-final-extra.log`.

| Final artifact | SHA-256 |
| --- | --- |
| Control `kast-control-v0.20261003.6324-macos-aarch64.tar.gz` | `5ce83e86c5fb549a1fa956f59f1185ebf2a50ab93e93e3c0c915a62c6a6f7aae` |
| Host `kast-ide-hosted-v0.20261003.6324-idea-262.zip` | `e1be4f04580488fa6e3854206fbe23e4e43c0ae3db86d2bae1772ca4afbf1a67` |

## Rebase onto main and combined-source qualification

After PR #916 merged, the four follow-up commits were rebased onto
`005cb5eb54e3b83599c547ad96150a44ae27e7c6`. The former tip remains at local
`recovery/6322-stress-before-main-1938f1b5a`. `git range-diff` reported identical
patches for all four commits; only these follow-up patches are included above main.
The rebased source tested here is `822f64618100c2b87f1f4f9b0a5a1b3df184da89`.

Focused finding/witness paging, dense storage admission, callable proof identity,
authority identity and configuration-bound tests passed. Native control and host
candidate `0.20261003.6325` were assembled from that exact source and installed with
the regular installer. Verified IDEA PID 31134 exited after shell SIGTERM; IDEA was
restarted before testing. Live `DESCRIBE` confirmed candidate `0.20261003.6325`,
PID 40770, host `48a70459-06b1-4279-8c47-71a7a86f3df8` and the exact fixture root.

The combined source again retained 150 original paths and 302 observations with
zero read rejections or checkpoint stops. Grants 100, 37 and 1 drained identical
findings, and all seven linked-path expansions matched. All 150 terminals remain
`UNRESOLVED_FLOW/UNMODELED_CALL`, preserving `UNRESOLVED[NATIVE_FLOW]` closure.
Past-end, unknown-result and previous-host-result rejections passed, followed by
six complete searches at concurrency 3. Receipts are in
`build/stress-6322/rebased-native/`, with artifacts in `rebased-assets/`.
The earlier qualification sections retain their original source and artifact identities.

| Rebased artifact | SHA-256 |
| --- | --- |
| Control `kast-control-v0.20261003.6325-macos-aarch64.tar.gz` | `07c4ab40a49c439012a64adde47a56c9a15c7298c3c64a1723f9772631ad0773` |
| Host `kast-ide-hosted-v0.20261003.6325-idea-262.zip` | `cd068dbff9a2af461fd52c87af9a4434d4ca34fd036d145ec776ce121cee7b9e` |

Logs: `/tmp/kast-6322-rebase-focused.log`, `/tmp/kast-6322-rebased-candidate-build.log`,
`/tmp/kast-6322-rebased-install.log`, `/tmp/kast-6322-rebased-probe.log`,
`/tmp/kast-6322-rebased-drain.log`, `/tmp/kast-6322-rebased-extra.log`.

## Executed baseline checks

| Boundary | Result |
| --- | --- |
| Actual JVM factory allocation test | 1 case, zero failures/errors/skips; 16-path and 1,000-path one-row pages both allocated 408 bytes; empty page 216 bytes; 100-row page 15,472 bytes. JVM Temurin `25.0.2+10-LTS`. |
| Query contract behavior | 52 cases, zero failures/errors/skips; includes full 1,000-path drains at sizes 1, 37 and 100, invalid ranges, immutable entries and exact path/ordinal preservation. Fresh execution after an initial cache restoration. |
| Finding query-protocol consumers | 6 cases, zero failures/errors/skips; retained links, expansion without semantic replay, qualification and encoded shape. Fresh execution. |
| Finding public-contract consumers | 8 cases, zero failures/errors/skips. Fresh execution. |
| CLI witness-schema consumers | 3 cases, zero failures/errors/skips. Fresh execution after an initial cache restoration. |
| Actual staged Tool RPC input boundary | 21 probes, concurrency 3; all expected typed rejections. Includes all 16 shipped invalid examples, malformed JSON, array root, unknown tool, empty object and input beyond 1 MiB. No semantic success is inferred. |
| Independent schema validation | All 21 shipped valid query examples accepted; all 16 invalid examples rejected; the Tool RPC guide's example accepted. Repository-provisioned `jsonschema` 4.26.0. |
| Exact assembled artifacts and installer staging | Existing `packaging/run-installed-product.py` passed artifact identity, launcher admission and canonical persistent staging in an owned HOME with its controlled IDEA fixture. This is not a live IDEA install/import or semantic test. |
| Actual staged native management executable | `--help` and `--version` succeeded; version `0.20261003.6322`. |
| Disposable genuine Kotlin fixture | Compiled with Gradle and Kotlin 2.4.10, then opened/imported automatically by the actual installed product in IDEA. Existing representation-impact fixture plus a producer with 150 `display(value)` calls. |
| Live native search | `Voltage`, `encrypt`, `denseImpact` and `investigate` returned complete compiler-backed declarations in the exact fixture. Six further calls at concurrency 3 returned complete, root-scoped results. |
| Live findings and linked paths | Two independent investigations each drained 150 original findings at grants 100, 37 and 1 (5, 5 and 150 calls). Within each retained investigation, full finding values, ordinal order, unique original path row IDs and unresolved original closure matched across grants. The second run also passed seven expansions at ordinals 0, 24, 25, 74, 99, 100 and 149. No identity equality across separate retained investigations is claimed. |
| Live rejection and recovery | Cursor 151 rejected as `result-cursor-out-of-range` with `correct_request`; unknown and older unavailable results rejected as `result-unavailable` with `restart_read`; negative cursor rejected at public schema admission. Fresh investigation and drain succeeded. |
| Build fix | Native compilation from a Temurin shell using current GraalVM through explicit path and environment discovery; final control archive layout and component-release guards also passed. 136 distribution CLI cases, zero failures/errors/skips; module formatting, architecture, JSON contracts and knowledge guards passed. Knowledge impact identified two concepts; their architecture and operation-outcome claims did not change. |

Commands were run from the tested worktree:

```sh
./gradlew --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g \
  :query:contract:test -PincludeTags=performance --tests '*QueryImpactFindingWorkTest'
./gradlew --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g \
  :query:contract:test :query:protocol:test --tests '*ImpactFinding*' \
  :protocol:contract:test --tests '*ImpactFinding*' \
  :cli:test --tests '*ImpactWitnessSchemaTest'
./gradlew --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g \
  :query:contract:test --rerun :cli:test --rerun --tests '*ImpactWitnessSchemaTest'
JAVA_HOME=/Users/amichne/.local/share/mise/installs/java/oracle-graalvm-25.0.2 \
GRAALVM_HOME=/Users/amichne/.local/share/mise/installs/java/oracle-graalvm-25.0.2 \
./gradlew --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g \
  -PcontrolVersion=0.20261003.6322 -PhostedPluginVersion=0.20261003.6322 \
  -PhostedIdeaHome='/Users/amichne/Applications/IntelliJ IDEA.app/Contents' \
  assembleKastControlDist generateHostReleaseRecord
./gradlew --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g preparePythonTestEnvironment
printf '%s\n' '{}' | build/control-product/bin/kast-tool-rpc call health_check
KAST_VERSION=0.20261003.6322 KAST_HOST_VERSION=0.20261003.6322 \
KAST_INSTALL_ASSETS_DIRECTORY="$PWD/build/stress-6322/assets" \
bash install.sh --idea-home '/Users/amichne/Applications/IntelliJ IDEA.app' \
  --skip-codex-mcp --verbose
build/python-tests/env/bin/python3 build/stress-6322/drain_findings.py
env -u GRAALVM_HOME \
JAVA_HOME=/Users/amichne/.local/share/mise/installs/java/temurin-25.0.2+10.0.LTS \
./gradlew --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g \
  -Porg.gradle.java.installations.paths=/Users/amichne/.local/share/mise/installs/java/oracle-graalvm-25.0.2 \
  :distribution:cli:nativeCompile
JAVA_HOME=/Users/amichne/.local/share/mise/installs/java/temurin-25.0.2+10.0.LTS \
GRAALVM_HOME=/Users/amichne/.local/share/mise/installs/java/oracle-graalvm-25.0.2 \
./gradlew --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g \
  :distribution:cli:nativeCompile
./gradlew --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g \
  :distribution:cli:test verifyKastArchitecture verifyJsonContracts \
  knowledgeImpact verifyKnowledgeBase
./gradlew --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g \
  :distribution:cli:spotlessCheck
JAVA_HOME=/Users/amichne/.local/share/mise/installs/java/temurin-25.0.2+10.0.LTS \
GRAALVM_HOME=/Users/amichne/.local/share/mise/installs/java/oracle-graalvm-25.0.2 \
./gradlew --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx5g \
  -PcontrolVersion=0.20261003.6322-buildfix \
  verifyKastControlDistLayout componentReleaseTest
```

## Evidence and prepared artifacts

Local machine-readable probe results, catalog, example validation, health responses and artifact digests are under `build/stress-6322/`. `native-host-description.json` records the live host identity. `native-drains/` retains the first investigation, including harness failures; `native-confirmation/` contains the successful full drain/expansion and rejection summaries. `native-concurrency.json` records the concurrency checks. The disposable fixture is `build/stress-6322/fixture`; exact installed baseline assets and checksum sidecars are retained under `build/stress-6322/assets`. Build outputs are local and untracked; this catalog is the durable summary.

Other logs: `/tmp/kast-6322-stress-allocation.log`, `/tmp/kast-6322-stress-consumers.log`, `/tmp/kast-6322-stress-fresh-tests.log`, `/tmp/kast-6322-stress-installed-product.log`, `/tmp/kast-6322-stress-fixture-build.log`. The allocation XML was subsequently replaced by the ordinary contract run; its observed values are recorded above.

Native/install and fix logs: `/tmp/kast-6322-stress-install.log`, `/tmp/kast-6322-stress-native-drains.log`, `/tmp/kast-6322-stress-native-confirmation.log`, `/tmp/kast-6322-stress-fix-guards.log`, `/tmp/kast-6322-stress-fix-format.log`, `/tmp/kast-6322-stress-fix-release.log`.

| Artifact | SHA-256 |
| --- | --- |
| Control `kast-control-v0.20261003.6322-macos-aarch64.tar.gz` | `6c4939a1115dc12941eb4ec39de802e563c1e27cb6b2c54ab5837a0adab73e70` |
| Host `kast-ide-hosted-v0.20261003.6322-idea-262.zip` | `8ca4567c54bb6cd38bd09d7dc3dd52b996ff659dd0bf7b57bba998da152a1b2e` |

The host release record identifies the full tested source SHA. The allocation assertion proves transient allocation in the pure finding factory, not hosted heap, CPU, latency, retention cost or semantic work. No baseline comparison or release qualification was performed. The baseline qualification used exact `6322cde6f` artifacts before the follow-up implementation. The current installed candidate and production changes are identified separately above.

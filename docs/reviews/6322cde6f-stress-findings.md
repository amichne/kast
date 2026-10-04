# Stress findings at 6322cde6f

Tested source: `6322cde6fe6859f6df04d599f8a32ac7f617afdf`, checked out detached on 2026-10-03. The original clean detached HEAD was `f58c2277bdce9eb2b9aaf2abf795d31c5fb0cf20`; the remote was fetched before switching. The exact baseline control and host were assembled and installed before making the separate build configuration fix described below. Changes remain uncommitted.

The bounded checks passed, including native compiler-backed searches, 150-path finding drains at grants 1, 37 and 100, seven linked-path expansions, concurrent searches and typed failure probes. IDEA was killed through the shell and restarted, as requested. No finding identity or ordering regression was observed. The investigation remains qualified because it exceeded checkpoint capacity and encountered unmodeled flow. These checks do not establish native allocation improvement, a baseline latency comparison or release qualification.

## Findings

### F1 — Public small-page requests still construct and encode a larger window

Status: confirmed from production source; native cost unmeasured. This is a remaining performance limitation, not a demonstrated correctness regression introduced by this commit.

[RetainedQueryPresentation](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/RetainedQueryPresentation.kt) selects `minOf(cursor + 100, sectionCount)` for finding reads. The `READ_RESULT` branch in [CanonicalQueryProtocol](../../query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/CanonicalQueryProtocol.kt) does not pass the request's admitted execution budget into retained presentation. [HostedQueryResponse](../../runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedQueryResponse.kt) encodes the original page before checking the result grant and fitting a smaller prefix.

Consequently, a public `READ_RESULT` for `FINDINGS` with `executionBudget.maxResults: 1` can construct up to 100 findings before fitting its returned page. The fix bounds construction by the internal presentation window, rather than the caller's smaller result grant. Its factory-only one-row allocation measurement must not be presented as allocation for that public request.

Native check: a retained 150-path investigation drained at grants 1, 37 and 100 with identical finding values, original ordinals, linked row IDs and unresolved qualification. Seven linked paths expanded correctly across the 100-row boundary. Byte fitting reduced the larger grants to five public calls each; grant 1 required 150 calls. Timings are observations, not a controlled comparison.

Proposed bounded fix: pass the admitted result grant into retained presentation and construct at most `min(100, maxResults, remaining)` finding entries. Preserve the original section count and ordinals, then retain byte fitting for the encoded page. Add a production presentation test proving a one-result grant constructs one finding, plus existing drain/expansion checks. This was not implemented here. The separate full-path-order equality check in `originalWitnessRowIds` also remains outside the factory allocation measurement.

### F2 — Native assembly inherits a shell JDK without Native Image

Status: reproduced and fixed locally; native compilation and required guards passed.

[README](../../README.md) and [development guide](../development.md) previously said Java 25 or newer without naming the native prerequisite. With Temurin `25.0.2+10-LTS`, assembling the exact baseline control failed at `:distribution:cli:nativeCompile`: `native-image` was missing and `gu` was unavailable. Switching the shell to Oracle GraalVM 25 completed baseline assembly.

The local fix sets a Java 25 `nativeImageCapable` launcher for all management CLI native binaries in [distribution/cli/build.gradle.kts](../../distribution/cli/build.gradle.kts). [gradle.properties](../../gradle.properties) registers `GRAALVM_HOME` as a Gradle discovery input. Native compilation can therefore select its toolchain independently of the shell JDK and use the existing Foojay resolver when no matching installation is detected. This follows the [Gradle Native Image toolchain contract](https://docs.gradle.org/current/userguide/toolchains.html#sec:selecting_toolchains_native_image) and [Native Build Tools launcher selection](https://graalvm.github.io/native-build-tools/latest/gradle-plugin).

Verified from a Temurin shell: native compilation succeeded with `GRAALVM_HOME` unset and an explicit Gradle installation path, then succeeded with the new `GRAALVM_HOME` discovery input. Both used Oracle GraalVM `25.0.2`. The final control archive layout and component-release guards also passed under separate local version `0.20261003.6322-buildfix`; that artifact was not installed. Automatic download was not exercised. An older auto-detected SDKMAN GraalVM `25+37` rejected the reachability metadata schema; discovery alone does not upgrade an existing matching installation. The development guide now explains selecting a current patch or supplying its path. Ordinary JVM checks retain their general Java prerequisite.

Logs: `/tmp/kast-6322-stress-build.log`, `/tmp/kast-6322-stress-build-graal.log`, `/tmp/kast-6322-stress-native-toolchain-fix.log`, `/tmp/kast-6322-stress-native-toolchain-path.log`, `/tmp/kast-6322-stress-native-toolchain-env.log`.

### F3 — Dense impact reaches the default checkpoint capacity

Status: observed natively; qualified resource limit, not a finding-page corruption.

The fixture's single producer feeds 150 `display(value)` calls. The investigation retained all 150 original paths, 25 observations and five read rejections, but required native-flow and execution-boundary obligations stayed unresolved. Expanded checkpoint stops report `requiredBytes: 8487400` against `availableBytes: 8388608`. Increasing a public result grant does not enlarge that checkpoint budget. The public result preserved `checkpoint-capacity-exceeded`, selected-subset qualifications and the original closure through every drain and expansion.

Immediate handling: consume the qualified findings and linked path obligations as returned; do not treat a drained presentation as proof of complete semantic flow. For a performance follow-up, measure detached checkpoint storage at its existing owner and determine whether common route prefixes can share immutable storage while preserving independent branch and model evidence. Raising the default cap without that measurement is not established as a general solution. No checkpoint policy or semantic model was changed here.

## Setup observations and recovery

- Direct `cli/build/install/kast/bin/kast-tool-rpc catalog` succeeded, but calls from that incomplete Gradle layout returned `OBSERVATION_UNAVAILABLE`. The required `share/kast/one-shot-observation-v1` marker is supplied by the assembled control product. Repeating the probes through `build/control-product/bin/kast-tool-rpc` returned the expected failures in all cases. This is a packaging/readiness distinction, not evidence that invalid requests are admitted.
- The initial staged health check rejected with `HOST_UNAVAILABLE`; preparation recorded `OPENING`, then `SELECTED_IDE_UNAVAILABLE`. After installing the exact artifacts and restarting IDEA, the first actual declaration query automatically opened and imported the owned Kotlin fixture (`OPENING` → `IMPORTING` → `ADMISSION` → `COMPLETED`, about 17.9 seconds). Subsequent health reported ready/indexed. A cold health observation alone did not predict the semantic query's outcome.
- The ordinary control installation was replaced from `0.1.0` with `0.20261003.6322` using the regular installer, preserving registration and workspace state. Only the verified IDEA executable was signaled through the shell; it exited after SIGTERM. IDEA `2026.2.3`, build `262.10968.63`, was then restarted. Live `DESCRIBE` independently reported hosted plugin `0.20261003.6322`, host `bcbe149a-1c3b-46e8-bb4f-7b427f772fce`, PID 17720, exact fixture root and native Kotlin stub index authority. The host release record binds the artifacts to the full source SHA.
- An older retained result later returned `execution-rejected/result-unavailable` with `next_action: restart_read`. A fresh investigation restored the drains and expansions. The source's default retained-state TTL is ten minutes; unavailable handles must be reacquired rather than reused. The recovery check did not manufacture a clock transition or claim the precise eviction cause.
- Temporary expansion assertions initially assumed `rowId` instead of `row_id` and compared full terminals directly with compact finding terminals. Corrected checks assert original row identity, producer, destination and variant-specific obligations independently. Those harness errors are retained in local receipts and are not product findings.
- Supplied thread instructions referred to retired session installations and the previous `knowledge/` tree. This checkout's authored instructions use persistent installation and `openwiki/`. The mismatch is outside the tested production change.

## Executed checks

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

The host release record identifies the full tested source SHA. The allocation assertion proves transient allocation in the pure finding factory, not hosted heap, CPU, latency, retention cost or semantic work. No baseline comparison or release qualification was performed. The installed baseline remains exact `6322cde6f`; the separate build configuration and prerequisite documentation fix, plus this catalog, are left uncommitted. No semantic production code or repository test was changed.

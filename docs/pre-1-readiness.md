# Pre-1.0 requirement implementation record

Assessment authority: the supplied **Kast 1.0 audit**, which reviewed
`9e4c246c4bae0be9b78fbbe967ceb65c1c6a920c`. Implementation starts from
`8edd1a43012f82a75e27bba6f5cd10b0f7736a06` and preserves its module architecture.
This record separates implemented checks from observed installed acceptance.

The complete local gate passed at
`c4d0fbaaf4fef129d0459079086b6b69de40c25f`. Its receipt is
`build/release/v1.0.0/kast-release-receipt-v1.0.0.json`, SHA-256
`1fda96dc12e9ff78dfec0a67a8d14e3ba9168cfcb8936c6824b013153e48e3fe`.
The detached source graph passed 301 tasks in 2 minutes 12 seconds; exact-archive
acceptance passed all eight required journeys in 389,037 milliseconds. A separate
receipt verification passed. This documentation update records that historical
proof; CI must independently prove each subsequent PR head.

| Requirement | Owner and proof boundary | Current evidence |
| --- | --- | --- |
| 1. Independent semantic CLI | CLI composition and installed journey without Codex | Exact archives passed start, semantic reads, mutation, restart and stop without Codex or broker state |
| 2. Publish after exact-asset proof | `releaseSourceGate`, release receipt, publication admission | Complete exact-head receipt passed; missing, failed, foreign and changed proof reject publication |
| 3. Explicit Gradle import environment/JVM | Distribution admission, cache identity, IntelliJ import | Fresh-home installed matrix passes Gradle 7.6.4 / Java 17, 8.14.3 / 21, 9.4.1 / 25 and finite 7.6.4 / 25 rejection |
| 4. Startup phase and actionable failure | Bootstrap schema, phase publisher, lifecycle/status | Contract, indexer and CLI tests pass; installed rejection identifies JVM selection and import stages |
| 5. 1.x compatibility and migration | Public policy, release compatibility diff, installer journeys | Policy, immutable-baseline check, transactional installer fixtures and published 0.32.2 to exact-archive 1.0.0 upgrade/corruption passed |
| 6. Cold broker budget/cancellation | Canonical execution budgets, broker and process ownership | Metadata budgets and cancellation tests pass; exact installed cold broker invocation passed in 29,628 milliseconds; integration remains read-only preview |
| 7. Derived Codex presentation/replay | Observer projection, canonical evidence, history replay | Source snapshot lines, diagnostics and replay reconstruction tests pass across source, protocol and CLI |

## Refined findings

- The source has advanced since the assessment. PR #667 supplies rich observer
  rendering; it needs replay validation and reconstruction rather than replacement.
- The current production startup timeout is 17 minutes. The installed acceptance
  threshold is a stricter 240 seconds. Operation metadata must preserve the actual
  timeout authority while the installed gate independently enforces performance.
- Uninstall previously scanned machine-wide indexer processes even under an
  isolated `HOME`. `--installation-only` now confines removal to selected paths,
  requires selected workspaces to be stopped, and preserves unrelated processes.
- Installed acceptance now compares tracked and untracked source bytes, modes,
  links and Git index state across uninstall, reinstall and final removal. The
  prior `git diff` comparison could miss untracked deletion and staged changes.
  All six exact-archive observations retained the same 31-file source identity
  and Git index. Rejected observations persist before failure.
- The release installer now admits exact local archive/checksum files with
  `--assets-directory`, retaining normal release URL and manifest identity checks.
- The aggregate graph includes the separate `build-logic` build's `check` task;
  consuming its plugins only compiles them and does not execute their tests.
- Supply-chain checks now include the wrapper checksum, strict dependency
  metadata, full-commit Action pins, archive-derived CycloneDX inventory,
  publication provenance and a security-reporting policy.
- The compatibility policy defines stable, versioned, preview and unsupported
  surfaces. Each candidate captures schema, command graph, server projection,
  finite process exits, configuration keys and persisted codec ownership.
  Same-major incompatible or unproven changes reject; a next-major transition
  requires explicit authority.
- A stable baseline must come from an immutable release with a verified asset
  digest. Before the first stable 1.x release, observed release-catalog evidence
  admits the explicit `pre-stable` or `first-stable` boundary. State-codec source
  fingerprints deliberately reject unproven refactors rather than claim semantic
  equivalence.
- Primary installation instructions pin both installer content and product
  assets to published 0.32.2. Activation failure or HUP, INT or TERM restores
  prior managed links and configuration. SIGKILL and power loss cannot run the
  rollback trap; rerunning a pinned installer reselects a verified version.

## Installed failures found during integration

- The enterprise fixture lacked the repository Gradle wrapper. It now receives
  the exact wrapper files before its clean Git baseline is created. This moved
  the installed run past its initial `gradle-jvm-unavailable` rejection.
- Repeated enterprise runs exposed an intermittent
  `MISSING_DEPENDENCY_SUPERCLASS` diagnostic before mutation. The cause was an
  asynchronous global workspace/JPS SDK synchronization race. Startup now awaits
  synchronization before assigning the bootstrap SDK, with closed synchronized,
  timed-out, unavailable and linkage-invalid outcomes. Bounded baseline and
  post-mutation compiler diagnostics retain evidence of this boundary.
- The Gradle 7.6.4 / Java 17 matrix selected the correct JVM but rejected during
  model import because the injected tooling payload included newer bytecode.
  A separate classified tooling JAR now targets Java 8; the sidecar retains its
  admitted Java 25 JBR. Import diagnostics distinguish incompatible payloads and
  retain bounded class-file-major evidence.
- A rerun with a fresh isolated home also exposed Java `user.home` reaching the host
  account despite the isolated process `HOME`. The indexer launcher now binds
  Java `user.home` to the admitted home. After both fixes, fresh isolated homes
  passed the three admitted Gradle/JDK pairs and the explicit 7.6.4 / Java 25
  rejection listed above. The recorded combined gate repeated all four cases
  successfully against its exact archives.
- A missing Gradle initialization script exposed shared temporary-file ownership
  in the pinned IntelliJ implementation. A controlled two-JVM experiment proved
  identical scripts can be reused and deleted when their creator exits. Every
  bootstrap attempt now owns a canonical private temporary directory. Launcher,
  finite failure-observation, actual import and final matrix checks pass. The
  specific process that deleted the original failed run's script was not observed.
- A real traversal continuation exceeded the CLI's ordinary 4,096-character
  argument limit. Admission now preserves a typed relation or traversal document
  through the parser boundary and derives its bound from canonical protocol text.
  Ordinary arguments retain their existing limits. A rebuilt CLI passed valid
  resume and malformed/digest-tampered rejection for both continuation families.
  The recorded combined gate repeated these checks with matched archives.
- The combined archive journey exposed a cold broker rejection. Its report now
  lives under `build/reports/release-gate/cold-broker.json`, outside the temporary
  host, so cleanup preserves failure evidence. A fresh source attempt deletes
  prior broker reports before admission; earlier success cannot survive a rerun.
- That rejection was `idea-installation-missing`: the fixture's standard IDEA
  `Contents` path was a symlink, which canonical discovery correctly rejected.
  The isolated fixture now creates a real `Contents` directory and links each
  child to its resolved admitted IDEA authority. The unchanged archives passed
  the corrected cold probe in 66,653 milliseconds. This fixes test setup without
  weakening product admission. The recorded combined gate passed this journey
  in 29,628 milliseconds with CLI equivalence and selector reuse established.
- A later matched-archive run reached final uninstall but rejected changed
  repository identity. Its deleted host could not identify the changed item.
  Retained reproduction with the same archives isolated changes to
  `.gradle/9.4.1/fileHashes/fileHashes.bin` and `fileHashes.lock`, with no other
  file or index change. The fixture had no ignore file despite already admitting
  root `.gradle/` writes. It now tracks only `/.gradle/`; source identity filtering
  remains unchanged, and nested cache directories or arbitrary source stay visible.
- Workspace proof now captures schema-2 file/index components before and after
  the cold broker and before final uninstall, in addition to installation
  checkpoints. Rejection retains finite change-kind counts and at most 64 path
  digests. This strengthens the failed-boundary evidence without emitting paths
  or source payloads. All six checkpoints passed in the recorded combined receipt.
- Stronger mutation acceptance now requires complete zero diagnostics after the
  edit, discovery and inspection of the inserted class member at its canonical
  file, and rejection of a previously prepared same-file plan with
  `content-changed`. The actual staged journey passes and verifies that the stale
  plan changes neither source nor indexed evidence.

## Observed upgrade and resource evidence

The actual upgrade smoke downloaded the latest immutable published 0.x release,
0.32.2, and verified all four archive/checksum assets against GitHub digests.
It checked the installed old version and passive stopped status without starting
that runtime, then upgraded the same installation to the staged 1.0.0 pair.
The candidate control manifest matched the copied semantic runtime digest.

Copies of the exact candidate archives exercised a mismatched checksum and a
rechecksummed control archive containing `../escape`. Both rejected with their
specific expected diagnostic. Active candidate files, managed links,
configuration and repository contents retained identical observed identities.
The recorded combined gate repeated this upgrade/corruption journey and binds
its observed identities to the exact candidate archives.

The semantic corruption journey uses tokens emitted by the running product and
requires a valid resume before each corruption experiment. All four malformed
and digest-tampered relation/traversal cases reject with their finite usage
diagnostic. Corrupting the selected v3 cache identity receipt makes passive status
reject with `status-cache-invalid-identity`; exact byte and mode restoration
recovers status and source read without changing repository contents. Both the
staged probe and the recorded exact-archive journey passed.

The installed receipt has bounded observation points after start, a semantic
read, restart and stop. Headless IntelliJ does not always write a `.pid` file, so
the observer can locate the exact owned `idea.system.path` process and separately
verify its PID and RSS. It serializes neither command lines nor unrelated process
data. The recorded combined receipt binds live and stopped samples and apparent
state-disk bytes to the archive proof. Its live RSS samples ranged from
1,300,758,528 to 2,436,907,008 bytes; selected installation and runtime roots
occupied 598,842,931 to 601,297,150 apparent bytes across the observations.
These are samples, not continuous peak measurements or evidence that a resource
threshold passed. A stopped observation does not invent an RSS value.

## Semantic tooling observations

Native Kast `symbol discover` succeeded on the original checkout. This session
exposes no callable Kast or IntelliJ-index MCP tools, so native Kast is the
available semantic authority. A failed `status --root` attempt was a CLI usage
error: Kast selects the repository from its working directory. Worktree-specific
failures and fallback evidence belong below as they are observed.

## Release decision

No 1.0 release is authorized or produced by this work. All required core journeys
passed at the exact combined head recorded above. Subsequent stacked PR heads
require their own passing CI evidence; publication separately requires the exact
main revision, a fresh successful release gate and verified matching assets.
Source, module tests, staged-product tests, installed acceptance, and remote checks
are distinct evidence levels.

The broker remains a decoupled read-only preview. The installed harness exercises
the production dispatcher and real CLI, including cold invocation and canonical
evidence presentation. Real Codex model/conversation reload, cancellation against
a real cold sidecar, and live broker progress qualification remain preview limits;
the core receipt does not claim those external integration proofs.

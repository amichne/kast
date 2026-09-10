# Executed acceptance — 2026-09-09

This change implements the manual observer slice through safe replacement, starting from remote `main` at `74e47f4efe348036ddb8db5927eff1b6ed9e6382`. The remote reference was checked again after implementation and still matched.

Qualified carrier SHA-256:

```text
736c33e6fe308b7e540d41564650faa6c3001ab0da1861cf90f71bb6df027274
```

The real installation was `/Users/amichne/Applications/IntelliJ IDEA.app/Contents`: IDEA 2026.2.2 / `262.10315.125`, Kotlin plugin `262.10315.125-IJ`, built-in Kotlin engine `Kotlin - Beta` / `2.4.20-ij262-52`, JBR `25.0.4+1-b508.27`. The host process remained PID 69171, started `2026-09-08T23:13:00.151Z`. The IDE was not restarted and no plugin was installed. A failed, separate Kotlin compiler daemon was explicitly recovered between qualification runs, as recorded below.

## Mechanical results

| Check | Executed result |
|---|---|
| `python3 -m unittest discover -s experiments/host-observation -p 'test_*.py'` | 16 tests passed. Correlation, strict input bounds, malformed records, historical-status limits, retained valid journal prefixes, and release proof are covered. |
| `check_carrier.py --idea-contents … --jdk …` | Passed using the installed compiler and JDK 25. Exact carrier gate, parser, UTF-8/size limits, no-follow file admission, and the actual bootstrap failure classifier were exercised. |
| `run_host_acceptance.py --idea-contents …` | 18 case groups passed against the actual carrier in independent native script evaluations. |
| `check_schema.py --evidence …` | 86 examples/records validated; unsupported versions and extra fields rejected. |
| `./gradlew knowledgeImpact verifyKnowledgeBase productBuildGate` | Passed: 242 actionable tasks, 141 executed and 101 from cache. |
| `./gradlew hostObservationTest knowledgeImpact verifyKnowledgeBase` | Passed after the final controller tests: 16 tests; 22 knowledge concepts, zero validation issues. |
| `git diff --check` | Passed. |

Use the full commands in [README.md](README.md) to repeat qualification on an explicitly selected installation. The local final host evidence directory was `/private/var/folders/zj/xhhqh2nd6qxc_m2tgcl_scw00000gn/T/kast-host-acceptance-usx01rgh`. Its `report.json` records `PASSED`, the carrier digest, and every completed case. Request directories retain correlated receipts, bounded terminal snapshots, and journal-size summaries even after owner-controlled session retention evicts old journals. This local path is evidence from this run, not a portable dependency.

## Actual-owner cases

- The evaluated script returned; later real VFS and indexing events still reached its original owner.
- A newly added fixture content root produced actual scanning history. Adding a source folder within existing content coverage had caused IDEA to skip scanning; that skipped task was not accepted as a scan receipt.
- A foreign project held focus. Only the explicit target was admitted, and foreign paths were excluded.
- An independent duplicate attach returned `OWNERSHIP_CONFLICT`; the winner continued observing.
- Stop was delivered as data from a separate controller. Replacement used a new session, preserved the host PID and exact fixture project object, and did not change the retired journal.
- Reusing a retained retired session ID was rejected before acquisition; its original status, marker, control request, and journal bytes stayed unchanged.
- A real VFS burst rotated the journal within four 1 MiB segments and reported coverage loss.
- Holding the actual writer saturated the bounded queue while loss remained visible outside that queue.
- Injected failures after storage, disposable registration, VFS, indexing, dumb-mode registration, writer start, and control start all ran original-owner rollback.
- Explicit callback and writer barriers prevented clean retirement. After the deadline, status was unconfirmed and shared admission stayed held. Releasing the barrier allowed the same owner to detach.
- Injected write and projection failures produced typed stop causes and retired their owners; output failure retained explicit loss evidence.
- Closing the owned fixture project retired its observer. After explicit stop/replacement checks, no observation threads remained.

Test hooks wrap acquisition and effect boundaries of `observer.kts` itself. They do not provide a second owner implementation or synthesize platform indexing events. Fixture setup and event generation remain separate from the launch-free observer/controller.

## Observed RED checks

The exact carrier check initially exited 1 because duplicate stop fields were accepted. Its failure message was `Ambiguous duplicate field authorized stop`; strict fixed-shape parsing made the same check pass.

The exact carrier check then exited 1 with `Callback admission exceeded its bound` when a 33rd permit was admitted. Explicit saturation at 32 permits made that check pass while preserving already-issued permits through revocation.

The installed scripting engine also rejected two expression-bodied returns that the separate compiler invocation had accepted. Hosted evaluation, rather than the standalone compilation alone, established the final script's executability. Preflight initially rejected the real Kast project at a 128-root bound; its measured 152 roots motivated the explicit 256-root cap, within the unchanged receipt byte limit.

## Compiler boundary observed during sustained qualification

After repeated full qualification runs, the separate IDEA Kotlin compiler daemon (PID 54871, parent IDEA PID 69171) returned `OutOfMemoryError: GC overhead limit exceeded` while constructing REPL compiler state. `jcmd GC.heap_info` showed its old generation at 100% usage (5,592,576 KiB); the IDE itself used about 1.1 GiB. This is runtime evidence of compiler resource exhaustion, not proof of a particular object-retention cause.

All previously admitted observers had retired. The failed compiler process alone received SIGTERM; bundled tooling recreated it. IDEA's process, open user project, and index storage stayed in place. The failed run remained unqualified and was not relabeled as a pass. The final full run used the recovered compiler and the same IDE host incarnation.

The change now preserves correlated engine diagnostics, classifies an exposed out-of-memory cause through a bounded causal chain, and always writes an incomplete acceptance report if cleanup fails. Success and resource-failure classification are tested without allocating an exhausted heap. Diagnostics MCP and IDE Perf were not enabled; inspection used the known processes' bounded heap summaries. No heap or index dump was retained.

This observed failure reinforces the restriction on unattended or repeated long-duration engine use. The runner does not recycle either process automatically, and these results do not establish a bound on compiler-daemon memory.

## Preserved boundaries and promotion limits

The change adds an experiment, controller checks in root `check`, and source-bound documentation. Kast runtime composition, semantic publication, worker recovery, platform dependency pins, index ownership, and seed admission are unchanged. The user's original checkout and its existing edits were preserved in a separate worktree.

Receipts are advisory historical evidence. There is no runtime consumer, coalescer, targeted refresh capability, workspace-model observer, cache copy, or live seed. No speedup or semantic convergence claim follows from these events.

Manual detach remains required before engine/plugin changes. Safe dynamic unload, unattended activation, attach-only launcher discovery, JVM crash recovery, classloader collection, and long-duration memory behavior are not qualified by these results. Observation remains disabled for replacement when retirement cannot be established.

# Dual-pane "grep vs kast" demo

## Context

The current `kast demo` streams a single bordered transcript panel through three sequential acts (references → rename → callers). The grep baseline is *narrated* in a text line ("grep baseline 38 matches / 19 false positives") but the user never sees the noise. The pitch — "kast is night and day better than grep" — is told, not shown.

This change replaces the sequential layout with a **simultaneous split-screen**: left pane streams grep noise (loud, fast, mostly red), right pane streams kast results (calm, slow, green), and a scoreboard fades in below each round with hard delta numbers. Each of the three rounds (References, Rename, Call Graph) reuses the same dual-pane frame so the contrast compounds.

Dual-pane becomes the default; terminals narrower than 120 columns automatically fall back to today's single-pane orchestrator. A new `--fixture <path>` option lets the demo replay a canned capture for podium-quality reproducibility while live mode stays honest.

## Decisions taken

- **Code location:** all new files live in `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/demo/` next to the existing live runtime. The orphaned `kast-demo/` module (`DualPaneRenderer`, `GrepAct`, `ResolutionAct`, `RippleAct`, `Models`, `DualPaneModels`, `ActHeader`, `ModulePalette`) is **left untouched in this PR** and deleted in a follow-up — none of it is wired into any production path today.
- **Rollout:** dual-pane replaces the current default. The single-pane orchestrator (`KotterDemoOrchestration.runKotterDemoSession`) stays as the narrow-terminal fallback only; no `--split` flag.
- **Data sourcing:** live workspace by default (driven by `DemoCommandSupport.analyzeTextSearch` + real backend calls). New `--fixture <path>` reads canned `DualPaneCapture` JSON for talks/CI snapshots.

## Files to create

All under `/Users/amichne/code/kast/kast-cli/src/main/kotlin/io/github/amichne/kast/cli/demo/`:

### `DualPaneScenario.kt` — data model

```kotlin
internal data class DualPaneScenario(val rounds: List<DualPaneRound>)

internal data class DualPaneRound(
    val title: String,                     // e.g. "References"
    val leftCommand: String,               // grep -rn "execute" --include="*.kt"
    val rightCommand: String,              // kast references --symbol execute
    val leftLines: List<DualPaneLeftLine>,
    val rightLines: List<KotterDemoTranscriptLine>,
    val leftFooter: String,                // "⚑ 38 hits · 0 type info · 0 scope"
    val rightFooter: String,               // "✓ 6 refs · typed · scoped · proven"
    val scoreboard: List<ScoreboardRow>,
)

internal data class DualPaneLeftLine(
    val text: String,
    val category: DemoTextMatchCategory,   // reuse DemoCommandSupport.kt:662
    val codePreview: String? = null,
)

internal data class ScoreboardRow(
    val metric: String,
    val grepValue: String,
    val kastValue: String,
    val delta: String,
    val isNewCapability: Boolean,          // renders "★ NEW" badge
)
```

### `DualPaneRoundBuilder.kt` — pure builders

```kotlin
internal fun buildReferencesRound(report: DemoReport, textSearch: DemoTextSearchSummary): DualPaneRound
internal fun buildRenameRound(report: DemoReport, textSearch: DemoTextSearchSummary): DualPaneRound
internal fun buildCallGraphRound(
    report: DemoReport,
    workspaceRoot: Path,
    textSearchOf: (String) -> DemoTextSearchSummary,
    verbose: Boolean,
): DualPaneRound
```

Pure — no Kotter, no I/O — so they're trivially testable. `textSearchOf` is injected so Round 3 can synthesize per-caller-name grep explosions deterministically in tests.

### `GrepNoisePalette.kt` — left-pane tone

```kotlin
internal fun grepNoiseColor(c: DemoTextMatchCategory): RGB    // mirrors TranscriptPalette.kt:38-45
internal fun grepNoisePrefix(c: DemoTextMatchCategory): String
```

| Category         | Prefix | Color (RGB hex) |
|------------------|--------|-----------------|
| `LIKELY_CORRECT` | `?`    | default white   |
| `IMPORT`         | `~`    | `0xFFD75F`      |
| `COMMENT`        | `#`    | `0xAF5F5F` (dim)|
| `STRING`         | `"`    | `0xAF5F5F` (dim)|
| `SUBSTRING`      | `✕`    | `0xFF5F5F`      |

### `ScoreboardRenderer.kt`

```kotlin
internal fun RenderScope.renderScoreboard(rows: List<ScoreboardRow>, totalWidth: Int)
```

Reuses the box-drawing structure from `KotterDemoBranchGridRenderer.branchGridLines` (`KotterDemoBranchGridRenderer.kt:21-35`). 4 columns: Metric | grep | kast | Δ. Rows where `isNewCapability` render the delta cell as `★ NEW` in bright green.

### `DualPaneScheduler.kt` — pure scheduler (testable with virtual clock)

```kotlin
internal data class Tick(val side: Side, val lineIndex: Int)
internal enum class Side { LEFT, RIGHT, SCOREBOARD }

internal class DualPaneScheduler(
    val leftCadenceMs: Long = 50L,
    val rightCadenceMs: Long = 300L,
    val scoreboardRevealMs: Long = 120L,
    val roundHoldMs: Long = 1500L,
)
```

Why a separate scheduler: lets us unit-test cadence ordering (`runTest { advanceTimeBy(...) }`) without standing up a Kotter `Session`.

### `DualPaneOrchestration.kt` — Kotter session driver

```kotlin
internal fun Session.runDualPaneSession(
    scenario: DualPaneScenario,
    layout: KotterDemoDualPaneLayout,
    scheduler: DualPaneScheduler = DualPaneScheduler(),
)
```

Structure (sidesteps `KotterDemoSessionController` — adding two streams + a scoreboard to its single-`StateFlow` model isn't reuse, it's coupling):

```kotlin
section { /* chrome + renderDualTranscriptPanel + renderScoreboard */ }
    .runUntilSignal {
        for (round in scenario.rounds) {
            val left = liveVarOf<List<DualPaneLeftLine>>(emptyList())
            val right = liveVarOf<List<KotterDemoTranscriptLine>>(emptyList())
            val scoreboardRevealed = liveVarOf<List<ScoreboardRow>>(emptyList())
            coroutineScope {
                launch {
                    for (line in round.leftLines) {
                        delay(scheduler.leftCadenceMs); left.value = left.value + line
                    }
                }
                launch {
                    for (line in round.rightLines) {
                        delay(scheduler.rightCadenceMs); right.value = right.value + line
                    }
                }
            }
            for (row in round.scoreboard) {
                delay(scheduler.scoreboardRevealMs); scoreboardRevealed.value = scoreboardRevealed.value + row
            }
            delay(scheduler.roundHoldMs)
        }
        signal()
    }
```

### `DualPaneCapture.kt` — fixture serialization

```kotlin
@Serializable
internal data class DualPaneCapture(val scenario: DualPaneScenario, val symbolFqn: String)

internal fun loadCapture(path: Path): DualPaneCapture
internal fun saveCapture(path: Path, capture: DualPaneCapture)
```

Uses kotlinx.serialization (already a dep). `--fixture <path>` calls `loadCapture` and skips the live data-collection phase entirely. Live mode runs as today and may optionally write a capture via a hidden `--capture-fixture <path>` flag for "save the demo I just gave" — small ergonomic win, not strictly required for v1.

## Files to modify

### `KotterDemoChromeRenderers.kt`

Add (does **not** replace `renderTranscriptPanel` at lines 176–191):

```kotlin
internal fun RenderScope.renderDualTranscriptPanel(
    leftHeader: String,
    leftLines: List<DualPaneLeftLine>,
    rightHeader: String,
    rightLines: List<KotterDemoTranscriptLine>,
    paneWidth: Int,
    paneHeight: Int,
    leftFooter: String,
    rightFooter: String,
    gap: Int = 1,
)
```

Implementation: render two `renderPanel`-style boxes line-by-line, joined with `" ".repeat(gap)`. Each row of the inner content is: `│ <left line padded to paneWidth-2> │ <right line padded to paneWidth-2> │`. Bottom rule includes the per-pane footer.

### `KotterDemoLayout.kt`

```kotlin
internal enum class KotterDemoLayoutMode { Single, DualPane }

// existing KotterDemoLayoutRequest gains:
val mode: KotterDemoLayoutMode = KotterDemoLayoutMode.DualPane

// existing KotterDemoLayoutDecision.Ready gains:
val dualPane: KotterDemoDualPaneLayout? = null,
val fallbackToSingle: Boolean = false,

internal data class KotterDemoDualPaneLayout(
    val paneWidth: Int,
    val gap: Int,
    val totalWidth: Int,
)

private const val MIN_DUAL_PANE_WIDTH = 120
private const val DUAL_PANE_GAP = 1
```

`KotterDemoLayoutCalculator.layout()` (`KotterDemoLayout.kt:65-93`) gains a branch:

- `mode == DualPane && terminalWidth >= 120` → `Ready(dualPane = KotterDemoDualPaneLayout(paneWidth = (terminalWidth - SHELL_INSET_WIDTH - DUAL_PANE_GAP) / 2, ...))`
- `mode == DualPane && terminalWidth in 80..119` → `Ready(dualPane = null, fallbackToSingle = true)` — the caller dispatches to legacy `runKotterDemoSession`
- `terminalWidth < 80` → existing `Halted` path unchanged

Existing `KotterDemoLayoutTest` cases pass `mode = Single` (or default that resolves to it for backward compat — see test note below) and stay green.

### `DemoCommandSupport.kt`

- `runInteractive()` (`DemoCommandSupport.kt:165-292`): after the 5-step load phase populates `report` and `textSearchSummary`, branch on layout decision:
    - If `Ready.dualPane != null`: build `DualPaneScenario` via the three round builders and call `runDualPaneSession`.
    - If `Ready.fallbackToSingle`: call existing `runKotterDemoSession` with the existing `presentationFor(report, verbose)`.
    - If `--fixture <path>` was passed: skip the load phase, `loadCapture(path)`, dispatch to `runDualPaneSession`.
- `presentationFor()` (lines 294–307) and the three operation builders (`referencesOperation` 309–356, `renameOperation` 358–398, `callersOperation` 400–440) stay untouched — they drive the fallback path.
- New top-level helpers (private): `buildDualPaneScenario(report, textSearchSummary, workspaceRoot, verbose): DualPaneScenario` — composes the three round builders and the scoreboard rows.

### `DemoOptions.kt` and `CliCommandCatalog.kt:813-831`

- Add `fixture: Path? = null` to `DemoOptions`.
- Register `--fixture <path>` on the `demo` command.
- (No `--split`/`--dual-pane` flag — dual-pane is the default per the rollout decision.)

## Data sourcing per round

### Round 1: References

- **Left lines:** `textSearchSummary.sampleMatches` (already classified at `DemoCommandSupport.kt:486-498`). Each `DemoTextMatch` → `DualPaneLeftLine(text = "${path}:${line}  ${preview}", category = match.category)`.
- **Left footer:** `"⚑ ${totalMatches} hits · 0 type info · 0 scope"` plus a category breakdown line.
- **Right lines:** `report.references.references` (already loaded). Each `Reference` → `KotterDemoTranscriptLine(text = "${relativePath}:${line} ${kind}", tone = CONFIRMED)`. Header lines from `report.references.declaration` and `report.references.searchScope` (from `analysis-api/SearchScope.kt`).
- **Right footer:** `"✓ ${references.size} refs · typed · scoped · proven"`.
- **Scoreboard:** noise reduction %, false-positives eliminated, type information (`none → full FQN + kind` ★ NEW), scope proof (`exhaustive=true` ★ NEW from `SearchScope.exhaustive`).

### Round 2: Rename

- **Left:** `textSearchSummary.sampleMatches` filtered to `category != LIKELY_CORRECT` → `"sed would rewrite ${file}:${line}"` with red prefix. Footer: `"⚑ ${count} blind edits, ${falsePositives} would break"`.
- **Right:** drives off `report.rename` (already populated by `cliService.rename(...)` at `DemoCommandSupport.kt:239`-style flow). `RenameResult.edits` → `"✓ ${file}:${range}  ${oldText} → ${newText}"`. Then `RenameResult.fileHashes: List<FileHash>` (confirmed at `analysis-api/.../RenameResult.kt:15`) → `"✓ SHA-256 ${hash.take(12)}…  ${file}"`. **No backend change required.**
- **Scoreboard:** files touched (grep all matches vs rename `edits.size`), rename safety (`blind sed → SHA-256 verified` ★ NEW).

### Round 3: Call Graph

- **Left:** for each top-level caller name in `report.callers.callers`, run `analyzeTextSearch(workspaceRoot, callerName)` and emit the first ~6 hits per caller. Most flagged as not `LIKELY_CORRECT`. Footer: `"⚑ caller identity unrecoverable: ${totalNoise} hits across ${distinctCallerNames} names"`.
- **Right:** reuse `renderCallTreePreview(workspaceRoot, root = report.callers.toCallNode(), verbose, limit = 15)` from `CallTreePreview.kt:8-29`. Each output line wrapped in `KotterDemoTranscriptLine(tone = STRUCTURE)`.
- **Scoreboard:** call-graph capability (`unavailable → bounded N-hop tree` ★ NEW).

## Critical files

- `/Users/amichne/code/kast/kast-cli/src/main/kotlin/io/github/amichne/kast/cli/DemoCommandSupport.kt` — branch in `runInteractive`, add `buildDualPaneScenario` helper.
- `/Users/amichne/code/kast/kast-cli/src/main/kotlin/io/github/amichne/kast/cli/DemoOptions.kt` — add `fixture: Path?`.
- `/Users/amichne/code/kast/kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandCatalog.kt` — register `--fixture`.
- `/Users/amichne/code/kast/kast-cli/src/main/kotlin/io/github/amichne/kast/cli/demo/KotterDemoChromeRenderers.kt` — add `renderDualTranscriptPanel`.
- `/Users/amichne/code/kast/kast-cli/src/main/kotlin/io/github/amichne/kast/cli/demo/KotterDemoLayout.kt` — add `DualPane` mode + min-width gate.

## Tests

New unit tests under `/Users/amichne/code/kast/kast-cli/src/test/kotlin/io/github/amichne/kast/cli/demo/`. Prefer narrow renderer/scheduler/builder slices over end-to-end Kotter snapshots — timing-driven section output is fragile.

- `DualPaneRoundBuilderTest.kt` — given fixed `DemoReport` + `DemoTextSearchSummary`, assert each round's `leftLines.size`, `rightLines.size`, footer text, scoreboard rows, `isNewCapability` flags.
- `DualPaneSchedulerTest.kt` — `runTest { advanceTimeBy(...) }`. After 100ms left has emitted 2 lines and right has emitted 0; after 600ms left ~12, right 2.
- `DualTranscriptPanelRenderingTest.kt` — `paneWidth=58`, two fixed line lists, assert rendered `List<String>` shape (border chars at expected indices, gap of one space between panes, headers, footers).
- `ScoreboardRendererTest.kt` — column-width math, `★ NEW` badge presence on rows where `isNewCapability=true`, no badge on plain rows, ANSI color escapes present.
- `GrepNoisePaletteTest.kt` — every `DemoTextMatchCategory` maps to a non-null prefix and RGB; `STRING`/`COMMENT` share the dim red palette; `SUBSTRING` distinct from both.
- `KotterDemoLayoutDualPaneTest.kt` — width 200 → `Ready` with `dualPane != null` and `paneWidth ≈ 98`; width 100 → `Ready` with `fallbackToSingle = true`; width 70 → `Halted`.
- `DualPaneCaptureTest.kt` — round-trip serialize/deserialize a small `DualPaneCapture` → identical value.

Existing tests stay green: `KotterDemoOrchestrationTest`, `KotterDemoLayoutTest`, `TranscriptPanelRenderingTest`, `KotterDemoChromeRenderersTest`, `KotterDemoSessionStateTest`, `KotterDemoBranchGridRendererTest`. The default `KotterDemoLayoutRequest.mode` defaults to `Single` (or the existing tests get one-line updates to pass `Single` explicitly) so the existing layout tests don't regress.

`DemoCommandSupportTest.kt` adds: `runInteractive` with `terminalWidth=200` produces a dual-pane scenario; with `terminalWidth=100` falls back to single-pane.

## Verification (end-to-end)

1. **Build + unit tests:** `./gradlew :kast-cli:test` — all new and existing demo tests pass.
2. **Live dual-pane (wide terminal):** open a 140-column terminal in `~/code/kast`, run `./kast.sh demo --workspace-root .` and pick a symbol with many references (e.g. an interface method). Verify left pane streams quickly with mixed colors, right pane streams calmly in green, scoreboard fades in below each round, three rounds advance.
3. **Narrow-terminal fallback:** resize the terminal to 90 columns, rerun. Verify the legacy single-pane orchestrator runs and produces today's output. Resize to 70 columns — verify the existing "terminal too narrow" halt message fires.
4. **Fixture replay:** capture a known-good dual-pane run to JSON (manual: `kast demo render` flow if extended, or hand-author one), then `kast demo --fixture <path>` reproduces identical output without needing a backend or workspace.
5. **Round 2 SHA-256:** rename a real symbol in fixture mode and verify the rendered hash prefixes match `RenameResult.fileHashes` from a parallel `kast rename --dry-run` invocation byte-for-byte.

## Known risks / out of scope

- **Backend offline during a live run** — Round 2 (rename) and Round 3 (callers) need the daemon. If unreachable, the right pane stalls while the left floods. v1 behavior: surface the existing backend error from `DemoCommandSupport` and abort before `runDualPaneSession` (no partial render). Followup: a "kast (offline)" placeholder per round.
- **Repaint cost at 50ms cadence on slow terminals (Terminal.app, tmux over ssh)** — Kotter rerenders the whole `section` on every `liveVarOf` write. If profiling shows flicker, batch left writes into chunks of 3 (raises effective cadence to 150ms while keeping the visual flood). Cadence constants live in `DualPaneScheduler` for easy tuning.
- **Heuristic noise classification** — `classifyTextMatch` (`DemoCommandSupport.kt:486-498`) may over- or under-count comments/strings on real workspaces, weakening live-mode scoreboard claims. Mitigation: ship the fixture path so podium runs are reproducible; iterate on classifier accuracy in a separate change.
- **`kast-demo/` module deletion** — deferred to a follow-up PR. This change leaves the orphaned scaffolding in place; nothing depends on it from the live runtime, so it's safe to ignore until cleanup.

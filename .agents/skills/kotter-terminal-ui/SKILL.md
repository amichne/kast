---
name: kotter-terminal-ui
description: Build, refactor, and test Kotter/Kotterx terminal UIs with strong defaults for pure renderers, interactive key-driven flows, and in-memory test coverage. Use this whenever the user mentions Kotter, kotterx, RenderScope, testSession, liveVarOf, runUntilSignal, bordered or grid layouts, arrow-key navigation, in-memory terminal tests, terminal dashboards, CLI demos, or converting ad-hoc println output into a structured terminal UI — even if they do not explicitly say "Kotter."
compatibility: Requires bash, view, and apply_patch.
---

# Kotter Terminal UI

Use this skill to turn Kotter work into a repeatable three-layer shape:

1. **Display model** — small data classes that already reflect the screen you want.
2. **Renderer** — pure `RenderScope` extensions that only draw.
3. **Interaction / orchestration** — session state, key handling, file IO, backend calls, and tests.

That split keeps the rendering cheap to reason about, cheap to test, and cheap to reuse across modules.

## Start with the right path

### Static terminal output

Use this shape when the screen is just a report or summary:

- Define a compact display model first.
- Write `RenderScope` extension functions over that model.
- Use Kotter layout primitives like `bordered`, `grid`, colors, and `textLine`.
- Keep truncation and limits explicit in the UI (`"Showing first N of M"`).

Read `references/renderers.md` for exemplars.

### Interactive key-driven UI

Use this shape when the user needs arrow keys, page state, or selection:

- Keep the **state machine pure** if you can.
- Store session state with `liveVarOf` / live collections.
- Render from current state inside `section { ... }`.
- Update state in `runUntilSignal { onKeyPressed { ... } }`.
- Compute focused windows / viewports as pure functions instead of mutating render code.

Read `references/interactive.md` for exemplars.

### Tests

Default to Kotter’s in-memory terminal tooling:

- `testSession`
- `resolveRerenders()`
- `stripFormatting()`
- `terminal.press(...)`
- `terminal.type(...)`
- `blockUntilRenderMatches(...)`
- `sendKeys(...)`

Read `references/testing.md` for exemplars.

## Working rules

### 1. Keep renderers pure

Do not mix file reads, backend calls, symbol resolution, or grep execution into `RenderScope` functions. Map domain data into a screen-shaped model first, then render.

### 2. Make limits visible

If you cap rows, files, hits, or children for responsiveness, print the cap. Silent truncation makes demos feel dishonest.

### 3. Prefer screen-shaped models over raw domain payloads

If the upstream data is rich, flatten it before rendering:

- `GrepDiffRow`
- `ResolvedReference`
- `RippleView`
- `RippleLine`

That keeps the renderer short and reduces token and reasoning cost later.

### 4. Keep interaction logic out of text formatting

If the UI has movement (`UP`, `DOWN`, file tabs, hit navigation), model it as state transitions first. Then let rendering consume the current state.

### 5. Test the state machine separately

If the interactive flow has navigation rules, add a pure state test for them before asserting the full terminal transcript.

### 6. Export Kotter APIs with `api(...)` when needed

If one module exposes public `RenderScope` extension functions and another module imports them, the producer module often needs `api(libs.kotter)` rather than `implementation(libs.kotter)`.

## Watch-outs

### `input()` is not available from plain `RenderScope`

For multi-input UIs, helpers need `MainRenderScope`, not just `RenderScope`.

### Multiple inputs must have unique IDs

If you use more than one `input(...)` in a section, assign stable unique IDs. Reusing IDs causes surprising focus behavior.

### ESC is special

Terminals often encode ESC as a prefix for ANSI sequences. Use Kotter’s key APIs (`Keys.ESC`, `terminal.press(Keys.ESC)`) rather than trying to hand-roll escape handling.

### Know the difference between `type`, `press`, and `sendKeys`

- `terminal.type(...)` sends literal characters.
- `terminal.press(...)` sends semantic terminal keys like arrows and ESC.
- `sendKeys(...)` injects Kotter `Key` events from inside a run block.

### Separate ANSI-sensitive and content-sensitive assertions

If the test cares about the words, use `resolveRerenders().stripFormatting()`.  
If the test cares about precise render state, use `assertMatches` / `blockUntilRenderMatches`.

### Grids need bounded content

Kotter grids are happiest when previews are truncated intentionally. Do not dump full source lines into narrow columns.

## Default implementation workflow

1. Define the smallest display model that matches the screen.
2. Write a pure renderer around it.
3. Add a red test with `testSession`.
4. If the UI is interactive, add a pure state-machine test.
5. Only then wire the renderer to real backend / file / CLI orchestration.

## Source map

The bundled references were distilled from these local exemplars:

- `kast-demo/src/main/kotlin/io/github/amichne/kast/demo/GrepAct.kt`
- `kast-demo/src/main/kotlin/io/github/amichne/kast/demo/ResolutionAct.kt`
- `kast-demo/src/main/kotlin/io/github/amichne/kast/demo/RippleAct.kt`
- `kotter/examples/list/src/main/kotlin/main.kt`
- `kotter/examples/input/src/main/kotlin/main.kt`
- `kotter/examples/keys/src/main/kotlin/main.kt`
- `kotter/kotter/src/commonTest/kotlin/com/varabyte/kotter/foundation/input/InputSupportTest.kt`
- `kotter/kotter/src/commonMain/kotlin/com/varabyte/kotter/foundation/input/InputSupport.kt`
- `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/demo/InteractiveDemoStateTest.kt`

Open the specific reference file only when you need more detail for that subproblem.

---
name: kotter-terminal-ui
description: "Design, implement, refactor, and test Kotter/Kotterx terminal UIs, including styling, layout, input, animations, live state, offscreen composition, grids, borders, and custom extensions. Use this whenever the user wants richer CLI or terminal UX in this repository: demos, dashboards, progress views, interactive selectors, input forms, terminal games, compiler-like output, or converting raw println/ANSI output into a structured terminal UI, even if they never mention Kotter by name."
compatibility: Requires bash, view, and apply_patch.
---

# Kotter Terminal UI

Treat this as the primary source for terminal UI work in this repository.

Prefer Kotter unless the user explicitly wants plain one-shot output or the task is so small that raw `println` is the better tool. Kotter already covers most of what terminal code here needs out of the box:

- text and styling
- reactive rerendering
- typed input and key handling
- timers and animations
- offscreen composition and asides
- grids, borders, justification, and indentation
- in-memory test support

Start with `references/capabilities.md` if you need the broad feature map first.

## Build terminal code in three layers

Use this shape by default:

1. **Display model** — small data classes that already reflect the screen you want.
2. **Renderer** — pure `RenderScope` extensions that only draw.
3. **Interaction / orchestration** — session state, key handling, file IO, backend calls, and tests.

That split keeps the rendering easy to reason about, easy to test, and easy to evolve as the terminal UX grows.

## Pick the right reference

- `references/capabilities.md` — high-level map of what Kotter supports out of the box.
- `references/styling.md` — colors, decorations, paragraphs, links, RGB / HSV, resets, and style composition.
- `references/composition.md` — sections, static history, asides, offscreen buffers, borders, grids, justification, and shifting.
- `references/reactivity.md` — `session`, `section`, `run`, `LiveVar`, live collections, signals, timers, shutdown hooks, and thread model.
- `references/interactive.md` — `input`, completions, multiline input, key handling, multi-field UIs, and navigation patterns.
- `references/renderers.md` — repo-local renderer exemplars that worked well for `kast`.
- `references/extending.md` — how to add new reusable Kotter helpers without fighting the framework.
- `references/testing.md` — `testSession`, in-memory terminal assertions, async render waits, and key simulation.

Open only the references you need for the current subproblem.

## Default workflow

1. Define the smallest display model that matches the screen.
2. Write a pure renderer around it.
3. Pick the narrowest scope that owns the behavior:
   - `RenderScope` for drawing
   - `MainRenderScope` for interactive inputs
   - `RunScope` for timers, input listeners, shutdown hooks, and side effects
   - `Session` for live state / section composition
4. Use live state first (`liveVarOf`, `liveListOf`, `liveMapOf`, `liveSetOf`) and manual `rerender()` only when you truly need it.
5. Make limits explicit in the UI.
6. Add Kotter test support before wiring real orchestration.

## Strong defaults for this repo

- Prefer structured layouts over ad hoc ANSI strings:
  - `bordered` for framed panels
  - `grid` for tables and side-by-side comparisons
  - `justified` / `shiftRight` for alignment
- Keep backend or file work out of render blocks. Renderers should consume prepared models.
- If the UI has navigation, keep the transition logic pure and let rendering consume focused state.
- Use `aside` for "history grows while the active area keeps repainting" flows.
- Use `offscreen` whenever you need to measure, align, pad, or decorate previously rendered content.
- Keep static instructions in their own finished section when they do not need rerendering.

## Working rules

### 1. Keep renderers pure

Do not mix file reads, backend calls, symbol resolution, grep execution, or process control into `RenderScope` functions.

### 2. Prefer screen-shaped models over raw payloads

Flatten rich domain data into view-specific models before rendering. This keeps the UI logic short and prevents giant renderer branches.

### 3. Make limits visible

If rows, files, hits, lines, or animations are capped for responsiveness, print the cap. Silent truncation makes demos and tools feel dishonest.

### 4. Model interaction separately from formatting

If the UI moves between items, panes, files, pages, or hits, model those transitions in pure functions first.

### 5. Use the right composition primitive

- `section` for the active render area
- `aside` for durable history emitted while a section is still active
- `offscreen` when you need to measure or replay rendered content
- `bordered` / `grid` / `justified` / `shiftRight` when composition matters more than raw text order

### 6. Choose the narrowest extension receiver

- `Session` for live state and section composition
- `RenderScope` for reusable drawing helpers
- `MainRenderScope` for helpers that call `input`
- `OffscreenRenderScope` / `AsideRenderScope` when a helper only makes sense there
- `RunScope` for listeners, timers, async orchestration, and shutdown hooks
- `SectionScope` only when a helper genuinely needs to work in both render and run phases

### 7. Store long-lived custom behavior in Kotter data lifecycles

If you build custom terminal helpers, prefer the framework's `data` + lifecycle model over global state. See `references/extending.md`.

### 8. Test the state machine separately

If the UI is interactive, write pure tests for the navigation rules before asserting the terminal transcript.

### 9. Export Kotter APIs with `api(...)` when needed

If one module exposes public `RenderScope`-based APIs that another module imports, the producer often needs `api(libs.kotter)` instead of `implementation(libs.kotter)`.

## Watch-outs

### `input()` is not available from plain `RenderScope`

Helpers that call `input(...)` or `multilineInput(...)` need `MainRenderScope`.

### Multiple inputs must have unique IDs

If a section contains multiple inputs, assign stable unique IDs or focus behavior becomes confusing.

### Truecolor may degrade

`rgb(...)` and `hsv(...)` are supported, but some terminals will approximate them. Avoid assuming smooth gradients always look smooth.

### Links are best-effort

`link(...)` gracefully degrades to normal text on terminals that do not support it.

### ESC is special

Use Kotter key APIs (`Keys.ESC`, `terminal.press(Keys.ESC)`) instead of manually synthesizing escape sequences.

### Shutdown hooks are best-effort

Keep them short, never rely on them for core correctness, and treat the extra rerender opportunity as a convenience rather than a guarantee.

### Sections are sequential

Kotter is built around one active rendering area at a time. Do not design around multiple simultaneously active sections.

### Gradle run is not the best UX proxy

For realistic interactive behavior, prefer running installed binaries or direct executables instead of relying on `gradlew run`.

## Source map

The bundled references were synthesized from the local Kotter README, the local examples, test-support sources, and the repo's own Kotter usage. Key inputs included:

- `kotter/README.md`
- `kotter/examples/text/src/main/kotlin/main.kt`
- `kotter/examples/input/src/main/kotlin/main.kt`
- `kotter/examples/keys/src/main/kotlin/main.kt`
- `kotter/examples/grid/src/main/kotlin/main.kt`
- `kotter/examples/border/src/main/kotlin/main.kt`
- `kotter/examples/anim/src/main/kotlin/main.kt`
- `kotter/examples/blink/src/main/kotlin/main.kt`
- `kotter/examples/picker/src/main/kotlin/main.kt`
- `kotter/examples/extend/src/main/kotlin/main.kt`
- `kotter/examples/extend/src/main/kotlin/textorize.kt`
- `kotter/examples/splash/src/main/kotlin/main.kt`
- `kotter/examples/chatgpt/src/main/kotlin/main.kt`
- `kotter/kotter/src/commonMain/kotlin/com/varabyte/kotter/foundation/**/*.kt`
- `kotter/kotter/src/commonMain/kotlin/com/varabyte/kotterx/**/*.kt`
- `kotter/kotterx/kotter-test-support/src/commonMain/kotlin/com/varabyte/kotterx/test/**/*.kt`
- `kast-demo/src/main/kotlin/io/github/amichne/kast/demo/GrepAct.kt`
- `kast-demo/src/main/kotlin/io/github/amichne/kast/demo/ResolutionAct.kt`
- `kast-demo/src/main/kotlin/io/github/amichne/kast/demo/RippleAct.kt`
- `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/demo/InteractiveDemoStateTest.kt`

Open the specific reference file only when you need more detail for that subproblem.

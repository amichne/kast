# Kotter capability map

Use this file when you need the broad answer to "what does Kotter already support?" before choosing an implementation path.

## Out-of-the-box functionality

| Area | Core capabilities | Primary source(s) |
| --- | --- | --- |
| Text rendering | `text`, `textLine`, `p` | `kotter/README.md`, `foundation/text/TextSupport.kt` |
| Styling | `bold`, `underline`, `strikethrough`, `color`, `rgb`, `hsv`, `invert`, clear/reset helpers, `link` | `examples/text`, `foundation/text/ColorSupport.kt`, `DecorationSupport.kt`, `LinkSupport.kt` |
| Reactive rendering | `run`, `rerender`, `liveVarOf` | `kotter/README.md`, `foundation/LiveVar.kt` |
| Reactive collections | `liveListOf`, `liveMapOf`, `liveSetOf`, read/write locks | `foundation/collections/*.kt` |
| Input | `input`, `multilineInput`, completions, `onInputChanged`, `onInputEntered`, `onInputActivated`, `onInputDeactivated`, `setInput`, `getInput`, `enterInput`, `clearInput`, `sendKeys` | `examples/input`, `foundation/input/InputSupport.kt` |
| Key handling | `onKeyPressed`, `runUntilKeyPressed`, `Keys.*`, `CharKey` | `examples/keys`, `foundation/input/InputSupport.kt`, `Keys.kt` |
| Timers | `addTimer`, `runFor`, `runForAtLeast`, `runForAtMost`, `onFinishing` cleanup patterns | `examples/blink`, `foundation/timer/TimerSupport.kt` |
| Animations | `textAnimOf`, `renderAnimOf`, templates, one-shot animations, `totalDuration`, `currFrame` restart | `examples/anim`, `examples/splash`, `foundation/anim/*.kt` |
| Composition | `offscreen`, `aside`, `bordered`, `justified`, `shiftRight` | `README` offscreen/aside sections, `kotterx` support files |
| Tables and panels | `grid`, `Cols`, `GridCharacters`, row / col span, fit/fixed/star columns | `examples/grid`, `kotterx/grid/GridSupport.kt` |
| Lifecycle and shutdown | `signal`, `waitForSignal`, `runUntilSignal`, `addShutdownHook` | `kotter/README.md`, `foundation/shutdown/ShutdownSupport.kt` |
| Extensibility | Scope-based extension functions, `ConcurrentScopedData`, custom lifecycles | `examples/extend`, `README` advanced section |
| Testing | `testSession`, `assertMatches`, `matches`, `stripFormatting`, `highlightControlCharacters`, `blockUntilRenderWhen`, `blockUntilRenderMatches` | `kotterx/kotter-test-support` |

## Choose the right primitive

### If you want rich text

Reach for:

- `text`, `textLine`, `p`
- `bold`, `underline`, `strikethrough`
- `color`, `rgb`, `hsv`, `invert`
- `link`

Read `styling.md`.

### If you want a structured screen

Reach for:

- `bordered` for framed panels
- `grid` for tabular or side-by-side layout
- `justified` for alignment
- `shiftRight` for indentation or splash-style positioning
- `offscreen` when you need to measure content before placing it

Read `composition.md`.

### If you want live progress or app state

Reach for:

- `liveVarOf`
- `liveListOf`, `liveMapOf`, `liveSetOf`
- `signal` / `runUntilSignal`
- `addTimer`
- animations

Read `reactivity.md`.

### If you want forms or interaction

Reach for:

- `input` / `multilineInput`
- `Completions`
- `onInputChanged`, `onInputEntered`
- `onKeyPressed`
- `runUntilInputEntered`, `runUntilKeyPressed`

Read `interactive.md`.

### If you want new reusable helpers

Reach for:

- `RenderScope` / `RunScope` / `Session` extensions
- `ConcurrentScopedData`
- lifecycle-tied state

Read `extending.md`.

### If you want confidence

Reach for:

- `testSession`
- `terminal.resolveRerenders()`
- `stripFormatting()`
- `assertMatches`
- `blockUntilRenderMatches`

Read `testing.md`.

## Good mental model

Kotter is not "manual ANSI with nicer names." It is a small reactive terminal framework:

1. `session` owns shared state and lifecycles.
2. `section` defines the active render area.
3. `run` handles async work, listeners, and timers.
4. live state drives rerenders.
5. `aside` writes history above the active area.
6. `offscreen` lets you compose and measure before emitting.

If you keep that model in mind, most API choices become obvious.

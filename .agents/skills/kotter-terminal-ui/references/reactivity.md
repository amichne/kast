# Reactivity, lifecycle, and long-running behavior

Use this file when the terminal UI needs to stay alive, react to state, or coordinate work over time.

## Scopes

Kotter is easiest to use when you keep the scopes straight.

### `Session`

Owns:

- shared state
- `liveVarOf`
- `liveListOf`, `liveMapOf`, `liveSetOf`
- section composition

### `RenderScope`

Owns drawing:

- text
- styling
- layout helpers

### `RunScope`

Owns behavior:

- listeners
- timers
- signals
- shutdown hooks
- asynchronous orchestration

## `run` and rerendering

The `section { ... }` block can render more than once. A `run { ... }` block can:

- change state
- wait for work
- trigger rerenders

Manual `rerender()` works, but prefer live state when possible.

## `LiveVar`

Use:

```kotlin
var result by liveVarOf<Int?>(null)
```

When you update a `LiveVar`, Kotter automatically requests another render.

Choose `LiveVar` when:

- one value drives visible output
- state changes happen over time
- you want to avoid remembering explicit `rerender()` calls

## Live collections

Kotter also ships:

- `liveListOf`
- `liveMapOf`
- `liveSetOf`

They rerender when mutated.

### Locking guidance

If you are reading or writing several properties together from a run block, use:

- `withReadLock { ... }`
- `withWriteLock { ... }`

This matters most when:

- trimming a rolling window
- updating multiple related collection entries
- reading several values that must stay consistent

## Signals

Kotter provides:

- `signal()`
- `waitForSignal()`
- `runUntilSignal { ... }`

Use them when the run block should stay alive until some event fires.

This is usually cleaner than hand-rolling a latch or deferred.

## Timers

Use:

```kotlin
addTimer(duration, repeat = true) { ... }
```

Timer callbacks can:

- flip live state
- stop repetition by setting `repeat = false`
- chain follow-up timers
- signal when done

Kotter also supports:

- `runFor`
- `runForAtLeast`
- `runForAtMost`

These are useful for bounded waits or small time-boxed interactions.

## `onFinishing`

Use `onFinishing` for cleanup when timers or toggled state may leave the final screen in the wrong condition.

Typical cases:

- blinking or inverted state
- temporary warning banners
- transient selections that should normalize on exit

## Animations

Kotter supports:

- `textAnimOf`
- `renderAnimOf`
- templates
- one-shot animations (`looping = false`)

Use `textAnimOf` for simple string-frame animations.

Use `renderAnimOf` when frames need styling, layout, or arbitrary render logic.

Useful properties:

- `totalDuration`
- `currFrame`

Reset `currFrame = 0` to restart a one-shot animation.

## Shutdown hooks

Use `addShutdownHook { ... }` when CTRL-C should trigger a short cleanup action or last visible warning.

There are session- and run-scoped variants.

Rules:

- keep them short
- do not rely on them for correctness
- treat the extra render opportunity as best-effort only

## Thread model

Kotter is intentionally centered on one active rendering area at a time.

- sections render sequentially on one render thread
- the run block executes on the calling thread
- trying to run competing sections concurrently is the wrong shape

Design your UI as a sequence of sections, not as multiple active terminals fighting each other.

## Good source examples

- `kotter/README.md`
- `kotter/examples/anim/src/main/kotlin/main.kt`
- `kotter/examples/blink/src/main/kotlin/main.kt`
- `kotter/examples/chatgpt/src/main/kotlin/main.kt`
- `kotter/foundation/collections/*.kt`

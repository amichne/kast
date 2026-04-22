# Extending Kotter safely

Use this file when the built-in primitives are close but you want a reusable helper or new abstraction.

## Start by choosing the right receiver

### `fun RenderScope...`

Use for pure drawing helpers:

```kotlin
fun RenderScope.shellCommand(command: String, arg: String) {
    cyan { text(command) }
    textLine(" $arg")
}
```

This is the default choice for reusable render fragments.

### `fun MainRenderScope...`

Use when a helper needs:

- `input(...)`
- `multilineInput(...)`

Do not use plain `RenderScope` for input helpers.

### `fun RunScope...`

Use for helpers that:

- register listeners
- add timers
- wait for signals
- coordinate async work

### `fun Session...`

Use for:

- multi-section flows
- session-level state setup
- reusable terminal application steps

### `fun SectionScope...`

Use rarely, but it is useful for helpers that must be readable from both render and run phases.

## Use Kotter's data store for long-lived custom state

Kotter's advanced extensibility model is based on `ConcurrentScopedData` plus lifecycle-scoped keys.

This is the right tool when you need:

- state that survives rerenders
- custom controls
- extension methods that coordinate render and run phases
- per-section or per-run ephemeral state

## Lifecycle rules

Common lifecycles:

- `Session.Lifecycle`
- `Section.Lifecycle`
- `MainRenderScope.Lifecycle`
- `RunScope.Lifecycle`

Most custom extension state should live in `Section.Lifecycle`, not `MainRenderScope.Lifecycle`, because render lifecycle data disappears after a single render pass.

## Good extension pattern

The `textorize` example in `kotter/examples/extend` is a strong template:

1. Create a lifecycle-scoped key.
2. Put state into `data` lazily with `putIfAbsent`.
3. Let the renderer consume the current state.
4. Let run-scope helpers mutate the state.
5. Use timers to drive rerenders when the extension is animated.

This pattern scales to:

- custom badges
- reusable animated headers
- domain-specific terminal widgets
- terminal progress effects

## Prefer "small helpers over giant frameworks"

Kotter is intentionally succinct. Lean into that.

Good custom extensions usually:

- do one thing
- sit on top of existing Kotter primitives
- hide repeated styling or layout noise
- avoid inventing a second rendering model

## When to use `offscreen`

If your extension needs to measure or replay child content, it probably wants `offscreen`.

Common examples:

- borders
- centering / alignment helpers
- custom callout blocks
- decorators that need to wrap each rendered row

## When to use custom data

If your helper needs memory across rerenders, do not sneak it into globals or companion objects. Use Kotter-managed data keyed to the right lifecycle.

## Cross-module export watch-out

If a module exposes public Kotter extension APIs to other modules, dependency visibility matters. Public `RenderScope`-based APIs often require `api(libs.kotter)` in the producer module.

## Source exemplars

- `kotter/examples/extend/src/main/kotlin/textorize.kt`
- `kotter/examples/extend/src/main/kotlin/main.kt`
- `kotter/kotter/src/commonMain/kotlin/com/varabyte/kotter/foundation/render/OffscreenSupport.kt`

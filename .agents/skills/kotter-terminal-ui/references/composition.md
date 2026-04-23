# Composition and layout

Use this file when the task is about arranging terminal content, not just styling it.

## Think in two areas

Kotter output usually has:

1. **Static history** above
2. **Active rendering area** at the bottom

Use separate finished sections for static instructions. Use `aside` when you need to append history while an active section is still rerendering.

## `section` vs `aside`

### `section`

Use `section` for the active, rerendered area:

```kotlin
section {
    textLine("Searching...")
}.run { ... }
```

### `aside`

Use `aside` from inside a `run` block when long-running work emits durable history:

```kotlin
aside { textLine("Compiled Foo.kt") }
```

This is a strong fit for:

- compilers
- test runners
- chat transcripts
- logs emitted while a spinner or status panel stays active

## `offscreen`

Use `offscreen` when you need to render first, then measure or replay the result.

It gives you:

- `lineLengths`
- `numLines`
- `createRenderer()`

This is the core primitive behind higher-level composition helpers.

### Common `offscreen` use cases

1. Measure content width before drawing a frame.
2. Right-align or center a previously rendered block.
3. Replay content row-by-row while wrapping it with other characters.
4. Build your own decoration helpers.

### Important property

An offscreen buffer inherits parent style initially, but local style changes inside the buffer stay local to the buffer.

## `bordered`

Use `bordered(...)` when a screen area needs to read like a panel:

```kotlin
bordered(
    borderCharacters = BorderCharacters.CURVED,
    paddingLeftRight = 1,
    paddingTopBottom = 1,
) {
    textLine("Panel title")
    textLine("Panel body")
}
```

Available border styles:

- `BorderCharacters.ASCII`
- `BorderCharacters.BOX_THIN`
- `BorderCharacters.BOX_DOUBLE`
- `BorderCharacters.CURVED`

Use ASCII when portability matters more than polish.

## `grid`

Use `grid(...)` when content is truly tabular or when you want side-by-side panels.

### Column types

- `fixed(width)`
- `fit()`
- `star(ratio = ...)`

Use `targetWidth` when star-sized columns need real room distribution.

### Column builder

```kotlin
grid(
    Cols {
        fit(maxWidth = 24)
        fixed(8, justification = Justification.CENTER)
        star(minWidth = 10)
    },
    targetWidth = 80,
) { ... }
```

### Grid features worth remembering

- `colSpan`
- `rowSpan`
- `nextEmptyCellRow`
- `nextEmptyCellCol`
- per-cell `justification`
- alternate border styles via `GridCharacters`

### Border styles

- `GridCharacters.ASCII`
- `GridCharacters.BOX_THIN`
- `GridCharacters.BOX_DOUBLE`
- `GridCharacters.CURVED`
- `GridCharacters.INVISIBLE`

`INVISIBLE` is useful when you want table-like flow without visible walls.

### Grid guidance

- Bound cell content before it reaches the grid.
- Keep preview columns short.
- Use grids for comparisons, summaries, inventories, or dashboards.
- Do not dump full source lines or huge payloads into narrow cells.

## `justified`

Use `justified(...)` when you need alignment over a rendered block:

- `Justification.LEFT`
- `Justification.CENTER`
- `Justification.RIGHT`

This is useful for:

- centered banners
- aligned labels
- padding content inside custom decorated layouts

`padRight = false` can reduce trailing whitespace when alignment is all you care about.

## `shiftRight`

Use `shiftRight(amount)` when content should be horizontally offset without building a grid just for that:

```kotlin
shiftRight(20) {
    textLine("Indented content")
}
```

It is especially useful for:

- splash screens
- centered-ish art
- nested focus blocks

## Composition recipes

### Boxed status panel

Use `bordered` around a compact render model.

### Diff or comparison panel

Use `grid` with two or three columns, then a summary line below.

### Measured custom layout

Use `offscreen` to measure text width, then replay it with your own padding or framing.

### Live progress with durable history

Use a status `section` plus `aside` calls from the run block.

## Good source examples

- `kotter/examples/border/src/main/kotlin/main.kt`
- `kotter/examples/grid/src/main/kotlin/main.kt`
- `kotter/examples/chatgpt/src/main/kotlin/main.kt`
- `kotter/examples/splash/src/main/kotlin/main.kt`
- `kotter/kotter/src/commonMain/kotlin/com/varabyte/kotter/foundation/render/OffscreenSupport.kt`

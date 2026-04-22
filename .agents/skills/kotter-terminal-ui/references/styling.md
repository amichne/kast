# Styling with Kotter

Use this file when the task is mostly about how terminal text should look.

## Core text primitives

Start with:

- `text(...)`
- `textLine(...)`
- `p { ... }`

`p` is useful when a block should be visually separated by surrounding newlines.

## Decorations

Kotter ships these standard decorations:

- `bold`
- `underline`
- `strikethrough`

Use them as either state setters:

```kotlin
bold()
textLine("Title")
clearBold()
```

or scoped helpers:

```kotlin
bold { textLine("Title") }
```

The scoped form is safer when you want short-lived style.

## Colors

### Named colors

You can use:

- `black`
- `red`
- `green`
- `yellow`
- `blue`
- `magenta`
- `cyan`
- `white`

Each supports:

- `layer = ColorLayer.FG` or `BG`
- `isBright = true` for bright variants

Example:

```kotlin
white(ColorLayer.BG) {
    black { textLine("Black on white") }
}
```

### Generic color APIs

Use these when you want more control:

- `color(Color.RED)`
- `color(index = 208)` for 256-color lookups
- `rgb(r, g, b)` or `rgb(0xFFAA00)`
- `hsv(h, s, v)`

`HSV` is especially handy when you want smoothly changing hues.

## Resets and clears

Kotter exposes reset helpers. The useful ones are:

- `clearColor(layer)`
- `clearColors()`
- `clearBold()`
- `clearUnderline()`
- `clearStrikethrough()`
- `clearInvert()`
- `clearAll()`

Use scoped styling first. Reach for explicit clears when you intentionally want to manipulate style incrementally inside one block.

## Inversion

`invert()` swaps foreground and background styling. It is great for:

- current selection
- transient alerts
- active button / tab affordances

Use it sparingly so the screen keeps a clear visual hierarchy.

## Scoped state

`scopedState { ... }` is the main escape hatch for local style changes:

```kotlin
scopedState {
    red()
    blue(ColorLayer.BG)
    underline()
    textLine("Local styling only")
}
textLine("Back to default")
```

Remember: many scoped helpers like `red { ... }` or `bold { ... }` are convenience wrappers around `scopedState`.

## Links

Kotter supports:

```kotlin
link("https://github.com/varabyte/kotter", "learn Kotter")
```

Use links when:

- the terminal is an operator-facing tool
- the destination is genuinely actionable

Do not depend on links for core usability. Some terminals render them as plain text.

## Styling patterns that scale

### 1. Semantic wrappers

Create small renderer helpers instead of sprinkling raw colors everywhere:

```kotlin
fun RenderScope.info(block: RenderScope.() -> Unit) = cyan(scopedBlock = block)
fun RenderScope.warning(block: RenderScope.() -> Unit) = yellow(scopedBlock = block)
fun RenderScope.error(block: RenderScope.() -> Unit) = red(scopedBlock = block)
```

That gives you one place to restyle the whole app later.

### 2. Separate content from emphasis

Prefer:

```kotlin
text("Result: ")
green { textLine("PASS") }
```

over mixing style decisions into data assembly code.

### 3. Keep dense areas readable

For tables, logs, and side-by-side comparisons:

- style headers and highlights, not every cell
- bound long text before applying layout
- use one accent color per meaning

## Truecolor guidance

`rgb(...)` and `hsv(...)` are supported, but terminals vary:

- gradients may look clumped
- some terminals approximate colors
- splash screens and pickers are safer places for rich color than dense data tables

## Limitations

- italics are intentionally not exposed
- links are best-effort
- heavy color usage can degrade readability quickly

## Good source examples

- `kotter/examples/text/src/main/kotlin/main.kt`
- `kotter/examples/picker/src/main/kotlin/main.kt`
- `kotter/examples/splash/src/main/kotlin/main.kt`

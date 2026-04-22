# Interactive Kotter exemplars

This file captures the patterns that worked best for key-driven Kotter UIs.

## 1. Arrow-key selection list

**Source:** `kotter/examples/list/src/main/kotlin/main.kt`

The core pattern is:

```kotlin
var cursorIndex by liveVarOf(0)

section {
    items.forEachIndexed { i, item ->
        text(if (i == cursorIndex) '>' else ' ')
        text(' ')
        textLine(item.label)
    }
}.runUntilSignal {
    onKeyPressed {
        when (key) {
            Keys.UP -> cursorIndex -= 1
            Keys.DOWN -> cursorIndex += 1
            Keys.ENTER -> signal()
        }
    }
}
```

### Why it works

- Rendering is just a function of state.
- Key handling is tiny and readable.
- Wraparound / clamping rules are explicit.

## 2. Multiple active inputs

**Source:** `kotter/examples/input/src/main/kotlin/main.kt`

When you need multiple input fields:

```kotlin
fun MainRenderScope.colorInput(line: Int, prompt: String) {
    scopedState {
        if (selectedLine == line) bold()
        text("$prompt: ")
        input(id = line, initialText = colors[line].toString(), isActive = selectedLine == line)
        textLine()
    }
}
```

### Watch-outs

- Use `MainRenderScope`, not plain `RenderScope`.
- Every input needs a unique `id`.
- Keep selection state outside the helper.

## 3. Ripple / viewport navigation as pure state + focus

**Source:** current `kast` demo work

The reusable pattern is:

1. Keep navigation state tiny (`act`, `activeFileIndex`, `activeHitIndex`).
2. Advance it with a pure function:

```kotlin
fun advance(key: Key, deck: RippleDeck): InteractiveDemoState?
```

3. Convert that state into a viewport with another pure function:

```kotlin
fun RippleDeck.focus(fileIndex: Int, hitIndex: Int): RippleView
```

### Why it works

- You can unit-test navigation without booting a terminal.
- The renderer only sees the focused view.
- Changing navigation rules does not require changing presentation code.

## 4. Key inspection / debugging

**Source:** `kotter/examples/keys/src/main/kotlin/main.kt`

If you need to discover how a key is being surfaced, capture it directly with `onKeyPressed` and print the `Keys.*` mapping before building a larger interaction model.

## Interactive checklist

1. Is the state machine pure?
2. Are wraparound / bounds rules explicit?
3. Does rendering depend only on current state?
4. If using inputs, are IDs unique?
5. Can arrow-key behavior be tested separately from the transcript?

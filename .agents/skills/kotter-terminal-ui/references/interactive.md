# Input and interaction

Use this file when the terminal UI accepts input, reacts to keys, or supports navigation.

## Typed input

The basic pattern is:

```kotlin
section {
    text("Name: ")
    input()
}.runUntilInputEntered {
    onInputEntered { name = input }
}
```

`input()`:

- rerenders as text changes
- manages cursor display
- handles arrow keys plus home/end

## Input validation

### On each keystroke

Use `onInputChanged` when invalid characters should be rejected immediately:

```kotlin
onInputChanged {
    if (input.any { !it.isDigit() }) rejectInput()
}
```

### On enter

Use `onInputEntered` when the full value should be validated at commit time:

```kotlin
onInputEntered {
    if (input.isBlank()) rejectInput()
    else accepted = input
}
```

## Completions

Kotter supports custom `InputCompleter` implementations, plus the built-in:

```kotlin
input(Completions("yes", "no"))
```

Use this for:

- prompts with a small known vocabulary
- command palettes
- confirmations

Remember that completion order matters.

## `viewMap` and `customFormat`

### `viewMap`

Use this for visual-only transformations like password masking:

```kotlin
input(viewMap = { '*' })
```

The underlying input remains unchanged.

### `customFormat`

Use this for character-level styling:

```kotlin
input(customFormat = {
    if (ch.isDigit()) green() else red()
})
```

This is good for:

- showing valid vs invalid characters
- highlighting delimiters
- basic inline syntax cues

## Multiple inputs in one section

This is supported, but there are two rules:

1. the helper must live on `MainRenderScope`
2. every input needs a unique `id`

Pattern:

```kotlin
fun MainRenderScope.channelInput(line: Int, label: String) {
    text("$label: ")
    input(id = line, isActive = selectedLine == line)
    textLine()
}
```

Keep focus state outside the helper.

## Multiline input

Use `multilineInput()` when ENTER should insert a newline instead of submitting the field.

Important behavior:

- finish with CTRL-D / EOF
- arrow/home/end/page keys navigate within the text
- content after the multiline input starts on a later line

This is a good fit for:

- commit messages
- chat prompts
- note entry

## Key handling

Use `onKeyPressed` for semantic key events:

```kotlin
onKeyPressed {
    when (key) {
        Keys.UP -> ...
        Keys.DOWN -> ...
        Keys.ENTER -> ...
        Keys.ESC -> ...
    }
}
```

Useful keys include:

- arrows
- `ENTER`
- `ESC`
- `TAB`
- `HOME`, `END`
- `PAGE_UP`, `PAGE_DOWN`
- `SPACE`
- `EOF`

For literal typed characters, you may receive `CharKey`.

## Convenience runners

Use:

- `runUntilInputEntered`
- `runUntilKeyPressed(Keys.Q)`
- `runUntilSignal`

These express intent better than hand-written loops in common cases.

## Programmatic input control

Kotter exposes helpers that are easy to forget but useful:

- `getInput(id)`
- `setInput(text, cursorIndex, id)`
- `enterInput()`
- `clearInput()`
- `sendKeys(...)`

These are useful for:

- seeding a field with previous content
- carrying a draft into the next interaction step
- injecting keys from inside a running section

## Navigation patterns

### 1. Arrow-key list

Use a live cursor index plus `onKeyPressed`:

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
            Keys.UP -> cursorIndex = (cursorIndex + items.size - 1) % items.size
            Keys.DOWN -> cursorIndex = (cursorIndex + 1) % items.size
            Keys.ENTER -> signal()
        }
    }
}
```

### 2. Viewport navigator

For richer UIs, keep:

1. a tiny state object
2. a pure `advance(key)` function
3. a pure `focus(...)` or `view(...)` function
4. a renderer that only consumes focused state

This is the best pattern for file navigation, ripples, tab sets, or paged detail panels.

### 3. Picker-style mixed key + input UI

The color picker example demonstrates a useful hybrid:

- arrow keys move a cursor
- letter keys switch modes
- `input()` is used for precise entry in edit modes
- `ESC` returns to navigation mode

Use this shape for compact power-user CLIs.

## Debugging interaction

If key behavior is unclear, build a tiny probe section first and print what `Keys.*` or `CharKey` values you are actually receiving.

## Interaction checklist

1. Is the state machine pure?
2. Are bounds or wraparound rules explicit?
3. Does the renderer depend only on current state?
4. Do multiple inputs have unique IDs?
5. Is `MainRenderScope` used when input helpers are extracted?
6. Could a convenience runner replace a manual loop?

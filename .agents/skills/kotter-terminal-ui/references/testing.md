# Testing Kotter UIs

These are the lowest-friction test patterns that worked well against the local Kotter source and the `kast` demo work.

## 1. Pure renderer snapshot

**Sources:**

- `kast-demo/src/test/kotlin/io/github/amichne/kast/demo/GrepActTest.kt`
- `kast-demo/src/test/kotlin/io/github/amichne/kast/demo/ResolutionActTest.kt`
- `kast-demo/src/test/kotlin/io/github/amichne/kast/demo/RippleActTest.kt`

Pattern:

```kotlin
testSession { terminal ->
    section {
        renderResolutionAct(sampleResult)
    }.run()

    val lines = terminal.resolveRerenders().stripFormatting()
    assertTrue(lines.any { "Resolver + Ripple" in it })
}
```

Use this when:

- the screen is static
- you care about visible content, not ANSI details

## 2. Key handling in a live section

**Source:** `kotter/kotter/src/commonTest/.../InputSupportTest.kt`

Pattern:

```kotlin
section {}.runUntilSignal {
    onKeyPressed {
        if (key == Keys.Q) signal()
    }
    terminal.press(Keys.Q)
}
```

Use this when:

- you want to prove a key path works
- ESC / arrows / ENTER matter

## 3. Input typing vs key pressing

**Source:** `InputSupportTest.kt`

Pattern:

```kotlin
terminal.type("Hello")
terminal.press(Keys.ENTER)
```

Use:

- `type(...)` for literal characters
- `press(...)` for semantic control keys

## 4. Waiting for rerenders

When the render settles asynchronously, use `blockUntilRenderMatches(...)` instead of assuming a single render pass.

## 5. Programmatic key injection from the run block

**Source:** `kotter/kotter/src/commonMain/.../InputSupport.kt`

```kotlin
sendKeys(Keys.W_UPPER, Keys.O, Keys.R, Keys.L, Keys.D)
```

This is useful when the UI is already running and you want to inject Kotter `Key` values without translating them to terminal byte sequences yourself.

## 6. Test the state machine separately

**Source:** `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/demo/InteractiveDemoStateTest.kt`

Pattern:

```kotlin
val actTwo = checkNotNull(InteractiveDemoState().advance(Keys.ENTER, deck))
val nextHit = checkNotNull(actTwo.advance(Keys.DOWN, deck))
```

Do this before writing a full terminal transcript test when the interaction logic is non-trivial.

## Testing checklist

1. Pure renderer test for visible content.
2. State-machine test for navigation rules.
3. Key-path test for ESC / arrows / ENTER if relevant.
4. `stripFormatting()` unless ANSI styling is the point of the test.

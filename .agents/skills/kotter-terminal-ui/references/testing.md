# Testing Kotter UIs

Use Kotter's in-memory test support by default. It is fast, deterministic, and good enough for most renderer and interaction tests.

## Core tools

The key primitives are:

- `testSession`
- `terminal.resolveRerenders()`
- `stripFormatting()`
- `assertMatches`
- `matches`
- `highlightControlCharacters()`
- `blockUntilRenderWhen`
- `blockUntilRenderMatches`
- `terminal.type(...)`
- `terminal.press(...)`
- `sendKeys(...)`

## 1. Pure renderer snapshot

Use this for static screens where visible content matters more than ANSI bytes:

```kotlin
testSession { terminal ->
    section {
        renderResolutionAct(sampleResult)
    }.run()

    val lines = terminal.resolveRerenders().stripFormatting()
    assertTrue(lines.any { "Resolver + Ripple" in it })
}
```

This is the cheapest, highest-value test shape for most renderers.

## 2. Exact render assertion

Use `assertMatches` when you want exact Kotter render output, including formatting:

```kotlin
terminal.assertMatches {
    green { textLine("PASS") }
}
```

If it fails, the diff output is much more readable than hand-rolled string assertions.

Use `matches(...)` when you just want a boolean.

## 3. Async render settling

Kotter sometimes resolves renders across multiple event-loop turns. When that matters, use:

```kotlin
blockUntilRenderMatches(terminal) {
    textLine("Expected final state")
}
```

or:

```kotlin
blockUntilRenderWhen {
    terminal.resolveRerenders().stripFormatting().contains("Done")
}
```

Prefer this over arbitrary sleeps.

## 4. Key handling

Use semantic key presses for arrows, ESC, ENTER, TAB, and similar controls:

```kotlin
terminal.press(Keys.DOWN)
terminal.press(Keys.ENTER)
```

Use this to prove navigation paths and quit / accept flows.

## 5. Typed input

Use literal typing for text entry:

```kotlin
terminal.type("Hello")
terminal.press(Keys.ENTER)
```

Rule of thumb:

- `type(...)` for characters
- `press(...)` for terminal control keys
- `sendKeys(...)` when injecting Kotter `Key` values from inside a running section

## 6. State-machine tests

If the UI has meaningful navigation logic, test that logic outside the terminal first:

```kotlin
val actTwo = checkNotNull(InteractiveDemoState().advance(Keys.ENTER, deck))
val nextHit = checkNotNull(actTwo.advance(Keys.DOWN, deck))
```

This keeps transcript tests focused on rendering instead of state math.

## 7. Control-character debugging

When a render mismatch is confusing, use `highlightControlCharacters()` or the failure output from `assertMatches` to make newlines and control codes visible.

## 8. Section exception behavior

`testSession` captures exceptions thrown during renders. By default, unexpected render exceptions fail the test, which is usually what you want.

If you intentionally need to inspect failure behavior, `suppressSectionExceptions = true` exists, but it should be rare.

## Good testing strategy

1. Pure state-machine test for non-trivial navigation.
2. Pure renderer test for visible content.
3. Focused key-path test for critical controls.
4. Async settle test only when rerender timing genuinely matters.

## Good sources

- `kotter/kotterx/kotter-test-support/src/commonMain/kotlin/com/varabyte/kotterx/test/foundation/TestSession.kt`
- `kotter/kotterx/kotter-test-support/src/commonMain/kotlin/com/varabyte/kotterx/test/runtime/RunScopeExtensions.kt`
- `kotter/kotterx/kotter-test-support/src/commonMain/kotlin/com/varabyte/kotterx/test/terminal/TerminalTestUtils.kt`
- `kast-demo/src/test/kotlin/io/github/amichne/kast/demo/*.kt`
- `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/demo/InteractiveDemoStateTest.kt`

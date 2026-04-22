# Renderer exemplars

This file captures the patterns that worked best for static Kotter renderers in the local `kast` demo work.

## 1. Header + summary report

**Source:** `kast-demo/src/main/kotlin/io/github/amichne/kast/demo/GrepAct.kt`

Use this when you need a framed act/screen header, a table, and a short verdict:

```kotlin
fun RenderScope.renderGrepAct(result: GrepResult) {
    renderActHeader(
        actNumber = 1,
        totalActs = 2,
        title = "Grep vs Resolver",
        subtitle = result.command,
    )
    textLine()
    renderComparisonTable(result.rows)
    textLine()
    red(isBright = true) {
        text("${result.totalGrepHits} grep hits")
        text("  vs  ")
        green(isBright = true) { text("${result.totalResolvedReferences} semantic refs") }
        textLine()
    }
}
```

### Why it works

- The renderer only consumes a screen-shaped model.
- Limits are rendered as text, not hidden in code.
- The summary line gives the screen a crisp takeaway.

## 2. Table with bounded previews

**Source:** `kast-demo/src/main/kotlin/io/github/amichne/kast/demo/ResolutionAct.kt`

Use `grid(...)` when you want clean columns, but feed it bounded strings:

```kotlin
grid(
    Cols { fit(minWidth = 20); fit(minWidth = 4); fit(minWidth = 10); fit(minWidth = 8) },
    characters = GridCharacters.BOX_THIN,
    paddingLeftRight = 1,
) {
    cell { text("File") }
    cell { text("Line") }
    cell { text("Module") }
    cell { text("Preview") }
}
```

### Best practice

- Bound preview text before it reaches the grid.
- Use module labels or short identifiers instead of full verbose payloads in columns.

## 3. Inline boxed viewport

**Source:** `kast-demo/src/main/kotlin/io/github/amichne/kast/demo/RippleAct.kt`

Use `bordered` for a focused sub-panel inside a larger act:

```kotlin
bordered(
    borderCharacters = BorderCharacters.BOX_THIN,
    paddingLeftRight = 1,
) {
    textLine("Ripple")
    textLine("File ${view.fileIndex} of ${view.fileCount} · Hit ${view.hitIndex} of ${view.hitCount}")
    textLine(view.file)
}
```

### Best practice

- Put status lines near the top so the operator can orient quickly.
- Use compact markers (`D`, `R`, `>`) instead of verbose prefixes on every line.

## Renderer checklist

Before you ship a Kotter renderer, confirm:

1. It accepts a dedicated display model.
2. It performs no backend or filesystem work.
3. It prints truncation notes when limits apply.
4. It keeps table cells bounded.
5. It can be exercised with `testSession` without mocking the world.

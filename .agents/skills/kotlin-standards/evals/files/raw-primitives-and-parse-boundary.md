# Raw primitives and parse boundary

The current design leaks raw transport values through the whole call chain:

```kotlin
data class SearchRequest(
    val filePath: String,
    val line: Int?,
    val column: Int?,
    val includeGenerated: Boolean?,
)

fun runSearch(
    filePath: String,
    line: Int?,
    column: Int?,
    includeGenerated: Boolean?,
): SearchResult
```

Problems:

- `filePath` must be normalized before use.
- `line` and `column` only make sense together.
- `includeGenerated = null` means "use default", which creates a hidden third state.
- Every downstream function repeats normalization and null checks.

The expected guidance should favor meaningful types, parsed models, and a
single parsing step at the boundary.

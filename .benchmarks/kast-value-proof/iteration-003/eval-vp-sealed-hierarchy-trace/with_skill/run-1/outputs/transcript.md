# Transcript: vp-sealed-hierarchy-trace

Workspace root: `/Users/amichne/code/kast/.benchmarks/kast-value-proof/_workspaces/konditional-with`

## Prompt

> List every implementation of the sealed interface Konstrained. For each implementation, show its file location and which module it belongs to.

## Tool calls

1. `kast_resolve(workspaceRoot=..., symbol="io.amichne.konditional.core.types.Konstrained", kind="class", fileHint=...)` → failed: no symbol matching FQ name with class kind.
2. `kast_scaffold(workspaceRoot=..., targetFile=".../Konstrained.kt", targetSymbol="Konstrained", mode="summary")` → failed: invalid scaffold mode `summary`.
3. `kast_references(workspaceRoot=..., symbol="Konstrained", kind="interface", fileHint=..., includeDeclaration=true)` → failed: extension/backend schema mismatch on `includeUsageSiteScope`.
4. `kast_scaffold(workspaceRoot=..., targetFile=".../Konstrained.kt", targetSymbol="Konstrained")` → failed: extension/backend schema mismatch on `includeUsageSiteScope`.
5. `kast_references(workspaceRoot=..., symbol="Konstrained", fileHint=...)` → failed: extension/backend schema mismatch on `includeUsageSiteScope`.
6. `kast_workspace_files(workspaceRoot=..., includeFiles=true, maxFilesPerModule=200)` → succeeded; confirmed modules and source roots.
7. `kast_resolve(workspaceRoot=..., symbol="Konstrained", kind="interface", fileHint=...)` → succeeded; resolved sealed interface at `Konstrained.kt:59`.
8. `kast_resolve(workspaceRoot=..., symbol="Konstrained", fileHint=...)` → succeeded; resolved sealed interface at `Konstrained.kt:59`.
9. `kast_references(workspaceRoot=..., symbol="Konstrained", kind="extends", fileHint=..., includeDeclaration=false)` → failed: `extends` is not a valid named symbol kind for this wrapper.
10. `kast_metrics(workspaceRoot=..., metric="impact", symbol="io.amichne.konditional.core.types.Konstrained", depth=2, limit=100)` → succeeded with no indexed impact results.
11. `kast_scaffold(workspaceRoot=..., targetFile=".../Konstrained.kt")` → succeeded; scaffold identified the sealed hierarchy in `Konstrained.kt`.

## Final list

| Implementation | File location | Module |
|---|---|---|
| `Konstrained.Primitive<V>` | `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt:60` | `konditional-types` |
| `Konstrained.Primitive.Int` | `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt:69` | `konditional-types` |
| `Konstrained.Primitive.String` | `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt:70` | `konditional-types` |
| `Konstrained.Primitive.Boolean` | `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt:71` | `konditional-types` |
| `Konstrained.Primitive.Double` | `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt:72` | `konditional-types` |
| `Konstrained.Object` | `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt:75` | `konditional-types` |
| `Konstrained.Array<E>` | `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt:77` | `konditional-types` |
| `Konstrained.AsString<T, V>` | `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt:111` | `konditional-types` |
| `Konstrained.AsInt<T, V>` | `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt:122` | `konditional-types` |
| `Konstrained.AsBoolean<T, V>` | `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt:133` | `konditional-types` |
| `Konstrained.AsDouble<T, V>` | `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt:144` | `konditional-types` |

Executor duration: 52.0 seconds

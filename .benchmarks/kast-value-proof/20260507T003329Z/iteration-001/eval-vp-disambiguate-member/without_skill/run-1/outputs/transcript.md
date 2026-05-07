# Transcript

## Prompt
List every implementation of the sealed interface Konstrained. For each implementation, show its file location and which module it belongs to.

## Target workspace used
/Users/amichne/code/konditional

## Major tool calls / steps
- Read `konditional-types/.../Konstrained.kt` to inspect the sealed hierarchy.
- Used `rg -n ': Konstrained|Konstrained\.'` across the workspace to find concrete implementations outside the declaration file.
- Checked `konditional-engine/src/testFixtures/.../TestFixtures.kt` for the only concrete repo implementation found by search.

## Key findings
- The sealed family is declared in `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt`.
- Direct sealed-family implementations there are `Primitive<V>`, `Object`, `Array<E>`, `AsString`, `AsInt`, `AsBoolean`, and `AsDouble`; `Primitive` also contains `Int`, `String`, `Boolean`, and `Double` subinterfaces.
- The only concrete repo implementation found elsewhere was `RetryPolicy : Konstrained.Object` in engine test fixtures.

## Final answer
Direct sealed-family implementations of `Konstrained` are all declared in `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt` (module: `konditional-types`):

- `Konstrained.Primitive<V>` — `Konstrained.kt:60`
  - `Konstrained.Primitive.Int` — `Konstrained.kt:69`
  - `Konstrained.Primitive.String` — `Konstrained.kt:70`
  - `Konstrained.Primitive.Boolean` — `Konstrained.kt:71`
  - `Konstrained.Primitive.Double` — `Konstrained.kt:72`
- `Konstrained.Object` — `Konstrained.kt:75`
- `Konstrained.Array<E>` — `Konstrained.kt:77`
- `Konstrained.AsString<T : Any, V : AsString<T, V>>` — `Konstrained.kt:111`
- `Konstrained.AsInt<T : Any, V : AsInt<T, V>>` — `Konstrained.kt:122`
- `Konstrained.AsBoolean<T : Any, V : AsBoolean<T, V>>` — `Konstrained.kt:133`
- `Konstrained.AsDouble<T : Any, V : AsDouble<T, V>>` — `Konstrained.kt:144`

Concrete implementation found elsewhere in the workspace:

- `RetryPolicy : Konstrained.Object` — `konditional-engine/src/testFixtures/kotlin/io/amichne/konditional/fixtures/TestFixtures.kt:52` (module: `konditional-engine`, testFixtures)

I did not find any other concrete `Konstrained` implementations in the workspace.

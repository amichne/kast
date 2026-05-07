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

# Transcript

## Prompt
List every implementation of the sealed interface Konstrained. For each implementation, show its file location and which module it belongs to.

## Target workspace used
`/Users/amichne/code/konditional`

## Major tool calls / steps
- Used `kast_resolve` to pin the sealed interface declaration.
- Used `kast_scaffold` on `Konstrained.kt` to read the semantic outline and hierarchy.
- Mapped each implementation file back to its owning module using `kast_workspace_files` module/source-root data.

## Key findings
- All direct sealed-interface subtypes are declared in `Konstrained.kt` in `konditional.konditional-types.main`.
- One concrete external implementation surfaced in test fixtures: `RetryPolicy` in `konditional.konditional-engine.testFixtures`.

## Final answer
Kast resolved `Konstrained` to `io.amichne.konditional.core.types.Konstrained` in `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt:59`. From the semantic hierarchy/scaffold output, these implementations were present:

- `Konstrained.Primitive` — `konditional-types/src/main/kotlin/io/amichne/konditional/core/types/Konstrained.kt:60` — module `konditional.konditional-types.main`
- `Konstrained.Primitive.Int` — `.../Konstrained.kt:69` — module `konditional.konditional-types.main`
- `Konstrained.Primitive.String` — `.../Konstrained.kt:70` — module `konditional.konditional-types.main`
- `Konstrained.Primitive.Boolean` — `.../Konstrained.kt:71` — module `konditional.konditional-types.main`
- `Konstrained.Primitive.Double` — `.../Konstrained.kt:72` — module `konditional.konditional-types.main`
- `Konstrained.Object` — `.../Konstrained.kt:75` — module `konditional.konditional-types.main`
- `Konstrained.Array` — `.../Konstrained.kt:77` — module `konditional.konditional-types.main`
- `Konstrained.Encoder` — `.../Konstrained.kt:92` — module `konditional.konditional-types.main`
- `Konstrained.Decoder` — `.../Konstrained.kt:102` — module `konditional.konditional-types.main`
- `Konstrained.AsString` — `.../Konstrained.kt:111` — module `konditional.konditional-types.main`
- `Konstrained.AsInt` — `.../Konstrained.kt:122` — module `konditional.konditional-types.main`
- `Konstrained.AsBoolean` — `.../Konstrained.kt:133` — module `konditional.konditional-types.main`
- `Konstrained.AsDouble` — `.../Konstrained.kt:144` — module `konditional.konditional-types.main`
- Concrete external implementation: `io.amichne.konditional.fixtures.RetryPolicy` — `konditional-engine/src/testFixtures/kotlin/io/amichne/konditional/fixtures/TestFixtures.kt:47` — module `konditional.konditional-engine.testFixtures`

The hierarchy output was semantically expanded from `Konstrained`; the only truncation reported was cycle handling, not file sampling.

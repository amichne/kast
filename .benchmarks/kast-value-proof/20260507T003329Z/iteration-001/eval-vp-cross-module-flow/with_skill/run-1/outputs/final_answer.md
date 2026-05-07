Resolved the exact type to `io.amichne.konditional.internal.serialization.models.FlagValue` at `konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt:40`.

Direct consumer trace from the exact type:

- In-module consumers in `konditional.konditional-json.main`:
  - `internal/serialization/adapters/FlagValueAdapter.kt` (imports plus serialization/deserialization branches)
  - `internal/serialization/models/SerializableFlag.kt`
  - `internal/serialization/models/SerializableRule.kt`
  - nested/subclass and companion references inside `FlagValue.kt` itself

Cross-module result:

- I found **no direct references to `FlagValue` in any other module**. Kast marked the search exhaustive over `19/19` candidate files in the dependent-module scope.
- So the cross-module dependency chain stops at `konditional.konditional-json.main`. `FlagValue` is an internal JSON-layer model used to wrap engine/type values during serialization, but other modules do not reference the type directly.

In short: `FlagValue` is defined and consumed within `konditional-json.main`; there is no direct downstream cross-module consumer of the type itself.

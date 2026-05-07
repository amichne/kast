I searched the entire workspace with `rg -n '\bFlagValue\b'` from the repo root. The search was exhaustive: it covered the whole workspace and the result set was **not** sampled or truncated.

Matches:

- `konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/adapters/FlagValueAdapter.kt`
  - lines `11, 16, 18, 23-29, 32, 35, 44, 48, 52, 56, 60, 65, 71, 81, 85-88, 90, 95, 100, 110, 123`
- `konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/SerializableRule.kt`
  - lines `16, 22, 55`
- `konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/FlagValue.kt`
  - lines `40, 44, 53, 60, 67, 74, 87, 104, 121, 169, 175, 193, 212`
- `konditional-json/src/main/kotlin/io/amichne/konditional/internal/serialization/models/SerializableFlag.kt`
  - lines `31, 37, 77`

I found no `FlagValue` references outside those four files.

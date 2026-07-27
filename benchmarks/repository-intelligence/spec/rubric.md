# Repository Intelligence Evaluation Rubric

Both systems receive the same frozen corpus and natural-language question. Each
dimension scores `0`, `1`, or `2`.

| Dimension | 0 | 1 | 2 |
|---|---|---|---|
| Answer correctness | wrong or absent | partly answers | satisfies every hard assertion |
| Identity precision | merged or guessed | name-level only | exact overload-safe identity or explicit ambiguity |
| Relation fidelity | generic or wrong | partly typed/directed | all used relations have the required kind and direction |
| Evidence and provenance | absent | indirect or sampled without retrieval | source occurrence or derivation for every relation |
| Scope and uncertainty | absent or overstated | partial metadata | generation, scope, coverage, bounds, and truncation visible |
| Discovery answerability | cannot answer | relevant material but no exact resolution | bounded discovery terminates in exact identity |
| Architectural usefulness | unsupported label | numeric result only | named finding with deterministic metric and evidence subgraph |

The exact-Kotlin category is the sum of exact identity, directional path, and
impact questions. Discovery answerability is measured on discovery,
architecture, and context questions. Unsupported Graphify operations are
recorded as unsupported, not silently scored as Kast wins.

Any critical failure forces the overall result to fail:

- distinct Kotlin declarations are merged;
- ambiguity is silently guessed;
- a semantic relation lacks source evidence or derivation;
- a complete negative claim is made for incomplete scope;
- relation kind or direction is wrong;
- inference is labeled compiler evidence; or
- repeated normalized results differ.

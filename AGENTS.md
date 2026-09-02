# Engineering Dictum

1. Refine; never erase.
   Information must move monotonically from weaker representation to stronger representation. Once a property is proven, preserve that proof in the representation.
2. Domain meaning requires a type.
   Primitives belong at boundaries. Inside the system, every value with domain meaning must have a representation that encodes its identity and invariants.
3. Make invalid states unrepresentable.
   States, transitions, operations, and combinations that are not valid must be excluded by construction whenever the language can express that exclusion.
4. Failure is finite data.
   Expected failure must be represented by a closed, typed, exhaustive set of conditions. Do not use exceptions, nulls, sentinels, booleans, strings, or arbitrary primitives as failure protocols.
5. Keep the core pure; make effects explicit.
   Domain logic must be deterministic and side-effect-free. Mutation, I/O, time, randomness, and external state must exist only through explicit boundaries and capabilities.
6. Fail closed.
   Unknown, ambiguous, unsupported, incomplete, or unproven states are failures. Never guess, silently recover, weaken an invariant, or manufacture success.
7. Use the strongest available authority.
   Prefer compiler proof over schema proof, schema proof over structured semantic evidence, semantic evidence over runtime observation, runtime observation over text, and text over heuristic inference. Never present weaker evidence as stronger evidence.
8. Reduce the space in which error can exist.
   Prefer fewer states, fewer transitions, fewer representations, fewer execution paths, and fewer abstractions. Completion requires mechanical evidence that the intended invariant holds.
9. Instrument as you investigate.
   When diagnosing an opaque failure requires source-level investigation, progressively make that boundary observable in the same change. Add bounded, structured, typed stage and outcome evidence at the narrowest effect boundary, and test both success and failure signals. Temporary probes may guide diagnosis, but completion replaces them with durable instrumentation. Never record secrets, source payloads, or unbounded data.

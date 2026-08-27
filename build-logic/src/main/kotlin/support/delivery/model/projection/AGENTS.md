# Deterministic delivery projection model

This directory owns KVP-005's pure projection bundle, generated JSON Schema documents, canonical
serialization, and finite admission result. Schema documents use generated serializers; generic
`JsonElement` traversal is confined to applying the admitted JSON Schema boundary.

`ValidatedProgramProjection.kt` derives graph-owned atomic task packets, definition digests, and
the topological order plus external dependency set of each admitted delivery batch.
`DeterministicProgramProjection` derives all five checked-in artifacts from one admitted delivery
program. Admission compares two independent generations before validating canonical bytes, the
absence of writable status fields, the exact schema authorities, and both data projections.
`Kvp005ProjectionProof.kt` derives the exact positive and four-case negative proof values consumed
by the Gradle gates and versioned receipt progression.

Run `DeliveryProjectionTest` and both KVP-005 Gradle gates after changing this directory.

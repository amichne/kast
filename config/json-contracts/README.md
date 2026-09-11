# JSON contract syntax guard

Run `./gradlew verifyJsonContracts`. Root `check` and `productBuildGate` both
require this single task. It never updates a baseline, generates allowances,
installs a Git hook, or offers a suppression switch.

The task parses repository Kotlin `.kt` and `.kts` files, including production,
tests, fixtures, and build logic. It excludes build outputs, `.gradle`, `.kotlin`,
Git and IDE metadata, `out`, and `node_modules`. Kotlin compiler 2.4.10 supplies
PSI syntax parsing in a separate JVM and separately compiled source set. Its
classes never enter the Kotlin Gradle plugin runtime. The pinned legacy parser
environment is used only to construct PSI; no K1 semantic analysis runs.

The following expression forms produce findings:

- Calls to `kotlinx.serialization.json.buildJsonObject`, `buildJsonArray`,
  `JsonObject`, and `JsonArray`, identified through explicit imports, import
  aliases, star imports, the JSON package, or qualified call syntax.
- String literals, interpolated templates, and direct string concatenations
  whose literal structure parses as a JSON object or array. Interpolation
  expressions are represented by a placeholder solely for this syntax test.

The scanner visits Kotlin expressions. It does not search inside comments or
source-code snippet strings for builder names. A snippet containing an actual
JSON object or array is still a handwritten JSON fixture and needs either typed
serialization or a justified, exact fixture allowance.

Each finding binds the repository-relative file, enclosing named declaration
scope, expression kind, SHA-256 of its normalized Kotlin tokens, and occurrence
count. Whitespace, comments, statement separators, and optional trailing commas
are excluded from normalization; literal contents remain significant. Changing
or extending a builder changes its fingerprint. Copying an unchanged expression
inside its scope increases its count. Moving it to another file or named scope
requires a different fingerprint.

`baseline.json` is a versioned DTO document. Each allowance contains
`fingerprint` (`path`, `scope`, `kind`, `sha256`), a positive exact `count`, and a
nonblank `justification`. Paths must be normalized and relative; hashes must be
64 lowercase hexadecimal characters; duplicate fingerprints and unknown fields
are rejected. There are no glob allowances. Removed expressions and decreased
counts make their old allowances fail as stale: remove or reduce the obsolete
entry so the debt cannot return unnoticed.

Initial existing debt is reviewed once from the rejected report at
`build/reports/json-contracts/report.json`. The report contains the complete
finding inventory and closed violation variants, serialized from DTOs; it does
not include source expressions or payloads. The task never writes allowances.
An intentionally malformed or incompatible negative test, or an independent
expected-shape fixture, may have an explicitly justified fingerprint allowance.
That is a reviewed exception for its exact syntax, not a directory exemption.
New production shapes must use DTOs rather than adding allowances.

This is **Kotlin PSI syntax evidence**, not resolved symbol, schema, or runtime
contract proof. Imported names can be shadowed. Arbitrary map assembly, helper
functions, reflection, type aliases, dynamic string fragments, invalid JSON
strings, and generated shapes outside the scanned source forms are not fully
covered. The guard cannot prove default-field encoding, exhaustive outcome
coverage, field semantics, or actual wire-schema validity. Keep focused encoded
shape tests and authoritative schema qualification alongside it, as required by
`AGENTS.md`.

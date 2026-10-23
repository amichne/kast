# Public canonical fixture provenance

## 2026-10-23 callback schema refresh

All three schema files were exported from the local callback changes based on
source `7e8832dfa`. The exporter now uses `generatedOutputSchema` for question
and qualification schemas. It cuts recursion only at the exact
`ImpactInvocationReferenceDocument` serializer descriptor and includes its
complete definition. The result schema still uses
`installedSemanticResultSchema(QUERY_RUN)`.

These offline exports establish serialized shapes, not native semantic behavior.
The following digests identify the exporter, compiled artifact, and outputs.

| Artifact | SHA-256 |
| --- | --- |
| `PiQuestionSchema.java` | `3fd516b232b68c42b849399ec147da751cd92f56c5d87ebf01e165d5222c2b28` |
| `cli-0.50.2-all.jar` | `b4ce91a5cb13db39c6ad0fe828310cd2517f6d680e384c738861a23645c88cc9` |
| `query-question.schema.json` | `d7e0bff0bf290095b985c461ef38cdfb57b97a0c029ba0a37dd2db56fb0ef8e5` |
| `query-qualification.schema.json` | `d8bb99d97e575fb36f34594285a8306027853e990a5d38c455f5d9fc14e478b3` |
| `query-result.schema.json` | `60b0c92d8f750e7e77b4ef68a5b2937ec44f39b471aac4ada9be3d8f7eaf3e0b` |

Reproduce from the repository root after building the CLI artifact:

```sh
mise exec -- java -cp 'cli/build/libs/cli-0.50.2-all.jar:query/contract/build/classes/kotlin/main' \
  experiments/host-observation/PiQuestionSchema.java \
  experiments/host-observation/pi-fixtures/query-question.schema.json \
  experiments/host-observation/pi-fixtures/query-qualification.schema.json \
  experiments/host-observation/pi-fixtures/query-result.schema.json
```

## Historical 2026-10-18 schema export

`query-question.schema.json`, `query-qualification.schema.json` and
`query-result.schema.json` are exported
offline by `PiQuestionSchema.java`. It calls the existing `generatedRequestSchema`
owner with `QueryQuestionDocument.serializer()` and
`CanonicalQueryCliDocuments.qualificationSerializer`. The result schema reuses
`installedSemanticResultSchema(QUERY_RUN)`, which composes the actual complete,
qualified and rejected serializers with existing local output definitions.
The 2026-10-18 export uses the compositional callback changes based on source `50f064f1779433c49f3d513919b3474b7cc3ed0e`.
The artifact digests identify the examined sources and generated result schema.

`result_projection` keeps the exact callback result site, relationship direction, semantic basis, and selected result domain.
`result_projection.subject` carries the enclosing exact reference.
Relation and WALK admission reject a reference for another question or frontier.
Callback and callable observations keep the native semantic basis.
`ADMITTED.basis` must match that observation basis even when flow evidence is unavailable.

The exporter uses the CLI classpath from this task checkout and the compiled `query/contract` classes.
Only `query-result.schema.json` changed.
It includes source-relationship evidence, explicit effect gaps, and recursive callback ownership.
The question and qualification schemas keep their previous bytes.
The export does no semantic work and supplies no native qualification.

| Artifact | SHA-256 |
| --- | --- |
| `query-result.schema.json` | `ad514bd6f027de2dd8b4413e9d217b53c3a0f1c0843761f3eb1a466fad2f8a72` |
| `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt` | `6bce48440f332bc2c937d3fce0647a989dbed218a079d613fb0dce56681ba2b0` |
| `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCallbackResultProjectionDocument.kt` | `713a975eb208568089b8a14e28cc7c6d0dbc9ff2c6b1c8f223568aaeadf5202d` |
| `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCallbackObservationDocument.kt` | `da3b8b3ead71dd51a003c90260223ae8c31afbfaecf374c10d29aa95f1f836b3` |
| `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCallableObservationDocument.kt` | `69b2b2fa4e08490549e4a74fa6cae8df037231063127a3ea077ad498337034ec` |
| `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCallbackBodyBindingDocument.kt` | `77d4fea6994e8416db6853bdb3fb300fd1085b9e3955dc6840248d4fb3fea5e6` |
| `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryImmutableCallbackFlowDocument.kt` | `83888ce308bed5d764ee98dd27f48adb53d80c0540554b910fa0e89b3c955b8e` |
| `protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/presentation/CanonicalCallbackSchemaDocuments.kt` | `da10edbfdfcd00f1d8f07cd1a101cecc3363f0e0d147ff509ac2e12abdf9a27c` |
| `query/protocol/src/main/kotlin/io/github/amichne/kast/query/protocol/QueryCallbackGraphAdmission.kt` | `11d1c26a2af0572961a8a3f049360bb23344dcc4ebd3aba171bede6385a8df7a` |

`PiQuestionSchemaParityTest` compares all three committed schemas with the current owners.
Gradle tracks these schemas as inputs.

```sh
java -cp "cli/build/install/kast/lib/*:query/contract/build/classes/kotlin/main" \
  experiments/host-observation/PiQuestionSchema.java \
  /tmp/query-question.schema.json /tmp/query-qualification.schema.json \
  /tmp/query-result.schema.json
```

The experimental partial-prefix policy uses the generated question constraints
and the complete rejection/page DTO constraints, including rows, nested evidence,
recovery, budget and authority. Its finite progress/limitation transformation
follows `completionEvidenceRead` and `RetainedQueryPresentation.pageCoverage`.
The independent Python JSON Schema oracle checks the pure Node shape admission
against authoritative documents and recursive type/unknown-property mutations.
These offline assets never become provider tool declarations or response bytes.

`query-original-limitations.json` is an optional fourth export: the actual
`query/contract` QueryLimitation enum names serialized with the existing JSON
library. It excludes read-only wire qualifications. The required producer test
checks exact equality with the current enum and declares the asset as an input.
To export it, add `query/contract/build/libs/*` to the classpath above and pass
its destination after the three schema paths. Existing three-argument export
remains supported. No CLI production dependency or public wire enum is added.

The three `*-document.json` files are public/synthetic query document DTOs,
serialized offline by the installed release's **actual Kotlin serializers** with
`PiCanonicalFixture.java`. No services, IDEs, compiler queries or models were
composed. They prove producer-owned serialized shapes, not semantic admission
of their synthetic identity tokens or their row counts.

Producer: Kast LOCAL_BUILD 0.50.0, source
`659714d4aa8adfeb2e8b085efdb3cf230f82e92c`, tree
`b7766ef03c19bc7c7025152f6fadbb34901f8e07`, contract 14.
`wire-0.50.0.jar` SHA256:
`118574d3786ef3d7b281a2c5e5e79c94a8c26464e515287a672eb99c9992bc73`.

Owner: `protocol/wire/.../presentation/CanonicalQueryCliDocuments.kt` provides
`getCompleteSerializer`, `getQualifiedSerializer`, `getRejectedSerializer`.
`QueryCompletionRejectionCliDocument.kt` owns `originalCoverage`; the qualified
projection owns `qualification`. Qualified CLI limitations use kebab-case
(`byte-limit-reached`), while rejection's original domain coverage retains the
uppercase enum (`BYTE_LIMIT_REACHED`). The progress owner is
`protocol/contract/.../QueryQualifiedProgressDocument.kt`.

Reproduce offline with an admitted release classpath:

```sh
java -cp "$KAST_RELEASE/lib/*" experiments/host-observation/PiCanonicalFixture.java \
  complete experiments/host-observation/pi-fixtures/complete-document.json /tmp/complete.json
java -cp "$KAST_RELEASE/lib/*" experiments/host-observation/PiCanonicalFixture.java \
  qualified experiments/host-observation/pi-fixtures/qualified-document.json /tmp/qualified.json
java -cp "$KAST_RELEASE/lib/*" experiments/host-observation/PiCanonicalFixture.java \
  rejected_document experiments/host-observation/pi-fixtures/rejected-document.json /tmp/rejected.json
```

The outer `complete`, `qualified`, `rejected_document` envelope variants match
`pi/extension.ts`'s production `ToolRpcResult`. Node tests exercise those fixtures
through the same guard callbacks used by the worker and independently assert
qualification, rejection details and non-exhaustive outcomes.

`installed-sdk-evidence.json` is the sanitized, no-inference output from
`pi_evaluation_sdk_check.mjs`. It pins all three resolved package versions and
source digests, loads the actual installed Kast adapter, constructs real Pi SDK
sessions through the committed worker, and doubles only external provider
transports with synthetic credentials/catalog storage. No actual credentials
are read, and no semantic tools or provider requests execute. It demonstrates
cached WebSocket reuse sending one denied request, and the isolated SSE worker
sending zero denied requests. It is compatibility/transport proof, not live
model, native query or exhaustive-oracle evidence.

# Reviewed shared-client envelope provenance

`delivery-canonical-rpc.json` comes from the actual compiled #995 fixture owner
`QueryDeliveryFixtures.INSTANCE.serialized()`, called by
`PiDeliveryCanonicalFixtures.java`. That owner constructs domain facts, uses the
production Kotlin serializers, and asserts every document against its actual
query schema. No owner checkout or compiled file was modified.

Pinned #995 source: `36a2e6d256d620cf37a4e7e622ee643d4c38c178`.

- `cli/src/main/js/query-delivery.mjs` SHA256: `681d1291a6afabe9614efbdc8d0f12095a421f0ade72fb8e18d7a54fd3b366fd`
- `QueryDeliveryFixtures.kt` SHA256: `e7067d689a70cbc29f269aeb62c0352028145675e8e23ada173f9e0ba480a5bc`
- Actual compiled fixture class SHA256: `11dfeab7a65936ce7628d968de9f29628e9d1943d08478df4edb03d37162656d`
- Canonical JSON SHA256: `868a92ac907a860b35ea08dee088f25661aa1535c011467786fc2000231705ad`

`query-delivery-cases.json` is emitted by that pinned production shared client
using these canonical inputs. It covers complete, qualified, rejected proof,
suffix retention loss, expired result, output/read budget increases, cancellation
before/after a page, lost delivery, aggregate deadline, and oversized initial
presentation with null initial. The oversized input control repeats the owning
client test's deliberate name-byte overflow; its envelope is still produced by
the real client. No positive envelope is hand-shaped.

Reproduce the stored envelope bytes with the exact client source:

```sh
node experiments/host-observation/generate_pi_delivery_fixtures.mjs \
  "$CLIENT_SOURCE" experiments/host-observation/pi-fixtures/delivery-canonical-rpc.json \
  /tmp/query-delivery-cases.json
```

The generator changes no production source; it exposes the two existing
functions only, doubles external RPC transport and checks that every request
after the initial submission is READ_RESULT or output-only RESUME. The
classifier/controller do not contain continuation-management logic. These are
portable client/harness contract observations, not native work, owner-clock
expiry, cancellation settlement, or model usability evidence.

# Public canonical fixture provenance

## 2026-10-27 Outcome forwarding refresh

These schemas include the repaired Outcome forwarding evidence.
The exporter uses exact invocation and callback-capture descriptors as recursive references.
Each reference keeps its complete serializer definition.
The result schema uses `installedSemanticResultSchema(QUERY_RUN)` and the shared call-target definition.

The exporter ran against repaired sources based on `d280e9fb04cfb3d266c84371fe97e103b99ccb59`.
The digests identify the exact exporter, compiled artifact, and output bytes.
These exports prove schema shapes only.
They do not prove installed-host behavior.

| Artifact | SHA-256 |
| --- | --- |
| `PiQuestionSchema.java` | `ae501dfb73a6fd2fe6ddbccb9d8000fdb87c64e8ac198c530b1f0640a0cab54d` |
| `cli-0.50.2-all.jar` | `926a03f3ec0891c03dbe6855ba3356444a95aa17c7a73b78c24164cc49154730` |
| `query-question.schema.json` | `1935bc9f5488d86d1110834ff41a7ae43aed739397594f19e62f0e03cf4ce836` |
| `query-qualification.schema.json` | `33ec05e89fef425c439389b790e68a7b336521f02cc2e46499299d6cf0e90a43` |
| `query-result.schema.json` | `303fbae5fd7140c1eb5e0ae25a0400b0f510e2e7db4050eee78905c4cb886421` |

Reproduce the exports from the repository root:

```sh
mise exec -- ./gradlew -Pversion=0.50.2 :cli:shadowJar
mise exec -- java -cp 'cli/build/libs/cli-0.50.2-all.jar:query/contract/build/classes/kotlin/main' \
  experiments/host-observation/PiQuestionSchema.java \
  experiments/host-observation/pi-fixtures/query-question.schema.json \
  experiments/host-observation/pi-fixtures/query-qualification.schema.json \
  experiments/host-observation/pi-fixtures/query-result.schema.json
```

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
qualified and rejected serialization code with existing local output definitions.
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

The historical three `*-document.json` files are public/synthetic query document DTOs,
serialized offline by the installed release's **actual Kotlin serialization code** with
`PiCanonicalFixture.java`. No service, IDE, compiler query, or model ran. They prove producer-owned serialized shapes, not semantic admission
of their synthetic identity tokens or their row counts.

Historical producer: Kast LOCAL_BUILD 0.50.0, source
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

# Current shared-client envelope provenance

`query-delivery-cases.json` now comes from the candidate shared client.
It includes the necessary semantic header and exact aggregate serialization costs.
The canonical input comes from the current compiled `QueryDeliveryFixtures` owner.
That owner serializes typed facts and admits each document against its current schema.
The three synthetic `*-document.json` files also use the current compiled serialization code.
Their original public facts remain unchanged.
The output records the exact client and canonical SHA-256 values.
These fixtures prove portable delivery behavior, without native semantic work or model inference.

```sh
node experiments/host-observation/generate_pi_delivery_fixtures.mjs \
  cli/src/main/js/query-delivery.mjs \
  experiments/host-observation/pi-fixtures/delivery-canonical-rpc.json \
  experiments/host-observation/pi-fixtures/query-delivery-cases.json --candidate
```

The candidate mode uses the supplied source directly.
The historical mode still needs the pinned source digest.
It also needs the pinned historical canonical input digest.
The deadline case explicitly selects `BOUNDED` because `RUNNER_CONTROLLED` has no implicit semantic deadline.

`native-current-documents.json` contains current typed projections of the pinned native receipts.
The original `query-presentation-native.json` receipts stay unchanged.
The Python oracle compares each original field and budget grant with its current projection.
An absent legacy summary becomes explicit `null`, which means that original counts remain unknown.
The old budget shape uses the concrete `ExecutionBudgetReport.Bounded` decoder.
The normal serializer emits its explicit `BOUNDED` discriminator.
Unknown discriminators still fail admission.

Use the current compiled classpath for these offline exports:

```sh
mise exec -- java -cp "$CURRENT_CLASSPATH" \
  experiments/host-observation/PiDeliveryCanonicalFixtures.java \
  experiments/host-observation/pi-fixtures/delivery-canonical-rpc.json
mise exec -- java -cp "$CURRENT_CLASSPATH" \
  experiments/host-observation/PiCanonicalFixture.java native-projection \
  cli/src/test/resources/query-presentation-native.json \
  experiments/host-observation/pi-fixtures/native-current-documents.json \
  --legacy-nullable-input --legacy-bounded-input
```

The same exporter refreshes each synthetic document with its variant, input path, output path, and `--legacy-nullable-input`.
These exports preserve synthetic and recorded semantic facts. They establish current serialized shapes without new native or model work.

# Historical shared-client envelope provenance

The historical `delivery-canonical-rpc.json` came from the actual compiled #995 fixture owner
`QueryDeliveryFixtures.INSTANCE.serialized()`, called by
`PiDeliveryCanonicalFixtures.java`. That owner constructs domain facts, uses the
production Kotlin serialization code, and asserts every document against its actual
query schema. No owner checkout or compiled file was modified.

Pinned #995 source: `36a2e6d256d620cf37a4e7e622ee643d4c38c178`.

- `cli/src/main/js/query-delivery.mjs` SHA256: `681d1291a6afabe9614efbdc8d0f12095a421f0ade72fb8e18d7a54fd3b366fd`
- `QueryDeliveryFixtures.kt` SHA256: `e7067d689a70cbc29f269aeb62c0352028145675e8e23ada173f9e0ba480a5bc`
- Actual compiled fixture class SHA256: `11dfeab7a65936ce7628d968de9f29628e9d1943d08478df4edb03d37162656d`
- Canonical JSON SHA256: `868a92ac907a860b35ea08dee088f25661aa1535c011467786fc2000231705ad`

The historical `query-delivery-cases.json` came from that pinned production shared client
using these canonical inputs. It covers complete, qualified, rejected proof,
suffix retention loss, expired result, output/read budget increases, cancellation
before/after a page, lost delivery, aggregate deadline, and oversized initial
presentation with null initial. The oversized input control repeats the owning
client test's deliberate name-byte overflow; its envelope is still produced by
the real client. No positive envelope is hand-shaped.

Reproduce the historical envelope bytes with the exact client source and canonical input:

```sh
node experiments/host-observation/generate_pi_delivery_fixtures.mjs \
  "$CLIENT_SOURCE" "$HISTORICAL_CANONICAL_INPUT" \
  /tmp/query-delivery-cases.json
```

Both historical input files must match the pinned digests. The current canonical fixture uses the candidate producer.

The generator changes no production source; it exposes the two existing
functions only, doubles external RPC transport and checks that every request
after the initial submission is READ_RESULT or output-only RESUME. The
classifier/controller do not contain continuation-management logic. These are
portable client/harness contract observations, not native work, owner-clock
expiry, cancellation settlement, or model usability evidence.

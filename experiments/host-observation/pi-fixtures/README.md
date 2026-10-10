# Public canonical fixture provenance

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

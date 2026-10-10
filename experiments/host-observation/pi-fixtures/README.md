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

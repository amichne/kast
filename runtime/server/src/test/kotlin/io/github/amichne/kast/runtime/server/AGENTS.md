# Runtime server contract tests

This package proves contract-only dispatch across generated operation wire bindings.

- Construct test bindings through `GeneratedOperationWireBindingFactory` with explicit generated
  request and response serializers; do not instantiate internal JSON adapters.
- Cover complete, qualified, rejected, malformed, and operation-mismatch frames without importing
  target services or platform adapters.

Run `./gradlew :runtime:server:test --tests '*RuntimeServerContractTest'`.

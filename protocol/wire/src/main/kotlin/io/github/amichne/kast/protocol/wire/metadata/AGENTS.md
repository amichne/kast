# Wire metadata owner

This directory owns generated wire metadata and the IDE endpoint descriptor contract.

- `CanonicalOperationWireBindings` projects the permanent operation registry.
- `IdeEndpointDescriptorV2` is the sole public refinement boundary for the closed v2 endpoint
  document. Keep raw JSON and primitive endpoint fields inside its codec.
- `IdeEndpointDescriptorProjection` emits the deterministic KVP-013 descriptor report through a
  generated serializer. The artifact is a self-contained schema/codec fixture descriptor, not the
  KVP-012 supported host tuple; the predecessor receipt binds that physical compatibility evidence.
- Reuse the module-owned `wireJson`; do not create another `Json` configuration or construct known
  JSON through maps, elements, builders, or handwritten serializers.

Run `./gradlew :protocol:wire:test --tests '*IdeEndpointDescriptorNegativeTest' --tests
'*IdeEndpointDescriptorTest'`.

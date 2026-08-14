# IntelliJ change verification adapter guide

`:change:verify:intellij` owns the KIP-035 pinned compiler-backed verification read. It consumes
live IntelliJ PSI, K2, VFS, document, diagnostics, search, project-model, and classpath state only
inside one scoped smart read and returns detached `:change:verify:spi` evidence.

## Invariants

- The result publication is re-observed before and inside the single smart read; moved or
  unavailable publication fails closed.
- Exact physical postimage bytes and normalized IntelliJ document text must agree with the plan.
- Compiler context preserves exact model, classpath, target postimage, and non-target file hashes.
- Declaration identity comes from the exact appended top-level PSI declaration. Callable collision
  checks compare live K2 types with `KaType.semanticallyEquals`; rendered or hashed signatures are
  forbidden.
- Bounded error diagnostics, outbound unique resolution, and zero rebinding candidates must all be
  proven before issuing typed SPI markers.
- Cancellation is distinct; every other expected adapter failure is finite typed rejection data.

## Verification

Run `./gradlew :change:verify:intellij:test --tests '*AddDeclaration*Verif*'`.

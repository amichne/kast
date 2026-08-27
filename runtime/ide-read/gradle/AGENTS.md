# Runtime IDE-read focused Gradle proof scripts

This directory splits graph-named, task-local proof registration from the size-bounded module build
script. Each script may register only its named task's test selectors and must reuse the owning
module's compiled test classpath. Product dependencies and shared conventions remain in the module
build script or typed build logic.

`kvp028-workspace-inspect.gradle.kts` owns only the KVP-028 misuse and legal acceptance selectors.
`kvp029-symbol-discover.gradle.kts` owns only the KVP-029 hosted-route selectors and binds their
execution to the existing native IntelliJ discovery test suite.
`kvp030-symbol-resolve.gradle.kts` owns only the KVP-030 candidate-capability misuse and exact
resolution selectors and binds them to the existing native IntelliJ exact-selector suite.

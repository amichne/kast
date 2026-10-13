# Available developer TRACE cases

The command replays compiler fixtures, retained presentation, accounting, schema admission, and controlled read permits.
It does not replay the unavailable enterprise requests or provision an installed Host.

Run the command from the repository root with the pinned native SDK under `relation/intellij/build/native-test/idea`.
The SDK is `IC-262.9437.185`.
Project-owned mise supplies Java and Node.

```sh
mise exec -- ./gradlew \
  :runtime:hosted:nativeFixtureTest --tests '*NativeScopedCacheManagerTraceTest' \
  :relation:intellij:nativeFixtureTest \
    --tests '*NativeDependencyCallbackTest' \
    --tests '*NativeCallbackCoverageTest' \
    --tests '*NativeCallbackExpiryTest' \
  :symbol:intellij:nativeFixtureTest \
    --tests '*DiscoveryScopeGrantNativeTest' \
    --tests '*DiscoveryDirectoryNativeTest' \
  :query:contract:test --tests '*QueryCoverageRetentionTest' \
  :query:protocol:test \
    --tests '*RetainedGraphDelivery*Test' \
    --tests '*RetainedGraphNodeRetrievalTest' \
    --tests '*RetainedSymbolGraph*Test' \
  :protocol:wire:test --tests '*RetainedRelationGraphProjectionTest' \
  :workspace:intellij-read:test \
    --tests '*HostedQueryLifetimeTest' \
    --tests '*HostedQueryExecutorTest.two readers overlap at configured capacity and cancellation owns only one slot' \
    --tests '*HostedQueryExecutorTest.retirement drains every admitted read and rejects their late publication' \
    --tests '*HostedQueryExecutorTest.caller cancellation drains owned analysis before releasing admission' \
    --tests '*HostedReadRecoveryTest.exhaustion and cancellation have different actionable recovery' \
  :cli:test \
    --tests '*RetainedRelationGraphSchemaTest' \
    --tests '*RetainedSymbolGraphSchemaTest' \
    --tests '*QueryReferenceSurfaceTest' \
    --tests '*PiQuestionSchemaParityTest' \
  :cli:queryPresentationTest \
  -PnativeFixtureIdeaHome="$PWD/relation/intellij/build/native-test/idea" \
  --console=plain
```

Each `--tests` selector belongs to the preceding Gradle test task.
JUnit receipts identify the selected cases and outcomes.
The retained measurements preserve the canonical page allowance and aggregate client allowance.
Scripted client calls establish delivery behavior without physical RPC or semantic evaluation.

The [result record](VALIDATION.md) names the separate promoted live proof and its source identity.
Its private Host and fixture no longer exist after recorded cleanup.
An installed replay needs a new owned profile, matching artifacts, imported Gradle roots, and fresh references.

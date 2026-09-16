# v0.42 scoped repair acceptance

Scope is the six workstreams in the supplied `kast-v042-scoped-workstreams.md`, based on current main `4bd888349`. No release publication or user workspace/settings changes are included.

## Verification boundaries

- WS1: nullable/invalid PSI regression, exact service mapping and public wire rejections pass. Original request recovered from the stress thread: exact function `main`, `cli/src/main`, source set `main`, 60 s / 1,000,000 work / 128 results / 1 MiB. The original host later rejected model admission; source-level regression reproduces its nullable-range failure.
- WS2: complete evidence alone permits planning; all rejected/incomplete relation, traversal and diagnostic reasons survive public hosted detail. Existing page continuations cannot prove complete accumulated planning evidence and report `COMPLETE_EVIDENCE_ACCUMULATION_UNAVAILABLE`. Mutation and recovery require the matching native fixture gate below.
- WS3: funded diagnostics advance within one request; shared-budget continuation, no duplicate output and stale checkpoint tests pass. No blanket default changes. Original 10 s / 100,000 work / 10 results / 49,152 byte query expanded `CanonicalSymbolId` references; original diagnostics selected its declaration file with 20 results. Native timing evidence must distinguish these recovered requests from fixture measurements.
- WS4: explicit discriminator metadata and strict unknown-keyword test; existing invalid variants remain rejected. PR #788 carries this repair.
- WS5: retirement lifecycle/recovery tests, inherited/explicit selector behavior and locked owned-alias ingress tests pass. Current main already accepts the reported `semantic_query` and `impact_analyze` tool aliases; that outcome was skipped. The stress transcript unset both the configuration selector and tool list together, so it did not isolate their effects. Native running/stopped IDEA upgrade remains a separate gate.
- WS6: asynchronous lifecycle state and CLI tests pass. Native fixture adds external-file visibility, a real Gradle module change and failed import/restoration. Cancellation and concurrency state tests are unit evidence; they do not substitute for a native import cancellation observation.

The final delivery report records actual artifact/native commands and results. An unavailable native gate is not a pass.

## Integrated evidence

At clean source `0c93796b58bb92d24587de4b72d159aa0e8653b2`, `uv run --no-project --with jsonschema ./gradlew productBuildGate --console=plain` passed (545 tasks, 42 seconds). This includes architecture, JSON contracts, generated callable contracts, configuration ingress, formatting, static analysis and module checks. `./gradlew knowledgeImpact verifyKnowledgeBase` and `mint validate` also passed. The architecture policy suite passed 97 tests.

Matching private native artifacts were built with `./gradlew stageKastControlProduct :runtime:hosted:hostedPlugin :app-server:hostedChangeHarnessJar :change:intellij:nativeFixturePlugin -PhostedIdeaHome='/Users/amichne/Applications/IntelliJ IDEA.app/Contents'` (29 seconds). The private runner used IntelliJ build `262.10315.125`, isolated Python with `jsonschema`, and the existing raw Codex schemas. No user project, plugin installation or settings were changed.

The full native report `kast-v042-scoped-native-0c93796b5-run2.json` passed 252/252 read cases, 156/156 concurrent first-attempt reads, saved-configuration continuity, AddDeclaration approval/apply/verification, interruption and divergent-document recovery, fresh-owner recovery, duplicate/concurrent application, plugin retirement, process replacement and provider routing. Only the new workspace refresh matrix remained unqualified: imported module visibility, duplicate identity, conflicting-request refusal, failed import and restoration passed, but external file visibility failed. The report retains that failure; a scoped follow-up must supply independent refresh evidence before calling that gate passed.

The private read fixture retained 16 completed timing observations across CLI/provider search, source, relation and traversal requests with requested 10/20-second grants. Existing deadline policy admitted 3,748–3,749 ms; observed round trips were 550.744–624.420 ms. Exact-file diagnostics completed through both surfaces with no errors, but the fixture did not retain diagnostic elapsed time. These are fixture measurements, not original user-repository timing replays.

Artifact SHA-256 identities for that full native run:

- Product inventory: `afbb8f0427a426e7ad094d3f9afbfce42bb25a6969a06b0d24c00f010f2538cd`.
- Hosted plugin: `1272ce74d3541cc2196d08be2ca2f4b70f7ae3d9504a63dc4d31a17dc8947123`.
- Harness: `0e8d845c76c37acd8fd872c82007dba90d0cf8fda004ad652182bbd5d49600b8`.
- Private probe: `d93b61e0762da785334e2ecaa8478f891f44db5156d6490085c62941225411cd`.

Original user-repository timing replays, an actual native import cancellation, and installation upgrades with IDEA running/stopped remain distinct from these private-fixture and unit results. No qualification is inferred for those observations.

The existing native upgrade harness rejects previous-release inputs in source-product mode. Its release mode requires checksum-bound artifacts at an exact clean version tag and performs installation before launching IDEA; it has no running-IDE upgrade transition. Extending that harness or creating a release is outside this scoped source-artifact verification. Lifecycle and interrupted-journal tests remain the available WS5 upgrade evidence.

## Deferred findings

- Public troubleshooting frontmatter still cites retired implementation paths — `docs/public/troubleshooting.mdx` existing `code_sources` — independent source-binding documentation review.
- Existing build output reports deprecated Gradle/Kotlin APIs outside changed owners — focused Gradle logs — independent maintenance when upgrading those owners.

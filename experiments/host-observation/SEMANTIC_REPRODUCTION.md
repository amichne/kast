# Native semantic-query reproduction

`reproduce_semantic_queries.py` creates a compiling synthetic Kotlin fixture, pins a loaded CLI/plugin pair independently, and replays public queries against an already imported IDEA project. Its [report](../../docs/reviews/hosted-semantic-reproduction.md) separates release observations from instrumented mechanism evidence.

Use Python 3.11+ with the existing `requirements-test.txt` (`jsonschema`), JDK 25, and `kotlinc` for the optional production-provider harness. Use the packaged v0.38.0 control CLI and its checksum-verified hosted plugin for IDEA build 262.10315.125. Install/load the plugin explicitly using the existing [endpoint workflow](HOSTED_ENDPOINT.md); do not replace a running user's installation implicitly.

Create a fresh fixture outside the repository and every other open project:

```sh
python3 experiments/host-observation/reproduce_semantic_queries.py setup \
  --fixture /private/tmp/kast-semantic-fixture \
  --output /private/tmp/kast-semantic-setup
```

Explicitly open and import that fixture's Gradle build in IDEA. Wait for import and indexing to finish, and save/commit documents. All imports and fixture edits must finish before replay, including imports in other projects that can advance the global PSI modification epoch. Setup never imports; replay never opens, imports, refreshes, invalidates caches, or starts an isolated worker.

```sh
python3 experiments/host-observation/reproduce_semantic_queries.py pin \
  --fixture /private/tmp/kast-semantic-fixture \
  --cli /path/to/v0.38.0/control/bin/kast \
  --idea-contents '/path/to/IntelliJ IDEA.app/Contents' \
  --output /private/tmp/kast-semantic-pin
```

One replay command runs the complete matrix, retains canonical CLI responses, and exercises the production provider without a Codex session:

```sh
python3 experiments/host-observation/reproduce_semantic_queries.py replay \
  --fixture /private/tmp/kast-semantic-fixture \
  --cli /path/to/v0.38.0/control/bin/kast \
  --pin /private/tmp/kast-semantic-pin/pin.json \
  --surface provider --repeats 2 \
  --output /private/tmp/kast-semantic-replay
```

Use `--surface cli` to exercise direct CLI behavior. Each output directory must be fresh. The provider harness compiles against the selected distribution's actual App Server jar and delegates to its production process executor, recording the underlying command, stdin, stdout, stderr, exit, elapsed time and envelope. Its process startup/qualification time is separate from native semantic-stage duration. `pin.json` records executable/JAR hashes, loaded plugin class hashes, IDEA/Kotlin/JBR versions, imported Gradle projects/source sets, IDEA modules/roots, and effective limits. Source checkout hashes are labelled unproven correspondence until matched to an actual build/release receipt. The runner does not equate a CLI version with a plugin version.

`receipts.json` contains one classification per case, independent expected identities, actual identities/counts, occurrences, aggregate and connection coverage, host/epoch, assertions, and diagnostic correlation. Raw `request.json`, `response.json`, `process.json` and provider process receipts remain beside each case. A nonmatching observation is a successful measurement (exit 0); missing execution prerequisites give exit 2. Stale authority/epoch drift is blocked evidence. Keep local receipts private: they include host paths and opaque references. No receipts belong in public commits.

`semantic-fixture/expected.json` is an independently authored source oracle. It includes five helper calls, six alias type uses (four constructor owners, a builder and a top-level function), four concrete descendants and three overrides. It never decodes references or derives the expected declarations from Kast. The runtime-issued tokens must survive byte-for-byte, and repeated references are a separate deduplication control. Fields are requested independently. Constructor-property discovery failures remain visible in `ALL`; the oracle does not remove them to match output.

For causal experiments, run setup in separate directories, import explicitly, then pin and replay serially. Change one dimension at a time:

| Setup option | Independent variable |
|---|---|
| `--callee kotlin` (default) | One Kotlin helper call; complete control |
| `--callee java` | Adds only `java.lang.System.nanoTime()` |
| `--callee outside` | Adds a Kotlin helper in another retained package/module |
| `--java-references` | Adds Java-only manager-class references |
| `--noise-modules N` | Unrelated Gradle projects; IDEA module count is measured separately |
| `--noise-names N --noise-kind class\|function` | Unrelated declarations inside one admitted module |

`--cases all-core-alias,core-alias` selects the same tiny package/type-alias ALL, exact and fuzzy controls at each load. Other useful prefixes are `trace-callees`, `manager-references` and `unused-references`. `first` means first invocation in that replay, not a cold IDE cache. No timing threshold is tied to developer hardware. Production budgets remain unchanged.

For diagnosis **after** capturing unchanged-release behavior, build the instrumented plugin with `:runtime:hosted:hostedPlugin -PhostedIdeaHome=...`, explicitly load it, and pin it into a new evidence directory. Its version may equal the release version; its hashes do not. Current builds log diagnostics by default; earlier opt-in diagnostic builds require their historical enable property. Add `--idea-log /path/to/idea.log` during replay. Restore temporary experiment settings and the selected plugin after the experiment, unless the task explicitly calls for installing the corrected build.

One `kast_semantic_read` JSON record is emitted after the read drains. Finite enums identify stages, contributors, counters and termination reasons; counts saturate. The record has no names, source text, paths or references. It records remaining outer time at semantic entry, module/root admission, name/candidate caps, filtering, K2 refinement, relation facts, native unsupported targets, and time/work/result/byte limits. The collector reads only a bounded appended log segment and reports rotation/overflow gaps. Name/candidate caps may map to public `work-limit-reached`; the diagnostic reason preserves the narrower cause. Host-only correlation before authority admission never invents an epoch. A diagnostic `completed` outcome means the hosted boundary returned; it does not assert complete semantic coverage. Contributor-specific completion can coexist with another contributor's cap. Current execution logs by default. The effective policy and bounded unexpected-failure frames accompany the request receipt; see [configuration](../../docs/hosted-read-configuration.md).

Run the focused deterministic checks with:

```sh
./gradlew hostObservationTest :workspace:intellij-read:test \
  --tests '*HostedReadDiagnosticsTest' --tests '*HostedQueryExecutorTest' \
  :symbol:intellij:test --tests '*SymbolDiscoveryTest' :relation:intellij:test
./gradlew productBuildGate knowledgeImpact verifyKnowledgeBase
```

The fake-clock deadline test proves ordering and owner recovery only. Native receipts establish actual discovery and K2 behavior. This runner is opt-in; it adds no routine native CI matrix.

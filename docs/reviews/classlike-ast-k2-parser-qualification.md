# Exact IDE K2 parser compatibility

Installed qualification of #1009 preserved a complete exhaustive result through the old PSI visitor. Its AST path did not activate: the active IDE K2 parser failed the exact base-parser check before hashing or mapping probes. That run establishes no AST speed benefit.

The additional supported class is `org.jetbrains.kotlin.idea.base.analysisApiPlatform.IDEKotlinK2KotlinParserDefinition`. Its JVM class is final and extends `KotlinParserDefinition` directly. It declares a constructor, `createFile(FileViewProvider)` and a private injection-context helper. `createElement(ASTNode)`, `createParser(Project)`, `createLexer(Project)` and `getFileNodeType()` remain inherited from the already fingerprinted `KotlinCommonParserDefinition`.

The `createFile` override checks whether a provider is injected. Ordinary providers produce `KtFile(provider, false)`, as the base parser does. An injected provider which should not receive full analysis produces a `KtBlockCodeFragment` with an injection context. This extension does not infer ordinary-file semantics for injections. Existing physical, non-script, non-compiled file eligibility and conservative per-node classification remain unchanged.

Read-only bytecode inspection found the same 3,775-byte class in the repository SDK, build `262.9437.185`, and installed IDEA `IU-262.10968.63`. Its SHA256 is `b63bd58b0258bc6e2c3629587e5e3c61249a3364370cecf315d5d1cd1b829ddb`. Installed `kotlin.plugin.k2.xml` and `META-INF/plugin.xml` register that class as the Kotlin parser. All ten existing parser/platform fingerprints and four loader fingerprints also match the installed archives. None of those witness archives declares multi-release behavior. These are artifact compatibility observations, under the [trusted-runtime precondition](classlike-ast-runtime-qualification.md), not loaded-definition attestation or installed activation proof.

The native handoff identifies the loaded parser name and defining-loader class, but does not measure its defining resource URL or loader object identity. The local archive lookup proves a matching artifact is present. It does not close those loaded-origin observation gaps. The additional K2 witness archives have no multi-release flag and no versioned class entries.

## Admission and judgment calls

- Admit the two exact class identities. Resolve the Kotlin-internal K2 class through the bundled base parser's defining loader and compare actual JVM class identity. A name match, `is` check, or inherited method shape alone cannot admit another subclass or loader.
- Fingerprint the K2 class as an additional witness through the existing bounded origin, loader and archive checks. Increase the maximum witness count from 14 to 15: eleven parser/platform classes plus four audited loader classes. Per-class and chunk limits remain unchanged.
- Cache immutable qualifications separately for the two closed parser variants. A successful base-parser cache entry cannot bypass the K2 fingerprint or mapping probes. Incomplete and cancelled qualifications remain uncached.
- Retain unknown-parser, unknown-loader, startup-agent, transformer, multi-release and factory fallbacks. Add a typed parser rejection counter and a binding call timer at the existing dispatcher boundary. Neither diagnostic contains source names or paths.
- Keep exhaustive traversal, lazy/error fallback, admission, projection, finite budgets and cancellation ownership unchanged. No lexical absence shortcut is introduced.

## Executable qualification

`ClasslikeAstK2ParserTest` constructs the actual SDK class reflectively, without visibility suppressions. The parser is registered before physical fixture files are created. Its dense dispatcher case requires 18,063 AST entries, two explicit AST-to-PSI requests, zero legacy/parser fallbacks and an identical complete outcome to the old visitor. The cached-wrapper census runs on a pre-expanded AST. Separate candidate-first cases require initially unloaded contents for recovered enums, lazy function bodies, erroneous bodies, constructors and initializers.

The same test requires fresh K2 qualification after a base cache hit: 173,712 class bytes and 116 mapping probes under the SDK application loader. Later reads reuse only that variant's metadata. A same-named K2 class defined by an unknown loader must preserve the old outcome and emit the parser fallback on repeated reads, even after supported metadata exists.

`ClasslikeAstK2ParserArchiveTest` launches disposable JDK children with an ordinary SDK control and an owned jar containing the exact named K2 class with a changed injection-context literal. The changed class retains inherited factories and ordinary file behavior. Its own fingerprint must still reject it before mapping probes. Both alternatives must preserve the complete old-visitor outcome.

The existing cancellation/retry and startup-agent fixtures now exercise both exact variants. Cancellation preserves the original exception, halted qualification establishes no cache entry, and retry performs complete source/factory qualification. Both variants retain the explicit startup-agent fallback with zero AST, hash and mapping work. The original loader, multi-release, differential and finite-outcome controls remain required.

```sh
./gradlew :symbol:intellij:test --tests '*ClasslikeAst*Test'
./gradlew :symbol:intellij:check :workspace:intellij-read:check :runtime:hosted:check verifyJsonContracts knowledgeImpact verifyKnowledgeBase
```

## Installed qualification still required

The native owner must bind the exact immutable build and loaded Host source, runtime and parser identities. Repeat the frozen eligible query and require nonzero AST traversal, zero whole-file legacy/parser/startup-agent fallbacks, the same candidate order, and exhaustive coverage. Retain the binding timer and its byte/mapping counters to distinguish first-use qualification from traversal. Only then continue the declared warm, negative and larger controls. Source compatibility and pure dispatcher activation do not prove native latency or an enterprise improvement. No installation, IDE restart, native query or inference is performed by this source change.

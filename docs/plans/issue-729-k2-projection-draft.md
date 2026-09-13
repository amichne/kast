# Issue #729: K2 canonical compiler projection draft

Status: design-only draft against `main` at `58de29715b0dc526234df50a58ebe8d030878a62`.

This file makes the first implementation slice of #729 concrete. It intentionally does not change production behavior yet.

## Boundary

The extraction sits below selector encoding and below hosted reference transport:

```text
IntelliJ / K2 analysis session
          |
          v
KaSymbol
          |
          v
+--------------------------------------+
| shared K2 canonical projection       |
|                                      |
| kind                                 |
| qualified identity                   |
| CanonicalCompilerSignature           |
| CompilerSymbolIdentity               |
+--------------------------------------+
          |
          +--------------------+
          |                    |
          v                    v
 exact symbol evidence    relation comparison
          |
          v
 canonical selector encoding
          |
          v
 QueryReferenceTransport
          |
          v
 optional exact:v4 / candidate:v4 handle
```

`exact:v4` and `candidate:v4` are transport handles over already encoded canonical selector tokens. They are not compiler identities and are outside this abstraction.

## Proposed file/module shape

The important property is a narrow shared dependency. The module name is provisional.

```text
symbol/
  contract/
    CanonicalCompilerSignature.kt          # unchanged authority
    CompilerSymbolIdentity.kt              # unchanged authority

  intellij-identity/                       # new, narrow K2 adapter
    build.gradle.kts
    src/main/kotlin/.../
      K2CanonicalCompilerProjection.kt
    src/test/kotlin/.../
      K2CanonicalCompilerProjectionTest.kt

  intellij/
    .../exact/IntellijKotlinCompilerSymbolLookup.kt

relation/
  intellij/
    .../IntellijK2SymbolIdentity.kt
```

Dependency direction:

```text
:symbol:contract
      ^
      |
:symbol:intellij-identity
      ^                 ^
      |                 |
:symbol:intellij   :relation:intellij
```

Do not make `:relation:intellij` depend on the complete `:symbol:intellij` implementation.

## New shared projection

Pseudo-code close to the intended implementation:

```kotlin
package io.github.amichne.kast.symbol.intellij.identity

internal data class K2CanonicalCompilerProjection(
    val kind: CompilerSymbolKind,
    val qualifiedIdentity: String,
    val signature: CanonicalCompilerSignature,
    val identity: CompilerSymbolIdentity,
)

internal sealed interface K2CanonicalCompilerProjectionResult {
    data class Projected(
        val projection: K2CanonicalCompilerProjection,
    ) : K2CanonicalCompilerProjectionResult

    data object Unsupported : K2CanonicalCompilerProjectionResult
}

internal fun KaSymbol.toCanonicalCompilerProjection(): K2CanonicalCompilerProjectionResult =
    when (this) {
        is KaValueParameterSymbol ->
            generatedPrimaryConstructorProperty?.toCanonicalCompilerProjection()
                ?: K2CanonicalCompilerProjectionResult.Unsupported

        is KaConstructorSymbol -> {
            val owner = containingClassId?.asSingleFqName()?.asString()
                ?: return K2CanonicalCompilerProjectionResult.Unsupported

            project(
                kind = CompilerSymbolKind.CONSTRUCTOR,
                qualifiedIdentity = "$owner.<init>",
                signature = functionSignature("$owner.<init>"),
            )
        }

        is KaFunctionSymbol -> {
            val callable = callableId?.asSingleFqName()?.asString()
                ?: return K2CanonicalCompilerProjectionResult.Unsupported

            project(
                kind = CompilerSymbolKind.FUNCTION,
                qualifiedIdentity = callable,
                signature = functionSignature(callable),
            )
        }

        is KaKotlinPropertySymbol -> {
            val callable = callableId?.asSingleFqName()?.asString()
                ?: return K2CanonicalCompilerProjectionResult.Unsupported

            project(
                kind = CompilerSymbolKind.PROPERTY,
                qualifiedIdentity = callable,
                signature = CanonicalCompilerSignature.property(
                    rawQualifiedIdentity = callable,
                    rawReceiverType = receiverParameter?.returnType?.toString(),
                    rawContextReceiverTypes = contextReceivers.map { it.type.toString() },
                    rawReturnType = returnType.toString(),
                ),
            )
        }

        is KaTypeAliasSymbol -> {
            val className = classId?.asSingleFqName()?.asString()
                ?: return K2CanonicalCompilerProjectionResult.Unsupported

            project(
                kind = CompilerSymbolKind.TYPE_ALIAS,
                qualifiedIdentity = className,
                signature = CanonicalCompilerSignature.typeAlias(className),
            )
        }

        is KaClassLikeSymbol -> {
            val className = classId?.asSingleFqName()?.asString()
                ?: return K2CanonicalCompilerProjectionResult.Unsupported

            project(
                kind = CompilerSymbolKind.CLASSLIKE,
                qualifiedIdentity = className,
                signature = CanonicalCompilerSignature.classLike(className),
            )
        }

        else -> K2CanonicalCompilerProjectionResult.Unsupported
    }

private fun KaFunctionSymbol.functionSignature(
    qualifiedIdentity: String,
): Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure> =
    CanonicalCompilerSignature.function(
        rawQualifiedIdentity = qualifiedIdentity,
        rawReceiverType = receiverParameter?.returnType?.toString(),
        rawContextReceiverTypes = contextReceivers.map { it.type.toString() },
        rawValueParameterTypes = valueParameters.map { it.returnType.toString() },
        rawTypeParameterCount = (this as? KaNamedFunctionSymbol)?.typeParameters?.size ?: 0,
    )

private fun project(
    kind: CompilerSymbolKind,
    qualifiedIdentity: String,
    signature: Refinement<CanonicalCompilerSignature, CanonicalCompilerSignatureFailure>,
): K2CanonicalCompilerProjectionResult =
    when (signature) {
        is Refinement.Refined -> {
            val canonical = signature.value
            K2CanonicalCompilerProjectionResult.Projected(
                K2CanonicalCompilerProjection(
                    kind = kind,
                    qualifiedIdentity = qualifiedIdentity,
                    signature = canonical,
                    identity = CompilerSymbolIdentity.fromCanonicalSignature(canonical),
                )
            )
        }

        is Refinement.Rejected -> K2CanonicalCompilerProjectionResult.Unsupported
    }
```

The K2 symbol and all K2 types remain analysis-session-local. The returned projection contains only detached contract values.

## Exact lookup: before and after

Current responsibility in `IntellijKotlinCompilerSymbolLookup.kt`:

```text
find PSI declaration
  -> acquire K2 symbol
  -> classify K2 symbol
  -> build canonical signature
  -> build exact evidence
```

Target responsibility:

```text
find PSI declaration
  -> acquire K2 symbol
  -> shared projection
  -> map Unsupported to COMPILER_IDENTITY_UNAVAILABLE
  -> build exact evidence
```

Conceptual diff:

```diff
- private data class IntellijCompilerSymbolProjection(...)
- private sealed interface IntellijCompilerSymbolProjectionResult ...
- private fun KaSymbol.toCompilerProjection() ...
- private fun KaFunctionSymbol.functionSignature(...) ...
- private fun projected(...) ...
- private fun compilerProjectionRejected() ...

  analyze(declaration.kaModule(null)) {
      val symbol = when (declaration) {
          is KtNamedDeclaration -> declaration.symbol
          is PsiClass -> declaration.namedClassSymbol
          is PsiMember -> declaration.callableSymbol
          else -> null
      }

-     symbol?.toCompilerProjection() ?: compilerProjectionRejected()
+     symbol?.toCanonicalCompilerProjection()
+         ?: K2CanonicalCompilerProjectionResult.Unsupported
  }
```

The consumer then performs its own failure mapping:

```kotlin
val projection = when (val result = ...) {
    is K2CanonicalCompilerProjectionResult.Projected -> result.projection
    K2CanonicalCompilerProjectionResult.Unsupported -> {
        observation.count(IntellijReadCounter.COMPILER_REFINEMENTS_REJECTED)
        return rejected(IntellijSymbolSelectorRejection.COMPILER_IDENTITY_UNAVAILABLE)
    }
}
```

`CompilerGroundedSymbolEvidence.fromBoundary(...)` remains in exact lookup because file/range/name admission is exact-lookup evidence, not generic symbol identity.

## Relation identity: before and after

Current `IntellijK2SymbolIdentity.kt` owns another full projection implementation.

Target file becomes approximately:

```kotlin
internal enum class IntellijSymbolIdentityComparison {
    SAME,
    DIFFERENT,
    UNSUPPORTED,
}

internal fun KaSymbol.compareIdentity(other: KaSymbol): IntellijSymbolIdentityComparison {
    val left = when (val result = toCanonicalCompilerProjection()) {
        is K2CanonicalCompilerProjectionResult.Projected -> result.projection.identity
        K2CanonicalCompilerProjectionResult.Unsupported ->
            return IntellijSymbolIdentityComparison.UNSUPPORTED
    }

    val right = when (val result = other.toCanonicalCompilerProjection()) {
        is K2CanonicalCompilerProjectionResult.Projected -> result.projection.identity
        K2CanonicalCompilerProjectionResult.Unsupported ->
            return IntellijSymbolIdentityComparison.UNSUPPORTED
    }

    return if (left == right) {
        IntellijSymbolIdentityComparison.SAME
    } else {
        IntellijSymbolIdentityComparison.DIFFERENT
    }
}
```

The relation-local `IntellijCompilerProjection`, signature helper, kind switch, and identity construction disappear.

## Hosted reference transport: deliberately unchanged

No changes are expected in:

```text
query/protocol/.../QueryReferenceTransport.kt
runtime/hosted/.../HostedReferenceStore.kt
protocol/contract/.../HostedSymbolHandle.kt
```

The current transport still behaves as:

```text
canonical selector token
  -> compactSymbolReference(token)
  -> SHA-256-derived v4 handle
  -> current-epoch handle table stores canonical token

v4 handle
  -> table lookup
  -> canonical token
  -> canonical selector decode
  -> authority / freshness validation
```

A test for this issue should prove that extracting K2 projection does not alter the canonical selector that is placed behind the handle. It should not require stable v4 wire text across unrelated epoch/store state.

## Test shape

The shared adapter should have independent semantic fixtures for the identity-producing cases rather than merely running both consumers through the same implementation.

Minimum matrix:

| Declaration | Expected proof |
| --- | --- |
| overloaded functions | different canonical signatures/identities |
| constructor | `<owner>.<init>` identity |
| generic function | type-parameter count retained |
| extension function/property | receiver retained |
| context receiver | ordered context receivers retained |
| constructor `val`/`var` parameter | normalizes through `generatedPrimaryConstructorProperty` |
| local/unavailable identity | `Unsupported` |

Consumer characterization remains separate:

```text
exact lookup Unsupported
  -> COMPILER_IDENTITY_UNAVAILABLE

relation Unsupported
  -> IntellijSymbolIdentityComparison.UNSUPPORTED
```

Existing compact-reference tests remain transport tests; do not fold them into projector unit tests.

## First implementation PR: expected changed files

The implementation PR should be deliberately small:

```text
settings.gradle.kts                                      # include narrow module, if a module is used
symbol/intellij-identity/build.gradle.kts                # minimal K2 adapter dependencies
symbol/intellij-identity/.../K2CanonicalCompilerProjection.kt
symbol/intellij-identity/.../K2CanonicalCompilerProjectionTest.kt
symbol/intellij/.../IntellijKotlinCompilerSymbolLookup.kt
relation/intellij/.../IntellijK2SymbolIdentity.kt
symbol/intellij/build.gradle.kts                         # narrow dependency
relation/intellij/build.gradle.kts                       # narrow dependency
relevant architecture/knowledge declarations only if mechanically required
```

Explicitly not expected:

```text
runtime/hosted/.../HostedReferenceStore.kt
query/protocol/.../QueryReferenceTransport.kt
protocol wire schemas
app-server reference schemas
topology runtime dependencies
```

## Stop condition

The first implementation PR is complete when:

1. the duplicated K2 kind/signature switch exists once;
2. exact lookup and relation identity consume it;
3. canonical signatures and compiler identities match independent expected fixtures;
4. existing selector/handle restoration tests still pass unchanged;
5. no transport, topology, scope-planning, or unrelated cleanup is included.

Only then should #729 proceed to the separate scope-ownership extraction.
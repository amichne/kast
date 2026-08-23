@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologySymbol
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import java.nio.file.Path

internal sealed interface TopologySymbolProjection {
    data class Projected(val symbol: TopologySymbol) : TopologySymbolProjection
    data object Unsupported : TopologySymbolProjection
    data object Rejected : TopologySymbolProjection
}

internal sealed interface TopologyK2IdentityProjection {
    data class Projected(val identity: CompilerSymbolIdentity) : TopologyK2IdentityProjection
    data object Unsupported : TopologyK2IdentityProjection
    data object Rejected : TopologyK2IdentityProjection
}

internal sealed interface TopologyOverrideProjection {
    data class Projected(
        val identities: List<CompilerSymbolIdentity>,
    ) : TopologyOverrideProjection

    data object Rejected : TopologyOverrideProjection
}

internal fun isRepositoryDeclaration(declaration: KtNamedDeclaration): Boolean =
    declaration is KtClassOrObject || declaration is KtConstructor<*> ||
        declaration is KtNamedFunction || declaration is KtProperty || declaration is KtTypeAlias

/**
 * Proof transition: `(TopologySourceFile, KtNamedDeclaration) -> TopologySymbolProjection`.
 *
 * Projected establishes the same overload-aware K2 identity used by exact symbol selection,
 * detached onto the exact admitted file. Unsupported local/unaddressable declarations and
 * rejected detached facts remain closed; no PSI or K2 value escapes.
 */
internal fun projectTopologySymbol(
    file: TopologySourceFile,
    declaration: KtNamedDeclaration,
): TopologySymbolProjection {
    val projection = when (val result = analyze(declaration) {
        declaration.symbol.topologyProjection(file)
    }) {
        is TopologyCompilerProjectionResult.Projected -> result.projection
        TopologyCompilerProjectionResult.Unsupported -> return TopologySymbolProjection.Unsupported
    }
    val compilerIdentity = when (val parsed = CompilerSymbolIdentity.parse(projection.identity)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return TopologySymbolProjection.Rejected
    }
    val absolute = Path.of(file.workspace.lease.workspaceRoot.value).resolve(file.path.value)
    val fileIdentity = when (val detached = SymbolDiscoveryFileIdentity.fromBoundary(
        file.workspace.lease.workspaceRoot,
        absolute,
        absolute.toUri().toString(),
    )) {
        is Refinement.Refined -> detached.value
        is Refinement.Rejected -> return TopologySymbolProjection.Rejected
    }
    val evidence = when (val detached = CompilerGroundedSymbolEvidence.fromBoundary(
        fileIdentity,
        declaration.textRange.startOffset,
        declaration.textRange.endOffset,
        declaration.name.orEmpty(),
        projection.qualifiedIdentity,
        projection.kind,
        compilerIdentity,
    )) {
        is Refinement.Refined -> detached.value
        is Refinement.Rejected -> return TopologySymbolProjection.Rejected
    }
    return when (val symbol = TopologySymbol.admit(file, evidence)) {
        is Refinement.Refined -> TopologySymbolProjection.Projected(symbol.value)
        is Refinement.Rejected -> TopologySymbolProjection.Rejected
    }
}

/**
 * Proof transition: `(KaSymbol, TopologySourceFile) -> TopologyK2IdentityProjection`.
 *
 * Projected establishes one refined detached [CompilerSymbolIdentity]. Unsupported and Rejected
 * close unaddressable and invalid compiler identities. Raw K2 identity extraction remains inside
 * the current analysis session.
 */
internal fun KaSymbol.topologyIdentityProjection(
    file: TopologySourceFile,
): TopologyK2IdentityProjection {
    val projection = when (val result = topologyProjection(file)) {
        is TopologyCompilerProjectionResult.Projected -> result.projection
        TopologyCompilerProjectionResult.Unsupported -> return TopologyK2IdentityProjection.Unsupported
    }
    return when (val identity = CompilerSymbolIdentity.parse(projection.identity)) {
        is Refinement.Refined -> TopologyK2IdentityProjection.Projected(identity.value)
        is Refinement.Rejected -> TopologyK2IdentityProjection.Rejected
    }
}

/**
 * Proof transition: `(KaSymbol, TopologyProjectionRegistry) ->
 * TopologyK2IdentityProjection`.
 *
 * Projected establishes one source-scoped compiler identity owned by the exact admitted candidate
 * generation. Unsupported closes library, synthetic, missing, and outside-generation symbols;
 * Rejected closes invalid detached identities. Raw K2 and PSI extraction remains inside the
 * current analysis session.
 */
internal fun KaSymbol.topologyIdentityProjection(
    registry: TopologyProjectionRegistry,
): TopologyK2IdentityProjection = when (val source = topologySourceFile(registry)) {
    is TopologyK2SourceFileProjection.Found -> topologyIdentityProjection(source.file)
    TopologyK2SourceFileProjection.Unsupported -> TopologyK2IdentityProjection.Unsupported
}

/**
 * Proof transition: `(KtNamedDeclaration, TopologyProjectionRegistry) ->
 * TopologyOverrideProjection`.
 *
 * Projected establishes the canonical refined K2 identities directly overridden by this
 * declaration. Rejected closes an invalid K2 identity. Raw override symbols remain inside the
 * current analysis session.
 */
internal fun KtNamedDeclaration.directOverrideTopologyIdentities(
    registry: TopologyProjectionRegistry,
): TopologyOverrideProjection =
    analyze(this) {
        val identities = linkedSetOf<CompilerSymbolIdentity>()
        for (overridden in (symbol as? KaCallableSymbol)?.directlyOverriddenSymbols.orEmpty()) {
            when (val projection = overridden.topologyIdentityProjection(registry)) {
                is TopologyK2IdentityProjection.Projected -> identities += projection.identity
                TopologyK2IdentityProjection.Unsupported -> Unit
                TopologyK2IdentityProjection.Rejected ->
                    return@analyze TopologyOverrideProjection.Rejected
            }
        }
        TopologyOverrideProjection.Projected(identities.sortedBy(CompilerSymbolIdentity::value))
}

private data class TopologyCompilerProjection(
    val kind: CompilerSymbolKind,
    val qualifiedIdentity: String,
    val identity: String,
)

private sealed interface TopologyCompilerProjectionResult {
    data class Projected(
        val projection: TopologyCompilerProjection,
    ) : TopologyCompilerProjectionResult

    data object Unsupported : TopologyCompilerProjectionResult
}

private fun KaSymbol.topologyProjection(
    file: TopologySourceFile,
): TopologyCompilerProjectionResult = when (this) {
    is KaConstructorSymbol -> {
        val owner = containingClassId?.asSingleFqName()?.asString()
                    ?: return TopologyCompilerProjectionResult.Unsupported
        projected(
            CompilerSymbolKind.CONSTRUCTOR,
            "$owner.<init>",
            functionIdentity("$owner.<init>"),
            file,
        )
    }
    is KaFunctionSymbol -> {
        val callable = callableId?.asSingleFqName()?.asString()
                       ?: return TopologyCompilerProjectionResult.Unsupported
        projected(CompilerSymbolKind.FUNCTION, callable, functionIdentity(callable), file)
    }
    is KaKotlinPropertySymbol -> {
        val callable = callableId?.asSingleFqName()?.asString()
                       ?: return TopologyCompilerProjectionResult.Unsupported
        projected(
            CompilerSymbolKind.PROPERTY,
            callable,
            buildString {
                append("property|").append(callable).append('|')
                append(
                    receiverParameter?.returnType?.toString()?.canonicalCompilerType() ?: "-",
                ).append('|')
                contextReceivers.forEachIndexed { index, receiver ->
                    if (index > 0) append(',')
                    append(receiver.type.toString().canonicalCompilerType())
                }
                append('|')
                append(returnType.toString().canonicalCompilerType())
            },
            file,
        )
    }
    is KaTypeAliasSymbol -> {
        val name = classId?.asSingleFqName()?.asString()
                   ?: return TopologyCompilerProjectionResult.Unsupported
        projected(CompilerSymbolKind.TYPE_ALIAS, name, "typealias|$name", file)
    }
    is KaClassLikeSymbol -> {
        val name = classId?.asSingleFqName()?.asString()
                   ?: return TopologyCompilerProjectionResult.Unsupported
        projected(CompilerSymbolKind.CLASSLIKE, name, "classlike|$name", file)
    }
    else -> TopologyCompilerProjectionResult.Unsupported
}

private fun KaFunctionSymbol.functionIdentity(callable: String): String = buildString {
    append("function|").append(callable).append('|')
    append(receiverParameter?.returnType?.toString()?.canonicalCompilerType() ?: "-").append('|')
    append(contextReceivers.joinToString(",") { it.type.toString().canonicalCompilerType() })
        .append('|')
    append(valueParameters.joinToString(",") { it.returnType.toString().canonicalCompilerType() })
        .append('|')
    append((this@functionIdentity as? KaNamedFunctionSymbol)?.typeParameters?.size ?: 0)
}

private fun projected(
    kind: CompilerSymbolKind,
    qualifiedIdentity: String,
    identity: String,
    file: TopologySourceFile,
): TopologyCompilerProjectionResult = TopologyCompilerProjectionResult.Projected(
    TopologyCompilerProjection(
        kind,
        qualifiedIdentity,
        "$identity|file|${file.path.value}",
    ),
)

private sealed interface TopologyK2SourceFileProjection {
    data class Found(
        val file: TopologySourceFile,
    ) : TopologyK2SourceFileProjection

    data object Unsupported : TopologyK2SourceFileProjection
}

/**
 * Proof transition: `(KaSymbol, TopologyProjectionRegistry) ->
 * TopologyK2SourceFileProjection`.
 *
 * Found establishes the exact admitted content-identified source file owning this live compiler
 * symbol. Unsupported closes library, synthetic, missing, and outside-generation PSI. Raw K2,
 * PSI, and paths remain inside the request-local analysis boundary.
 */
private fun KaSymbol.topologySourceFile(
    registry: TopologyProjectionRegistry,
): TopologyK2SourceFileProjection {
    val virtualFile = psi?.containingFile?.virtualFile
                      ?: return TopologyK2SourceFileProjection.Unsupported
    return when (val lookup = registry.fileAt(Path.of(virtualFile.path))) {
        is TopologyRegistryFileLookup.Found -> TopologyK2SourceFileProjection.Found(lookup.file)
        TopologyRegistryFileLookup.Unavailable -> TopologyK2SourceFileProjection.Unsupported
    }
}

private fun String.canonicalCompilerType(): String = filterNot(Char::isWhitespace)

@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
    org.jetbrains.kotlin.analysis.api.KaPlatformInterface::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.projectStructure.KaBuiltinsModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.idea.references.KtInvokeFunctionReference
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtOperationReferenceExpression
import org.jetbrains.kotlin.psi.KtPropertyDelegate

/** One function that a Kotlin convention can call without a call expression. A role selects at most one target. */
internal enum class ConventionCallRole(val functionName: String?) {
    OPERATOR(null),
    COMPONENT(null),
    GET("get"),
    SET("set"),
    ITERATOR("iterator"),
    HAS_NEXT("hasNext"),
    NEXT("next"),
    PROVIDE_DELEGATE("provideDelegate"),
    GET_VALUE("getValue"),
    SET_VALUE("setValue"),
    INVOKE("invoke");

    /** Retained locators of one site differ only by role. */
    val discriminator: String
        get() = "callee-convention:$name"

    /** A write-back operator also resolves indexed accessors, which have their own roles. */
    fun selects(name: String): Boolean =
        when (this) {
            OPERATOR -> name != GET.functionName && name != SET.functionName
            else -> functionName == null || functionName == name
        }

    /** A site can omit an optional role. A required role without a compiler target is unresolved, never absent. */
    fun requiresTarget(resolved: Collection<KaSymbol>): Boolean =
        when (this) {
            INVOKE,
            PROVIDE_DELEGATE,
            SET_VALUE -> false
            GET,
            SET,
            OPERATOR,
            COMPONENT -> resolved.isEmpty()
            ITERATOR,
            HAS_NEXT,
            NEXT,
            GET_VALUE -> true
        }
}

/** Compiler selection for one role. [NoCall] is proof that the site calls no function in that role. */
internal sealed interface ConventionCallTarget {
    data object NoCall : ConventionCallTarget

    /** The compiler module proves that the target has no source declaration in this workspace. */
    data object Binary : ConventionCallTarget

    data class Selected(val declaration: IntellijK2ResolvedDeclaration) : ConventionCallTarget
}

/** The syntax fixes every role before K2 work. An infix name is an ordinary single-target reference, not a role. */
internal fun PsiElement.conventionRoles(): List<ConventionCallRole> =
    when (this) {
        is KtOperationReferenceExpression ->
            when {
                !callsFunction() || isInfixName() -> emptyList()
                operationToken() in WRITE_BACK_OPERATIONS -> listOf(ConventionCallRole.OPERATOR, ConventionCallRole.SET)
                else -> listOf(ConventionCallRole.OPERATOR)
            }
        is KtArrayAccessExpression -> listOf(ConventionCallRole.GET, ConventionCallRole.SET)
        is KtDestructuringDeclarationEntry ->
            if (name == IGNORED_DESTRUCTURING_ENTRY) emptyList() else listOf(ConventionCallRole.COMPONENT)
        is KtForExpression -> listOf(ConventionCallRole.ITERATOR, ConventionCallRole.HAS_NEXT, ConventionCallRole.NEXT)
        is KtPropertyDelegate ->
            listOf(
                ConventionCallRole.PROVIDE_DELEGATE,
                ConventionCallRole.GET_VALUE,
                ConventionCallRole.SET_VALUE,
            )
        is KtCallExpression -> listOf(ConventionCallRole.INVOKE)
        else -> emptyList()
    }

/** Every role at one site shares the native reference that owns the site's range. */
internal fun PsiElement.conventionReferences(): List<KtReference> {
    val references = references.filterIsInstance<KtReference>()
    return if (this is KtCallExpression) references.filterIsInstance<KtInvokeFunctionReference>() else references
}

/** A reference at this site names a call target although no call expression contains it as a callee. */
internal fun PsiElement.isConventionCallSite(): Boolean =
    this is KtOperationReferenceExpression && callsFunction() ||
        this !is KtCallExpression && conventionRoles().isNotEmpty()

internal fun KtOperationReferenceExpression.isInfixName(): Boolean = operationToken() == KtTokens.IDENTIFIER

private fun KtOperationReferenceExpression.callsFunction(): Boolean = operationToken() !in NON_CALL_OPERATIONS

private fun KtOperationReferenceExpression.operationToken() = getReferencedNameElementType()

/** Kotlin calls no component function for an ignored entry. */
private const val IGNORED_DESTRUCTURING_ENTRY = "_"

/** Control flow, identity, casts, type tests, and plain assignment have no convention function. */
private val NON_CALL_OPERATIONS =
    setOf(
        KtTokens.EQ,
        KtTokens.ANDAND,
        KtTokens.OROR,
        KtTokens.ELVIS,
        KtTokens.EQEQEQ,
        KtTokens.EXCLEQEQEQ,
        KtTokens.EXCLEXCL,
        KtTokens.AS_KEYWORD,
        KtTokens.AS_SAFE,
        KtTokens.IS_KEYWORD,
        KtTokens.NOT_IS,
    )

/** On an indexed value, K2 attributes the write-back accessor to the operator and the read to the indexed site. */
private val WRITE_BACK_OPERATIONS =
    setOf(
        KtTokens.PLUSEQ,
        KtTokens.MINUSEQ,
        KtTokens.MULTEQ,
        KtTokens.DIVEQ,
        KtTokens.PERCEQ,
        KtTokens.PLUSPLUS,
        KtTokens.MINUSMINUS,
    )

internal fun IntellijK2RelationProjection.resolveConvention(
    reference: KtReference,
    role: ConventionCallRole,
): ConventionCallTarget =
    observation.observedAnalyze(reference.element) {
        val resolved = reference.resolveToSymbols()
        val functions =
            resolved.filterIsInstance<KaNamedFunctionSymbol>().filter { role.selects(it.name.asString()) }.distinct()
        when (functions.size) {
            0 ->
                if (role.requiresTarget(resolved))
                    ConventionCallTarget.Selected(IntellijK2ResolvedDeclaration.Unresolved)
                else ConventionCallTarget.NoCall
            1 -> selectedConvention(functions.single(), role, observation)
            else -> ConventionCallTarget.Selected(IntellijK2ResolvedDeclaration.Unresolved)
        }
    }

private fun KaSession.selectedConvention(
    function: KaNamedFunctionSymbol,
    role: ConventionCallRole,
    observation: IntellijReadObservation,
): ConventionCallTarget =
    when {
        // The ordinary callee reference already owns a builtin function-value invocation.
        role == ConventionCallRole.INVOKE && confirmsBuiltinFunctionInvoke(function) -> ConventionCallTarget.NoCall
        function.containingModule.isBinary() -> ConventionCallTarget.Binary
        else -> ConventionCallTarget.Selected(resolvedSymbolDeclaration(function, observation))
    }

private fun KaModule.isBinary(): Boolean = this is KaLibraryModule || this is KaBuiltinsModule

/** A convention reference can select several functions; a subject is named when any selected target is exact. */
internal fun IntellijK2RelationProjection.resolveConventionTargets(
    reference: KtReference
): List<IntellijK2ResolvedDeclaration> =
    observation.observedAnalyze(reference.element) {
        reference.resolveToSymbols().filterIsInstance<KaNamedFunctionSymbol>().map {
            resolvedSymbolDeclaration(it, observation)
        }
    }

internal fun KaSession.resolvedSymbolDeclaration(
    symbol: KaSymbol,
    observation: IntellijReadObservation,
): IntellijK2ResolvedDeclaration {
    val psi = symbol.psi
    val declaration = psi as? PsiNamedElement
    return when {
        psi == null -> {
            observation.terminated(IntellijReadTermination.K2_SYMBOL_WITHOUT_PSI)
            sourceLessCallable(symbol)
        }
        declaration == null -> {
            observation.terminated(IntellijReadTermination.K2_NON_KOTLIN_PSI)
            IntellijK2ResolvedDeclaration.Unresolved
        }
        else -> IntellijK2ResolvedDeclaration.Found(declaration)
    }
}

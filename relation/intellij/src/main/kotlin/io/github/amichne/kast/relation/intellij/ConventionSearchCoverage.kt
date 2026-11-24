@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.containingSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.idea.stubindex.KotlinFunctionShortNameIndex
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/** Whether exhausting native reference search can prove that no convention use of one subject remains. */
internal enum class ConventionSearchCoverage {
    EXHAUSTIVE,
    INCOMPLETE,
}

/**
 * Native search has no provider for loop steps or for the indexed write in a compound assignment, and it misses
 * delegate accessors behind `provideDelegate`. For other operators it follows inferred types, and that route stops at
 * values from a source-declared iterator or delegate. Such a link qualifies the subject only when the link can produce
 * the subject's receiver type.
 */
internal fun IntellijK2RelationProjection.conventionSearchCoverage(
    project: Project,
    subject: PsiNamedElement,
): ConventionSearchCoverage {
    val incomplete =
        when (subject) {
            // Kotlin accepts a matching Java method as an operator without a modifier.
            is PsiMethod -> subject.name in UNSEARCHABLE_CONVENTIONS
            is KtNamedFunction ->
                isConventionSubject(subject) &&
                    (subject.isBinaryDeclaration() ||
                        subject.name in UNSEARCHABLE_CONVENTIONS ||
                        hasBrokenTypeLink(project, functionReceiver(subject)))
            // A data class property is also the target of its generated component function.
            is KtParameter -> subject.isDataComponent() && hasBrokenTypeLink(project, dataReceiver(subject))
            // Decompiled source lists that property in the class body, and native search misses its destructuring.
            is KtProperty -> subject.isBinaryDeclaration() && subject.isDataProperty()
            else -> false
        }
    return if (incomplete) ConventionSearchCoverage.INCOMPLETE else ConventionSearchCoverage.EXHAUSTIVE
}

/** The receiver identity limits which links matter. An unavailable identity leaves every link possible. */
private sealed interface ConventionReceiver {
    data class Known(val own: ClassId, val supertypes: Set<ClassId>) : ConventionReceiver

    data object Unavailable : ConventionReceiver
}

/** Classes that a link's declared type names, and the supertypes that those classes also satisfy. */
private sealed interface ProducedClasses {
    data class Known(val named: Set<ClassId>, val satisfied: Set<ClassId>) : ProducedClasses

    /** A type variable or error type excludes no receiver. */
    data object Unbounded : ProducedClasses
}

private fun IntellijK2RelationProjection.isConventionSubject(function: KtNamedFunction): Boolean =
    observation.observedAnalyze(function) {
        when (val symbol = function.symbol) {
            is KaNamedFunctionSymbol -> isConvention(symbol)
            else -> false
        }
    }

private fun KtParameter.isDataComponent(): Boolean =
    hasValOrVar() &&
        ((ownerFunction as? KtPrimaryConstructor)?.getContainingClassOrObject() as? KtClass)?.isData() == true

private fun KtProperty.isDataProperty(): Boolean {
    val owner = containingClassOrObject as? KtClass ?: return false
    return owner.isData() && owner.primaryConstructorParameters.any { it.name == name }
}

private fun IntellijK2RelationProjection.functionReceiver(function: KtNamedFunction): ConventionReceiver =
    observation.observedAnalyze(function) {
        val symbol = function.symbol as? KaNamedFunctionSymbol
        receiver(symbol?.receiverParameter?.returnType ?: (symbol?.containingSymbol as? KaClassSymbol)?.defaultType)
    }

private fun IntellijK2RelationProjection.dataReceiver(parameter: KtParameter): ConventionReceiver {
    val owner =
        (parameter.ownerFunction as? KtPrimaryConstructor)?.getContainingClassOrObject()
            ?: return ConventionReceiver.Unavailable
    return observation.observedAnalyze(owner) { receiver((owner.symbol as? KaClassSymbol)?.defaultType) }
}

private fun KaSession.receiver(type: KaType?): ConventionReceiver {
    val own = (type as? KaClassType)?.classId?.takeUnless { it.isLocal } ?: return ConventionReceiver.Unavailable
    return ConventionReceiver.Known(own, type.allSupertypes.mapNotNull { (it as? KaClassType)?.classId }.toSet())
}

private fun IntellijK2RelationProjection.hasBrokenTypeLink(project: Project, receiver: ConventionReceiver): Boolean {
    val scope = GlobalSearchScope.projectScope(project)
    return TYPE_LINKS.any { name ->
        val links = mutableListOf<KtNamedFunction>()
        KotlinFunctionShortNameIndex.processElements(name, project, scope) { links.add(it) }
        links.any { link -> observation.canProduce(link, receiver) }
    }
}

private fun IntellijReadObservation.canProduce(link: KtNamedFunction, receiver: ConventionReceiver): Boolean =
    observedAnalyze(link) {
        val function = link.symbol as? KaNamedFunctionSymbol ?: return@observedAnalyze true
        if (!isConvention(function) || isFollowedOverride(function)) return@observedAnalyze false
        when (receiver) {
            ConventionReceiver.Unavailable -> true
            is ConventionReceiver.Known ->
                when (val produced = producedClasses(function.returnType)) {
                    ProducedClasses.Unbounded -> true
                    is ProducedClasses.Known ->
                        receiver.own in produced.satisfied || produced.named.any { it in receiver.supertypes }
                }
        }
    }

private fun KaSession.isConvention(function: KaNamedFunctionSymbol): Boolean =
    function.isOperator || function.allOverriddenSymbols.any { (it as? KaNamedFunctionSymbol)?.isOperator == true }

/** Native search follows loops over platform iterables. It does not follow a delegate accessor override. */
private fun KaSession.isFollowedOverride(function: KaNamedFunctionSymbol): Boolean =
    function.name.asString() != ConventionCallRole.GET_VALUE.functionName &&
        function.allOverriddenSymbols.any {
            it.origin == KaSymbolOrigin.LIBRARY || it.origin == KaSymbolOrigin.JAVA_LIBRARY
        }

private fun KaSession.producedClasses(type: KaType): ProducedClasses {
    val named = mutableSetOf<ClassId>()
    val satisfied = mutableSetOf<ClassId>()
    val pending = ArrayDeque(listOf(type))
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst() as? KaClassType ?: return ProducedClasses.Unbounded
        if (!named.add(current.classId)) continue
        val supertypes = current.allSupertypes.toList()
        satisfied += current.classId
        satisfied += supertypes.mapNotNull { (it as? KaClassType)?.classId }
        pending += current.typeArguments.mapNotNull { it.type }
        // A step type such as `Steps : Iterator<Money>` produces the argument of its supertype.
        pending += supertypes.flatMap { (it as? KaClassType)?.typeArguments.orEmpty() }.mapNotNull { it.type }
    }
    return ProducedClasses.Known(named, satisfied)
}

private val UNSEARCHABLE_CONVENTIONS =
    listOf(
            ConventionCallRole.HAS_NEXT,
            ConventionCallRole.NEXT,
            ConventionCallRole.GET_VALUE,
            ConventionCallRole.SET_VALUE,
            ConventionCallRole.SET,
        )
        .mapNotNull { it.functionName }
        .toSet()

private val TYPE_LINKS =
    listOf(ConventionCallRole.ITERATOR, ConventionCallRole.NEXT, ConventionCallRole.GET_VALUE).mapNotNull {
        it.functionName
    }

@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.util.Processor
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaCompoundVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaSyntheticJavaPropertySymbol
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.propertyNameByGetMethodName
import org.jetbrains.kotlin.load.java.propertyNamesBySetMethodName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtValueArgumentName
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedExpressionForSelectorOrThis

/** The Java accessors that one Kotlin name calls through property syntax; empty when the name is no Java property. */
internal fun IntellijK2RelationProjection.javaAccessorRoles(site: KtNameReferenceExpression): List<ConventionCallRole> {
    if (!site.canNameProperty()) return emptyList()
    return observation.observedAnalyze(site) {
        if (javaProperty(site.mainReference) == null) emptyList() else accessorRoles(site.accessedExpression())
    }
}

internal fun IntellijK2RelationProjection.resolveJavaAccessor(
    reference: KtReference,
    role: ConventionCallRole,
): ConventionCallTarget =
    observation.observedAnalyze(reference.element) {
        val property = javaProperty(reference)
        val accessor =
            if (role == ConventionCallRole.JAVA_SETTER) property?.javaSetterSymbol else property?.javaGetterSymbol
        when (accessor) {
            null -> ConventionCallTarget.Selected(IntellijK2ResolvedDeclaration.Unresolved)
            else -> selectedConvention(accessor, role, observation)
        }
    }

/**
 * Whether Kotlin source in [searched] can name a property that calls [subject]. Native reference search enumerates uses
 * of the method name only. The compiler's naming rule supplies the property names, and the word index then proves that
 * no Kotlin file contains one.
 */
internal fun IntellijK2RelationProjection.hasUnsearchedPropertyUses(
    project: Project,
    subject: PsiNamedElement,
    searched: GlobalSearchScope,
): Boolean {
    val names = javaPropertyNames(subject)
    if (names.isEmpty()) return false
    val kotlin = GlobalSearchScope.getScopeRestrictedByFileTypes(searched, KotlinFileType.INSTANCE)
    return names.any { name -> !isAbsentFromCode(project, name, kotlin) }
}

private fun isAbsentFromCode(project: Project, name: Name, scope: GlobalSearchScope): Boolean =
    PsiSearchHelper.getInstance(project)
        .processCandidateFilesForText(
            scope,
            UsageSearchContext.IN_CODE,
            true,
            name.asString(),
            // The first file stops the search and makes the result false.
            Processor { false },
        )

private fun IntellijK2RelationProjection.javaPropertyNames(subject: PsiNamedElement): List<Name> =
    when (subject) {
        is PsiMethod ->
            if (subject.isConstructor || subject.hasModifierProperty(PsiModifier.STATIC)) emptyList()
            else accessorPropertyNames(subject.name, subject.parameterList.parametersCount)
        is KtNamedFunction -> {
            val names = accessorPropertyNames(subject.name.orEmpty(), subject.valueParameters.size)
            // Kotlin keeps the Java property for a function that overrides one of its accessors.
            if (names.isNotEmpty() && overridesJava(subject)) names else emptyList()
        }
        else -> emptyList()
    }

private fun accessorPropertyNames(method: String, parameters: Int): List<Name> =
    when {
        !Name.isValidIdentifier(method) -> emptyList()
        parameters == 0 -> listOfNotNull(propertyNameByGetMethodName(Name.identifier(method)))
        parameters == 1 -> propertyNamesBySetMethodName(Name.identifier(method))
        else -> emptyList()
    }

private fun IntellijK2RelationProjection.overridesJava(function: KtNamedFunction): Boolean =
    function.hasModifier(KtTokens.OVERRIDE_KEYWORD) &&
        observation.observedAnalyze(function) {
            (function.symbol as? KaNamedFunctionSymbol)?.allOverriddenSymbols.orEmpty().any {
                it.origin == KaSymbolOrigin.JAVA_SOURCE || it.origin == KaSymbolOrigin.JAVA_LIBRARY
            }
        }

/** Syntax excludes names that another callee item owns and names that cannot read or write a property. */
private fun KtNameReferenceExpression.canNameProperty(): Boolean =
    getReferencedNameElementType() == KtTokens.IDENTIFIER &&
        when (val owner = parent) {
            is KtUserType,
            is KtValueArgumentName -> false
            is KtCallElement -> owner.calleeExpression !== this
            is KtCallableReferenceExpression -> owner.callableReference !== this
            else -> true
        }

private fun KaSession.javaProperty(reference: KtReference): KaSyntheticJavaPropertySymbol? =
    reference.resolveToSymbol() as? KaSyntheticJavaPropertySymbol

private fun KtNameReferenceExpression.accessedExpression(): KtExpression {
    val selected: KtExpression = getQualifiedExpressionForSelectorOrThis()
    return generateSequence(selected) { it.parent as? KtParenthesizedExpression }.last()
}

private fun KaSession.accessorRoles(accessed: KtExpression): List<ConventionCallRole> {
    val operation = accessed.parent
    return when {
        operation is KtBinaryExpression && operation.left === accessed && operation.operationToken == KtTokens.EQ ->
            listOf(ConventionCallRole.JAVA_SETTER)
        writesBack(operation as? KtExpression, accessed) ->
            listOf(ConventionCallRole.JAVA_GETTER, ConventionCallRole.JAVA_SETTER)
        else -> listOf(ConventionCallRole.JAVA_GETTER)
    }
}

/** An assignment operator such as `plusAssign` changes the value in place, so only K2 proves that a setter runs. */
private fun KaSession.writesBack(operation: KtExpression?, accessed: KtExpression): Boolean =
    when (operation) {
        is KtBinaryExpression -> operation.left === accessed
        is KtUnaryExpression -> operation.baseExpression === accessed
        else -> false
    } && operation?.resolveToCall()?.successfulCallOrNull<KaCompoundVariableAccessCall>() != null

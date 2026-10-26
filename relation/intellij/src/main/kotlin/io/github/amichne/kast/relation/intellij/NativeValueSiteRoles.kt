@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkAllowance
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.symbols.KaLocalVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/** Native binding and type proof for one restored exact role; lexical shape alone never establishes a value. */
internal fun nativeValueSiteRole(
    element: PsiElement,
    claim: ValueSiteRoleClaim,
    prepared: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    executionOwner: ValueExecutionOwner,
    componentPermit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    if (
        element is KtForExpression && (claim is ValueSiteRoleClaim.LoopResult || claim is ValueSiteRoleClaim.Receiver)
    ) {
        return nativeLoopSiteRole(element, claim, executionOwner, prepared.scope, prepared.projection, componentPermit)
    }
    return when (claim) {
        is ValueSiteRoleClaim.LoopResult -> roleRejected()
        ValueSiteRoleClaim.ExpressionResult ->
            if (element is KtDestructuringDeclarationEntry)
                componentResultRole(
                    entry = element,
                    prepared = prepared,
                    executionOwner = executionOwner,
                    permit = componentPermit,
                )
            else expressionRole(element, prepared.projection.observation)
        ValueSiteRoleClaim.LocalBinding ->
            localBindingRole(
                element = element,
                prepared = prepared,
                executionOwner = executionOwner,
                permit = componentPermit,
            )
        ValueSiteRoleClaim.PropertyAssignment -> propertyRole(element, prepared.projection.observation)
        ValueSiteRoleClaim.LocalRead ->
            localReadRole(
                element = element,
                owner = prepared.owner,
                observation = prepared.projection.observation,
                executionOwner = executionOwner,
                permit = componentPermit,
            )
        ValueSiteRoleClaim.Return ->
            returnRole(
                element = element,
                observation = prepared.projection.observation,
                workAllowance = prepared.scope.request.budget.resources.workAllowance,
                executionOwner = executionOwner,
            )
        is ValueSiteRoleClaim.Argument -> argumentRole(element, prepared, executionOwner)
        is ValueSiteRoleClaim.Receiver ->
            receiverRole(
                element = element,
                prepared = prepared,
                executionOwner = executionOwner,
                permit = componentPermit,
            )
    }
}

private fun componentResultRole(
    entry: KtDestructuringDeclarationEntry,
    prepared: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    executionOwner: ValueExecutionOwner,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    val target =
        when (
            val admitted =
                componentSiteTarget(
                    entry = entry,
                    prepared = prepared,
                    executionOwner = executionOwner,
                    permit = permit,
                )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return admitted
        }
    if (target is NativeComponentTarget.Callable) {
        when (val allowed = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        when (
            val receiver =
                nativeComponentReceiver(
                    entry = entry,
                    target = target,
                    executionOwner = executionOwner,
                    scope = prepared.scope,
                    projection = prepared.projection,
                )
        ) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return receiver
        }
    }
    return Refinement.Refined(ValueRole.ExpressionResult)
}

private fun componentSiteTarget(
    entry: KtDestructuringDeclarationEntry,
    prepared: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    executionOwner: ValueExecutionOwner,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<NativeComponentTarget, ValueFlowUnsupportedCause> =
    when (val declaration = entry.parent) {
        is KtDestructuringDeclaration ->
            when (
                val admitted =
                    NativeDestructuringAdmission.read(
                        declaration = declaration,
                        enclosing = executionOwner.enclosing,
                        scope = prepared.scope,
                        projection = prepared.projection,
                        permit = permit,
                    )
            ) {
                is Refinement.Refined -> admitted.value.target(entry)
                is Refinement.Rejected -> admitted
            }
        else -> roleRejected()
    }

private fun expressionRole(
    element: PsiElement,
    observation: IntellijReadObservation,
): Refinement<ValueRole, ValueFlowUnsupportedCause> =
    if (element is KtExpression && element !is KtNamedDeclaration && element.hasNativeType(observation))
        Refinement.Refined(ValueRole.ExpressionResult)
    else roleRejected()

private fun localBindingRole(
    element: PsiElement,
    prepared: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    executionOwner: ValueExecutionOwner,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    if (element is KtDestructuringDeclarationEntry) {
        when (
            val admitted =
                componentResultRole(
                    entry = element,
                    prepared = prepared,
                    executionOwner = executionOwner,
                    permit = permit,
                )
        ) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return admitted
        }
    }
    return if (
        element.nativeLocalBindingMutability() is Refinement.Refined &&
            element.hasNativeLocalDeclaration(prepared.projection.observation)
    )
        Refinement.Refined(ValueRole.LocalBinding)
    else roleRejected()
}

private fun propertyRole(
    element: PsiElement,
    observation: IntellijReadObservation,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    val property = element as? KtProperty ?: return roleRejected()
    return if (!property.isLocal && property.initializer != null && property.hasNativeDeclaration(observation))
        Refinement.Refined(ValueRole.PropertyAssignment)
    else roleRejected()
}

private fun localReadRole(
    element: PsiElement,
    owner: PsiElement,
    observation: IntellijReadObservation,
    executionOwner: ValueExecutionOwner,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    if (element.isMutableLocalReference(observation)) {
        return when (
            val version =
                nativeLocalValueVersion(
                    definition = element,
                    execution = executionOwner,
                    observation = observation,
                    permit = permit,
                )
        ) {
            is Refinement.Refined -> Refinement.Refined(ValueRole.LocalRead)
            is Refinement.Rejected -> version
        }
    }
    if (element is KtThisExpression) {
        val function = owner as? KtNamedFunction ?: return roleRejected()
        if (valueNativeOwnership(element, function) != NativeValueOwnership.ADMITTED) return roleRejected()
        return when (val receiver = nativeThisReceiverKind(function, element, observation)) {
            is Refinement.Refined -> Refinement.Refined(ValueRole.LocalRead)
            is Refinement.Rejected -> receiver
        }
    }
    return if (element is KtExpression && element.hasNativeLocalReference(observation))
        Refinement.Refined(ValueRole.LocalRead)
    else roleRejected()
}

private fun PsiElement.isMutableLocalReference(observation: IntellijReadObservation): Boolean {
    if (this !is org.jetbrains.kotlin.psi.KtNameReferenceExpression) return false
    val reference = references.filterIsInstance<KtReference>().singleOrNull() ?: return false
    return when (val declaration = resolveLocalValueReference(reference, observation)) {
        is NativeLocalValueReferenceResolution.Resolved ->
            declaration.declaration.nativeLocalBindingMutability() ==
                Refinement.Refined(NativeLocalBindingMutability.MUTABLE)
        NativeLocalValueReferenceResolution.Unresolved -> false
    }
}

private fun returnRole(
    element: PsiElement,
    observation: IntellijReadObservation,
    workAllowance: WorkAllowance,
    executionOwner: ValueExecutionOwner,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    if (element.parent is KtReturnExpression && executionOwner.context is ValueExecutionContext.Callback)
        return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    if (element !is KtExpression || !element.hasNativeType(observation)) return roleRejected()
    val block = element.parent as? org.jetbrains.kotlin.psi.KtBlockExpression
    val callback = block?.parent as? org.jetbrains.kotlin.psi.KtFunctionLiteral
    if (callback == null || block.statements.lastOrNull() !== element)
        return if (element.isReturnedExpression()) Refinement.Refined(ValueRole.Return) else roleRejected()
    return when (
        val semantics =
            nativeCallbackReturnSemantics(
                expression = element,
                function = callback,
                observation = observation,
                workAllowance = workAllowance,
            )
    ) {
        is Refinement.Refined ->
            when (semantics.value) {
                NativeCallbackReturnSemantics.VALUE -> Refinement.Refined(ValueRole.Return)
                NativeCallbackReturnSemantics.UNIT_COERCION -> roleRejected()
            }
        is Refinement.Rejected -> semantics
    }
}

private fun argumentRole(
    element: PsiElement,
    prepared: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    executionOwner: ValueExecutionOwner,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    val expression = element as? KtExpression ?: return roleRejected()
    val argument = element.parent as? KtValueArgument ?: return roleRejected()
    return nativeValueArgument(
        expression = expression,
        argument = argument,
        scope = prepared.scope,
        projection = prepared.projection,
        executionOwner = executionOwner,
    )
}

private fun receiverRole(
    element: PsiElement,
    prepared: IntellijValueFlowCompilerAdapter.Prepared.Ready,
    executionOwner: ValueExecutionOwner,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<ValueRole, ValueFlowUnsupportedCause> {
    if (element is KtDestructuringDeclarationEntry)
        return when (
            val target =
                componentSiteTarget(
                    entry = element,
                    prepared = prepared,
                    executionOwner = executionOwner,
                    permit = permit,
                )
        ) {
            is Refinement.Rejected -> target
            is Refinement.Refined ->
                when (val selected = target.value) {
                    is NativeComponentTarget.Field -> roleRejected()
                    is NativeComponentTarget.Callable ->
                        nativeComponentReceiver(
                            entry = element,
                            target = selected,
                            executionOwner = executionOwner,
                            scope = prepared.scope,
                            projection = prepared.projection,
                        )
                }
        }
    val expression = element as? KtExpression ?: return roleRejected()
    val qualified = element.parent as? KtQualifiedExpression ?: return roleRejected()
    return nativeValueReceiver(
        expression = expression,
        qualified = qualified,
        scope = prepared.scope,
        projection = prepared.projection,
        executionOwner = executionOwner,
    )
}

private fun KtExpression.hasNativeType(observation: IntellijReadObservation): Boolean =
    observation.observedAnalyze(this) {
        val type = expressionType
        type != null && type !is KaErrorType
    }

private fun KtProperty.hasNativeDeclaration(observation: IntellijReadObservation): Boolean =
    observation.observedAnalyze(this) { symbol.psi === this@hasNativeDeclaration }

private fun PsiElement.hasNativeLocalDeclaration(observation: IntellijReadObservation): Boolean =
    when (this) {
        is KtProperty -> hasNativeDeclaration(observation)
        is KtDestructuringDeclarationEntry -> {
            val entry = this
            observation.observedAnalyze(entry) {
                val resolved = entry.symbol
                resolved is KaLocalVariableSymbol &&
                    resolved.origin == KaSymbolOrigin.SOURCE &&
                    resolved.psi?.originalElement === entry.originalElement
            }
        }
        else -> false
    }

private fun KtExpression.hasNativeLocalReference(observation: IntellijReadObservation): Boolean {
    val reference = references.filterIsInstance<KtReference>().singleOrNull() ?: return false
    return when (val resolved = resolveLocalValueReference(reference, observation)) {
        is NativeLocalValueReferenceResolution.Resolved ->
            resolved.declaration.nativeLocalBindingMutability() is Refinement.Refined
        NativeLocalValueReferenceResolution.Unresolved -> false
    }
}

private fun KtExpression.isReturnedExpression(): Boolean =
    when (val container = parent) {
        is KtReturnExpression -> container.returnedExpression === this
        is KtNamedFunction -> container.bodyExpression === this
        is KtPropertyAccessor -> container.isGetter && container.bodyExpression === this
        else -> false
    }

private fun roleRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)

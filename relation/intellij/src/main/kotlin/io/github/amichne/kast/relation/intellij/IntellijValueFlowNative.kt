@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRejection
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

/** All live elements and K2 values remain inside the caller's one native read action. */
internal class IntellijValueFlowNative(
    private val request: ValueFlowRequest,
    private val owner: PsiElement,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val observation: IntellijReadObservation,
    private val started: Long,
    private val onWork: (RelationWorkCount) -> Unit,
) {
    private val retained =
        NativeValueFlowTransfers(request = request, owner = owner, scope = scope, observation = observation)
    private val edges
        get() = retained.edges

    private val obligations
        get() = retained.obligations

    private val allowance = IntellijRelationAllowance(started) { System.nanoTime() }.apply { examine() }
    private val examined: Long
        get() = allowance.examined

    fun read(element: PsiElement): ValueFlowRead {
        val compiledFormal =
            request.source.role is ValueRole.Formal && (element.containingFile as? KtFile)?.isCompiled == true
        val localBinding =
            element.isNativeReferenceBinding(request.source.role) ||
                request.source.localVersion is io.github.amichne.kast.relation.contract.ValueLocalVersion.Defined
        if (!compiledFormal && element.destructuredValueParameter() == null && localBinding)
            return IntellijLocalBindingReads(request, owner, scope, projection, observation, started, onWork)
                .read(element)

        val base = ValueFlowStep.detachedByteCount(request.source, scope.request, emptyList(), emptyList()).value
        val reserved = reservedValueObligationBytes(request.source)
        if (!request.budget.permitsReturnedBytes(base + reserved)) {

            return ValueFlowRead.Rejected(ValueFlowRejection.GRANT_TOO_SMALL, nativeWorkCount(examined))
        }
        when (val read = readPermittedRole(element, compiledFormal)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return ValueFlowRead.Rejected(read.failure, nativeWorkCount(examined))
        }
        val terminal =
            if (obligations.isEmpty()) ValueFlowTerminal.SupportedDomainExhausted else ValueFlowTerminal.Unresolved
        return when (
            val step =
                ValueFlowStep.fromEvidence(
                    request.source,
                    edges,
                    obligations,
                    terminal,
                    scope.request,
                    nativeWorkCount(examined),
                    retained.reviewedEdges,
                )
        ) {
            is Refinement.Refined ->
                when (val complete = step.value.withStringConstructions(retained.stringConstructions)) {
                    is Refinement.Refined -> ValueFlowRead.Observed(complete.value)
                    is Refinement.Rejected ->
                        ValueFlowRead.ContractRejected(complete.failure, nativeWorkCount(examined))
                }
            is Refinement.Rejected -> ValueFlowRead.ContractRejected(step.failure, nativeWorkCount(examined))
        }
    }

    private fun readPermittedRole(element: PsiElement, compiledFormal: Boolean): Refinement<Unit, ValueFlowRejection> {
        if (permitted() is Refinement.Rejected) return Refinement.Refined(Unit)
        if (!compiledFormal) return readRole(element)
        unresolved(ValueFlowUnsupportedCause.EXTERNAL_CALL)
        return Refinement.Refined(Unit)
    }

    private fun readRole(element: PsiElement): Refinement<Unit, ValueFlowRejection> {
        when (val role = request.source.role) {
            is ValueRole.StringConversionResult -> convertedString(element, role)
            ValueRole.LocalBinding -> retained.unresolvedAt(element.nativeLocalBindingCause(), element)
            is ValueRole.LoopResult ->
                if (element is KtForExpression)
                    NativeValueLoopInput(request.source, scope, projection, retained, ::permitted)
                        .result(element, role.result)
                else retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, element)
            is ValueRole.Argument,
            is ValueRole.Receiver -> return callEntry()
            is ValueRole.Formal -> formalInput(element)
            ValueRole.Return -> return sourceReturn()
            ValueRole.PropertyAssignment -> unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
            ValueRole.ExpressionResult,
            ValueRole.LocalRead -> {
                val expression =
                    element as? KtExpression
                        ?: run {
                            retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, element)
                            return Refinement.Refined(Unit)
                        }
                return readExpression(expression)
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun convertedString(element: PsiElement, role: ValueRole.StringConversionResult) {
        val expression = element as? KtExpression
        val entry = expression?.parent as? org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
        if (expression == null || entry == null)
            retained.unresolvedAt(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION, element)
        else
            NativeStringContent(request.source, scope, projection, retained)
                .readConverted(expression, entry, role.invocation)
    }

    private fun formalInput(element: PsiElement) {
        val parameter = element.destructuredValueParameter()
        if (parameter != null) {
            NativeValueDestructuring(
                    source = request.source,
                    scope = scope,
                    projection = projection,
                    reviewed =
                        NativeReviewedComponentScope.Available(
                            request.source.executionOwner,
                            receiverContext(request, scope, projection, allowance),
                        ),
                )
                .readParameter(
                    parameter = parameter,
                    permit = ::permitted,
                    consume = { retained.emitTarget(it.target, it.kind, it.evidence) },
                    unsupported = { cause, at -> retained.unresolvedAt(cause, at) },
                )
        } else receiverFields(element)
    }

    private fun callEntry(): Refinement<Unit, ValueFlowRejection> {
        val result =
            NativeValueCallFlow(
                    request = request,
                    scope = scope,
                    projection = projection,
                    allowance = allowance,
                    retained = retained,
                    permit = { permitted() is Refinement.Refined },
                )
                .read()
        onWork(nativeWorkCount(examined))
        return result
    }

    private fun sourceReturn(): Refinement<Unit, ValueFlowRejection> {
        val read =
            NativeValueReturnFlow(
                    owner = owner,
                    returns =
                        NativeValueSourceReturns(
                            request = request,
                            scope = scope,
                            projection = projection,
                            allowance = allowance,
                        ),
                    retained = retained,
                    permit = { permitted() is Refinement.Refined },
                )
                .read()
        onWork(nativeWorkCount(examined))
        return read
    }

    private fun receiverFields(element: PsiElement) {
        if (element is KtProperty) {
            if (
                sourceScopeAllowance() == IntellijRelationProviderEnumerationAdmission.READY &&
                    permitted() is Refinement.Refined
            )
                NativeValueReceiverReads(request, scope, projection).getterRead(element, retained::adopt)
            return
        }
        val function =
            element as? KtFunction
                ?: run {
                    unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
                    return
                }
        val exhausted =
            NativeValueReceiverReads(request, scope, projection)
                .forEachRead(
                    function = function,
                    admitTraversal = { sourceScopeAllowance() == IntellijRelationProviderEnumerationAdmission.READY },
                    permit = { permitted() is Refinement.Refined },
                    consume = retained::adopt,
                )
        if (!exhausted && obligations.isEmpty()) unresolved(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    }

    private fun sourceScopeAllowance(): IntellijRelationProviderEnumerationAdmission =
        nativeSourceScopeAllowance(request, allowance, ::unresolved)

    private fun permitted(): Refinement<Unit, ValueFlowUnsupportedCause> {
        ProgressManager.checkCanceled()
        val grant = request.budget.resources
        return when {
            !grant.hasWorkCapacity(examined) -> stopped(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
            retained.resultCount >= request.budget.outputResults.value ->
                stopped(ValueFlowUnsupportedCause.RESULT_LIMIT_REACHED)
            allowance.elapsedLimitReached(grant) -> stopped(ValueFlowUnsupportedCause.TIME_LIMIT_REACHED)
            else -> {
                allowance.examine()
                onWork(nativeWorkCount(examined))
                Refinement.Refined(Unit)
            }
        }
    }

    private fun stopped(cause: ValueFlowUnsupportedCause): Refinement.Rejected<ValueFlowUnsupportedCause> {
        unresolved(cause)
        return Refinement.Rejected(cause)
    }

    private fun readExpression(expression: KtExpression) =
        NativeValueExpressionFlow(
                request = request,
                owner = owner,
                scope = scope,
                projection = projection,
                allowance = allowance,
                retained = retained,
                permit = ::permitted,
            )
            .readExpression(expression)

    private fun unresolved(cause: ValueFlowUnsupportedCause) = retained.unresolved(cause)
}

private fun nativeWorkCount(examined: Long): RelationWorkCount =
    when (val count = RelationWorkCount.parse(examined)) {
        is Refinement.Refined -> count.value
        is Refinement.Rejected -> error("Native work counter cannot be negative")
    }

private fun PsiElement.destructuredValueParameter(): KtParameter? =
    when (this) {
        is KtParameter -> takeIf { destructuringDeclaration != null }
        is KtDestructuringDeclaration -> (parent as? KtParameter)?.takeIf { it.destructuringDeclaration === this }
        else -> null
    }

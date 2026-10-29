package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry

/**
 * Request-local permission for every invoked sibling, not a payload-dependent inventory. Multiple authored local
 * components require effect-free source bodies; this does not claim compiler-proven totality for arbitrary calls.
 */
internal class NativeDestructuringAdmission
private constructor(val components: List<Pair<KtDestructuringDeclarationEntry, NativeComponentTarget>>) {
    val unresolvedGetters: List<KtDestructuringDeclarationEntry>
        get() = components.filter { it.second is NativeComponentTarget.Reviewed }.map { it.first }

    fun target(entry: KtDestructuringDeclarationEntry): Refinement<NativeComponentTarget, ValueFlowUnsupportedCause> =
        when (val selected = components.singleOrNull { it.first === entry }) {
            null -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            else -> Refinement.Refined(selected.second)
        }

    companion object {
        fun read(
            declaration: KtDestructuringDeclaration,
            enclosing: RelationEndpoint,
            scope: CompiledRelationScope,
            projection: IntellijK2RelationProjection,
            permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
        ): Refinement<NativeDestructuringAdmission, ValueFlowUnsupportedCause> =
            read(
                declaration,
                NativeComponentAdmissionContext(
                    enclosing = enclosing,
                    scope = scope,
                    projection = projection,
                    permit = permit,
                    reviewed = NativeReviewedComponentScope.Unavailable,
                ),
            )

        fun readReviewed(
            declaration: KtDestructuringDeclaration,
            scope: CompiledRelationScope,
            projection: IntellijK2RelationProjection,
            permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
            reviewed: NativeReviewedComponentScope.Available,
        ): Refinement<NativeDestructuringAdmission, ValueFlowUnsupportedCause> =
            read(
                declaration,
                NativeComponentAdmissionContext(
                    enclosing = reviewed.execution.enclosing,
                    scope = scope,
                    projection = projection,
                    permit = permit,
                    reviewed = reviewed,
                ),
            )

        private fun read(
            declaration: KtDestructuringDeclaration,
            admission: NativeComponentAdmissionContext,
        ): Refinement<NativeDestructuringAdmission, ValueFlowUnsupportedCause> {
            val enclosing = admission.enclosing
            val scope = admission.scope
            val projection = admission.projection
            val permit = admission.permit
            val reviewed = admission.reviewed
            val context = declaration.nativeDestructuringContext()
            if (context == NativeDestructuringContext.Unsupported) return unsupportedComponents()
            val components =
                when (val selected = selectComponents(declaration, projection, permit, reviewed)) {
                    is Refinement.Refined -> selected.value
                    is Refinement.Rejected -> return selected
                }
            val ordered = components.size > 1 && components.any { it.second is NativeComponentTarget.Callable }
            if (ordered) {
                when (val admitted = admitOrderedContext(context, enclosing, scope, projection, permit)) {
                    is Refinement.Refined -> Unit
                    is Refinement.Rejected -> return admitted
                }
            }
            val bodies =
                NativeNormalComponentBody(
                    enclosing = enclosing,
                    scope = scope,
                    projection = projection,
                    permit = permit,
                )
            for ((entry, target) in components) {
                when (val allowed = permit()) {
                    is Refinement.Refined -> Unit
                    is Refinement.Rejected -> return allowed
                }
                val admitted = bodies.admitSelected(entry, target, ordered)
                if (admitted is Refinement.Rejected) return admitted
            }
            return Refinement.Refined(NativeDestructuringAdmission(components))
        }
    }
}

private fun admitOrderedContext(
    context: NativeDestructuringContext,
    enclosing: RelationEndpoint,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<Unit, ValueFlowUnsupportedCause> {
    val loop =
        when (context) {
            is NativeDestructuringContext.Local,
            is NativeDestructuringContext.Parameter -> return Refinement.Refined(Unit)
            is NativeDestructuringContext.Iteration -> context.loop
            NativeDestructuringContext.Unsupported -> return unsupportedComponents()
        }
    val calls =
        when (
            val selected =
                NativeForLoopCalls.read(loop, ValueExecutionOwner.declaration(enclosing), scope, projection, permit)
        ) {
            is Refinement.Refined -> selected.value
            is Refinement.Rejected -> return selected
        }
    return when (val admitted = calls.normalIteration(scope, projection, permit)) {
        is Refinement.Rejected -> admitted
        is Refinement.Refined ->
            when (admitted.value) {
                NativeLoopGuard.Empty -> unsupportedComponents()
                is NativeLoopGuard.Iteration -> Refinement.Refined(Unit)
            }
    }
}

private fun NativeNormalComponentBody.admitSelected(
    entry: KtDestructuringDeclarationEntry,
    target: NativeComponentTarget,
    requireNormalBody: Boolean,
): Refinement<Unit, ValueFlowUnsupportedCause> =
    when (target) {
        is NativeComponentTarget.Field -> stored(target.parameter)
        is NativeComponentTarget.Source -> if (requireNormalBody) read(entry, target) else Refinement.Refined(Unit)
        is NativeComponentTarget.Getter -> readGetter(entry, target)
        // Admission keeps the virtual getter unresolved; it does not call this a normal source body.
        is NativeComponentTarget.Reviewed -> Refinement.Refined(Unit)
    }

private fun selectComponents(
    declaration: KtDestructuringDeclaration,
    projection: IntellijK2RelationProjection,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
    reviewed: NativeReviewedComponentScope,
): Refinement<List<Pair<KtDestructuringDeclarationEntry, NativeComponentTarget>>, ValueFlowUnsupportedCause> {
    val components = mutableListOf<Pair<KtDestructuringDeclarationEntry, NativeComponentTarget>>()
    for (entry in declaration.entries) {
        // A positional underscore has no invocation; a named `_ = property` still evaluates its getter.
        if (entry.name == "_" && !entry.hasInitializer()) continue
        when (val allowed = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        when (val target = nativeComponentTarget(entry, projection)) {
            is Refinement.Refined ->
                when (val selected = nativeReviewedComponentTarget(entry, target.value, reviewed)) {
                    is Refinement.Refined -> components += entry to selected.value
                    is Refinement.Rejected -> return selected
                }
            is Refinement.Rejected -> return target
        }
    }
    return nativeReviewedComponentOrder(components.toList(), sourceValueRange(declaration))
}

/** Component proof spends the caller's original allowance, including repeated admission at return boundaries. */
internal fun IntellijRelationAllowance.permitDestructuring(
    scope: CompiledRelationScope
): Refinement<Unit, ValueFlowUnsupportedCause> {
    ProgressManager.checkCanceled()
    return when (admitCallbackWork(scope.request.budget.resources)) {
        CallbackWorkAdmission.READY -> Refinement.Refined(Unit)
        CallbackWorkAdmission.WORK_LIMIT_REACHED -> Refinement.Rejected(ValueFlowUnsupportedCause.WORK_LIMIT_REACHED)
        CallbackWorkAdmission.TIME_LIMIT_REACHED -> Refinement.Rejected(ValueFlowUnsupportedCause.TIME_LIMIT_REACHED)
    }
}

private fun unsupportedComponents(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)

private data class NativeComponentAdmissionContext(
    val enclosing: RelationEndpoint,
    val scope: CompiledRelationScope,
    val projection: IntellijK2RelationProjection,
    val permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
    val reviewed: NativeReviewedComponentScope,
)

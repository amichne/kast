package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.ValueExecutionContext
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueLoopIteration
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtParameter

/** Component outputs preserve their owner. Independently sequenced authored effects remain outside this slice. */
internal class NativeValueDestructuring(
    private val source: ValueSite,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
) {
    fun read(
        expression: KtExpression,
        declaration: KtDestructuringDeclaration,
        permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
        consume: (ValueTransfer) -> Unit,
        unsupported: (ValueFlowUnsupportedCause) -> Unit,
    ) {
        val context = declaration.nativeDestructuringContext()
        if (context !is NativeDestructuringContext.Local || context.initializer !== expression) {
            unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            return
        }
        readComponents(declaration, permit, consume, unsupported)
    }

    fun readParameter(
        parameter: KtParameter,
        permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
        consume: (ValueTransfer) -> Unit,
        unsupported: (ValueFlowUnsupportedCause) -> Unit,
    ) {
        val declaration = parameter.destructuringDeclaration
        if (declaration == null) {
            unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            return
        }
        val context = declaration.nativeDestructuringContext()
        val formal = (source.role as? ValueRole.Formal)?.position as? ValueFormalPosition.Parameter
        val execution = source.executionOwner.context as? ValueExecutionContext.Callback
        if (context !is NativeDestructuringContext.Parameter || formal == null || execution == null) {
            unsupported(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            return
        }
        if (
            formal.parameter.executionOwner != source.executionOwner ||
                formal.parameter.range != sourceValueRange(parameter) ||
                sourceValueRange(context.function) != execution.body.range
        ) {
            unsupported(ValueFlowUnsupportedCause.NESTED_EXECUTION)
            return
        }
        readComponents(declaration, permit, consume, unsupported)
    }

    fun readIteration(
        declaration: KtDestructuringDeclaration,
        iteration: ValueLoopIteration,
        permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
        consume: (ValueTransfer) -> Unit,
        unsupported: (ValueFlowUnsupportedCause) -> Unit,
    ) {
        val context = declaration.nativeDestructuringContext()
        if (
            context !is NativeDestructuringContext.Iteration ||
                source.role != ValueRole.LoopResult(iteration.elementResult)
        ) {
            unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            return
        }
        if (sourceValueRange(context.loop) != iteration.protocol.next.range) {
            unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            return
        }
        readComponents(declaration, permit, consume, unsupported)
    }

    private fun readComponents(
        declaration: KtDestructuringDeclaration,
        permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
        consume: (ValueTransfer) -> Unit,
        unsupported: (ValueFlowUnsupportedCause) -> Unit,
    ) {
        val components =
            when (val admitted = targets(declaration, permit)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> {
                    unsupported(admitted.failure)
                    return
                }
            }
        val transfers =
            when (val projected = projected(components, permit)) {
                is Refinement.Refined -> projected.value
                is Refinement.Rejected -> {
                    unsupported(projected.failure)
                    return
                }
            }
        for (transfer in transfers) {
            if (permit() is Refinement.Rejected) return
            consume(transfer)
        }
    }

    private fun projected(
        admission: NativeDestructuringAdmission,
        permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
    ): Refinement<List<ValueTransfer>, ValueFlowUnsupportedCause> {
        val transfers = mutableListOf<ValueTransfer>()
        for ((entry, target) in admission.components) {
            when (val allowed = permit()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return allowed
            }
            when (val projected = project(entry, target)) {
                is Refinement.Refined ->
                    when (val read = projected.value) {
                        is NativeValueFieldRead.Transfer -> transfers += read.value
                        NativeValueFieldRead.DifferentField -> Unit
                    }
                is Refinement.Rejected -> return projected
            }
        }
        return Refinement.Refined(transfers)
    }

    fun bind(
        entry: KtDestructuringDeclarationEntry,
        permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
        consume: () -> Unit,
        unsupported: (ValueFlowUnsupportedCause) -> Unit,
    ) {
        val declaration = entry.parent as? KtDestructuringDeclaration
        if (declaration == null || !entry.isNativeComponent()) {
            unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            return
        }
        when (val admitted = targets(declaration, permit)) {
            is Refinement.Refined -> consume()
            is Refinement.Rejected -> unsupported(admitted.failure)
        }
    }

    private fun targets(
        declaration: KtDestructuringDeclaration,
        permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
    ): Refinement<NativeDestructuringAdmission, ValueFlowUnsupportedCause> =
        NativeDestructuringAdmission.read(
            declaration = declaration,
            enclosing = source.enclosing,
            scope = scope,
            projection = projection,
            permit = permit,
        )

    private fun project(
        entry: KtDestructuringDeclarationEntry,
        target: NativeComponentTarget,
    ): Refinement<NativeValueFieldRead, ValueFlowUnsupportedCause> =
        when (target) {
            is NativeComponentTarget.Field ->
                nativeComponentFieldRead(
                    source = source,
                    expression = entry,
                    parameter = target.parameter,
                    scope = scope,
                    projection = projection,
                )
            is NativeComponentTarget.Source ->
                when (
                    val receiver =
                        nativeComponentReceiver(
                            entry = entry,
                            target = target,
                            executionOwner = source.executionOwner,
                            scope = scope,
                            projection = projection,
                        )
                ) {
                    is Refinement.Rejected -> receiver
                    is Refinement.Refined ->
                        when (val arrived = nativeImplicitReceiverArrival(source, receiver.value)) {
                            is Refinement.Refined -> Refinement.Refined(NativeValueFieldRead.Transfer(arrived.value))
                            is Refinement.Rejected -> arrived
                        }
                }
        }
}

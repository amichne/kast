package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtExpression

/** Local positional components share one RHS; independently sequenced authored effects remain outside this slice. */
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
        if (declaration.initializer !== expression) {
            unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            return
        }
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
        components: List<Pair<KtDestructuringDeclarationEntry, NativeComponentTarget>>,
        permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
    ): Refinement<List<ValueTransfer>, ValueFlowUnsupportedCause> {
        val transfers = mutableListOf<ValueTransfer>()
        for ((entry, target) in components) {
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
        if (declaration == null || !entry.isNativeLocalComponent()) {
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
    ): Refinement<List<Pair<KtDestructuringDeclarationEntry, NativeComponentTarget>>, ValueFlowUnsupportedCause> =
        nativeDestructuringTargets(
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

/** Admission is shared by forward flow, returned component bindings, and exact result-site revalidation. */
internal fun nativeDestructuringTargets(
    declaration: KtDestructuringDeclaration,
    enclosing: RelationEndpoint,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<List<Pair<KtDestructuringDeclarationEntry, NativeComponentTarget>>, ValueFlowUnsupportedCause> {
    if (!declaration.isNativeLocalDestructuring())
        return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    if (declaration.isVar) return Refinement.Rejected(ValueFlowUnsupportedCause.MUTABLE_CONTROL_FLOW)
    val components = mutableListOf<Pair<KtDestructuringDeclarationEntry, NativeComponentTarget>>()
    for (entry in declaration.entries) {
        if (entry.name == "_") continue
        when (val allowed = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        when (val target = nativeComponentTarget(entry, projection)) {
            is Refinement.Refined -> components += entry to target.value
            is Refinement.Rejected -> return target
        }
    }
    if (components.size > 1 && components.any { it.second is NativeComponentTarget.Source })
        return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    return when (
        val stored =
            nativeStoredComponents(
                components = components,
                enclosing = enclosing,
                scope = scope,
                projection = projection,
                permit = permit,
            )
    ) {
        is Refinement.Refined -> Refinement.Refined(components)
        is Refinement.Rejected -> stored
    }
}

private fun nativeStoredComponents(
    components: List<Pair<KtDestructuringDeclarationEntry, NativeComponentTarget>>,
    enclosing: RelationEndpoint,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
    permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
): Refinement<Unit, ValueFlowUnsupportedCause> {
    for ((_, target) in components) {
        if (target !is NativeComponentTarget.Field) continue
        when (val allowed = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return allowed
        }
        when (
            val field =
                nativeValueField(
                    declaration = target.parameter,
                    enclosing = enclosing,
                    scope = scope,
                    projection = projection,
                )
        ) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return field
        }
    }
    return Refinement.Refined(Unit)
}

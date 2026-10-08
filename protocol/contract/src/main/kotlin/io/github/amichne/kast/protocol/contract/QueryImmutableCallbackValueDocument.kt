package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

sealed interface QueryImmutableCallbackValueOriginDocument {
    data class Returned(val factory: QueryCallbackFactoryReturnDocument) : QueryImmutableCallbackValueOriginDocument

    data class Anonymous(val body: QueryCallbackBodyDocument.Anonymous) : QueryImmutableCallbackValueOriginDocument

    data class Named(
        val basis: ImpactSemanticBasisDocument,
        val occurrence: RelationOccurrenceDocument,
        val target: QueryCallbackCallableDocument,
        val dispatchReceiver: QueryCallbackReferenceReceiverDocument,
        val extensionReceiver: QueryCallbackReferenceReceiverDocument,
    ) : QueryImmutableCallbackValueOriginDocument
}

enum class QueryImmutableCallbackValueFailure {
    INVALID_ORIGIN,
    INVALID_TRANSPORT,
}

@ConsistentCopyVisibility
data class QueryImmutableCallbackValueDocument
private constructor(
    val origin: QueryImmutableCallbackValueOriginDocument,
    val source: ImpactValueSiteReferenceDocument,
    val destination: ImpactValueSiteReferenceDocument,
    val transfers: BoundedProtocolList<ImpactCompilerTransferDocument>,
    val invokedCallables: BoundedProtocolList<QueryCallbackCallableDocument>,
) {
    companion object {
        fun create(
            origin: QueryImmutableCallbackValueOriginDocument,
            source: ImpactValueSiteReferenceDocument,
            destination: ImpactValueSiteReferenceDocument,
            transfers: BoundedProtocolList<ImpactCompilerTransferDocument>,
            invokedCallables: BoundedProtocolList<QueryCallbackCallableDocument>,
        ): Refinement<QueryImmutableCallbackValueDocument, QueryImmutableCallbackValueFailure> {
            when (val admitted = origin.admitSource(source)) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> Unit
            }
            if (!validTransport(source, destination, transfers.values)) return invalidImmutableTransport()
            if (!invokedCallables.values.admitArguments(source, destination, transfers.values))
                return invalidImmutableTransport()
            return Refinement.Refined(
                QueryImmutableCallbackValueDocument(origin, source, destination, transfers, invokedCallables)
            )
        }
    }
}

private fun ImpactValueSiteReferenceDocument.validImmutableSite(): Boolean {
    if (
        range.start.value >= range.end.value ||
            range.start.value < enclosing.range.start.value ||
            range.end.value > enclosing.range.end.value
    )
        return false
    val argument = role as? ImpactValueRoleDocument.Argument ?: return true
    return argument.invocation.callable.basis == enclosing.basis &&
        argument.invocation.range.start.value >= enclosing.range.start.value &&
        argument.invocation.range.end.value <= enclosing.range.end.value &&
        range.start.value >= argument.invocation.range.start.value &&
        range.end.value <= argument.invocation.range.end.value
}

private fun ImpactCompilerTransferDocument.validImmutableTransfer(owner: ImpactDeclarationReferenceDocument): Boolean =
    source != target &&
        admitsEvidence() &&
        source.enclosing == owner &&
        target.enclosing == owner &&
        source.validImmutableSite() &&
        target.validImmutableSite() &&
        when (kind) {
            ImpactTransferKindDocument.LOCAL_BINDING -> target.role == ImpactValueRoleDocument.LocalBinding
            ImpactTransferKindDocument.LOCAL_READ ->
                source.role == ImpactValueRoleDocument.LocalBinding && target.role == ImpactValueRoleDocument.LocalRead
            ImpactTransferKindDocument.ARGUMENT -> target.role is ImpactValueRoleDocument.Argument
            ImpactTransferKindDocument.BRANCH_ALTERNATIVE -> target.role == ImpactValueRoleDocument.ExpressionResult
            ImpactTransferKindDocument.WRAPPER_RETURN -> {
                val argument = source.role as? ImpactValueRoleDocument.Argument
                argument != null &&
                    target.role == ImpactValueRoleDocument.ExpressionResult &&
                    target.range == argument.invocation.range
            }
            ImpactTransferKindDocument.RETURN,
            ImpactTransferKindDocument.PROPERTY_ASSIGNMENT -> false
        }

private fun QueryImmutableCallbackValueOriginDocument.admitSource(
    source: ImpactValueSiteReferenceDocument
): Refinement<Unit, QueryImmutableCallbackValueFailure> {
    val location =
        when (this) {
            is QueryImmutableCallbackValueOriginDocument.Anonymous ->
                when (val body = body.admitOwner()) {
                    is Refinement.Refined ->
                        body.value.file to
                            ImpactSourceRangeDocument(body.value.range.startInclusive, body.value.range.endExclusive)
                    is Refinement.Rejected -> return invalidImmutableOrigin()
                }
            is QueryImmutableCallbackValueOriginDocument.Returned -> {
                if (factory.enclosing != source.enclosing) return invalidImmutableOrigin()
                factory.enclosing.file to factory.invocation.range
            }
            is QueryImmutableCallbackValueOriginDocument.Named -> {
                if (!admitsNamedOrigin(source)) return invalidImmutableOrigin()
                occurrence.file to
                    ImpactSourceRangeDocument(occurrence.range.startInclusive, occurrence.range.endExclusive)
            }
        }
    if (
        source.role != ImpactValueRoleDocument.ExpressionResult ||
            source.enclosing.file != location.first ||
            source.range != location.second
    )
        return invalidImmutableOrigin()
    return Refinement.Refined(Unit)
}

private fun QueryImmutableCallbackValueOriginDocument.Named.admitsNamedOrigin(
    source: ImpactValueSiteReferenceDocument
): Boolean =
    basis == source.enclosing.basis &&
        target.validCallable() &&
        listOf(dispatchReceiver, extensionReceiver).all { receiver ->
            when (receiver) {
                is QueryCallbackReferenceReceiverDocument.Bound -> occurrence.contains(receiver.occurrence)
                is QueryCallbackReferenceReceiverDocument.Implicit -> receiver.declaration.validDeclaration()
                QueryCallbackReferenceReceiverDocument.Absent,
                QueryCallbackReferenceReceiverDocument.Unbound -> true
            }
        }

private fun validTransport(
    source: ImpactValueSiteReferenceDocument,
    destination: ImpactValueSiteReferenceDocument,
    edges: List<ImpactCompilerTransferDocument>,
): Boolean {
    if (!source.validImmutableSite() || !destination.validImmutableSite() || destination.enclosing != source.enclosing)
        return false
    if (edges.any { !it.validImmutableTransfer(source.enclosing) }) return false
    if (edges.isEmpty()) return source == destination
    return edges.first().source == source &&
        edges.last().target == destination &&
        edges.zipWithNext().all { (left, right) -> left.target == right.source }
}

private fun List<QueryCallbackCallableDocument>.admitArguments(
    source: ImpactValueSiteReferenceDocument,
    destination: ImpactValueSiteReferenceDocument,
    edges: List<ImpactCompilerTransferDocument>,
): Boolean {
    if (any { !it.validCallable() } || distinct().size != size) return false
    val roles =
        (listOf(source, destination) + edges.flatMap { listOf(it.source, it.target) }).mapNotNull {
            it.role as? ImpactValueRoleDocument.Argument
        }
    if (roles.any { !admitsArgument(it, source.enclosing.basis) }) return false
    return all { callable -> roles.any { it.invocation.callable == callable.reference(source.enclosing.basis) } }
}

private fun List<QueryCallbackCallableDocument>.admitsArgument(
    role: ImpactValueRoleDocument.Argument,
    basis: ImpactSemanticBasisDocument,
): Boolean {
    val callable = singleOrNull { it.reference(basis) == role.invocation.callable } ?: return false
    val signature =
        callable.compilerTarget.compilerEvidence.signature as? CompilerCallableSignatureDocument ?: return false
    return role.index.value in signature.valueParameters.values.indices
}

private fun invalidImmutableOrigin() = Refinement.Rejected(QueryImmutableCallbackValueFailure.INVALID_ORIGIN)

private fun invalidImmutableTransport() = Refinement.Rejected(QueryImmutableCallbackValueFailure.INVALID_TRANSPORT)

package io.github.amichne.kast.relation.contract

/**
 * An independent origin and compiler receiver edge supply representation evidence before the automatic model applies.
 */
internal class ModeledValueProvenanceOracleCase(private val generation: Long = 1) {
    val model = ReviewedModelOracleCase(generation)
    val receiver = model.receiver()
    private val seed =
        ValueInvocation.fromCompiler(
                enclosing = model.host,
                range = flowRange(40, 43),
                callable = flowEndpoint(name = "produceList", start = 800, end = 860, generation = generation),
                executionOwner = model.execution,
            )
            .flowRefined()
    private val identity =
        ContractModelIdentity(
            id("provenance-oracle"),
            ModelVersion.parse(1).flowRefined(),
            id("review:modeled-value"),
        )
    private val opaque = id("OPAQUE")
    private val readable = id("READABLE")
    private val domain = RepresentationDomain.admit(identity, listOf(opaque, readable)).flowRefined()
    private val originRule =
        RepresentationRule.Origin.admit(
                ModelRuleReference(identity, id("origin")),
                bind(seed.callable, ModelValuePosition.Result),
                domain.state(opaque).flowRefined(),
            )
            .flowRefined()
    private val seeded =
        seed
            .resultSite()
            .withState(
                callStack = model.stack,
                predicates = model.predicates,
            )
            .flowRefined()
    val origin = RepresentationEvidence.origin(seeded, seed, originRule).flowRefined()
    val receiverTransfer = ValueTransfer.fromCompiler(origin.site, receiver, ValueTransferKind.RECEIVER).flowRefined()
    val known = origin.transfer(receiverTransfer).flowRefined()

    fun application(source: ValueSite = receiver): ValueModelApplication.CollectionSelection {
        val call = (source.role as ValueRole.Receiver).call
        val binding =
            ReviewedLibraryBinding.fromCompiler(call, ReviewedValueModel.FirstOrNull, model.artifact).flowRefined()
        return ValueModelApplication.CollectionSelection.selectFirst(source, binding).flowRefined()
    }

    fun unknownInput(): RepresentationEvidence {
        val callable =
            flowEndpoint(
                name = "representationChange",
                start = 900,
                end = 960,
                parameters = listOf("String"),
                generation = generation,
            )
        val call =
            ValueInvocation.fromCompiler(
                    enclosing = model.host,
                    range = flowRange(43, 46),
                    callable = callable,
                    executionOwner = model.execution,
                )
                .flowRefined()
        val zero = ValueArgumentPosition.parse(0).flowRefined()
        val argument =
            ValueSite.fromCompiler(
                    enclosing = model.host,
                    range = flowRange(44, 45),
                    role = ValueRole.Argument(call, zero),
                    executionOwner = model.execution,
                    callStack = model.stack,
                    predicates = model.predicates,
                )
                .flowRefined()
        val actual =
            origin
                .transfer(ValueTransfer.fromCompiler(origin.site, argument, ValueTransferKind.ARGUMENT).flowRefined())
                .flowRefined()
        val result = call.resultSite().withState(callStack = model.stack, predicates = model.predicates).flowRefined()
        val transformation =
            RepresentationRule.Transformation.admit(
                    reference = ModelRuleReference(identity, id("needs-readable")),
                    input = bind(callable, ModelValuePosition.Argument(zero)),
                    output = bind(callable, ModelValuePosition.Result),
                    from = domain.state(readable).flowRefined(),
                    to = domain.state(opaque).flowRefined(),
                )
                .flowRefined()
        val changed = actual.transform(result, call, transformation).flowRefined()
        return changed
            .transfer(ValueTransfer.fromCompiler(result, receiver, ValueTransferKind.RECEIVER).flowRefined())
            .flowRefined()
    }

    private fun bind(endpoint: RelationEndpoint, position: ModelValuePosition): ExactModelCallablePosition =
        ExactModelCallablePosition.admit(
                ModelCallableReference(
                    basis = endpoint.lease.identity,
                    callable = endpoint.compilerIdentity,
                    file = endpoint.file,
                    range = endpoint.range,
                    position = position,
                ),
                RevalidatedRelationEndpoint.validate(endpoint, (endpoint as RelationEndpoint.Resolved).evidence)
                    .flowRefined(),
            )
            .flowRefined()

    private fun id(value: String) = ModelIdentifier.parse(value).flowRefined()
}

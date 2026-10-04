package io.github.amichne.kast.protocol.contract

internal class ImpactPeerFixture {
    val values = ImpactFindingFixture()
    val source = values.producer
    val peerBasis =
        ImpactSemanticBasisDocument.Live(
            values.text("/peer"),
            values.text("00000000-0000-0000-0000-000000000009"),
            ImpactEvidenceRevisionDocument.parse(9).refined(),
            ImpactContentViewDocument.SAVED_PSI_COMMITTED,
            ImpactModelFormatDocument.Current,
        )
    val target = source.copy(enclosing = source.enclosing.copy(basis = peerBasis, file = values.text("/peer/File.kt")))
    val admission =
        ImpactPeerSiteAdmissionDocument(
            ImpactPeerAcquisitionReceiptDocument(
                peerBasis,
                values.flowDomain.budget,
                values.count(4),
                values.count(1_500_000),
            ),
            ImpactSiteAdmissionDocument(values.flowDomain.budget, values.count(2)),
        )
    val rule =
        ImpactBoundaryRuleDocument.Continuation(
            values.reference.rule,
            values.boundary.copy(site = source),
            values.boundary.copy(site = target),
            values.bounded(listOf(ImpactBoundaryCompatibilityDocument.CONTRACT_COMPATIBLE)),
        )
    val connection = ImpactBoundaryConnectionDocument(values.reference, rule, values.bounded(emptyList()))
    val terminal =
        ImpactPathTerminalDocument.UnresolvedPeerContinuation(
            values.reference,
            target,
            admission,
            ImpactPeerContinuationReasonDocument.PEER_FLOW_NOT_INVESTIGATED,
        )
    val findingTerminal =
        ImpactFindingTerminalDocument.UnresolvedPeerContinuation(
            values.reference,
            target,
            admission,
            ImpactPeerContinuationReasonDocument.PEER_FLOW_NOT_INVESTIGATED,
        )
    val path =
        ImpactPathDocument(
            source,
            values.bounded(listOf(ImpactPathStepDocument.ModeledBoundary(connection))),
            ImpactRepresentationEvidenceDocument.NotModeled,
            terminal,
        )
    val witness = ImpactWitnessDocument.PeerBoundaryModel(values.reference, rule, admission)

    fun result(path: ImpactPathDocument = this.path): QueryRunResult =
        QueryRunResult(
            question = question(),
            items = values.bounded(listOf(QueryResultItemDocument.ValuePath(path))),
            failures = values.bounded(emptyList()),
            impactAccounting = accounting(),
        )

    fun question() =
        QueryQuestionDocument(
            QueryFromDocument.Impact(
                QueryImpactSourceDocument(
                    seeds =
                        values.bounded(
                            listOf(
                                QueryImpactProducerDocument(
                                    values.text("exact:owner"),
                                    values.text("exact:callable"),
                                    source.range,
                                )
                            )
                        ),
                    declarations = values.bounded(emptyList()),
                    domain = QueryExpansionScopeDocument.Workspace,
                    flow = QueryImpactFlowDocument.KOTLIN_FORWARD_V1,
                    models =
                        values.bounded(
                            listOf(
                                ImpactModelDocument.Boundary(
                                    ImpactModelFormatDocument.Current,
                                    values.model,
                                    values.bounded(listOf(rule)),
                                )
                            )
                        ),
                )
            ),
            values.bounded(emptyList()),
            QueryOutputDocument.ValuePaths,
        )

    fun accounting() =
        ImpactAccountingDocument.Investigated(
            seeds = values.bounded(listOf(source)),
            requestedDomain = ImpactRequestedBoundaryDocument.Workspace,
            semantics = ImpactFlowSemanticsDocument.KOTLIN_FORWARD_V1,
            representationModelReferences = values.bounded(emptyList()),
            boundaryModelReferences = values.bounded(listOf(values.reference)),
            originalReadRejectionCount = values.count(0),
            originalObservationCount = values.count(0),
            originalPathCount = values.count(1),
            pagePathCount = values.count(1),
            status =
                ImpactAccountingStatusDocument.Unresolved(
                    values.bounded(listOf(ImpactRequiredObligationDocument.BOUNDARY))
                ),
            view = ImpactAccountingViewDocument.Paths,
            requestedSites = values.bounded(emptyList()),
        )

    private fun <T, F> io.github.amichne.kast.kernel.Refinement<T, F>.refined(): T =
        when (this) {
            is io.github.amichne.kast.kernel.Refinement.Refined -> value
            is io.github.amichne.kast.kernel.Refinement.Rejected -> error("Fixture rejection: $failure")
        }
}

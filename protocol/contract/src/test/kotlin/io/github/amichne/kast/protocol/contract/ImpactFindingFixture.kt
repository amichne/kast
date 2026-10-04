package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.kernel.WorkUnitLimit

internal class ImpactFindingFixture {
    val model = ImpactModelIdentityDocument(id("crypto"), ImpactModelVersionDocument.parse(1).refined(), id("reviewed"))
    val state = ImpactRepresentationStateDocument(model, bounded(listOf(id("HIPED"), id("VOLTAGE"))), id("VOLTAGE"))
    val reference = ImpactRuleReferenceDocument(model, id("rule"))
    val declaration =
        ImpactDeclarationReferenceDocument(
            basis =
                ImpactSemanticBasisDocument.Published(
                    text("/workspace"),
                    ImpactEvidenceRevisionDocument.parse(7).refined(),
                ),
            file = text("/workspace/File.kt"),
            range = ImpactSourceRangeDocument(offset(0), offset(100)),
            compilerIdentity = text("canonical-signature-sha256-v1|" + "a".repeat(64)),
        )
    val producer =
        ImpactValueSiteReferenceDocument(
            declaration,
            ImpactSourceRangeDocument(offset(10), offset(11)),
            ImpactValueRoleDocument.ExpressionResult,
        )
    val destination =
        ImpactValueSiteReferenceDocument(
            declaration,
            ImpactSourceRangeDocument(offset(20), offset(21)),
            ImpactValueRoleDocument.PropertyAssignment,
        )
    val boundary =
        ImpactBoundaryPositionDocument(
            site = destination,
            kind = ImpactBoundaryKindDocument.PERSISTENCE,
            contract = ImpactBoundaryContractDocument(id("storage"), ImpactModelVersionDocument.parse(1).refined()),
            slot = id("ciphertext"),
        )
    val obligation =
        ImpactBoundaryObligationDocument(
            boundary,
            bounded(
                listOf(
                    ImpactBoundaryRequiredDocument.RETENTION_POLICY,
                    ImpactBoundaryRequiredDocument.DECODING_COMPATIBILITY,
                    ImpactBoundaryRequiredDocument.MIGRATION_PROOF,
                )
            ),
        )
    val domain =
        QueryRelationDomainDocument(
            scope = QuerySemanticScopeDocument.Workspace,
            sourcePolicy = QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY,
            generatedSources = QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
            libraries = QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
            sourceSets = QueryDiscoverySourceSetsDocument.All,
            directory = null,
            packageName = null,
            declarationKinds = bounded(emptyList()),
        )
    val flowDomain =
        ImpactFlowDomainDocument(
            subject = declaration,
            meaning = RelationKindDocument.REFERENCES,
            requestedDomain = QueryRelationRequestedDomainDocument.WORKSPACE,
            domain = domain,
            fingerprint = QueryRelationDomainFingerprint.parse("b".repeat(64)).refined(),
            budget =
                ImpactFlowBudgetDocument(
                    maxElapsedMillis = ElapsedTimeLimitMillis.parse(100).refined(),
                    maxWorkUnits = WorkUnitLimit.parse(10).refined(),
                    maxResults = ResultLimit.parse(2).refined(),
                    maxReturnedBytes = ReturnedByteLimit.parse(10000).refined(),
                ),
            position = ImpactFlowReadPositionDocument.Start,
        )

    fun provenance(): List<ImpactFindingProvenanceDocument> =
        listOf(
            ImpactFindingProvenanceDocument.Origin(reference, state),
            ImpactFindingProvenanceDocument.ModeledTransfer(reference),
            ImpactFindingProvenanceDocument.ModeledTransformation(reference),
            ImpactFindingProvenanceDocument.BoundaryModel(reference),
            ImpactFindingProvenanceDocument.Unmodeled,
        )

    fun representation(): ImpactFindingRepresentationDocument.Present =
        ImpactFindingRepresentationDocument.Present(
            bounded(
                listOf(
                    ImpactFindingBranchDocument(
                        ImpactRepresentationCurrentDocument.Known(state),
                        bounded(provenance()),
                    ),
                    ImpactFindingBranchDocument(
                        ImpactRepresentationCurrentDocument.Unknown(
                            ImpactRepresentationUnknownDocument.UNMODELED_TRANSFORMATION
                        ),
                        bounded(listOf(ImpactFindingProvenanceDocument.Unmodeled)),
                    ),
                )
            )
        )

    fun terminals(): List<ImpactFindingTerminalDocument> =
        consumerTerminals() + boundaryTerminals() + readTerminals() + remainingTerminals()

    private fun consumerTerminals(): List<ImpactFindingTerminalDocument> =
        ImpactConsumerOutcomeDocument.entries.map {
            ImpactFindingTerminalDocument.Consumer(reference, state, it)
        }

    private fun boundaryTerminals(): List<ImpactFindingTerminalDocument> =
        listOf(
            ImpactFindingTerminalDocument.ModeledTerminal(
                reference = reference,
                source = boundary,
                meaning = ImpactBoundaryTerminalDocument.REVIEWED_RETENTION,
                obligations = bounded(listOf(obligation)),
            ),
            ImpactFindingTerminalDocument.UnresolvedFlow(ImpactFlowUnsupportedDocument.MUTABLE_CONTROL_FLOW),
        )

    private fun readTerminals(): List<ImpactFindingTerminalDocument> =
        listOf(
            ImpactFindingTerminalDocument.UnresolvedRead(
                ImpactReadRejectionDocument.Native(
                    source = destination,
                    domain = ImpactRequestedBoundaryDocument.Workspace,
                    cause = ImpactNativeReadRejectionDocument.AUTHORITY_MOVED,
                    examinedWorkUnits = count(3),
                )
            ),
            ImpactFindingTerminalDocument.UnresolvedRead(
                ImpactReadRejectionDocument.Contract(
                    source = destination,
                    domain = ImpactRequestedBoundaryDocument.Workspace,
                    cause = ImpactReadContractRejectionDocument.DOMAIN_MISMATCH,
                    examinedWorkUnits = count(4),
                )
            ),
        )

    private fun remainingTerminals(): List<ImpactFindingTerminalDocument> =
        listOf(
            ImpactFindingTerminalDocument.UnresolvedBoundary(
                boundary,
                ImpactBoundaryUnresolvedDocument.MISSING_MODEL,
                obligation,
            ),
            ImpactFindingTerminalDocument.ExecutionStop(ImpactFindingExecutionStopDocument.Cycle(count(2))),
            ImpactFindingTerminalDocument.ExecutionStop(
                ImpactFindingExecutionStopDocument.CheckpointCapacity(count(20), count(10))
            ),
            ImpactFindingTerminalDocument.SupportedDomainEnd(
                ImpactFlowEndObservationDocument(
                    source = destination,
                    domain = flowDomain,
                    examinedWorkUnits = count(1),
                    retainedBytes = count(200),
                    terminal = ImpactFlowTerminalDocument.SUPPORTED_DOMAIN_EXHAUSTED,
                )
            ),
            ImpactFindingTerminalDocument.ExplicitScopeExclusion(
                domain,
                ImpactScopeExclusionDocument.OUTSIDE_DIRECTORY,
            ),
        )

    fun finding(ordinal: Long = 2, rowId: Int = 1): ImpactFindingDocument =
        ImpactFindingDocument(
            path = ImpactFindingEvidenceReferenceDocument(count(ordinal), row(rowId)),
            producer = producer,
            destination = destination,
            representation = representation(),
            terminal = ImpactFindingTerminalDocument.UnresolvedFlow(ImpactFlowUnsupportedDocument.MUTABLE_CONTROL_FLOW),
            boundaryObligations = bounded(listOf(obligation)),
        )

    fun row(index: Int): QueryResultRowReference =
        QueryResultRowReference.parse("result-row:v1:00000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
            .refined()

    fun id(raw: String) = ImpactModelIdentifierDocument.parse(raw).refined()

    fun text(raw: String) = ProtocolText.parse(raw).refined()

    fun offset(raw: Int) = ProtocolOffset.parse(raw).refined()

    fun count(raw: Long) = QueryDiscoveryCountDocument.parse(raw).refined()

    fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejection: $failure")
        }
}

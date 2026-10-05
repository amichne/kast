package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.CompilerTypeParameterCountDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackCallableDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackExclusionReasonDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNamedPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourceSetsDocument
import io.github.amichne.kast.protocol.contract.QueryExcludedCompilerTargetDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainDocument
import io.github.amichne.kast.protocol.contract.QueryRelationDomainFingerprint
import io.github.amichne.kast.protocol.contract.QueryRelationRequestedDomainDocument
import io.github.amichne.kast.protocol.contract.QuerySemanticScopeDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationOccurrenceDocument
import io.github.amichne.kast.protocol.contract.SourceRangeDocument
import io.github.amichne.kast.protocol.contract.SymbolKindDocument

internal class QueryCallbackWireFixture {
    fun callbackDocument(): QueryCallbackObservationDocument {
        val owner = callable("Seed.kt", 0, 40, "sample.read", emptyList())
        val mapped = callable("Boundary.kt", 0, 30, "sample.nativeBoundary", listOf("kotlin.Function0<kotlin.Unit>"))
        val target = callable("Target.kt", 0, 20, "sample.readPrepared", emptyList())
        val body = occurrence("Seed.kt", 4, 18)
        val flow = callbackFlow(owner, mapped, body)
        return QueryCallbackObservationDocument.create(
                occurrence("Seed.kt", 5, 8),
                target,
                owner,
                body,
                QueryCallbackNamedPolicyDocument.Excluded(
                    QueryCallbackExclusionReasonDocument.NON_INLINE_ARGUMENT,
                    body,
                ),
                flow,
                RelationKindDocument.CALLEES,
                QueryRelationRequestedDomainDocument.WORKSPACE,
                QueryRelationDomainDocument(
                    QuerySemanticScopeDocument.Workspace,
                    QueryDiscoverySourcePolicyDocument.PRODUCTION_AND_TEST,
                    QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
                    QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
                    QueryDiscoverySourceSetsDocument.All,
                    null,
                    null,
                    bounded(emptyList()),
                ),
                QueryRelationDomainFingerprint.parse("1".repeat(64)).refined(),
            )
            .refined()
    }

    private fun callbackFlow(
        owner: QueryCallbackCallableDocument,
        mapped: QueryCallbackCallableDocument,
        body: RelationOccurrenceDocument,
    ): QueryCallbackFlowDocument.Observed {
        val basis =
            ImpactSemanticBasisDocument.Published(
                text("/workspace"),
                ImpactEvidenceRevisionDocument.parse(1L).refined(),
            )
        val callableRef =
            ImpactDeclarationReferenceDocument(
                basis,
                mapped.compilerTarget.file,
                ImpactSourceRangeDocument(
                    mapped.declaration.range.startInclusive,
                    mapped.declaration.range.endExclusive,
                ),
                mapped.compilerTarget.compilerEvidence.identity,
            )
        val binding =
            QueryCallbackBindingDocument.Bound(
                ImpactInvocationReferenceDocument(ImpactSourceRangeDocument(offset(2), offset(20)), callableRef),
                occurrence("Seed.kt", 2, 20),
                QueryCallbackBodyDocument.Named(owner),
                mapped,
                offset(0),
                occurrence("Boundary.kt", 2, 9),
            )
        val anonymousEvidence = signature("anonymous@Seed.kt#4:18", emptyList())
        return QueryCallbackFlowDocument.Observed(
            basis,
            QueryCallbackBodyDocument.Anonymous(body, anonymousEvidence),
            binding,
            bounded(
                listOf(
                    QueryCallbackInvocationDocument(
                        occurrence("Boundary.kt", 12, 20),
                        QueryCallbackBodyDocument.Named(mapped),
                        bounded(emptyList()),
                    )
                )
            ),
            bounded(emptyList()),
            bounded(emptyList()),
        )
    }

    fun callable(
        file: String,
        start: Int,
        end: Int,
        identity: String,
        parameters: List<String>,
    ): QueryCallbackCallableDocument {
        val declaration = occurrence(file, start, end)
        return QueryCallbackCallableDocument(
            declaration,
            QueryExcludedCompilerTargetDocument.create(
                    text(file),
                    declaration.range,
                    text(identity.substringAfterLast('.')),
                    SymbolKindDocument.FUNCTION,
                    signature(identity, parameters),
                )
                .refined(),
        )
    }

    fun signature(identity: String, parameters: List<String>) =
        CompilerSymbolEvidenceDocument.fromSignature(
                CompilerSignatureDocument.Function(
                    text(identity),
                    CompilerReceiverDocument.Absent,
                    bounded(emptyList()),
                    bounded(parameters.map(::text)),
                    CompilerTypeParameterCountDocument.parse(0).refined(),
                )
            )
            .refined()

    fun occurrence(file: String, start: Int, end: Int) =
        RelationOccurrenceDocument(
            text("candidate:$file:$start:$end"),
            text(file),
            SourceRangeDocument.create(offset(start), offset(end)).refined(),
        )

    fun text(value: String) = ProtocolText.parse(value).refined()

    private fun offset(value: Int) = ProtocolOffset.parse(value).refined()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid fixture: $failure")
        }
}

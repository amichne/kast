package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.EvidenceBasis
import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.LiveReadContentView
import io.github.amichne.kast.kernel.LiveReadEvidence
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ImpactContentViewDocument
import io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument
import io.github.amichne.kast.protocol.contract.ImpactModelFormatDocument
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.QueryCallableObservationDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowFailureDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphObligationsDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument
import io.github.amichne.kast.protocol.contract.QueryExpandedFrontierDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryKnownMinimum
import io.github.amichne.kast.protocol.contract.QueryLimitationDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryQualifiedProgressDocument
import io.github.amichne.kast.protocol.contract.QueryQuestionDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryRelationCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryRelationObservationDocument
import io.github.amichne.kast.protocol.contract.QueryRunQualification
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.QueryTerminalReasonDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCallableObservationDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCallbackObservationDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationProviderDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalExtentDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class QueryCallbackEnvelopeBasisTest {
    private val fixture = QueryCallbackWireFixture()
    private val sample = fixture.callbackDocument()
    private val binding = CanonicalOperationWireBindings.queryRun
    private val invalid = WireFailure.InvalidPayload(WireValueRole.RESULT)

    @TestFactory
    fun `operation envelope anchors every callback frame`() =
        Placement.entries.flatMap { placement ->
            Content.entries.flatMap { content ->
                basisCases().map { case ->
                    DynamicTest.dynamicTest("$placement/$content/${case.name}") {
                        assertEnvelope(placement, content, case)
                    }
                }
            }
        }

    @TestFactory
    fun `published frames need an independent workspace identity`() =
        Placement.entries.flatMap { placement ->
            Content.entries.flatMap { content ->
                listOf("/workspace", "/foreign").map { root ->
                    DynamicTest.dynamicTest("$placement/$content/published-root-$root") {
                        assertUnanchoredPublished(placement, content, root)
                    }
                }
            }
        }

    private fun assertUnanchoredPublished(placement: Placement, content: Content, root: String) {
        val basis = EvidenceBasis.Published(EvidenceGeneration.parse(1).refined())
        val observed = ImpactSemanticBasisDocument.Published(fixture.text(root), revision(1))
        val result = payload(placement, frame(content, observed))
        val empty = result.copy(relationObservations = bounded(emptyList()), walkObservations = bounded(emptyList()))
        val control = outcome(basis, empty)
        val encoded = binding.encodeOutcome(control) as WireEncoding.Encoded
        assertEquals(WireDecoding.Decoded(control), binding.decodeOutcome(encoded.document))
        val document = wireJson.decodeFromString(WireEnvelopeDocument.serializer(), encoded.document)
        val body = document.body as WireBodyDocument.Qualified
        val nested = CanonicalQuerySerializers.result.encode(result, WireValueRole.RESULT) as WireValueEncoding.Encoded
        val substituted = document.copy(body = body.copy(result = nested.value))
        val raw = wireJson.encodeToString(WireEnvelopeDocument.serializer(), substituted)
        assertAll(
            { assertEquals(WireDecoding.Rejected(invalid), binding.decodeOutcome(raw)) },
            { assertEquals(WireEncoding.Rejected(invalid), binding.encodeOutcome(outcome(basis, result))) },
        )
    }

    private fun assertEnvelope(placement: Placement, content: Content, case: BasisCase) {
        val original = outcome(case.envelope, payload(placement, frame(content, case.correct)))
        val encoded = binding.encodeOutcome(original) as WireEncoding.Encoded
        assertEquals(WireDecoding.Decoded(original), binding.decodeOutcome(encoded.document))
        val foreign = payload(placement, frame(content, case.foreign))
        val foreignJson =
            CanonicalQuerySerializers.result.encode(foreign, WireValueRole.RESULT) as WireValueEncoding.Encoded
        val document = wireJson.decodeFromString(WireEnvelopeDocument.serializer(), encoded.document)
        val body = document.body as WireBodyDocument.Qualified
        val substituted = document.copy(body = body.copy(result = foreignJson.value))
        val raw = wireJson.encodeToString(WireEnvelopeDocument.serializer(), substituted)
        assertAll(
            { assertEquals(WireDecoding.Rejected(invalid), binding.decodeOutcome(raw)) },
            { assertEquals(WireEncoding.Rejected(invalid), binding.encodeOutcome(outcome(case.envelope, foreign))) },
        )
    }

    private fun outcome(basis: EvidenceBasis, payload: QueryRunResult) =
        OperationOutcome.Qualified(
            EvidenceEnvelope(CanonicalOperation.QUERY_RUN.id, basis, payload),
            QueryRunQualification.create(
                    QueryKnownMinimum.parse(0).refined(),
                    listOf(QueryLimitationDocument.WORK_LIMIT_REACHED),
                    QueryQualifiedProgressDocument.TerminalIncomplete(QueryTerminalReasonDocument.UPSTREAM_INCOMPLETE),
                )
                .refined(),
        )

    private fun frame(content: Content, basis: ImpactSemanticBasisDocument): Frame {
        val original = sample.toWireDocument()
        val projection =
            when (content) {
                Content.CALLBACK_UNPROVEN,
                Content.NAMED_UNPROVEN ->
                    QueryCallbackResultProjectionWireDocument.Unproven(QueryCallbackFlowCauseDocument.OUTSIDE_DOMAIN)
                Content.CALLBACK_UNAVAILABLE,
                Content.CALLBACK_REJECTED,
                Content.NAMED_UNAVAILABLE ->
                    (original.resultProjection as QueryCallbackResultProjectionWireDocument.Admitted).copy(
                        basis = basis
                    )
            }
        return when (content) {
            Content.CALLBACK_UNAVAILABLE,
            Content.CALLBACK_REJECTED,
            Content.CALLBACK_UNPROVEN -> callbackFrame(original, content, basis, projection)
            Content.NAMED_UNAVAILABLE,
            Content.NAMED_UNPROVEN -> namedFrame(original, basis, projection)
        }
    }

    private fun callbackFrame(
        original: QueryCallbackObservationWireDocument,
        content: Content,
        basis: ImpactSemanticBasisDocument,
        projection: QueryCallbackResultProjectionWireDocument,
    ) =
        Frame.Callback(
            original
                .copy(
                    basis = basis,
                    resultProjection = projection,
                    flow =
                        if (content == Content.CALLBACK_REJECTED)
                            QueryCallbackFlowWireDocument.ContractRejected(
                                QueryCallbackFlowFailureDocument.BASIS_MISMATCH
                            )
                        else QueryCallbackFlowWireDocument.Unavailable(QueryCallbackFlowCauseDocument.STORED_CALLBACK),
                )
                .toContract()
                .value()
        )

    private fun namedFrame(
        original: QueryCallbackObservationWireDocument,
        basis: ImpactSemanticBasisDocument,
        projection: QueryCallbackResultProjectionWireDocument,
    ) =
        Frame.Callable(
            QueryCallableObservationWireDocument(
                    original.callbackBody,
                    original.lexicalOwner,
                    QueryCallbackBodyWireDocument.Named(original.lexicalOwner),
                    QueryCallableTargetWireDocument.NamedReference(
                        QueryNamedCallbackReferenceWireDocument(
                            original.target,
                            QueryCallbackReferenceReceiverWireDocument.Absent,
                            QueryCallbackReferenceReceiverWireDocument.Unbound,
                            QueryNamedCallbackReferenceFlowWireDocument.Unavailable(
                                QueryCallbackGraphObligationsDocument.from(
                                        listOf(QueryCallbackFlowCauseDocument.STORED_CALLBACK)
                                    )
                                    .refined()
                            ),
                        ),
                        projection,
                    ),
                    basis,
                )
                .toContract()
                .value()
        )

    private fun payload(placement: Placement, frame: Frame): QueryRunResult {
        val question =
            QueryQuestionDocument(
                QueryFromDocument.Location(fixture.text("Fixture.kt"), ProtocolOffset.parse(0).refined()),
                bounded(emptyList()),
                QueryOutputDocument.Symbols(bounded(listOf(QuerySymbolFieldDocument.NAME))),
            )
        val result = QueryRunResult(question, bounded(emptyList()), bounded(emptyList()))
        return when (placement) {
            Placement.RELATION -> result.copy(relationObservations = bounded(listOf(relation(frame))))
            Placement.WALK -> result.copy(walkObservations = bounded(listOf(walk(frame))))
        }
    }

    private fun relation(frame: Frame) =
        QueryRelationObservationDocument(
            subject = QueryReferenceDocument.ExactSymbol(fixture.text("exact:seed")),
            relation = RelationKindDocument.CALLEES,
            provider = RelationProviderDocument.INTELLIJ_CALLEES_V2,
            requestedDomain = sample.requestedDomain,
            effectiveDomain = sample.effectiveDomain,
            domainFingerprint = sample.domainFingerprint,
            coverage = QueryRelationCoverageDocument.Exhausted,
            scopeExclusions = bounded(emptyList()),
            callbackObservations =
                bounded(
                    when (frame) {
                        is Frame.Callback -> listOf(frame.value)
                        is Frame.Callable -> emptyList()
                    }
                ),
            callableObservations =
                bounded(
                    when (frame) {
                        is Frame.Callable -> listOf(frame.value)
                        is Frame.Callback -> emptyList()
                    }
                ),
        )

    private fun walk(frame: Frame): QueryWalkObservationDocument {
        val subject = QueryReferenceDocument.ExactSymbol(fixture.text("exact:seed"))
        val depth = TraversalDepthDocument.parse(0).refined()
        return QueryWalkObservationDocument(
            subject = subject,
            relation = RelationKindDocument.CALLEES,
            extent = TraversalExtentDocument.ThroughDepth(ProtocolCount.parse(1).refined()),
            expandedFrontier = QueryExpandedFrontierDocument.parse(1).refined(),
            progress = TraversalProgressDocument(1, 1, 0, 0),
            strategy = TraversalStrategyDocument.BreadthFirst,
            partialExpansions = bounded(emptyList()),
            coverage = QueryWalkCoverageDocument.Complete,
            requestedDomain = sample.requestedDomain,
            effectiveDomain = sample.effectiveDomain,
            domainFingerprint = sample.domainFingerprint,
            callbackObservations =
                bounded(
                    when (frame) {
                        is Frame.Callback -> listOf(QueryWalkCallbackObservationDocument(subject, depth, frame.value))
                        is Frame.Callable -> emptyList()
                    }
                ),
            callableObservations =
                bounded(
                    when (frame) {
                        is Frame.Callable ->
                            listOf(
                                QueryWalkCallableObservationDocument(
                                    subject,
                                    depth,
                                    frame.value,
                                    sample.requestedDomain,
                                    sample.effectiveDomain,
                                    sample.domainFingerprint,
                                )
                            )
                        is Frame.Callback -> emptyList()
                    }
                ),
        )
    }

    private fun basisCases(): List<BasisCase> {
        val host = "00000000-0000-0000-0000-000000000001"
        val live =
            EvidenceBasis.Live(
                LiveReadEvidence.create(
                        "/workspace",
                        UUID.fromString(host),
                        1,
                        LiveReadContentView.SAVED_PSI_COMMITTED,
                        1,
                    )
                    .refined()
            )
        val p = ImpactSemanticBasisDocument.Published(fixture.text("/workspace"), revision(1))
        val l =
            ImpactSemanticBasisDocument.Live(
                fixture.text("/workspace"),
                fixture.text(host),
                revision(1),
                ImpactContentViewDocument.SAVED_PSI_COMMITTED,
                ImpactModelFormatDocument.parse(1).refined(),
            )
        return listOf(
            BasisCase("live-kind", live, l, p),
            BasisCase("live-root", live, l, l.copy(root = fixture.text("/foreign"))),
            BasisCase("live-host", live, l, l.copy(host = fixture.text("00000000-0000-0000-0000-000000000002"))),
            BasisCase("live-epoch", live, l, l.copy(epoch = revision(2))),
        )
    }

    private fun revision(value: Long) = ImpactEvidenceRevisionDocument.parse(value).refined()

    private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).refined()

    private fun <T, F> Refinement<T, F>.refined(): T = (this as Refinement.Refined).value

    private fun <T> WireDocumentConversion<T>.value(): T = (this as WireDocumentConversion.Converted).value

    private enum class Placement {
        RELATION,
        WALK,
    }

    private enum class Content {
        CALLBACK_UNAVAILABLE,
        CALLBACK_REJECTED,
        CALLBACK_UNPROVEN,
        NAMED_UNAVAILABLE,
        NAMED_UNPROVEN,
    }

    private data class BasisCase(
        val name: String,
        val envelope: EvidenceBasis,
        val correct: ImpactSemanticBasisDocument,
        val foreign: ImpactSemanticBasisDocument,
    )

    private sealed interface Frame {
        data class Callback(val value: QueryCallbackObservationDocument) : Frame

        data class Callable(val value: QueryCallableObservationDocument) : Frame
    }
}

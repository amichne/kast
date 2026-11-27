package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CompilerReceiverDocument
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.CompilerSymbolEvidenceDocument
import io.github.amichne.kast.protocol.contract.CompilerTypeParameterCountDocument
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.QueryCallableObservationDocument
import io.github.amichne.kast.protocol.contract.QueryCallableTargetDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackDependencyContractProvenanceDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackDependencyInvocationKindDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackReferenceReceiverDocument
import io.github.amichne.kast.protocol.contract.QueryExcludedCompilerTargetDocument
import io.github.amichne.kast.protocol.contract.QueryExpandedFrontierDocument
import io.github.amichne.kast.protocol.contract.QueryNamedCallbackReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryNamedCallbackReferenceFlowDocument
import io.github.amichne.kast.protocol.contract.QueryReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCallableObservationDocument
import io.github.amichne.kast.protocol.contract.QueryWalkCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryWalkObservationDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.TraversalDepthDocument
import io.github.amichne.kast.protocol.contract.TraversalExtentDocument
import io.github.amichne.kast.protocol.contract.TraversalPartialExpansionDocument
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryNamedCallbackReferenceWireTest {
    private val fixture = QueryCallbackWireFixture()

    @Test
    fun `unproven membership still binds the available named flow basis`() {
        val wire = suppliedReference().toWireDocument()
        val target = wire.target as QueryCallableTargetWireDocument.NamedReference
        val unproven =
            wire.copy(
                target =
                    target.copy(
                        resultProjection =
                            QueryCallbackResultProjectionWireDocument.Unproven(
                                io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument.OUTSIDE_DOMAIN
                            )
                    )
            )
        assertTrue(unproven.toContract() is WireDocumentConversion.Converted)
        val foreign =
            io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument.Published(
                fixture.text("/workspace"),
                (io.github.amichne.kast.protocol.contract.ImpactEvidenceRevisionDocument.parse(2L)
                        as Refinement.Refined)
                    .value,
            )
        assertEquals(WireDocumentConversion.Rejected, unproven.copy(basis = foreign).toContract())
    }

    @Test
    fun `named projection rejects a substituted walk subject`() {
        val wire = namedReferenceWalk()
        assertTrue(decodeWalk(wire) is WireDocumentConversion.Converted)
        val other = QueryReferenceWireDocument.ExactSymbol("exact:other")
        assertEquals(
            WireDocumentConversion.Rejected,
            decodeWalk(
                wire.copy(
                    subject = other,
                    callableObservations = listOf(wire.callableObservations.single().copy(subject = other)),
                )
            ),
        )
        val entry = wire.callableObservations.single()
        val target = entry.observation.target as QueryCallableTargetWireDocument.NamedReference
        val projection = target.resultProjection as QueryCallbackResultProjectionWireDocument.Admitted
        val foreign =
            entry.copy(
                observation =
                    entry.observation.copy(target = target.copy(resultProjection = projection.copy(subject = other)))
            )
        assertEquals(WireDocumentConversion.Rejected, decodeWalk(wire.copy(callableObservations = listOf(foreign))))
    }

    @Test
    fun `callee walk rejects a valid caller occurrence projection`() {
        rejectCallerProjection(invocationSite = false)
    }

    @Test
    fun `callee walk rejects a valid caller invocation projection`() {
        rejectCallerProjection(invocationSite = true)
    }

    private fun rejectCallerProjection(invocationSite: Boolean) {
        val walk = namedReferenceWalk()
        assertTrue(decodeWalk(walk) is WireDocumentConversion.Converted)
        val entry = walk.callableObservations.single()
        val target = entry.observation.target as QueryCallableTargetWireDocument.NamedReference
        val projection = target.resultProjection as QueryCallbackResultProjectionWireDocument.Admitted
        val flow = target.reference.flow as QueryNamedCallbackReferenceFlowWireDocument.Supplied
        val site =
            if (invocationSite) QueryCallbackResultSiteWireDocument.Invocation(flow.invocations.single())
            else QueryCallbackResultSiteWireDocument.Occurrence(entry.observation.occurrence)
        val caller =
            entry.observation.copy(
                target =
                    target.copy(
                        resultProjection = projection.copy(relation = RelationKindDocument.CALLERS, site = site)
                    )
            )
        val admittedCaller = (caller.toContract() as WireDocumentConversion.Converted).value
        assertTrue(
            admittedCaller.admitsQuestion(
                QueryReferenceDocument.ExactSymbol(fixture.text("exact:seed")),
                RelationKindDocument.CALLERS,
                entry.effectiveDomain,
                entry.domainFingerprint,
            )
        )
        assertEquals(
            WireDocumentConversion.Rejected,
            decodeWalk(walk.copy(callableObservations = listOf(entry.copy(observation = caller)))),
        )
    }

    private fun namedReferenceWalk(): QueryWalkObservationWireDocument {
        val callback = fixture.callbackDocument()
        val subject = QueryReferenceDocument.ExactSymbol(fixture.text("exact:seed"))
        val callable =
            QueryWalkCallableObservationDocument(
                subject,
                TraversalDepthDocument.parse(0).value(),
                suppliedReference(),
                callback.requestedDomain,
                callback.effectiveDomain,
                callback.domainFingerprint,
            )
        return QueryWalkObservationDocument(
                subject = subject,
                relation = RelationKindDocument.CALLEES,
                extent = TraversalExtentDocument.ThroughDepth(ProtocolCount.parse(1).value()),
                expandedFrontier = QueryExpandedFrontierDocument.parse(1).value(),
                progress = TraversalProgressDocument(1, 1, 0, 0),
                strategy = TraversalStrategyDocument.BreadthFirst,
                partialExpansions = BoundedProtocolList.create(emptyList<TraversalPartialExpansionDocument>()).value(),
                coverage = QueryWalkCoverageDocument.Complete,
                requestedDomain = callback.requestedDomain,
                effectiveDomain = callback.effectiveDomain,
                domainFingerprint = callback.domainFingerprint,
                callableObservations = BoundedProtocolList.create(listOf(callable)).value(),
            )
            .toWireDocument()
    }

    private fun decodeWalk(
        wire: QueryWalkObservationWireDocument
    ): WireDocumentConversion<QueryWalkObservationDocument> {
        val raw = wireJson.encodeToString(QueryWalkObservationWireDocument.serializer(), wire)
        return wireJson.decodeFromString(QueryWalkObservationWireDocument.serializer(), raw).toContract()
    }

    private fun <V, F> Refinement<V, F>.value(): V = (this as Refinement.Refined).value

    @Test
    fun `named callable supply is distinct from invocation and preserves receivers`() {
        val value = suppliedReference()
        val wire = value.toWireDocument()
        assertEquals(WireDocumentConversion.Converted(value), wire.toContract())
        val raw = wireJson.encodeToJsonElement(QueryCallableObservationWireDocument.serializer(), wire).jsonObject
        val target = raw.getValue("target").jsonObject
        assertEquals(JsonPrimitive("NAMED_REFERENCE"), target.getValue("type"))
        val evidence = target.getValue("reference").jsonObject
        assertEquals(setOf("target", "dispatch_receiver", "extension_receiver", "flow"), evidence.keys)
        assertEquals(JsonPrimitive("UNBOUND"), evidence.getValue("extension_receiver").jsonObject.getValue("type"))
        assertEquals(setOf("type", "binding", "invocations", "forwarding"), evidence.getValue("flow").jsonObject.keys)
        assertThrows(SerializationException::class.java) {
            wireJson.decodeFromString(
                QueryCallableObservationWireDocument.serializer(),
                raw.toString().replace("UNBOUND", "UNKNOWN"),
            )
        }
        val named = wire.target as QueryCallableTargetWireDocument.NamedReference
        val outside =
            QueryCallbackReferenceReceiverWireDocument.Bound(
                RelationOccurrenceWireDocument("candidate:elsewhere", "Elsewhere.kt", SourceRangeWireDocument(0, 1))
            )
        assertEquals(
            WireDocumentConversion.Rejected,
            wire.copy(target = named.copy(reference = named.reference.copy(dispatchReceiver = outside))).toContract(),
        )
        val supplied = named.reference.flow as QueryNamedCallbackReferenceFlowWireDocument.Supplied
        val invalidBinding = supplied.binding as QueryCallbackBindingWireDocument.Bound
        val forged =
            wire.copy(
                target =
                    named.copy(
                        reference =
                            named.reference.copy(flow = supplied.copy(binding = invalidBinding.copy(position = 100)))
                    )
            )
        assertEquals(WireDocumentConversion.Rejected, forged.toContract())
    }

    @Test
    fun `reviewed supply encodes its action and rejects declared invocation provenance`() {
        val original = suppliedReference().toWireDocument()
        val named = original.target as QueryCallableTargetWireDocument.NamedReference
        val supplied = named.reference.flow as QueryNamedCallbackReferenceFlowWireDocument.Supplied
        val source = supplied.binding as QueryCallbackBindingWireDocument.Bound
        val binding =
            QueryCallbackBindingWireDocument.DependencyContract(
                basis = original.basis,
                occurrence = source.invocationOccurrence,
                owner = source.invocationOwner,
                target = iterableTarget().callbackWire(),
                position = 0,
                classDigest = "7f74e68459e9c8ae6c69510014b6fe0f565b9389ddbe5acb5e181dd773558878",
                provenance = QueryCallbackDependencyContractProvenanceDocument.REVIEWED_KOTLIN_2_4_10_ITERABLE_FOR_EACH,
                invocationKind = QueryCallbackDependencyInvocationKindDocument.ZERO_OR_MORE,
            )
        fun suppliedBy(proof: QueryCallbackBindingWireDocument.DependencyContract) =
            original.copy(
                target =
                    named.copy(
                        reference =
                            named.reference.copy(
                                flow = QueryNamedCallbackReferenceFlowWireDocument.ReviewedSupply(proof)
                            ),
                        resultProjection =
                            QueryCallbackResultProjectionWireDocument.Unproven(
                                io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument.OUTSIDE_DOMAIN
                            ),
                    )
            )
        val wire = suppliedBy(binding)
        assertTrue(wire.toContract() is WireDocumentConversion.Converted)
        val raw = wireJson.encodeToJsonElement(QueryCallableObservationWireDocument.serializer(), wire).jsonObject
        val serialized = raw.getValue("target").jsonObject.getValue("reference").jsonObject.getValue("flow").jsonObject
        assertEquals(JsonPrimitive("REVIEWED_SUPPLY"), serialized.getValue("type"))
        assertEquals(
            JsonPrimitive("ZERO_OR_MORE"),
            serialized.getValue("binding").jsonObject.getValue("invocation_kind"),
        )
        assertEquals(
            WireDocumentConversion.Rejected,
            suppliedBy(
                    binding.copy(provenance = QueryCallbackDependencyContractProvenanceDocument.KOTLIN_BINARY_CONTRACT)
                )
                .toContract(),
        )
    }

    private fun iterableTarget(): QueryExcludedCompilerTargetDocument {
        val id = "kotlin.collections.forEach"
        val base =
            fixture
                .callable(
                    file = "jar:///stdlib.jar!/kotlin/collections/CollectionsKt.class",
                    start = 0,
                    end = 40,
                    identity = id,
                    parameters = listOf("(T)->kotlin/Unit"),
                )
                .compilerTarget
        val signature = base.compilerEvidence.signature as CompilerSignatureDocument.Function
        val typeParameterCount = (CompilerTypeParameterCountDocument.parse(1) as Refinement.Refined).value
        val evidence =
            CompilerSymbolEvidenceDocument.fromSignature(
                signature.copy(
                    receiver = CompilerReceiverDocument.Present(fixture.text("kotlin/collections/Iterable<T>")),
                    typeParameterCount = typeParameterCount,
                )
            )
        return (QueryExcludedCompilerTargetDocument.create(
                file = base.file,
                range = base.range,
                name = base.name,
                kind = base.kind,
                compilerEvidence = (evidence as Refinement.Refined).value,
            ) as Refinement.Refined)
            .value
    }

    private fun suppliedReference(): QueryCallableObservationDocument {
        val observation = fixture.callbackDocument()
        val flow = observation.flow as QueryCallbackFlowDocument.Observed
        val binding = flow.binding as QueryCallbackBindingDocument.Bound
        val reference =
            QueryNamedCallbackReferenceDocument(
                observation.sourceTarget,
                QueryCallbackReferenceReceiverDocument.Absent,
                QueryCallbackReferenceReceiverDocument.Unbound,
                QueryNamedCallbackReferenceFlowDocument.Supplied(binding, flow.invocations, flow.forwarding),
            )
        return (QueryCallableObservationDocument.create(
                observation.callbackBody,
                observation.lexicalOwner,
                QueryCallbackBodyDocument.Named(observation.lexicalOwner),
                QueryCallableTargetDocument.NamedReference(reference, observation.resultProjection),
                observation.basis,
            ) as Refinement.Refined)
            .value
    }

    @Test
    fun `unused complete formal retains an empty invocation inventory`() {
        val observation = fixture.callbackDocument()
        val flow = observation.flow as QueryCallbackFlowDocument.Observed
        val reference =
            QueryNamedCallbackReferenceDocument(
                observation.sourceTarget,
                QueryCallbackReferenceReceiverDocument.Absent,
                QueryCallbackReferenceReceiverDocument.Absent,
                QueryNamedCallbackReferenceFlowDocument.Supplied(
                    flow.binding as QueryCallbackBindingDocument.Bound,
                    (BoundedProtocolList.create(
                            emptyList<io.github.amichne.kast.protocol.contract.QueryCallbackInvocationDocument>()
                        ) as Refinement.Refined)
                        .value,
                    flow.forwarding,
                ),
            )
        val value =
            (QueryCallableObservationDocument.create(
                    observation.callbackBody,
                    observation.lexicalOwner,
                    QueryCallbackBodyDocument.Named(observation.lexicalOwner),
                    QueryCallableTargetDocument.NamedReference(reference, observation.resultProjection),
                    observation.basis,
                ) as Refinement.Refined)
                .value
        val raw = value.toWireDocument()
        assertEquals(WireDocumentConversion.Converted(value), raw.toContract())
        val proof =
            (raw.target as QueryCallableTargetWireDocument.NamedReference).reference.flow
                as QueryNamedCallbackReferenceFlowWireDocument.Supplied
        assertEquals(emptyList<QueryCallbackInvocationWireDocument>(), proof.invocations)
    }
}

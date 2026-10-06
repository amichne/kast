package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackExclusionReasonDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/** Stateless exact-source fixtures shared by the distinct default, direct, forwarding and interruption cases. */
internal class QueryCallbackRefinementWireFixture {
    private val source = QueryCallbackWireFixture()

    fun base() = source.callbackDocument().toWireDocument()

    fun flow(value: QueryCallbackObservationWireDocument) = value.flow as QueryCallbackFlowWireDocument.Observed

    fun defaultObservation(): QueryCallbackObservationWireDocument {
        val seed = base()
        val owner =
            source
                .callable("Seed.kt", 0, 40, "sample.withDefault", listOf("kotlin.Function0<kotlin.Unit>"))
                .callbackWire()
        val parameter = QueryCallbackParameterIdentityWireDocument(owner, 0, occurrence("Seed.kt", 2, 20))
        return seed.copy(
            lexicalOwner = owner,
            namedPolicy =
                QueryCallbackNamedPolicyWireDocument.Excluded(
                    QueryCallbackExclusionReasonDocument.DEFAULT_PARAMETER,
                    parameter.parameter,
                ),
            flow =
                flow(seed)
                    .copy(
                        binding = QueryCallbackBindingWireDocument.Default(parameter, occurrence("Seed.kt", 4, 18)),
                        invocations = emptyList(),
                        scan = QueryCallbackInvocationScanDocument.EXHAUSTIVE,
                    ),
        )
    }

    fun directObservation(): QueryCallbackObservationWireDocument {
        val seed = base()
        val observed = flow(seed)
        val bound = observed.binding as QueryCallbackBindingWireDocument.Bound
        return seed.copy(
            namedPolicy = QueryCallbackNamedPolicyWireDocument.AdmittedDirect,
            flow =
                observed.copy(
                    binding =
                        QueryCallbackBindingWireDocument.Direct(
                            observed.basis,
                            bound.invocationOccurrence,
                            bound.invocationOwner,
                        ),
                    invocations = emptyList(),
                    scan = QueryCallbackInvocationScanDocument.NOT_APPLICABLE,
                ),
        )
    }

    fun forwardedObservation(): QueryCallbackObservationWireDocument {
        val seed = base()
        val observed = flow(seed)
        val initial = observed.binding as QueryCallbackBindingWireDocument.Bound
        val receiving =
            source
                .callable("Forward.kt", 0, 30, "sample.forward", listOf("kotlin.Function0<kotlin.Unit>"))
                .callbackWire()
        val mapped =
            QueryCallbackBindingWireDocument.Bound(
                ImpactInvocationReferenceDocument(range("Boundary.kt", 10, 25), reference(observed, receiving)),
                occurrence("Boundary.kt", 10, 25),
                QueryCallbackBodyWireDocument.Named(initial.callable),
                receiving,
                0,
                occurrence("Forward.kt", 2, 9),
            )
        val forwarding =
            QueryCallbackForwardingWireDocument(
                QueryCallbackParameterIdentityWireDocument(initial.callable, initial.position, initial.parameter),
                occurrence("Boundary.kt", 14, 18),
                mapped,
            )
        val invocation =
            observed.invocations
                .single()
                .copy(
                    occurrence = occurrence("Forward.kt", 12, 20),
                    owner = QueryCallbackBodyWireDocument.Named(receiving),
                    forwardings = listOf(forwarding),
                )
        return seed.copy(
            flow =
                observed.copy(invocations = listOf(invocation), scan = QueryCallbackInvocationScanDocument.EXHAUSTIVE)
        )
    }

    fun nestedObservation(
        supply: QueryCallbackBodySupplyWireDocument,
        cause: QueryCallbackFlowCauseDocument,
    ): QueryCallbackObservationWireDocument {
        val seed = base()
        val observed = flow(seed)
        val body = anonymous("Boundary.kt", 10, 25)
        val obligations = listOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION, cause)
        val owner =
            QueryCallbackBodyBindingWireDocument(
                body,
                supply,
                QueryCallbackBindingWireDocument.Unavailable(cause),
                obligations,
            )
        return seed.copy(
            flow =
                observed.copy(
                    invocations = listOf(observed.invocations.single().copy(owner = body)),
                    obligations = obligations,
                    ownerBindings = listOf(owner),
                    scan = QueryCallbackInvocationScanDocument.INCOMPLETE,
                )
        )
    }

    fun named(file: String, start: Int, end: Int, identity: String) =
        QueryCallbackBodyWireDocument.Named(source.callable(file, start, end, identity, emptyList()).callbackWire())

    fun anonymous(file: String, start: Int, end: Int) =
        QueryCallbackBodyWireDocument.Anonymous(
            occurrence(file, start, end),
            source.signature("anonymous@$file#$start:$end", emptyList()).toWireDocument(),
        )

    fun occurrence(file: String, start: Int, end: Int): RelationOccurrenceWireDocument {
        val value = source.occurrence(file, start, end)
        return RelationOccurrenceWireDocument(
            value.candidateSelector.value,
            value.file.value,
            value.range.toWireDocument(),
        )
    }

    fun decode(wire: QueryCallbackObservationWireDocument) =
        wireJson.decodeFromString(
            QueryCallbackObservationWireDocument.serializer(),
            wireJson.encodeToString(QueryCallbackObservationWireDocument.serializer(), wire),
        )

    fun assertAdmitted(wire: QueryCallbackObservationWireDocument) =
        assertTrue(decode(wire).toContract() is WireDocumentConversion.Converted)

    fun assertRejected(wire: QueryCallbackObservationWireDocument) =
        assertEquals(WireDocumentConversion.Rejected, decode(wire).toContract())

    private fun reference(flow: QueryCallbackFlowWireDocument.Observed, callable: QueryCallbackCallableWireDocument) =
        ImpactDeclarationReferenceDocument(
            flow.basis,
            source.text(callable.compilerTarget.file),
            range(
                callable.declaration.file,
                callable.declaration.range.startInclusive,
                callable.declaration.range.endExclusive,
            ),
            source.text(callable.compilerTarget.compilerEvidence.identity),
        )

    private fun range(file: String, start: Int, end: Int): ImpactSourceRangeDocument {
        val range = source.occurrence(file, start, end).range
        return ImpactSourceRangeDocument(range.startInclusive, range.endExclusive)
    }
}

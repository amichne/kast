package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy
import io.github.amichne.kast.relation.contract.CallbackSummaryCacheLookup
import io.github.amichne.kast.relation.contract.CompleteStaticCallbackGraph
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.StaticCallbackGraphFailure
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.topology.intellij.SemanticDependencyCaptureFailure
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Scripted native observations exercise real cache and graph admission; no platform or filesystem effects. */
class HostedCallbackCompletenessTest : HostedSemanticFactFixture() {
    @Test
    fun `modeled and unmodeled attribution preserve fresh complete and incomplete callback authority`() {
        val authority = owner.admit()
        val fresh = summary(authority)
        val partitions =
            listOf(
                HostedCallbackPartition.Available(snapshot(authority)),
                HostedCallbackPartition.Rejected(
                    HostedCallbackPartitionFailure.Capture(SemanticDependencyCaptureFailure.DEPENDENCY_MODULE_UNMODELED)
                ),
            )
        for (partition in partitions) {
            val dependencies =
                object : HostedCallbackDependencyPartitions {
                    override fun forward(endpoint: RelationEndpoint) = partition

                    override fun whole() = partition
                }
            val cache = HostedCallbackFactCache(dependencies, store, counts)
            // The native attribution observation may disable optional reuse, never fresh extraction.
            assertEquals(
                CallbackSummaryCacheLookup.Miss,
                cache.find(fresh.formal) { throw AssertionError("No previous summary exists") },
            )
            val complete = callback(authority, emptySet(), CallbackInvocationScan.EXHAUSTIVE)
            assertInstanceOf(Refinement.Refined::class.java, CompleteStaticCallbackGraph.admit(complete))
            val incomplete =
                callback(
                    authority,
                    setOf(CallbackInvocationFlowCause.PARAMETER_ESCAPES),
                    CallbackInvocationScan.INCOMPLETE,
                )
            assertEquals(
                Refinement.Rejected(
                    StaticCallbackGraphFailure.Unresolved((incomplete.flow as CallbackInvocationFlowRead.Observed).flow)
                ),
                CompleteStaticCallbackGraph.admit(incomplete),
            )
            cache.retain(fresh)
            assertEquals(
                when (partition) {
                    is HostedCallbackPartition.Available -> CallbackSummaryCacheLookup.Found(fresh)
                    is HostedCallbackPartition.Rejected -> CallbackSummaryCacheLookup.Miss
                },
                cache.find(fresh.formal) { throw AssertionError("Same authority requires no restoration") },
            )
        }
    }

    private fun callback(
        authority: LiveSemanticReadAuthority,
        causes: Set<CallbackInvocationFlowCause>,
        scan: CallbackInvocationScan,
    ): RelationCallbackObservation {
        val formal = summary(authority).formal
        val endpoint = formal.callable as RelationEndpoint.Resolved
        val range = RelationOccurrence.fromBoundary(endpoint.file, 30, 50).value()
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = RelationCallableBody.Anonymous.sourceIdentity(endpoint.file, range.range),
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = emptyList(),
                    rawTypeParameterCount = 0,
                )
                .value() as CanonicalCompilerSignature.Function
        val body = RelationCallableBody.Anonymous.fromCompiler(endpoint.file, range.range, signature).value()
        val binding =
            CallbackArgumentBinding.fromCompiler(
                    invocation =
                        ValueInvocation.fromCompiler(
                                endpoint,
                                RelationOccurrence.fromBoundary(endpoint.file, 20, 60).value().range,
                                endpoint,
                            )
                            .value(),
                    invocationOwner = RelationCallableBody.Named.fromCompiler(endpoint.evidence).value(),
                    position = formal.position,
                    parameter = formal.parameter,
                )
                .value()
        val flow =
            CallbackInvocationFlow.fromCompiler(
                    basis = authority.identity,
                    body = body,
                    binding = CallbackBindingEvidence.Bound(binding),
                    invocations = emptyList(),
                    obligations = causes,
                    scan = scan,
                )
                .value()
        return RelationCallbackObservation.fromNativeBoundary(
                request = named(authority).batch.request,
                occurrence = RelationOccurrence.fromBoundary(endpoint.file, 35, 36).value(),
                target = endpoint.evidence,
                lexicalOwner = endpoint.evidence,
                callbackBody = range,
                policy = CallbackNamedCallPolicy.AdmittedInline,
                flow = CallbackInvocationFlowRead.Observed(flow),
            )
            .value()
    }
}

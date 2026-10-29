package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.ReviewedCallbackInvocationEvidence
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueCaptureInvocation
import io.github.amichne.kast.relation.contract.ValueExecutionContext

internal fun ValueExecutionContext.storageBytes(g: QueryImpactRetainedGraph): Long =
    when (this) {
        ValueExecutionContext.Declaration -> 0L
        is ValueExecutionContext.ReviewedCallback -> g.node(this) { binding.storageBytes(g) }
        is ValueExecutionContext.DeclaredCallback -> g.node(this) { binding.storageBytes(g) }
        is ValueExecutionContext.Callback ->
            g.node(this) {
                body
                    .storageBytes(g)
                    .saturatedAdd(g.collection(invocations) { it.storageBytes(g) })
                    .saturatedAdd(selectedInvocation.storageBytes(g))
            }
    }

internal fun io.github.amichne.kast.relation.contract.DeclaredCallbackValueBinding.storageBytes(
    g: QueryImpactRetainedGraph
): Long = g.node(this) { retainedBytes }

internal fun ReviewedCallbackValueBinding.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        enclosing
            .storageBytes(g)
            .saturatedAdd(body.storageBytes(g))
            .saturatedAdd(
                g.node(occurrence) {
                    g.node(occurrence.file) { g.text(occurrence.file.stableValue) }
                        .saturatedAdd(g.node(occurrence.range) { 0L })
                }
            )
            .saturatedAdd(
                when (val evidence = invocationEvidence) {
                    ReviewedCallbackInvocationEvidence.Unestablished -> 0L
                    is ReviewedCallbackInvocationEvidence.Declared ->
                        g.node(evidence) { evidence.binding.storageBytes(g) }
                }
            )
            .saturatedAdd(g.node(selected) { target.storageBytes(g) })
            .saturatedAdd(artifact.storageBytes(g))
    }

internal fun ReviewedValueTransfer.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        source
            .storageBytes(g)
            .saturatedAdd(target.storageBytes(g))
            .saturatedAdd(binding.storageBytes(g))
            .saturatedAdd(relation.storageBytes(g))
    }

internal fun ValueCaptureInvocation.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        body
            .storageBytes(g)
            .saturatedAdd(supply.frame.storageBytes(g))
            .saturatedAdd(supply.invocationOwner.storageBytes(g))
            .saturatedAdd(g.node(supply) { g.node(supply.parameter) { g.text(supply.parameter.file.stableValue) } })
            .saturatedAdd(g.node(invocation) { invocation.retainedBytes })
    }

private fun RelationCallableBody.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.text(file.stableValue)
            .saturatedAdd(g.node(range) { 0L })
            .saturatedAdd(g.text(compilerIdentity.value))
            .saturatedAdd(
                when (this) {
                    is RelationCallableBody.Named -> evidence.signature.storageBytes(g)
                    is RelationCallableBody.Anonymous -> signature.storageBytes(g)
                }
            )
    }

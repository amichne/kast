package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueCaptureInvocation
import io.github.amichne.kast.relation.contract.ValueExecutionContext

internal fun ValueExecutionContext.storageBytes(g: QueryImpactRetainedGraph): Long =
    when (this) {
        ValueExecutionContext.Declaration -> 0L
        is ValueExecutionContext.ReviewedCallback -> g.node(this) { binding.storageBytes(g) }
        is ValueExecutionContext.Callback ->
            g.node(this) {
                body.storageBytes(g).saturatedAdd(g.collection(invocations) { it.storageBytes(g) })
            }
    }

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
            .saturatedAdd(g.node(selected) { target.storageBytes(g) })
            .saturatedAdd(
                g.node(artifact) {
                    g.text(artifact.binaryArtifact.value)
                        .saturatedAdd(g.text(artifact.sourceArtifact.value))
                        .saturatedAdd(g.text(artifact.sourceEntry))
                        .saturatedAdd(g.text(artifact.sourceEntrySha256))
                        .saturatedAdd(g.text(artifact.facadeSha256))
                        .saturatedAdd(g.text(artifact.implementationSha256))
                        .saturatedAdd(g.collection(artifact.sourceSections) { g.node(it) { 0L } })
                }
            )
    }

internal fun ReviewedValueTransfer.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        source.storageBytes(g).saturatedAdd(target.storageBytes(g)).saturatedAdd(binding.storageBytes(g))
    }

internal fun ValueCaptureInvocation.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        body
            .storageBytes(g)
            .saturatedAdd(supply.invocation.storageBytes(g))
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

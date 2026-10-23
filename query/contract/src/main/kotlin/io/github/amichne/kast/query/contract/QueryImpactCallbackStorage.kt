package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.ValueCaptureInvocation
import io.github.amichne.kast.relation.contract.ValueExecutionContext

internal fun ValueExecutionContext.storageBytes(g: QueryImpactRetainedGraph): Long =
    when (this) {
        ValueExecutionContext.Declaration -> 0L
        is ValueExecutionContext.Callback ->
            g.node(this) {
                body.storageBytes(g).saturatedAdd(g.collection(invocations) { it.storageBytes(g) })
            }
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

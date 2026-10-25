package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueSourceOperation

internal fun ValueAccessPath.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.collection(segments) { segment ->
            g.node(segment) {
                when (segment) {
                    ValuePathSegment.Element -> 0L
                    is ValuePathSegment.Field -> g.node(segment.field) { segment.field.declaration.storageBytes(g) }
                }
            }
        }
    }

internal fun ValueCallStack.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) { g.collection(frames) { it.storageBytes(g) } }

internal fun ValueCallFrame.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        invocation.storageBytes(g).saturatedAdd(callee.storageBytes(g)).saturatedAdd(target.storageBytes(g))
    }

internal fun ValueFormalPosition.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            is ValueFormalPosition.Parameter -> parameter.storageBytes(g)
            is ValueFormalPosition.Receiver -> 0L
        }
    }

internal fun ValueFormalParameter.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        callable
            .storageBytes(g)
            .saturatedAdd(g.text(name.value))
            .saturatedAdd(g.node(range) { 0L })
            .saturatedAdd(executionOwner.storageBytes(g))
    }

internal fun ValueSourceOperation.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        when (this) {
            is ValueSourceOperation.Enter -> frame.storageBytes(g).saturatedAdd(position.storageBytes(g))
            is ValueSourceOperation.Return -> frame.storageBytes(g)
            is ValueSourceOperation.SeedReturn -> frame.storageBytes(g)
            is ValueSourceOperation.CallbackReturn -> frame.storageBytes(g).saturatedAdd(invocation.storageBytes(g))
            is ValueSourceOperation.ReadField -> g.node(field) { field.declaration.storageBytes(g) }
            is ValueSourceOperation.ConstructField ->
                call
                    .storageBytes(g)
                    .saturatedAdd(
                        g.node(storage) {
                            storage.parameter
                                .storageBytes(g)
                                .saturatedAdd(g.node(storage.field) { storage.field.declaration.storageBytes(g) })
                        }
                    )
        }
    }

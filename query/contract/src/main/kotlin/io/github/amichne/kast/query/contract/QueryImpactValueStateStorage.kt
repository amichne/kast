package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueCondition
import io.github.amichne.kast.relation.contract.ValueConditions
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueSourceOperation

internal fun ValueConditions.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) { g.collection(facts) { it.storageBytes(g) } }

private fun ValueCondition.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.node(origin) {
                origin.execution
                    .storageBytes(g)
                    .saturatedAdd(g.node(origin.range) { 0L })
                    .saturatedAdd(origin.role.storageBytes(g))
                    .saturatedAdd(origin.valuePath.storageBytes(g))
                    .saturatedAdd(origin.callStack.storageBytes(g))
            }
            .saturatedAdd(g.node(useRange) { 0L })
            .saturatedAdd(
                when (this) {
                    is ValueCondition.StringContentNonNull -> g.node(entryRange) { 0L }
                    is ValueCondition.DeclaredCallbackResult -> binding.storageBytes(g)
                    is ValueCondition.ReviewedCallbackNormalReturn -> binding.storageBytes(g)
                    is ValueCondition.ReviewedCollectionNormalResult -> binding.storageBytes(g)
                    is ValueCondition.ReviewedEntryKeyUnique -> entry.storageBytes(g)
                    is ValueCondition.StableLocal ->
                        version
                            .storageBytes(g)
                            .saturatedAdd(g.text(originalType.value))
                            .saturatedAdd(g.text(refinedType.value))
                    is ValueCondition.StableReceiver ->
                        g.node(kind) { 0L }
                            .saturatedAdd(g.text(originalType.value))
                            .saturatedAdd(g.text(refinedType.value))
                    is ValueCondition.CastSuccess ->
                        g.node(kind) { 0L }
                            .saturatedAdd(g.text(originalType.value))
                            .saturatedAdd(g.text(targetType.value))
                }
            )
    }

internal fun ValueAccessPath.storageBytes(g: QueryImpactRetainedGraph): Long =
    g.node(this) {
        g.collection(segments) { segment ->
            g.node(segment) {
                when (segment) {
                    ValuePathSegment.Element -> 0L
                    is ValuePathSegment.PairComponent -> segment.origin.storageBytes(g)
                    is ValuePathSegment.MapEntryComponent -> segment.entry.storageBytes(g)
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
            is ValueSourceOperation.DeclaredCallbackResult -> binding.storageBytes(g)
            is ValueSourceOperation.CallbackReturn -> frame.storageBytes(g).saturatedAdd(invocation.storageBytes(g))
            is ValueSourceOperation.CallbackArgumentEntry ->
                supplierFrame
                    .storageBytes(g)
                    .saturatedAdd(invocation.storageBytes(g))
                    .saturatedAdd(parameter.storageBytes(g))
                    .saturatedAdd(g.node(argumentRange) { 0L })
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

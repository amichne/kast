package io.github.amichne.kast.relation.contract

/** A conservative detached estimate counts every proof reference without an interning cache. */
internal fun valueStateStorageBytes(site: ValueSite): Long =
    valuePathStorageBytes(site.valuePath)
        .addBytes(site.callStack.frames.fold(0L) { sum, frame -> sum.addBytes(valueFrameStorageBytes(frame)) })
        .addBytes(
            when (val context = site.executionOwner.context) {
                ValueExecutionContext.Declaration -> 0L
                is ValueExecutionContext.Callback ->
                    context.invocations.fold(VALUE_CALL_PROOF_BYTES) { total, frame ->
                        total
                            .addBytes(frame.invocation.retainedBytes)
                            .addBytes(valueSiteStorageBytes(frame.supply.invocation.resultSite()))
                            .addBytes(VALUE_CALL_PROOF_BYTES)
                    }
            }
        )

private fun valuePathStorageBytes(path: ValueAccessPath): Long =
    path.segments.fold(0L) { sum, segment ->
        when (segment) {
            is ValuePathSegment.Field ->
                sum.addBytes(VALUE_FIELD_PROOF_BYTES)
                    .addBytes(segment.field.declaration.detachedTextUnits().multiplyBytes(2))
        }
    }

internal fun valueFrameStorageBytes(frame: ValueCallFrame): Long =
    VALUE_CALL_PROOF_BYTES.addBytes(frame.invocation.enclosing.detachedTextUnits().multiplyBytes(2))
        .addBytes(frame.invocation.callable.detachedTextUnits().multiplyBytes(2))
        .addBytes(frame.callee.enclosing.detachedTextUnits().multiplyBytes(2))
        .addBytes(valueCallTargetStorageBytes(frame.target))

internal fun sourceEvidenceStorageBytes(operation: ValueSourceOperation): Long =
    when (operation) {
        is ValueSourceOperation.Enter -> valueFrameStorageBytes(operation.frame).addBytes(VALUE_CALL_PROOF_BYTES)
        is ValueSourceOperation.Return -> valueFrameStorageBytes(operation.frame)
        is ValueSourceOperation.SeedReturn -> valueFrameStorageBytes(operation.frame)
        is ValueSourceOperation.CallbackReturn ->
            valueFrameStorageBytes(operation.frame)
                .addBytes(operation.invocation.invocation.retainedBytes)
                .addBytes(valueSiteStorageBytes(operation.invocation.supply.invocation.resultSite()))
        is ValueSourceOperation.ReadField ->
            VALUE_FIELD_PROOF_BYTES.addBytes(operation.field.declaration.detachedTextUnits().multiplyBytes(2))
        is ValueSourceOperation.ConstructField ->
            VALUE_CALL_PROOF_BYTES.addBytes(operation.call.callable.detachedTextUnits().multiplyBytes(2))
                .addBytes(operation.storage.field.declaration.detachedTextUnits().multiplyBytes(2))
    }

internal fun ValueRole.hasReferenceReads(): Boolean =
    this == ValueRole.LocalBinding || (this is ValueRole.Formal && position is ValueFormalPosition.Parameter)

private const val VALUE_FIELD_PROOF_BYTES = 2048L
private const val VALUE_CALL_PROOF_BYTES = 4096L

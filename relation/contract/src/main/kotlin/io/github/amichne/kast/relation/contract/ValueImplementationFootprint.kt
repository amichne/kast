package io.github.amichne.kast.relation.contract

private const val IMPLEMENTATION_RECORD_BYTES = 4096L

internal fun valueCallTargetStorageBytes(target: ValueCallTarget): Long =
    when (target) {
        ValueCallTarget.Direct -> 0L
        is ValueCallTarget.Implementation ->
            implementationStorageBytes(target.dispatch)
                .addBytes(
                    target.ownership.root.let { root ->
                        listOf(
                                root.module.value,
                                root.project.buildRoot.value,
                                root.project.projectPath.value,
                                root.sourceSet.value,
                                root.sourceRoot.value,
                            )
                            .fold(IMPLEMENTATION_RECORD_BYTES) { bytes, text ->
                                bytes.addBytes(text.length.toLong().multiplyBytes(2))
                            }
                    }
                )
    }

internal fun valueObligationEvidenceStorageBytes(evidence: ValueFlowObligationEvidence): Long =
    when (evidence) {
        ValueFlowObligationEvidence.None -> 0L
        is ValueFlowObligationEvidence.CallbackInput -> evidence.failure.retainedBytes
        is ValueFlowObligationEvidence.Expression -> 256L.addBytes(evidence.kind.value.length.toLong().multiplyBytes(2))
        is ValueFlowObligationEvidence.ImplementationDispatch -> implementationStorageBytes(evidence.dispatch)
    }

private fun implementationStorageBytes(dispatch: ValueImplementationDispatch): Long {
    val batch = dispatch.batch
    val endpoints = batch.facts.flatMap { listOf(it.subject, it.source, it.target) } + batch.request.subject
    var bytes =
        IMPLEMENTATION_RECORD_BYTES.addBytes(dispatch.retainedIdentityBytes)
            .addBytes(batch.encodedBytes.value.multiplyBytes(4))
    endpoints.forEach { endpoint ->
        bytes = bytes.addBytes(IMPLEMENTATION_RECORD_BYTES).addBytes(endpoint.detachedTextUnits().multiplyBytes(2))
    }
    batch.omissions.forEach { omission ->
        bytes = bytes.addBytes(IMPLEMENTATION_RECORD_BYTES)
        omission.samples.locations.forEach { location ->
            bytes =
                bytes
                    .addBytes(IMPLEMENTATION_RECORD_BYTES)
                    .addBytes(location.file.stableValue.length.toLong().multiplyBytes(2))
        }
    }
    bytes = bytes.addBytes(batch.scopeExclusions.size.toLong().multiplyBytes(IMPLEMENTATION_RECORD_BYTES))
    when (val coverage = dispatch.coverage) {
        is ValueImplementationCoverage.Exhausted -> Unit
        is ValueImplementationCoverage.Incomplete ->
            when (val evidence = coverage.evidence) {
                is RelationIncompleteCoverage.Resumable ->
                    bytes = bytes.addBytes(evidence.continuation.providerState.retainedBytes)
                is RelationIncompleteCoverage.TerminalIncomplete -> Unit
            }
    }
    return bytes
}

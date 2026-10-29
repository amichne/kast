package io.github.amichne.kast.relation.contract

internal fun StringBuilder.reviewedCallbackBinding(binding: ReviewedCallbackValueBinding) {
    field(binding.body.compilerIdentity.value)
    range(binding.body.range)
    range(binding.occurrence.range)
    field(binding.target.compilerIdentity.value)
    field(binding.target.file.stableValue)
    range(binding.target.range)
    field(binding.position.value.toString())
    field(binding.artifact.binaryArtifact.value)
    field(binding.artifact.sourceArtifact.value)
}

internal fun StringBuilder.reviewedNormalReturn(condition: ValueCondition.ReviewedCallbackNormalReturn) {
    field("REVIEWED_CALLBACK_NORMAL_RETURN")
    conditionOrigin(condition.origin)
    range(condition.useRange)
    reviewedCallbackBinding(condition.binding)
    field(condition.binding.artifact.rule.name)
    when (val evidence = condition.binding.invocationEvidence) {
        ReviewedCallbackInvocationEvidence.Unestablished -> field("NOT_ESTABLISHED")
        is ReviewedCallbackInvocationEvidence.Declared -> {
            field("COMPILER_DECLARED")
            declaredCallbackBinding(evidence.binding)
        }
    }
}

internal fun StringBuilder.reviewedTupleOrigin(origin: ReviewedTupleOrigin) {
    valueCall(origin.binding.invocation)
    executionContext(origin.binding.invocation.executionOwner.context)
    field(origin.binding.artifact.rule.name)
    field(origin.binding.artifact.binaryArtifact.value)
    field(origin.binding.artifact.sourceArtifact.value)
    field(origin.callStack.frames.size.toString())
    origin.callStack.frames.forEach { callFrame(it) }
}

internal fun StringBuilder.reviewedMapEntry(entry: ReviewedMapEntryCorrelation) {
    reviewedCallbackBinding(entry.binding)
    field(entry.binding.artifact.rule.name)
    reviewedTupleOrigin(entry.tupleOrigin)
    range(entry.returnRange)
    executionContext(entry.returnExecution.context)
    field(entry.callStack.frames.size.toString())
    entry.callStack.frames.forEach { callFrame(it) }
    when (val evidence = entry.keyEvidence) {
        ReviewedEntryKeyEvidence.Conditional -> field("CONDITIONAL_ENTRY_KEYS")
        is ReviewedEntryKeyEvidence.Distinct -> reviewedDistinctKeys(evidence.proof)
    }
}

internal fun ValuePathSegment.reviewedPathEndpoints(): List<RelationEndpoint> =
    when (this) {
        ValuePathSegment.Element -> emptyList()
        is ValuePathSegment.Field -> listOf(field.declaration)
        is ValuePathSegment.PairComponent -> origin.reviewedEndpoints()
        is ValuePathSegment.MapEntryComponent ->
            entry.tupleOrigin.reviewedEndpoints() +
                entry.binding.enclosing +
                entry.returnExecution.enclosing +
                entry.callStack.frames.flatMap { it.stateEndpoints() } +
                entry.keyEvidence.reviewedEndpoints()
    }

private fun ReviewedTupleOrigin.reviewedEndpoints(): List<RelationEndpoint> =
    listOf(binding.enclosing, binding.invocation.callable) + callStack.frames.flatMap { it.stateEndpoints() }

internal fun StringBuilder.reviewedCollectionNormalResult(condition: ValueCondition.ReviewedCollectionNormalResult) {
    field("REVIEWED_COLLECTION_NORMAL_RESULT")
    conditionOrigin(condition.origin)
    range(condition.useRange)
    reviewedCallbackBinding(condition.binding)
    field(condition.binding.artifact.rule.name)
}

internal fun StringBuilder.reviewedEntryKeyUnique(condition: ValueCondition.ReviewedEntryKeyUnique) {
    field("REVIEWED_ENTRY_KEY_UNIQUE")
    conditionOrigin(condition.origin)
    range(condition.useRange)
    reviewedMapEntry(condition.entry)
}

private fun ReviewedEntryKeyEvidence.reviewedEndpoints(): List<RelationEndpoint> =
    when (this) {
        ReviewedEntryKeyEvidence.Conditional -> emptyList()
        is ReviewedEntryKeyEvidence.Distinct ->
            listOf(
                proof.input.invocation.enclosing,
                proof.input.invocation.callable,
                proof.key.formal.callable,
                proof.associationInvocation.enclosing,
                proof.associationInvocation.callable,
            )
    }

private fun StringBuilder.reviewedDistinctKeys(proof: ReviewedDistinctStringKeys) {
    field("DISTINCT_STRING_ENTRY_KEYS")
    reviewedStringList(proof.input)
    reviewedCallbackBinding(proof.key.binding)
    formalPosition(ValueFormalPosition.Parameter(proof.key.formal))
    reviewedTupleOrigin(proof.key.tupleOrigin)
    range(proof.key.keyRange)
    range(proof.key.returnRange)
    valueCall(proof.associationInvocation)
    executionContext(proof.associationInvocation.executionOwner.context)
    range(proof.receiverRange)
}

private fun StringBuilder.reviewedStringList(input: ReviewedStringListInput) {
    valueCall(input.invocation)
    executionContext(input.invocation.executionOwner.context)
    field(input.formal.position.value.toString())
    field(input.artifact.rule.name)
    field(input.artifact.binary.value)
    field(input.artifact.sources.value)
    field(input.actualArgumentCount.toString())
    input.actualArgumentRanges.forEach { range(it) }
    field(input.arguments.size.toString())
    input.arguments.forEach {
        field(it.ordinal.value.toString())
        range(it.range)
        field(it.value)
    }
}

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
    when (val parent = binding.parent) {
        ReviewedCallbackParent.Declaration -> field("DECLARATION_PARENT")
        is ReviewedCallbackParent.DeclaredCallback -> {
            field("DECLARED_CALLBACK_PARENT")
            declaredCallbackBinding(parent.binding)
        }
    }
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
        is ValuePathSegment.MapEntryComponent -> entry.reviewedEndpoints()
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

internal fun StringBuilder.reviewedMapEntryOrigin(entry: ReviewedMapEntryOrigin) {
    when (entry) {
        is ReviewedGroupBucket -> {
            field("GROUP_BUCKET")
            reviewedGroupBucket(entry)
        }
        is ReviewedMapEntryCorrelation -> {
            field("ASSOCIATION")
            reviewedMapEntry(entry)
        }
        is ReviewedMappedEntryOrigin -> {
            field("MAPPED_RETURN")
            field(entry.selection.name)
            reviewedMapEntryFamily(entry.family)
            range(entry.returnRange)
            executionContext(entry.returnExecution.context)
        }
        is ReviewedInputEntryOrigin -> {
            field("INPUT_ENTRY")
            field(entry.selection.name)
            reviewedMapEntryFamily(entry.family)
            range(entry.receiverRange)
            executionContext(entry.receiverExecution.context)
        }
        is ReviewedPreservedKeyOrigin -> {
            field("PRESERVED_KEY")
            field(entry.selection.name)
            reviewedMapEntryFamily(entry.family)
            range(entry.receiverRange)
            executionContext(entry.receiverExecution.context)
        }
    }
}

private fun ReviewedMapEntryOrigin.reviewedEndpoints(): List<RelationEndpoint> =
    when (this) {
        is ReviewedGroupBucket -> {
            val owners =
                when (val callbacks = callbacks) {
                    is ReviewedGroupCallbacks.OriginalElements -> listOf(callbacks.selector.enclosing)
                    is ReviewedGroupCallbacks.TransformedElements ->
                        listOf(callbacks.selector.enclosing, callbacks.transform.enclosing)
                }
            owners + sourceExecution.enclosing + callStack.frames.flatMap { it.stateEndpoints() }
        }
        is ReviewedMapEntryCorrelation ->
            tupleOrigin.reviewedEndpoints() +
                binding.enclosing +
                returnExecution.enclosing +
                callStack.frames.flatMap { it.stateEndpoints() } +
                keyEvidence.reviewedEndpoints()
        is ReviewedMappedEntryOrigin ->
            listOf(binding.enclosing, returnExecution.enclosing) + callStack.frames.flatMap { it.stateEndpoints() }
        is ReviewedInputEntryOrigin ->
            listOf(family.binding.enclosing, receiverExecution.enclosing) +
                family.callStack.frames.flatMap { it.stateEndpoints() }
        is ReviewedPreservedKeyOrigin ->
            listOf(family.binding.enclosing, receiverExecution.enclosing) +
                family.callStack.frames.flatMap { it.stateEndpoints() }
    }

internal fun StringBuilder.reviewedMappedKeySurvives(condition: ValueCondition.ReviewedMappedKeySurvives) {
    field("REVIEWED_MAPPED_KEY_SURVIVES")
    conditionOrigin(condition.origin)
    range(condition.useRange)
    reviewedMapEntryOrigin(condition.entry)
}

internal fun StringBuilder.reviewedMapEntryFamily(family: ReviewedMapEntryFamily) {
    reviewedCallbackBinding(family.binding)
    field(family.binding.artifact.rule.name)
    field(family.callStack.frames.size.toString())
    family.callStack.frames.forEach { callFrame(it) }
}

internal fun StringBuilder.reviewedPreservedKeySurvives(condition: ValueCondition.ReviewedPreservedKeySurvives) {
    field("REVIEWED_PRESERVED_KEY_SURVIVES")
    conditionOrigin(condition.origin)
    range(condition.useRange)
    reviewedMapEntryOrigin(condition.entry)
}

internal fun StringBuilder.reviewedEntryComponentStable(condition: ValueCondition.ReviewedEntryComponentStable) {
    field("REVIEWED_ENTRY_COMPONENT_STABLE")
    conditionOrigin(condition.origin)
    range(condition.useRange)
    reviewedEntryRead(condition.binding)
    reviewedMapEntryOrigin(condition.entry)
    field(condition.slot.name)
}

internal fun StringBuilder.reviewedEntryRead(binding: ReviewedCallValueBinding) {
    valueCall(binding.invocation)
    executionContext(binding.invocation.executionOwner.context)
    field(binding.artifact.rule.name)
    field(binding.artifact.binaryArtifact.value)
    field(binding.artifact.sourceArtifact.value)
    when (val order = binding.entryReadOrder) {
        ReviewedEntryReadOrder.Direct -> field("DIRECT_ENTRY_READ")
        is ReviewedEntryReadOrder.Destructured -> {
            field("DESTRUCTURED_ENTRY_READ")
            range(order.declarationRange)
            when (val prefix = order.prefix) {
                ReviewedEntryReadPrefix.Empty -> field("EMPTY_PREFIX")
                is ReviewedEntryReadPrefix.FirstComponent -> {
                    field("FIRST_COMPONENT_PREFIX")
                    valueCall(prefix.invocation.invocation)
                    executionContext(prefix.invocation.invocation.executionOwner.context)
                    field(prefix.invocation.artifact.rule.name)
                    field(prefix.invocation.artifact.binaryArtifact.value)
                    field(prefix.invocation.artifact.sourceArtifact.value)
                }
            }
        }
    }
}

internal fun StringBuilder.reviewedEntryPrefixNormal(condition: ValueCondition.ReviewedEntryPrefixNormal) {
    field("REVIEWED_ENTRY_PREFIX_NORMAL")
    conditionOrigin(condition.origin)
    range(condition.useRange)
    reviewedEntryRead(condition.binding)
    reviewedMapEntryOrigin(condition.entry)
    field(condition.slot.name)
}

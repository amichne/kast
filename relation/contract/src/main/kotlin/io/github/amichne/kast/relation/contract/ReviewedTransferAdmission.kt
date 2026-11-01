package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

private fun ValueSite.matchesReviewedReceiver(binding: ReviewedCallbackValueBinding): Boolean {
    if (
        valuePath != ValueAccessPath.Whole ||
            basis != binding.basis ||
            enclosing.valueIdentity != binding.enclosing.valueIdentity
    )
        return false
    return executionOwner.context == ValueExecutionContext.Declaration &&
        range.endExclusive <= binding.body.range.startInclusive
}

private fun ValueRole.Receiver.matchesReviewedCall(binding: ReviewedCallbackValueBinding): Boolean =
    kind == ValueReceiverKind.EXTENSION && call.range == binding.occurrence.range && call.matchesReviewedTarget(binding)

private fun ValueInvocation.matchesReviewedTarget(binding: ReviewedCallbackValueBinding): Boolean =
    callable.valueIdentity == ValueDeclarationIdentity.fromCompiler(binding.target) &&
        signature == binding.target.signature

internal fun callbackBinding(
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
): Refinement<ReviewedCallbackValueBinding, ValueFlowUnsupportedCause> =
    when (val owner = source.executionOwner.context) {
        is ValueExecutionContext.ReviewedCallback ->
            if (
                owner.binding == binding &&
                    binding.selected.operation in
                        setOf(
                            ReviewedLibraryOperation.ITERABLE_MAP,
                            ReviewedLibraryOperation.MAP_VALUES,
                            ReviewedLibraryOperation.ITERABLE_MAP_NOT_NULL,
                        )
            )
                Refinement.Refined(binding)
            else Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
        is ValueExecutionContext.DeclaredCallback ->
            if (
                binding.selected.operation in
                    setOf(
                        ReviewedLibraryOperation.LET,
                        ReviewedLibraryOperation.RUN_TOP_LEVEL,
                        ReviewedLibraryOperation.RUN_RECEIVER,
                        ReviewedLibraryOperation.WITH,
                    )
            )
                binding.composeDeclared(owner.binding)
            else Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
        ValueExecutionContext.Declaration,
        is ValueExecutionContext.Callback -> Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    }

internal fun ReviewedCallbackValueBinding.admitsReceiver(source: ValueSite): Boolean =
    when (val role = source.role) {
        is ValueRole.Receiver ->
            selected.operation in setOf(ReviewedLibraryOperation.ALSO, ReviewedLibraryOperation.APPLY) &&
                source.matchesReviewedReceiver(this) &&
                role.matchesReviewedCall(this)
        else -> false
    }

internal fun reviewedReceiverConditions(
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
): Refinement<ValueConditions, ValueFlowUnsupportedCause> {
    val condition =
        when (val admitted = ValueCondition.ReviewedCallbackNormalReturn.fromReviewed(source, binding)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
        }
    if (condition in source.conditions.facts) return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
    return when (val admitted = source.conditions.requiring(condition)) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
    }
}

internal fun ReviewedCallbackValueBinding.outputSemantics():
    Refinement<ReviewedValueSemantics, ValueFlowUnsupportedCause> =
    when (selected.operation) {
        ReviewedLibraryOperation.ITERABLE_MAP ->
            Refinement.Refined(ReviewedValueSemantics.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT)
        ReviewedLibraryOperation.RUN_TOP_LEVEL,
        ReviewedLibraryOperation.RUN_RECEIVER,
        ReviewedLibraryOperation.WITH,
        ReviewedLibraryOperation.LET ->
            Refinement.Refined(ReviewedValueSemantics.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT)
        ReviewedLibraryOperation.APPLY,
        ReviewedLibraryOperation.ALSO -> Refinement.Refined(ReviewedValueSemantics.CONDITIONAL_RECEIVER_TO_CALL_RESULT)
        ReviewedLibraryOperation.MAP_ENTRY_KEY,
        ReviewedLibraryOperation.MAP_ENTRY_VALUE,
        ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1,
        ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2,
        ReviewedLibraryOperation.MAP_VALUES,
        ReviewedLibraryOperation.ITERABLE_MAP_NOT_NULL,
        ReviewedLibraryOperation.ITERABLE_GROUP_BY,
        ReviewedLibraryOperation.ITERABLE_GROUP_BY_TRANSFORM,
        ReviewedLibraryOperation.ITERABLE_FILTER,
        ReviewedLibraryOperation.ITERABLE_SORTED_BY,
        ReviewedLibraryOperation.ITERABLE_ASSOCIATE,
        ReviewedLibraryOperation.TO,
        ReviewedLibraryOperation.PAIR_CONSTRUCTOR,
        ReviewedLibraryOperation.PAIR_FIRST,
        ReviewedLibraryOperation.PAIR_SECOND -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
    }

internal fun admitReviewedInput(
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
    formal: ValueFormalParameter,
): Refinement<Unit, ValueFlowUnsupportedCause> {
    val receiver =
        source.role as? ValueRole.Receiver
            ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    if (
        binding.selected.operation !in
            setOf(
                ReviewedLibraryOperation.ITERABLE_GROUP_BY,
                ReviewedLibraryOperation.ITERABLE_GROUP_BY_TRANSFORM,
                ReviewedLibraryOperation.ITERABLE_FILTER,
                ReviewedLibraryOperation.ITERABLE_MAP_NOT_NULL,
                ReviewedLibraryOperation.ITERABLE_MAP,
                ReviewedLibraryOperation.ITERABLE_SORTED_BY,
                ReviewedLibraryOperation.ITERABLE_ASSOCIATE,
            ) || !receiver.matchesReviewedCall(binding) || source.range.endExclusive > binding.body.range.startInclusive
    )
        return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    if (
        source.basis != binding.basis ||
            source.enclosing.valueIdentity != binding.enclosing.valueIdentity ||
            source.executionOwner.context != ValueExecutionContext.Declaration
    )
        return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    if (formal.executionOwner != ValueExecutionOwner.reviewedCallback(binding))
        return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    if (formal.position.value != 0) return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
    return Refinement.Refined(Unit)
}

internal fun ReviewedCallbackValueBinding.admitsSortedReceiver(source: ValueSite): Boolean =
    selected.operation == ReviewedLibraryOperation.ITERABLE_SORTED_BY && admitsSelectedReceiver(source)

internal fun ReviewedCallbackValueBinding.admitsFilteredReceiver(source: ValueSite): Boolean =
    selected.operation == ReviewedLibraryOperation.ITERABLE_FILTER && admitsSelectedReceiver(source)

internal fun ReviewedCallbackValueBinding.admitsSelectedReceiver(source: ValueSite): Boolean {
    val receiver = source.role as? ValueRole.Receiver ?: return false
    if (!receiver.matchesReviewedCall(this)) return false
    if (source.basis != basis || source.enclosing.valueIdentity != enclosing.valueIdentity) return false
    if (
        source.executionOwner.context != ValueExecutionContext.Declaration ||
            source.range.endExclusive > body.range.startInclusive
    )
        return false
    return source.valuePath == ValueAccessPath.Whole ||
        source.valuePath.segments.firstOrNull() == ValuePathSegment.Element
}

internal fun appendReviewedCondition(
    source: ValueSite,
    condition: ValueCondition,
): Refinement<ValueConditions, ValueFlowUnsupportedCause> {
    if (condition in source.conditions.facts) return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
    return when (val value = source.conditions.requiring(condition)) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
    }
}

internal fun reviewedTupleInput(
    source: ValueSite,
    binding: ReviewedCallValueBinding,
): Refinement<ReviewedTupleInput, ValueFlowUnsupportedCause> {
    if (source.basis != binding.basis || source.executionOwner != binding.invocation.executionOwner)
        return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    return when (val role = source.role) {
        is ValueRole.Receiver ->
            if (
                binding.selected.operation == ReviewedLibraryOperation.TO &&
                    role.kind == ValueReceiverKind.EXTENSION &&
                    role.call == binding.invocation
            )
                Refinement.Refined(ReviewedTupleInput.EXTENSION_RECEIVER)
            else Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
        is ValueRole.Argument -> role.reviewedTupleArgument(binding)
        else -> Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
    }
}

internal fun admitTupleProjection(
    source: ValueSite,
    binding: ReviewedCallValueBinding,
    component: ValuePathSegment.PairComponent,
): Refinement<Unit, ValueFlowUnsupportedCause> {
    val receiver =
        source.role as? ValueRole.Receiver ?: return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
    if (source.basis != binding.basis || component.origin.basis != binding.basis)
        return Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION)
    if (
        receiver.kind != ValueReceiverKind.DISPATCH ||
            receiver.call != binding.invocation ||
            source.executionOwner != binding.invocation.executionOwner
    )
        return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
    val selected =
        when (binding.selected.operation) {
            ReviewedLibraryOperation.PAIR_FIRST -> PairSlot.FIRST
            ReviewedLibraryOperation.PAIR_SECOND -> PairSlot.SECOND
            else -> return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
        }
    return if (selected == component.slot) Refinement.Refined(Unit)
    else Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_PROPERTY)
}

internal fun reviewedCallbackOutputPath(
    source: ValueSite,
    binding: ReviewedCallbackValueBinding,
): Refinement<ValueAccessPath, ValueFlowUnsupportedCause> {
    val path =
        when (binding.selected.operation) {
            ReviewedLibraryOperation.ITERABLE_MAP -> source.valuePath.prepend(ValuePathSegment.Element)
            ReviewedLibraryOperation.RUN_TOP_LEVEL,
            ReviewedLibraryOperation.RUN_RECEIVER,
            ReviewedLibraryOperation.WITH,
            ReviewedLibraryOperation.APPLY,
            ReviewedLibraryOperation.LET,
            ReviewedLibraryOperation.ALSO -> Refinement.Refined(source.valuePath)
            ReviewedLibraryOperation.MAP_ENTRY_KEY,
            ReviewedLibraryOperation.MAP_ENTRY_VALUE,
            ReviewedLibraryOperation.MAP_ENTRY_COMPONENT1,
            ReviewedLibraryOperation.MAP_ENTRY_COMPONENT2,
            ReviewedLibraryOperation.MAP_VALUES,
            ReviewedLibraryOperation.ITERABLE_MAP_NOT_NULL,
            ReviewedLibraryOperation.ITERABLE_GROUP_BY,
            ReviewedLibraryOperation.ITERABLE_GROUP_BY_TRANSFORM,
            ReviewedLibraryOperation.ITERABLE_FILTER,
            ReviewedLibraryOperation.ITERABLE_SORTED_BY,
            ReviewedLibraryOperation.ITERABLE_ASSOCIATE,
            ReviewedLibraryOperation.TO,
            ReviewedLibraryOperation.PAIR_CONSTRUCTOR,
            ReviewedLibraryOperation.PAIR_FIRST,
            ReviewedLibraryOperation.PAIR_SECOND ->
                return Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
        }
    return when (path) {
        is Refinement.Refined -> path
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED)
    }
}

private fun ValueRole.Argument.reviewedTupleArgument(
    binding: ReviewedCallValueBinding
): Refinement<ReviewedTupleInput, ValueFlowUnsupportedCause> {
    if (call != binding.invocation) return Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
    return when {
        position.value == 0 &&
            binding.selected.operation in
                setOf(ReviewedLibraryOperation.TO, ReviewedLibraryOperation.PAIR_CONSTRUCTOR) ->
            Refinement.Refined(ReviewedTupleInput.ARGUMENT_ZERO)
        position.value == 1 && binding.selected.operation == ReviewedLibraryOperation.PAIR_CONSTRUCTOR ->
            Refinement.Refined(ReviewedTupleInput.ARGUMENT_ONE)
        else -> Refinement.Rejected(ValueFlowUnsupportedCause.UNMODELED_CALL)
    }
}

internal fun reviewedAssociationPath(
    source: ValueSite,
    entry: ReviewedMapEntryCorrelation,
    slot: MapEntrySlot,
): Refinement<ValueAccessPath, ValueFlowUnsupportedCause> =
    when (
        val value =
            ValueAccessPath.fromSegments(
                listOf(ValuePathSegment.MapEntryComponent(entry, slot)) + source.valuePath.segments.drop(1)
            )
    ) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.ACCESS_PATH_DEPTH_EXCEEDED)
    }

internal fun reviewedAssociationConditions(
    source: ValueSite,
    entry: ReviewedMapEntryCorrelation,
): Refinement<ValueConditions, ValueFlowUnsupportedCause> =
    when (entry.keyEvidence) {
        is ReviewedEntryKeyEvidence.Distinct -> Refinement.Refined(source.conditions)
        ReviewedEntryKeyEvidence.Conditional ->
            when (val premise = ValueCondition.ReviewedEntryKeyUnique.fromReviewed(source, entry)) {
                is Refinement.Refined -> appendReviewedCondition(source, premise.value)
                is Refinement.Rejected -> Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN)
            }
    }

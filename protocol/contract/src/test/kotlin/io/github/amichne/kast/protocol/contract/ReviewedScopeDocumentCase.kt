package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

internal class ReviewedScopeDocumentCase {
    private val existing = ValueModelStructureCase()
    private val selection = existing.letApplication()
    val binding = selection.binding
    val basis = existing.basis
    val call = (binding.invocation as ImpactCallOccurrenceDocument.Declared).invocation
    val body = CallbackIdentityFixture().anonymousBody(52, 66)
    private val bodyDeclaration =
        binding.enclosing.declaration.copy(
            range = range(52, 66),
            compilerIdentity = text(body.compilerEvidence.identity),
        )
    val owner = ImpactExecutionOwnerDocument.Anonymous(bodyDeclaration)
    val formal =
        ImpactFormalParameterDocument(
            binding.enclosing.declaration,
            offset(0),
            text("it"),
            range(52, 66),
            owner,
            ImpactFormalOriginDocument.Implicit,
        )
    val target =
        ImpactCallTargetDocument.ReviewedCallback(
            binding,
            ImpactImplementationOccurrenceDocument(text(body.occurrence.file), range(52, 66)),
            formal,
            binding.enclosing,
            ImpactModelExecutionDocument.Anonymous(binding.enclosing, body),
        )
    val frame =
        ImpactCallFrameDocument(
            binding.enclosing.declaration,
            binding.invocation,
            ImpactExecutionReferenceDocument(binding.enclosing.declaration, owner),
            target,
        )
    private val receiver =
        selection.source.copy(valuePath = bounded(listOf(ImpactValuePathSegmentDocument.Element(offset(3)))))
    val source =
        receiver.copy(
            predicates =
                bounded(
                    listOf(
                        ImpactValuePredicateDocument.NonNull(
                            ImpactPredicateSubjectDocument(
                                receiver.enclosing,
                                receiver.range,
                                receiver.role,
                                receiver.executionOwner,
                                receiver.valuePath,
                                receiver.callStack,
                            )
                        )
                    )
                )
        )
    val entered =
        source.copy(
            range = formal.range,
            role = ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Parameter(formal)),
            executionOwner = owner,
            callStack = bounded(listOf(frame)),
        )
    val input = ImpactValueModelApplicationDocument.ScopeCallbackInput(source, entered, binding, frame)
    val returned = entered.copy(range = range(60, 65), role = ImpactValueRoleDocument.Return)
    val result = source.copy(range = call.range, role = ImpactValueRoleDocument.ExpressionResult)
    val output = ImpactValueModelApplicationDocument.ScopeCallbackReturn(returned, result, binding, frame)

    fun range(start: Int, end: Int) = existing.range(start, end)

    fun text(value: String) = ProtocolText.parse(value).scopeValue()

    fun offset(value: Int) = ProtocolOffset.parse(value).scopeValue()

    fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).scopeValue()
}

private fun <T> Refinement<T, *>.scopeValue(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Reviewed scope fixture prerequisite rejected: $failure")
    }

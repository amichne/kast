package io.github.amichne.kast.protocol.contract

internal class ImpactCallbackObservationIdentityFixture(private val forwarded: Boolean) {
    val implementation = ImplementationShapeFixture()
    val values = implementation.values
    val host = if (forwarded) implementation.other else values.declaration
    val body = ImpactCapturedBodyDocument(host.file, range(35, 45), values.text("anonymous-implementation-input"))
    val invocationOwner = captured(implementation.frame.callee.enclosing)
    val supplierFrame =
        if (forwarded)
            ImpactCallFrameDocument(
                enclosing = host,
                invocation = ImpactInvocationReferenceDocument(range(30, 50), values.declaration),
                callee = ImpactExecutionReferenceDocument(values.declaration),
            )
        else implementation.frame
    val stack = values.bounded(if (forwarded) listOf(supplierFrame, implementation.frame) else listOf(supplierFrame))

    fun capture(
        target: ImpactCallTargetDocument.Implementation = implementation.target
    ): ImpactCaptureInvocationDocument =
        ImpactCaptureInvocationDocument(
            body = body,
            enclosing = host,
            supply = (supplierFrame.invocation as ImpactCallOccurrenceDocument.Declared).invocation,
            supplyOwner = captured(host),
            parameter = range(10, 20),
            position = values.offset(0),
            invocation = range(60, 80),
            invocationOwner = invocationOwner,
            supplyTarget = if (forwarded) ImpactCallTargetDocument.Direct else target,
            forwardings =
                if (forwarded)
                    values.bounded(
                        listOf(
                            ImpactCallbackForwardingDocument(
                                sourceParameter = range(10, 20),
                                sourcePosition = values.offset(0),
                                argument = range(40, 42),
                                targetFrame = implementation.frame.copy(target = target),
                                parameter = range(10, 20),
                                position = values.offset(0),
                            )
                        )
                    )
                else EmptyImpactCallbackForwardings,
        )

    fun callback(capture: ImpactCaptureInvocationDocument = capture()) =
        ImpactExecutionOwnerDocument.Callback(
            body = body,
            invocations = values.bounded(listOf(capture)),
            selection = ImpactCallbackSelectionDocument.Exact(capture),
        )

    fun parameter(capture: ImpactCaptureInvocationDocument = capture()) =
        ImpactFormalParameterDocument(
            callable = host,
            index = values.offset(0),
            name = values.text("item"),
            range = range(36, 37),
            executionOwner = callback(capture),
        )

    fun formal(capture: ImpactCaptureInvocationDocument = capture()) =
        ImpactValueSiteReferenceDocument(
            enclosing = host,
            range = range(36, 37),
            role = ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Parameter(parameter(capture))),
            executionOwner = callback(capture),
            callStack = stack,
        )

    fun entry(evidenceCapture: ImpactCaptureInvocationDocument = capture()): ImpactCompilerTransferDocument =
        ImpactCompilerTransferDocument(
            source = actual(),
            target = formal(),
            kind = ImpactTransferKindDocument.SOURCE_CALL_ENTRY,
            evidence =
                ImpactTransferEvidenceDocument.Source(
                    ImpactSourceOperationDocument.CallbackArgumentEntry(
                        invocation = evidenceCapture,
                        supplierFrame = supplierFrame,
                        parameter = parameter(evidenceCapture),
                        argumentRange = range(65, 66),
                    )
                ),
        )

    fun returned(evidenceCapture: ImpactCaptureInvocationDocument = capture()): ImpactCompilerTransferDocument =
        ImpactCompilerTransferDocument(
            source = formal().copy(range = range(40, 41), role = ImpactValueRoleDocument.Return),
            target = actual().copy(range = range(60, 80)),
            kind = ImpactTransferKindDocument.SOURCE_CALL_RETURN,
            evidence =
                ImpactTransferEvidenceDocument.Source(
                    ImpactSourceOperationDocument.CallbackReturn(evidenceCapture, implementation.frame)
                ),
        )

    fun observedTarget() =
        implementation.target.copy(
            dispatch =
                implementation.dispatch.copy(examinedWorkUnits = values.count(7), encodedBytes = values.count(400))
        )

    fun range(start: Int, end: Int) = ImpactSourceRangeDocument(values.offset(start), values.offset(end))

    private fun actual() =
        ImpactValueSiteReferenceDocument(
            enclosing = implementation.frame.callee.enclosing,
            range = range(65, 66),
            role = ImpactValueRoleDocument.ExpressionResult,
            callStack = stack,
        )

    private fun captured(declaration: ImpactDeclarationReferenceDocument) =
        ImpactCapturedBodyDocument(declaration.file, declaration.range, declaration.compilerIdentity)
}

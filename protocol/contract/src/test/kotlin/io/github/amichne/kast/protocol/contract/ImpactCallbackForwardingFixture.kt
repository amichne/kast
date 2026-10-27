package io.github.amichne.kast.protocol.contract

internal class ImpactCallbackForwardingFixture {
    val fixture = ImpactFindingFixture()
    val host = fixture.declaration
    val relay = declaration(210, 400, 'b')
    val intermediate = declaration(450, 650, 'c')
    val terminal = declaration(700, 900, 'd')
    val body = ImpactCapturedBodyDocument(host.file, range(30, 70), fixture.text("anonymous-forwarded-input"))
    val supplierFrame =
        ImpactCallFrameDocument(host, ImpactInvocationReferenceDocument(range(20, 80), relay), execution(relay))
    val intermediateFrame =
        ImpactCallFrameDocument(
            relay,
            ImpactInvocationReferenceDocument(range(300, 340), intermediate),
            execution(intermediate),
        )
    val invocationFrame =
        ImpactCallFrameDocument(
            intermediate,
            ImpactInvocationReferenceDocument(range(550, 590), terminal),
            execution(terminal),
        )
    val first =
        ImpactCallbackForwardingDocument(
            sourceParameter = range(225, 245),
            sourcePosition = fixture.offset(0),
            argument = range(310, 315),
            targetFrame = intermediateFrame,
            parameter = range(465, 485),
            position = fixture.offset(0),
        )
    val second =
        ImpactCallbackForwardingDocument(
            sourceParameter = first.parameter,
            sourcePosition = first.position,
            argument = range(560, 565),
            targetFrame = invocationFrame,
            parameter = range(715, 735),
            position = fixture.offset(0),
        )
    val invocation =
        ImpactCaptureInvocationDocument(
            body = body,
            enclosing = host,
            supply = (supplierFrame.invocation as ImpactCallOccurrenceDocument.Declared).invocation,
            supplyOwner = captured(host),
            parameter = first.sourceParameter,
            position = first.sourcePosition,
            invocation = range(800, 830),
            invocationOwner = captured(terminal),
            forwardings = fixture.bounded(listOf(first, second)),
        )
    val stack = fixture.bounded(listOf(supplierFrame, intermediateFrame, invocationFrame))

    fun entry(
        capture: ImpactCaptureInvocationDocument = invocation,
        frames: BoundedProtocolList<ImpactCallFrameDocument> = stack,
    ): ImpactCompilerTransferDocument {
        val callback = ImpactExecutionOwnerDocument.Callback(body, fixture.bounded(listOf(capture)))
        val parameter =
            ImpactFormalParameterDocument(
                callable = host,
                index = fixture.offset(0),
                name = fixture.text("item"),
                range = range(32, 36),
                executionOwner = callback,
            )
        val source =
            ImpactValueSiteReferenceDocument(
                enclosing = terminal,
                range = range(806, 810),
                role = ImpactValueRoleDocument.ExpressionResult,
                callStack = frames,
            )
        val target =
            ImpactValueSiteReferenceDocument(
                enclosing = host,
                range = parameter.range,
                role = ImpactValueRoleDocument.Formal(ImpactFormalPositionDocument.Parameter(parameter)),
                executionOwner = callback,
                callStack = frames,
            )
        return ImpactCompilerTransferDocument(
            source = source,
            target = target,
            kind = ImpactTransferKindDocument.SOURCE_CALL_ENTRY,
            evidence =
                ImpactTransferEvidenceDocument.Source(
                    ImpactSourceOperationDocument.CallbackArgumentEntry(
                        invocation = capture,
                        supplierFrame = supplierFrame,
                        parameter = parameter,
                        argumentRange = source.range,
                    )
                ),
        )
    }

    fun returned(): ImpactCompilerTransferDocument {
        val entry = entry()
        return ImpactCompilerTransferDocument(
            source = entry.target.copy(range = range(50, 55), role = ImpactValueRoleDocument.Return),
            target = entry.source.copy(range = invocation.invocation),
            kind = ImpactTransferKindDocument.SOURCE_CALL_RETURN,
            evidence =
                ImpactTransferEvidenceDocument.Source(
                    ImpactSourceOperationDocument.CallbackReturn(invocation, invocationFrame)
                ),
        )
    }

    fun wrongSupplier(): ImpactCallFrameDocument {
        val call = (supplierFrame.invocation as ImpactCallOccurrenceDocument.Declared).invocation
        return supplierFrame.copy(invocation = ImpactCallOccurrenceDocument.Declared(call.copy(range = range(21, 81))))
    }

    fun range(start: Int, end: Int) = ImpactSourceRangeDocument(fixture.offset(start), fixture.offset(end))

    private fun declaration(start: Int, end: Int, identity: Char) =
        host.copy(
            range = range(start, end),
            compilerIdentity = fixture.text("canonical-signature-sha256-v1|" + identity.toString().repeat(64)),
        )

    private fun execution(declaration: ImpactDeclarationReferenceDocument) =
        ImpactExecutionReferenceDocument(declaration)

    private fun captured(declaration: ImpactDeclarationReferenceDocument) =
        ImpactCapturedBodyDocument(declaration.file, declaration.range, declaration.compilerIdentity)
}

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Detached identity controls preserve proof fields without granting native callback authority. */
class ImpactCallbackIdentityTest {
    @Test
    fun `renewed candidate handles preserve the exact callback frame identity`() {
        val f = CallbackIdentityFixture()
        val target =
            f.target.copy(
                flow =
                    f.flow.copy(
                        body = f.body.copy(occurrence = f.body.occurrence.copy(candidateSelector = "renewed:body"))
                    ),
                selected =
                    f.selected.copy(occurrence = f.selected.occurrence.copy(candidateSelector = "renewed:invoke")),
            )
        assertTrue(f.same(target))
    }

    @Test
    fun `callback obligation keeps its exact supplying frame and source role`() {
        val f = CallbackIdentityFixture()
        val evidence = ImpactFlowObligationEvidenceDocument.CallbackFlow(f.flow)
        assertTrue(evidence.admitsObligation(f.argument, f.basis, ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN))
        assertFalse(f.argument.copy(callStack = EmptyImpactCallStack).admitsCallbackQuestion(f.flow))
        assertFalse(
            evidence.admitsObligation(
                f.argument.copy(role = ImpactValueRoleDocument.ExpressionResult),
                f.basis,
                ImpactFlowUnsupportedDocument.CALLBACK_FLOW_UNPROVEN,
            )
        )
    }

    @Test
    fun `callback identity preserves body compiler identity and semantic basis`() {
        val f = CallbackIdentityFixture()
        assertFalse(
            f.same(
                f.target.copy(
                    flow =
                        f.flow.copy(
                            body = f.body.copy(compilerEvidence = f.body.compilerEvidence.copy(identity = "other:body"))
                        )
                )
            )
        )
        assertFalse(f.same(f.target.copy(flow = f.flow.copy(basis = f.basis.copy(generation = revision(8))))))
    }

    @Test
    fun `callback identity preserves exact formal position and selected invoke`() {
        val f = CallbackIdentityFixture()
        val binding = f.flow.binding as QueryCallbackBindingWireDocument.Bound
        assertFalse(f.same(f.target.copy(flow = f.flow.copy(binding = binding.copy(position = 1)))))
        assertFalse(
            f.same(
                f.target.copy(
                    selected =
                        f.selected.copy(
                            occurrence = f.selected.occurrence.copy(range = SourceRangeWireDocument(312, 320))
                        )
                )
            )
        )
    }

    @Test
    fun `callback identity preserves unresolved qualification and scan proof`() {
        val f = CallbackIdentityFixture()
        assertFalse(
            f.same(
                f.target.copy(
                    flow = f.flow.copy(obligations = listOf(QueryCallbackFlowCauseDocument.NO_INVOCATION_PROVEN))
                )
            )
        )
        assertFalse(f.same(f.target.copy(flow = f.flow.copy(scan = QueryCallbackInvocationScanDocument.INCOMPLETE))))
    }
}

internal class CallbackIdentityFixture {
    val basis = ImpactSemanticBasisDocument.Published(text("/workspace"), revision(7))
    private val host = declaration(0, 200, 'a')
    private val mapper = declaration(300, 400, 'b')
    private val call = ImpactInvocationReferenceDocument(range(30, 90), mapper)
    private val mapperCallable =
        QueryCallbackCallableWireDocument(
            occurrence(300, 400, "mapper"),
            QueryExcludedCompilerTargetWireDocument(
                "/workspace/File.kt",
                SourceRangeWireDocument(300, 400),
                "map",
                SymbolKindWireDocument.FUNCTION,
                compiler("fixture.map"),
            ),
        )
    private val hostCallable =
        QueryCallbackCallableWireDocument(
            occurrence(0, 200, "host"),
            QueryExcludedCompilerTargetWireDocument(
                "/workspace/File.kt",
                SourceRangeWireDocument(0, 200),
                "host",
                SymbolKindWireDocument.FUNCTION,
                compiler("fixture.host"),
            ),
        )
    val body =
        QueryCallbackBodyWireDocument.Anonymous(
            occurrence(40, 80, "body"),
            compiler("anonymous@/workspace/File.kt#40:80"),
        )
    val selected =
        QueryCallbackInvocationWireDocument(
            occurrence(310, 320, "invoke"),
            QueryCallbackBodyWireDocument.Named(mapperCallable),
            emptyList(),
            emptyList(),
        )
    val flow =
        QueryCallbackFlowWireDocument.Observed(
            basis,
            body,
            QueryCallbackBindingWireDocument.Bound(
                call,
                occurrence(30, 90, "call"),
                QueryCallbackBodyWireDocument.Named(hostCallable),
                mapperCallable,
                0,
                occurrence(301, 309, "formal"),
            ),
            listOf(selected),
            emptyList(),
            emptyList(),
            QueryCallbackInvocationScanDocument.EXHAUSTIVE,
            QueryCallbackForwardingEvidenceWireDocument.InvocationRoutes,
        )
    val target =
        ImpactCallTargetDocument.Callback(
            flow,
            selected,
            ImpactDeclaredCallFrameDocument(host, call, ImpactExecutionReferenceDocument(mapper)),
        )
    private val invocation =
        ImpactCallOccurrenceDocument.Function(
            ImpactFunctionInvocationReferenceDocument(
                range(310, 320),
                QuerySourceLessCallableWireDocument(
                    compiler("kotlin.Function1.invoke"),
                    SymbolKindWireDocument.FUNCTION,
                    QuerySourceLessCallableOriginDocument.LIBRARY,
                    QuerySourceLessCallableModuleKindDocument.LIBRARY,
                    "kotlin-stdlib",
                ),
            )
        )
    val frame =
        ImpactCallFrameDocument(
            mapper,
            invocation,
            ImpactExecutionReferenceDocument(host, ImpactExecutionOwnerDocument.Anonymous(declaration(40, 80, 'c'))),
            target,
        )

    val argument =
        ImpactValueSiteReferenceDocument(
            mapper,
            range(312, 318),
            ImpactValueRoleDocument.FunctionArgument(invocation.invocation, ProtocolOffset.parse(0).value()),
            callStack = bounded(listOf(target.supplyingFrame.asFrame())),
        )

    fun same(other: ImpactCallTargetDocument.Callback): Boolean =
        bounded(listOf(frame)).hasSameCallStackIdentity(bounded(listOf(frame.copy(target = other))))

    fun anonymousBody(start: Int, end: Int) =
        QueryCallbackBodyWireDocument.Anonymous(
            occurrence(start, end, "otherbody"),
            compiler("anonymous@/workspace/File.kt#$start:$end"),
        )

    private fun compiler(identity: String) =
        CompilerSymbolEvidenceDocument.fromSignature(
                CompilerSignatureDocument.Function(
                    text(identity),
                    CompilerReceiverDocument.Absent,
                    bounded(emptyList()),
                    bounded(listOf(text("String"))),
                    CompilerTypeParameterCountDocument.parse(0).value(),
                )
            )
            .value()
            .toWireDocument()

    private fun occurrence(start: Int, end: Int, handle: String) =
        RelationOccurrenceWireDocument(handle, "/workspace/File.kt", SourceRangeWireDocument(start, end))

    private fun declaration(start: Int, end: Int, hash: Char) =
        ImpactDeclarationReferenceDocument(
            basis,
            text("/workspace/File.kt"),
            range(start, end),
            text(
                compiler(
                        when (hash) {
                            'a' -> "fixture.host"
                            'b' -> "fixture.map"
                            else -> "anonymous@/workspace/File.kt#40:80"
                        }
                    )
                    .identity
            ),
        )
}

private fun text(value: String) = ProtocolText.parse(value).value()

private fun revision(value: Long) = ImpactEvidenceRevisionDocument.parse(value).value()

private fun range(start: Int, end: Int) =
    ImpactSourceRangeDocument(ProtocolOffset.parse(start).value(), ProtocolOffset.parse(end).value())

private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value

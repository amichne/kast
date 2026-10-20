@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.psi.KtExperimentalApi::class,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaExplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral

/**
 * The two original entries share one declared component callable. A matched return keeps its actual call occurrence.
 */
class NativePacketComponentReturnTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-component-return-jdk"

    fun testOriginalDestructuringEntriesResolveTheSameComponentInTheSameSequentialBody() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val entries = originalEntries(fixture)
        assertEquals(exact(RESPONSE_START, RESPONSE_END), entries.response)
        assertEquals(exact(LINK_START, LINK_END), entries.link)
        assertFalse(entries.response == entries.link)
        println("COMPONENT_RETURN_NEIGHBOR proof=ACTUAL_K2_SAME_CALLEE_AND_CALLER transfer=NOT_RUN")
    }

    fun testOriginalComponentReturnReachesOnlyItsMatchedResponseEntry() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val entries = originalEntries(fixture)
        val route = NativePacketBindBranchSource(project, getOrCreateProjectBaseDir(), fixture)
        val returned = componentReturn(route)
        assertEquals(exact(BIND_START, BIND_END), returned.range)
        assertEquals(ValueRole.Return, returned.role)
        val frame = returned.callStack.frames.last()
        assertEquals(entries.response, frame.invocation.range)
        assertEquals(entries.owner, frame.invocation.executionOwner)
        assertEquals(fixture.callableDeclaration(COMPONENT).compilerIdentity, frame.callee.enclosing.compilerIdentity)
        val result = route.read(returned)
        val exits = result.transfers.filter { transfer -> transfer.kind == ValueTransferKind.SOURCE_CALL_RETURN }
        assertEquals(
            "C2.7 needs component1 Return1137:1143 to original response entry806:814. " +
                "Causes=${result.obligations.map { it.cause }}. Entry binding and C2.8/C2.9 remain NOT_REACHED",
            1,
            exits.size,
        )
        val transfer = exits.single()
        assertExit(entries, returned, transfer)
        assertTrue(
            "The same component at the independently proved link entry must not receive this matched return",
            result.transfers.none { candidate ->
                candidate.kind == ValueTransferKind.SOURCE_CALL_RETURN && candidate.target.range == entries.link
            },
        )
        println("COMPONENT_RETURN_RESULT row=C2.7 stage=matched-component-return entry_binding,C2.8,C2.9=NOT_RUN")
    }

    private fun componentReturn(route: NativePacketBindBranchSource): ValueSite {
        val value = route.successValue()
        val branch =
            next(route = route, source = value, kind = ValueTransferKind.BRANCH_ALTERNATIVE, stage = "bind when result")
        val bindReturn = next(route = route, source = branch, kind = ValueTransferKind.RETURN, stage = "bind return")
        val bindResult =
            next(
                route = route,
                source = bindReturn,
                kind = ValueTransferKind.SOURCE_CALL_RETURN,
                stage = "matched bind return",
            )
        return next(route = route, source = bindResult, kind = ValueTransferKind.RETURN, stage = "component1 return")
    }

    private fun assertExit(entries: ComponentReturnEntries, returned: ValueSite, transfer: ValueTransfer) {
        val operation = (transfer.evidence as ValueSourceTransfer).operation as ValueSourceOperation.Return
        assertEquals(returned.callStack.frames.last(), operation.frame)
        assertEquals(returned, transfer.source)
        assertEquals(entries.response, transfer.target.range)
        assertEquals(ValueRole.ExpressionResult, transfer.target.role)
        assertEquals(entries.owner, transfer.target.executionOwner)
        assertEquals(returned.callStack.frames.dropLast(1), transfer.target.callStack.frames)
        assertEquals(returned.valuePath, transfer.target.valuePath)
        assertEquals(returned.predicates, transfer.target.predicates)
    }

    private fun next(
        route: NativePacketBindBranchSource,
        source: ValueSite,
        kind: ValueTransferKind,
        stage: String,
    ): ValueSite {
        val read = route.read(source)
        val selected = read.transfers.filter { transfer -> transfer.kind == kind }
        assertEquals(
            "The exact component return prefix needs $stage. Causes=${read.obligations.map { it.cause }}",
            1,
            selected.size,
        )
        return selected.single().target
    }

    private fun originalEntries(fixture: NativePacketValueFixture): ComponentReturnEntries =
        ReadAction.compute<ComponentReturnEntries, RuntimeException> {
            val expected =
                NativePacketDestructuringProbeSource(project, getOrCreateProjectBaseDir(), fixture).expectation()
            val file =
                checkNotNull(
                    getOrCreateProjectBaseDir().findFileByRelativePath("src/fixture/feature/OffersProcessor.kt")
                )
            val psi = PsiManager.getInstance(project).findFile(file) as KtFile
            val entries = PsiTreeUtil.collectElementsOfType(psi, KtDestructuringDeclarationEntry::class.java)
            val response = entries.single { entry -> entry.name == "response" }
            val link = entries.single { entry -> entry.name == "link" }
            assertEquals(exact(RESPONSE_START, RESPONSE_END), range(response))
            assertEquals(exact(LINK_START, LINK_END), range(link))
            val literal = checkNotNull(PsiTreeUtil.getParentOfType(response, KtFunctionLiteral::class.java))
            assertSame(literal, PsiTreeUtil.getParentOfType(link, KtFunctionLiteral::class.java))
            assertEquals(expected.executionOwner.range, range(literal))
            val component = fixture.callableDeclaration(COMPONENT)
            IntellijReadObservation.None.observedAnalyze(literal) {
                proveEntry(entry = response, literal = literal, component = component)
                proveEntry(entry = link, literal = literal, component = component)
            }
            ComponentReturnEntries(range(response), range(link), expected.executionOwner)
        }

    private fun KaSession.proveEntry(
        entry: KtDestructuringDeclarationEntry,
        literal: KtFunctionLiteral,
        component: SymbolSelector,
    ) {
        val resolved = entry.resolveCall()
        assertTrue("Each original entry needs a real K2 component call", resolved is KaFunctionCall<*>)
        val call = resolved as KaFunctionCall<*>
        val symbol = call.signature.symbol
        assertEquals(KaSymbolOrigin.SOURCE, symbol.origin)
        val projected = symbol.compilerProjection(this)
        assertTrue(projected is IntellijCompilerProjectionResult.Projected)
        val declaration = (projected as IntellijCompilerProjectionResult.Projected).projection
        assertEquals(component.compilerIdentity, declaration.identity)
        assertEquals(component.signature, declaration.signature)
        val extension = call.extensionReceiver as KaExplicitReceiverValue
        assertEquals(entry.originalElement, extension.expression.originalElement)
        assertEquals(range(entry), range(extension.expression))
        val dispatch = call.dispatchReceiver as KaImplicitReceiverValue
        assertEquals(literal.symbol.receiverParameter, dispatch.symbol)
        println(
            "COMPONENT_RETURN_K2 entry=${range(entry)} callee=${declaration.identity.value} " +
                "caller=${range(literal)} extension=ACTUAL_ENTRY dispatch=ACTUAL_ANONYMOUS_RECEIVER"
        )
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        val COMPONENT = PacketDeclaration(NativePacketCompositeSuccessSource.OUTCOME, "OutcomeScope", "component1")
        const val RESPONSE_START = 806
        const val RESPONSE_END = 814
        const val LINK_START = 931
        const val LINK_END = 935
        const val BIND_START = 1137
        const val BIND_END = 1143
    }
}

private data class ComponentReturnEntries(
    val response: ExactDeclarationTextRange,
    val link: ExactDeclarationTextRange,
    val owner: ValueExecutionOwner,
)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtQualifiedExpression

/** These isolated original positions and explicit controls do not replace the full S2 producer or query. */
internal class NativePacketLetCallbackSource(
    private val project: Project,
    private val base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    private val proof = NativePacketLetCallbackFacts(project, base)

    fun original(): LetCallbackFacts {
        val source = NativePacketThisReadSource(project, base, fixture)
        val formal = source.requiredFormal()
        val checked =
            source
                .read(formal)
                .transfers
                .single { transfer ->
                    transfer.kind == ValueTransferKind.LOCAL_READ &&
                        transfer.target.range == exact(THIS_START, THIS_END)
                }
                .target
        val receiver = next(checked, ValueTransferKind.RECEIVER)
        return proof.describe(receiver).also { facts ->
            assertEquals(exact(LET_START, LET_END), facts.binding.invocation.range)
            assertEquals(exact(BODY_START, LET_END), facts.formal.executionOwner.range)
            assertEquals(listOf(exact(IT_START, IT_END)), facts.references)
            assertEquals(exact(RESULT_START, RESULT_END), facts.result)
        }
    }

    fun control(method: String, index: Int = 0): LetCallbackFacts {
        val expression =
            ReadAction.compute<PacketExpression, RuntimeException> {
                val file = checkNotNull(base.findFileByRelativePath("src/fixture/controls/LetCallbackControls.kt"))
                val psi = PsiManager.getInstance(project).findFile(file) as KtFile
                val host =
                    PsiTreeUtil.collectElementsOfType(psi, KtNamedFunction::class.java).single { it.name == method }
                val selected =
                    PsiTreeUtil.collectElementsOfType(host, KtCallExpression::class.java)
                        .filter { it.calleeExpression?.text == "let" }
                        .sortedBy { it.textOffset }[index]
                val qualified = selected.parent as KtQualifiedExpression
                PacketExpression(
                    fixture.callableDeclaration(PacketDeclaration(CONTROL, "LetCallbackControls", method)),
                    exact(
                        qualified.receiverExpression.textRange.startOffset,
                        qualified.receiverExpression.textRange.endOffset,
                    ),
                )
            }
        val receiver = next(resolve(expression), ValueTransferKind.RECEIVER)
        return proof.describe(receiver)
    }

    fun normalReturn(facts: LetCallbackFacts): ValueSite {
        val frame = ValueCallFrame.reviewedCallback(facts.binding, facts.argument, facts.formal).nativeRefined()
        val controlled =
            ValueSite.fromCompiler(
                    enclosing = facts.receiver.enclosing,
                    range = facts.result,
                    role = ValueRole.ExpressionResult,
                    executionOwner = facts.formal.executionOwner,
                    valuePath = facts.receiver.valuePath,
                    predicates = facts.receiver.predicates,
                    callStack = facts.receiver.callStack.enter(frame).nativeRefined(),
                )
                .nativeRefined()
        val returned = next(controlled, ValueTransferKind.RETURN)
        assertEquals(ValueRole.Return, returned.role)
        assertEquals(facts.result, returned.range)
        assertEquals(controlled.executionOwner, returned.executionOwner)
        assertEquals(controlled.callStack, returned.callStack)
        assertEquals(controlled.predicates, returned.predicates)
        println("LET_RETURN_SETUP kind=ISOLATED_K2_NORMAL_RESULT entry_application=NOT_USED")
        return returned
    }

    fun read(site: ValueSite): ValueFlowStep {
        val result = readRaw(site)
        assertTrue(
            "Native one-hop setup failed before the Let assertion: ${readStatus(result)}",
            result is ValueFlowRead.Observed,
        )
        return (result as ValueFlowRead.Observed).step
    }

    fun readRaw(site: ValueSite): ValueFlowRead = runBlocking {
        IntellijValueFlowCompilerAdapter()
            .read(
                project = project,
                current = fixture.lease,
                request = ValueFlowRequest(site, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                model = fixture.model,
            )
    }

    private fun readStatus(result: ValueFlowRead): String =
        when (result) {
            is ValueFlowRead.Observed -> "OBSERVED"
            is ValueFlowRead.Suspended -> "SUSPENDED:${result.cause}"
            is ValueFlowRead.Rejected -> "REJECTED:${result.cause}"
            is ValueFlowRead.ContractRejected -> "CONTRACT_REJECTED:${result.cause}"
        }

    private fun resolve(expression: PacketExpression): ValueSite {
        val domain =
            RelationRequest.start(
                RelationEndpoint.subject(expression.enclosing),
                RelationMeaning.References,
                fixture.budget,
            )
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = fixture.lease,
                    enclosing = expression.enclosing,
                    anchor = expression.anchor,
                    domain = domain,
                    model = fixture.model,
                )
        }
        assertTrue("The exact isolated source expression must resolve", result is ValueSiteResolution.Found)
        return (result as ValueSiteResolution.Found).site
    }

    private fun next(source: ValueSite, kind: ValueTransferKind): ValueSite {
        val result = read(source)
        val selected = result.transfers.filter { it.kind == kind }
        assertEquals(
            "The fixed source prefix needs $kind. Causes=${result.obligations.map { it.cause }}",
            1,
            selected.size,
        )
        return selected.single().target
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    companion object {
        const val CONTROL = "src/main/kotlin/fixture/controls/LetCallbackControls.kt"
        private const val THIS_START = 1622
        private const val THIS_END = 1626
        private const val LET_START = 1622
        private const val LET_END = 1655
        private const val BODY_START = 1632
        private const val IT_START = 1650
        private const val IT_END = 1652
        private const val RESULT_START = 1634
        private const val RESULT_END = 1653
    }
}

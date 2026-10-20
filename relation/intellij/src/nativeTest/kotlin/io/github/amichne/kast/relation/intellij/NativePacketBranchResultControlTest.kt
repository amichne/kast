@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueProducerOrigin
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Separate source controls distinguish normal values, actual bottom types, and discarded statement contexts. */
class NativePacketBranchResultControlTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-branch-result-control-jdk"

    fun testNormalStringArmReachesTheBranchAndNamedReturn() = assertNormal(BranchControl.NORMAL)

    fun testNullableNothingArmRemainsANormalNullValue() = assertNormal(BranchControl.NULLABLE)

    fun testActualNothingCallDoesNotFeedTheStringBranchResult() {
        val case = control(BranchControl.ABRUPT)
        val step = read(case.fixture, case.source)
        assertTrue(
            "A call whose actual K2 type is nonnullable Nothing must not emit a normal branch value",
            step.transfers.none { transfer ->
                transfer.kind == ValueTransferKind.BRANCH_ALTERNATIVE || transfer.kind == ValueTransferKind.RETURN
            },
        )
        assertEquals(listOf(ValueFlowUnsupportedCause.ABRUPT_COMPLETION), step.obligations.map { it.cause })
    }

    fun testUnitCoercionDoesNotGiveTheInputAFunctionReturn() = assertNoReturn(BranchControl.UNIT)

    fun testDiscardedBranchDoesNotGiveTheInputTheLaterConstantReturn() = assertNoReturn(BranchControl.DISCARDED)

    private fun assertNormal(control: BranchControl) {
        val case = control(control)
        val step = read(case.fixture, case.source)
        val alternatives = step.transfers.filter { transfer -> transfer.kind == ValueTransferKind.BRANCH_ALTERNATIVE }
        assertEquals("A genuine normal arm needs the exact if result", 1, alternatives.size)
        val result = alternatives.single().target
        assertEquals(case.branch, result.range)
        assertEquals(ValueRole.ExpressionResult, result.role)
        assertEquals(case.source.executionOwner, result.executionOwner)
        assertEquals(case.source.valuePath, result.valuePath)
        assertEquals(case.source.predicates, result.predicates)
        assertEquals(case.source.callStack, result.callStack)
        val returned = read(case.fixture, result)
        assertEquals(listOf(ValueTransferKind.RETURN), returned.transfers.map { it.kind })
        assertEquals(ValueRole.Return, returned.transfers.single().target.role)
    }

    private fun assertNoReturn(control: BranchControl) {
        val case = control(control)
        val step = read(case.fixture, case.source)
        assertTrue(step.transfers.none { transfer -> transfer.kind == ValueTransferKind.RETURN })
        val alternatives = step.transfers.filter { transfer -> transfer.kind == ValueTransferKind.BRANCH_ALTERNATIVE }
        for (alternative in alternatives) {
            assertEquals(case.branch, alternative.target.range)
            val continuation = read(case.fixture, alternative.target)
            assertTrue(
                "The discarded branch must not borrow the function's other return",
                continuation.transfers.none { transfer -> transfer.kind == ValueTransferKind.RETURN },
            )
        }
        println("BRANCH_CONTROL_LIMIT case=${control.name} claim=NO_FALSE_RETURN completion=NOT_ASSERTED")
    }

    private fun control(control: BranchControl): BranchControlCase {
        val fixture = packetFixture(listOf(SOURCE))
        val syntax = syntax(control)
        val request =
            fixture.selection(
                PacketDeclaration(SOURCE, OWNER, control.method),
                control.callee,
                PacketDeclaration(SOURCE, OWNER, control.callee),
            )
        val found = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(
                    project = project,
                    current = fixture.lease,
                    request = request,
                    model = fixture.model,
                )
        }
        assertTrue(
            "A real source call must admit before its branch result can be examined",
            found is ValueProducerSeedRead.Seeded,
        )
        val seed = (found as ValueProducerSeedRead.Seeded).seed
        assertEquals(syntax.call, seed.site.range)
        assertEquals(
            request.expectedCallable.compilerIdentity,
            (seed.origin as ValueProducerOrigin.Declared).invocation.callable.compilerIdentity,
        )
        assertEquals(ValueRole.ExpressionResult, seed.site.role)
        return BranchControlCase(fixture, seed.site, syntax.branch)
    }

    private fun syntax(control: BranchControl): BranchControlSyntax =
        ReadAction.compute<BranchControlSyntax, RuntimeException> {
            val file =
                checkNotNull(
                    getOrCreateProjectBaseDir().findFileByRelativePath("src/fixture/controls/BranchResultControls.kt")
                )
            val psi = PsiManager.getInstance(project).findFile(file) as KtFile
            val host =
                PsiTreeUtil.collectElementsOfType(psi, KtNamedFunction::class.java).single { candidate ->
                    candidate.name == control.method
                }
            val call =
                PsiTreeUtil.collectElementsOfType(host, KtCallExpression::class.java).single { candidate ->
                    candidate.calleeExpression?.text == control.callee
                }
            val branch = checkNotNull(PsiTreeUtil.getParentOfType(call, KtIfExpression::class.java))
            assertSame(call, branch.then)
            assertBodyPosition(control, host, branch)
            captureTypes(control = control, host = host, call = call, branch = branch)
            BranchControlSyntax(range(call), range(branch))
        }

    private fun assertBodyPosition(control: BranchControl, host: KtNamedFunction, branch: KtIfExpression) {
        when (control) {
            BranchControl.NORMAL,
            BranchControl.ABRUPT,
            BranchControl.NULLABLE -> assertSame(branch, host.bodyExpression)
            BranchControl.UNIT,
            BranchControl.DISCARDED -> {
                val block = host.bodyExpression as KtBlockExpression
                assertSame(branch, block.statements.first())
                assertEquals(if (control == BranchControl.UNIT) 1 else DISCARDED_STATEMENT_COUNT, block.statements.size)
            }
        }
    }

    private fun captureTypes(
        control: BranchControl,
        host: KtNamedFunction,
        call: KtCallExpression,
        branch: KtIfExpression,
    ) =
        IntellijReadObservation.None.observedAnalyze(call) {
            val resolved = checkNotNull(call.resolveCall())
            assertEquals(KaSymbolOrigin.SOURCE, resolved.signature.symbol.origin)
            val source = call.expressionType as KaClassType
            val target = branch.expressionType as KaClassType
            val returned = host.symbol.returnType as KaClassType
            assertEquals(control.sourceClass, source.classId.asSingleFqName().asString())
            assertEquals(control.nullableSource, source.isMarkedNullable)
            assertEquals(control.returnClass, returned.classId.asSingleFqName().asString())
            println(
                "BRANCH_CONTROL_TYPE case=${control.name} source=${range(call)} target=${range(branch)} " +
                    "origin=${resolved.signature.symbol.origin} sourceClass=${source.classId.asSingleFqName()} " +
                    "sourceNullable=${source.isMarkedNullable} targetClass=${target.classId.asSingleFqName()} " +
                    "targetNullable=${target.isMarkedNullable} returnClass=${returned.classId.asSingleFqName()}"
            )
        }

    private fun read(fixture: NativePacketValueFixture, source: ValueSite): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("The admitted branch source needs a native read", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step.also { step ->
            println(
                "BRANCH_CONTROL_STAGE source=${source.range} " +
                    "transfers=${step.transfers.map { transfer -> transfer.kind to transfer.target.range }} " +
                    "causes=${step.obligations.map { obligation -> obligation.cause }}"
            )
        }
    }

    private companion object {
        const val SOURCE = "src/main/kotlin/fixture/controls/BranchResultControls.kt"
        const val OWNER = "BranchResultControls"
        const val DISCARDED_STATEMENT_COUNT = 2
    }
}

private enum class BranchControl(
    val method: String,
    val callee: String = "readValue",
    val sourceClass: String = "kotlin.String",
    val nullableSource: Boolean = false,
    val returnClass: String = "kotlin.String",
) {
    NORMAL("normal"),
    ABRUPT("abruptArm", "never", "kotlin.Nothing"),
    NULLABLE(method = "nullableArm", callee = "nullValue", sourceClass = "kotlin.Nothing", nullableSource = true),
    UNIT("unitCoercion", returnClass = "kotlin.Unit"),
    DISCARDED("discarded"),
}

private data class BranchControlSyntax(val call: ExactDeclarationTextRange, val branch: ExactDeclarationTextRange)

private data class BranchControlCase(
    val fixture: NativePacketValueFixture,
    val source: ValueSite,
    val branch: ExactDeclarationTextRange,
)

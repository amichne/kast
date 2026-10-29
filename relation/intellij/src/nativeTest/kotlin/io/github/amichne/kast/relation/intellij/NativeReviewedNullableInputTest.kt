package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import io.github.amichne.kast.relation.contract.ReviewedValueRelation
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

class NativeReviewedNullableInputTest : NativeReviewedValueFixture() {
    override val fixtureSdkName = "reviewed-nullable-input-jdk"

    fun testConstantResultCannotAcquireIncomingPayload() = assertDiscarded("nullableInputCannotCrossConstantReturn")

    fun testNullResultCannotAcquireIncomingPayload() = assertDiscarded("nullableInputCannotCrossNullReturn")

    fun testActualParameterReturnPreservesIncomingField() {
        val fixture = fixture(paths = listOf(CONTROLS, INPUT_CONTROLS))
        val owner = owner("nullableInputKeepsSelectedField")
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        val destination = expressionBodyRange(owner)
        val result = steps.singleOrNull { step ->
            step.source.range == destination && step.source.role == ValueRole.Return
        }
        assertNotNull("Actual input must cross the callback body: ${steps.flatMap { it.obligations }}", result)
        val path = checkNotNull(result).source.valuePath.segments
        assertEquals(2, path.size)
        assertEquals(ValuePathSegment.Element, path.first())
        assertEquals("selected", (path.last() as ValuePathSegment.Field).field.declaration.name.value)
        assertTrue(
            steps
                .flatMap { it.reviewedTransfers }
                .any { transfer ->
                    transfer.binding.target.name.value == "mapNotNull" &&
                        transfer.source.role == ValueRole.Return &&
                        transfer.relation is ReviewedValueRelation.NonNullCallbackResult
                }
        )
    }

    private fun assertDiscarded(name: String) {
        val fixture = fixture(paths = listOf(CONTROLS, INPUT_CONTROLS))
        val owner = owner(name)
        val steps = trace(fixture, owner, budget = composedBudget(fixture))
        val input = fixture.markedExpression(owner, "input")
        assertTrue("The selected input must reach mapNotNull", steps.any { it.source.range == input.anchor })
        val destination = expressionBodyRange(owner)
        assertFalse(
            "An ignored input cannot reach the output",
            steps.any { it.source.range == destination && it.source.role == ValueRole.Return },
        )
    }

    private fun expressionBodyRange(owner: PacketDeclaration): ExactDeclarationTextRange =
        ReadAction.compute<ExactDeclarationTextRange, RuntimeException> {
            val file =
                checkNotNull(
                    getOrCreateProjectBaseDir()
                        .findFileByRelativePath("src/fixture/controls/ReviewedNullableInputControls.kt")
                )
            val function =
                (psiManager.findFile(file) as KtFile).declarations.filterIsInstance<KtNamedFunction>().single {
                    it.name == owner.name
                }
            range(checkNotNull(function.bodyExpression))
        }

    private fun owner(name: String) = PacketDeclaration(INPUT_CONTROLS, "", name)

    private companion object {
        const val INPUT_CONTROLS = "src/main/kotlin/fixture/controls/ReviewedNullableInputControls.kt"
    }
}

package io.github.amichne.kast.change.plan

import io.github.amichne.kast.change.contract.AddFileChangePlan
import io.github.amichne.kast.change.contract.AddFilePlanRequest
import io.github.amichne.kast.change.contract.AddFileTargetAdmissionFailure
import io.github.amichne.kast.change.contract.AddFileTargetObservation
import io.github.amichne.kast.change.contract.CreatableKotlinFileTarget
import io.github.amichne.kast.change.contract.KotlinFileSourceText
import io.github.amichne.kast.change.contract.PlannedSourcePrecondition
import io.github.amichne.kast.change.contract.SourceTextMutation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Path

class AddFilePlanTest {
    private val fixture = AddDeclarationPlanFixture()

    @Test
    fun `authored absent Kotlin target produces one exact deterministic file plan`() {
        val base = fixture.request()
        val path = Path.of("/workspace/app/src/main/kotlin/sample/Added.kt")
        val file = SymbolDiscoveryFileIdentity.fromBoundary(
            base.target.lease.workspaceRoot,
            path,
            "file://$path",
        ).refined() as SymbolDiscoveryFileIdentity.Workspace
        val target = CreatableKotlinFileTarget.admit(
            AddFileTargetObservation(fixture.workspace(), file, base.target.owner),
        ).refined()
        val content = KotlinFileSourceText.parse("package sample\n\nclass Added\n").refined()
        val request = AddFilePlanRequest(target, content)

        val first = PureAddFilePlanningService().plan(request).planned()
        val second = PureAddFilePlanningService().plan(request).planned()

        assertEquals(first.planId, second.planId)
        assertEquals(file, first.writes.entries.single().source)
        assertEquals(PlannedSourcePrecondition.Absent, first.writes.entries.single().precondition)
        val create = assertInstanceOf(
            SourceTextMutation.CreateFile::class.java,
            first.writes.entries.single().mutations.single(),
        )
        assertEquals(content, create.content)
    }

    @Test
    fun `non Kotlin target cannot enter AddFile planning`() {
        val base = fixture.request()
        val path = Path.of("/workspace/app/src/main/kotlin/sample/Added.java")
        val file = SymbolDiscoveryFileIdentity.fromBoundary(
            base.target.lease.workspaceRoot,
            path,
            "file://$path",
        ).refined() as SymbolDiscoveryFileIdentity.Workspace

        val rejected = CreatableKotlinFileTarget.admit(
            AddFileTargetObservation(fixture.workspace(), file, base.target.owner),
        ) as Refinement.Rejected

        assertEquals(AddFileTargetAdmissionFailure.NON_KOTLIN_FILE, rejected.failure)
    }

    private fun io.github.amichne.kast.change.contract.AddFilePlanResult.planned():
        AddFileChangePlan = when (this) {
        is io.github.amichne.kast.change.contract.AddFilePlanResult.Planned -> plan
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}

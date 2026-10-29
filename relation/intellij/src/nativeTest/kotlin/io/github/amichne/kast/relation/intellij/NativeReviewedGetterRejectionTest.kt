package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ReviewedValueRelation
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueModelSiteRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationLimit
import io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest
import io.github.amichne.kast.relation.contract.ValueSiteRoleClaim
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking

class NativeReviewedGetterRejectionTest : NativeReviewedValueFixture() {
    override val fixtureSdkName = "reviewed-getter-rejection-jdk"

    fun testChangedSourceArchiveCannotAuthorizeTupleProjection() = runBlocking {
        val selected = getterFixture()
        val observation = ValueReturnTestObservation()
        val adapter = IntellijValueFlowCompilerAdapter(observation)
        val original =
            adapter.revalidateSite(
                project = project,
                current = selected.fixture.lease,
                request = selected.request,
                model = selected.fixture.model,
            )
        assertTrue(
            "The original public getter site must restore: $original",
            original is ValueModelSiteRead.Revalidated,
        )
        changeOwnedSourceArchive()
        val restored =
            adapter.revalidateSite(
                project = project,
                current = selected.fixture.lease,
                request = selected.request,
                model = selected.fixture.model,
            )
        if (restored is ValueModelSiteRead.Revalidated) assertEquals(selected.source.role, restored.position.site.role)
        val read =
            adapter.read(
                project = project,
                current = selected.fixture.lease,
                request =
                    ValueFlowRequest(selected.source, selected.fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                model = selected.fixture.model,
            )
        assertTrue("The exact stale-artifact getter read must run: $read", read is ValueFlowRead.Observed)
        val step = (read as ValueFlowRead.Observed).step
        assertTrue(
            "Changed source bytes cannot authorize a reviewed tuple projection",
            step.reviewedTransfers.isEmpty(),
        )
        assertTrue(step.obligations.any { it.cause == ValueFlowUnsupportedCause.NESTED_EXECUTION })
        assertTrue(IntellijReadTermination.VALUE_REVIEWED_ARTIFACT_MISMATCH in observation.terminations)
        println("GETTER_RESTORATION afterChangedArtifact=$restored")
    }

    fun testGetterRestorationKeepsTheReviewedWorkRefusal() = runBlocking {
        val selected = getterFixture()
        val resources = selected.fixture.budget.resources as ResourceBudget.Bounded
        val budget =
            selected.fixture.budget.copy(
                resources = resources.copy(workUnitLimit = WorkUnitLimit.parse(GETTER_WORK_LIMIT).nativeRefined())
            )
        val request =
            ValueSiteRevalidationRequest.create(
                    enclosing = selected.request.enclosing,
                    anchor = selected.request.anchor,
                    role = selected.request.role,
                    budget = budget,
                )
                .nativeRefined()
        val observation = ValueReturnTestObservation()
        val read =
            IntellijValueFlowCompilerAdapter(observation)
                .revalidateSite(
                    project = project,
                    current = selected.fixture.lease,
                    request = request,
                    model = selected.fixture.model,
                )
        assertTrue(
            "The case must reach ordinary reviewed-call admission",
            (observation.calls[IntellijReadCall.VALUE_REVIEWED_CALL_ADMISSION] ?: 0) > 0,
        )
        assertTrue(
            "The reviewed owner must refuse work before fallback",
            IntellijReadTermination.WORK_LIMIT in observation.terminations,
        )
        assertEquals(ValueModelSiteRead.Limited(ValueSiteRevalidationLimit.WORK_LIMIT_REACHED), read)
    }

    private fun getterFixture(): SelectedGetterFixture {
        val fixture = fixture(paths = listOf(CONTROLS, PIPELINE_VARIANTS, COLLECTION_CONTROLS))
        val owner = PacketDeclaration(COLLECTION_CONTROLS, "", "pairFirstMatches")
        val transfer =
            trace(fixture, owner)
                .flatMap { it.reviewedTransfers }
                .single {
                    it.relation is ReviewedValueRelation.TupleProjection
                }
        val role = transfer.source.role as ValueRole.Receiver
        val selected = SymbolSelector.issue(fixture.lease, transfer.source.enclosing.scope, transfer.binding.target)
        val request =
            ValueSiteRevalidationRequest.create(
                    enclosing = fixture.markedExpression(owner, "destination").enclosing,
                    anchor = transfer.source.range,
                    role = ValueSiteRoleClaim.Receiver(role.call.range, selected, role.kind),
                    budget = fixture.budget,
                )
                .nativeRefined()
        return SelectedGetterFixture(fixture, transfer.source, request)
    }

    private fun changeOwnedSourceArchive() {
        val owned = getOrCreateProjectBaseDir().toNioPath().resolve("kotlin-stdlib-2.3.0-sources.jar")
        Files.write(owned, byteArrayOf(0), StandardOpenOption.APPEND)
        ApplicationManager.getApplication().invokeAndWait {
            checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(owned)).refresh(false, false)
        }
    }

    private companion object {
        const val COLLECTION_CONTROLS = "src/main/kotlin/fixture/controls/ReviewedCollectionControls.kt"
        const val GETTER_WORK_LIMIT = 8L
    }
}

private data class SelectedGetterFixture(
    val fixture: NativePacketValueFixture,
    val source: ValueSite,
    val request: ValueSiteRevalidationRequest,
)

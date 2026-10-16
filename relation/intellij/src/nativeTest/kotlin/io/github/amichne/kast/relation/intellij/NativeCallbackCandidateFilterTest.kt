package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiReference
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.util.concurrent.atomic.AtomicInteger

/** Real indexed preparation proves capacity order without a callable graph or runtime activation claim. */
class NativeCallbackCandidateFilterTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "callback-candidate-filter-jdk"

    fun testOutsideOrdinaryReferencesDoNotConsumeCallbackCandidateCapacity() {
        val fixture = prepareReferenceFixture()
        assertPositiveControl(fixture)
        val text = "package consumer\nfun caller() { proof.target(); val callback = { proof.target() } }\n"
        WriteCommandAction.writeCommandAction(project).run<RuntimeException> {
            fixture.callerFile.setBinaryContent(text.toByteArray())
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val references = mutableListOf<PsiReference>()
        assertTrue(
            IntellijReadObservation.None.forEachReference(
                fixture.target,
                fixture.scope(IntellijReadObservation.None).nativeScope,
                nativeScopeTestAdmission(fixture.request, IntellijReadObservation.None),
            ) { reference ->
                assertSame(fixture.target, reference.resolve())
                references += reference
                true
            }
        )
        assertEquals(2, references.size)
        assertEquals(1, references.count(IntellijCallbackCallerProjection::isCandidate))

        val unfiltered = prepare(fixture) { true }
        assertSame(RelationInventoryPreparation.Unavailable, unfiltered.result)
        assertTrue(IntellijReadTermination.CANDIDATE_CAP in unfiltered.counts.terminations)
        assertEquals(1, unfiltered.counts.candidates.get())

        val selected = fixture.targetFileOnly().scope(IntellijReadObservation.None)
        val filtered =
            prepare(fixture) { reference ->
                reference.element.containingFile?.virtualFile?.let(selected.nativeScope::contains) == true ||
                    IntellijCallbackCallerProjection.isCandidate(reference)
            }
        assertTrue(filtered.result is RelationInventoryPreparation.Prepared)
        val state = (filtered.result as RelationInventoryPreparation.Prepared).state
        assertEquals(text.lastIndexOf("target()"), state.prepared.single().range.startInclusive)
        assertEquals(1, filtered.counts.candidates.get())
        assertEquals(1, filtered.counts.excluded.get())
        assertFalse(IntellijReadTermination.CANDIDATE_CAP in filtered.counts.terminations)
    }

    private fun prepare(fixture: NativeReferenceFixture, admits: (PsiReference) -> Boolean): Preparation {
        val limits =
            when (
                val parsed = ReadLimits.resolve(mapOf(ReadLimitParameter.RELATION_CANDIDATES.environmentKey to "1"))
            ) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> error("The bounded fixture needs admitted limits: ${parsed.failure}")
            }
        val counts = Counts()
        val scope = fixture.scope(counts)
        val collector =
            IntellijRelationCollector(
                request = fixture.request,
                clockNanoseconds = { 0L },
                observation = counts,
                limits = limits,
            )
        val locators =
            IntellijRelationLocators(
                project = project,
                scope = scope,
                projection = IntellijK2RelationProjection(project, fixture.request.subject.lease.workspaceRoot, counts),
                cancellationCheck = ProgressManager::checkCanceled,
                observation = counts,
            )
        val result =
            IntellijReferenceInventory(
                    scope = scope,
                    locators = locators,
                    collector = collector,
                    cancellationCheck = ProgressManager::checkCanceled,
                    limits = limits,
                    observation = counts,
                    admitsReference = admits,
                )
                .prepare(fixture.target)
        return Preparation(result, counts)
    }

    private data class Preparation(val result: RelationInventoryPreparation, val counts: Counts)

    private class Counts : IntellijReadObservation {
        val candidates = AtomicInteger()
        val excluded = AtomicInteger()
        val terminations = mutableListOf<IntellijReadTermination>()

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            if (counter == IntellijReadCounter.RELATION_CANDIDATES) candidates.addAndGet(amount)
            if (counter == IntellijReadCounter.SCOPE_FILTERED) excluded.addAndGet(amount)
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
            terminations += reason
        }
    }
}

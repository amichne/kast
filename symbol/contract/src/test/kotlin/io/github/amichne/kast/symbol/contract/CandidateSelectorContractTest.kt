package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class CandidateSelectorContractTest {
    @Test
    fun `file and text candidates retain their batch scope and constraints`() {
        val fileBatch = batch(SymbolDiscoveryKind.FILE)
        val file = CandidateSelector.file(fileBatch, 0).refined()
        val textBatch = batch(SymbolDiscoveryKind.TEXT)
        val range = CandidateSelector.range(textBatch, 0).refined()

        assertEquals(fileBatch.scope, file.scope)
        assertEquals(fileBatch.constraints, file.constraints)
        assertEquals(textBatch.scope, range.scope)
        assertEquals(textBatch.constraints, range.constraints)
        assertEquals(
            CandidateSelectorFailure.NEGATIVE_ORDINAL,
            (CandidateSelector.file(fileBatch, -1) as Refinement.Rejected).failure,
        )
        assertEquals(
            CandidateSelectorFailure.ORDINAL_OUT_OF_RANGE,
            (CandidateSelector.range(textBatch, 1) as Refinement.Rejected).failure,
        )
        assertEquals(
            CandidateSelectorFailure.WRONG_LOCATION_KIND,
            (CandidateSelector.file(textBatch, 0) as Refinement.Rejected).failure,
        )
        assertEquals(
            CandidateSelectorFailure.WRONG_LOCATION_KIND,
            (CandidateSelector.range(fileBatch, 0) as Refinement.Rejected).failure,
        )
    }

    @Test
    fun `historical raw candidate factories retain explicit exact file scope`() {
        val fileBatch = batch(SymbolDiscoveryKind.FILE)
        val file = CandidateSelector.file(fileBatch.candidates.single()).refined()
        val range = CandidateSelector.range(batch(SymbolDiscoveryKind.TEXT).candidates.single()).refined()
        for (candidate in listOf(file, range)) {
            assertEquals(
                SymbolSearchScope.ExactFile(
                    file.file.path,
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.INCLUDE,
                ),
                candidate.scope,
            )
            assertEquals(SymbolDiscoveryConstraints.None, candidate.constraints)
        }
    }

    @Test
    fun `range candidates retain zero width compiler insertion points`() {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val lease = SemanticReadLease(root, EvidenceGeneration.parse(3).refined())
        val file =
            SymbolDiscoveryFileIdentity.Workspace(
                CanonicalWorkspaceFilePath.fromCanonicalPath(
                        root,
                        Path.of("/workspace/src/Subject.kt"),
                    )
                    .refined()
            )

        val selector = CandidateSelector.restoreRange(lease, file, 7, 7).refined()

        assertEquals(7, selector.startInclusive.value)
        assertEquals(7, selector.endExclusive.value)
        assertEquals(
            CandidateSelectorFailure.REVERSED_RANGE,
            (CandidateSelector.restoreRange(lease, file, 8, 7) as Refinement.Rejected).failure,
        )
        assertInstanceOf(
            Refinement.Rejected::class.java,
            CandidateSelector.restoreRange(lease, file, -1, 0),
        )
    }

    private fun batch(kind: SymbolDiscoveryKind): SymbolDiscoveryBatch {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val lease = SemanticReadLease(root, EvidenceGeneration.parse(3).refined())
        val scope =
            SymbolSearchScopeRequest(
                lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_ONLY,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
            )
        val budget =
            SymbolDiscoveryBudget(
                ResourceBudget(
                    ResultLimit.parse(1).refined(),
                    WorkUnitLimit.parse(10).refined(),
                    ElapsedTimeLimitMillis.parse(1_000).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(10_000).refined(),
            )
        val request =
            if (kind == SymbolDiscoveryKind.FILE)
                SymbolDiscoveryRequest(
                    scope,
                    SymbolDiscoveryTarget.Name(
                        SymbolNameDiscoveryKind.FILE,
                        SymbolDiscoveryPattern.parse("Subject").refined(),
                        SymbolDiscoveryMatch.EXACT_NAME,
                    ),
                    budget,
                    SymbolDiscoveryConstraints(
                        SymbolDiscoveryDirectoryConstraint(
                            SymbolDiscoveryDirectory.parse("src").refined(),
                            SymbolDiscoveryContainment.DESCENDANTS,
                        ),
                        null,
                        sourceSets =
                            SymbolDiscoverySourceSets.Exact.from(setOf(WorkspaceSourceSetName.parse("main").refined()))
                                .refined(),
                    ),
                )
            else
                SymbolDiscoveryRequest(
                    scope,
                    SymbolDiscoveryTarget.Text(SymbolDiscoveryPattern.parse("subject").refined()),
                    budget,
                )
        val candidate =
            SymbolDiscoveryCandidate.fromBoundary(
                    kind,
                    "Subject.kt",
                    lease,
                    Path.of("/workspace/src/Subject.kt"),
                    "file:///workspace/src/Subject.kt",
                    if (kind == SymbolDiscoveryKind.TEXT) 7 else null,
                    if (kind == SymbolDiscoveryKind.TEXT) 14 else null,
                )
                .refined()
        val elapsed = SymbolDiscoveryElapsedNanoseconds.parse(1).refined()
        return SymbolDiscoveryBatch.create(
                request,
                listOf(candidate),
                candidate.projectedUtf8Size(),
                SymbolDiscoveryWorkCount.parse(1).refined(),
                SymbolDiscoveryTimings(elapsed, elapsed),
            )
            .refined()
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("unexpected rejection: $failure")
    }

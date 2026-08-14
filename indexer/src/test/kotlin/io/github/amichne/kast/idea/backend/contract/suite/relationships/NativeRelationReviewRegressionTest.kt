package io.github.amichne.kast.idea

import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiQualifiedNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import io.github.amichne.kast.idea.backend.workspace.IntellijKotlinRelationSemantics
import io.github.amichne.kast.idea.workspace.gradle.toWorkspaceSearchScopeModel
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.ExactDeclarationEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationSelector
import io.github.amichne.kast.symbol.contract.NativeRelationBudget
import io.github.amichne.kast.symbol.contract.NativeRelationByteLimit
import io.github.amichne.kast.symbol.contract.NativeRelationFamily
import io.github.amichne.kast.symbol.contract.NativeRelationOutcome
import io.github.amichne.kast.symbol.contract.NativeRelationRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.symbol.intellij.IntellijNativeRelationAdapter
import io.github.amichne.kast.symbol.intellij.IntellijNativeRelationResult
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.CurrentWorkspaceEpoch
import io.github.amichne.kast.workspace.contract.CurrentWorkspaceReadLease
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

@TestApplication
internal class NativeRelationReviewRegressionTest : KastIndexerBackendContractTestFixture() {
    private val relationFile: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "NativeRelationReview.kt",
        """
        package review.relations

        class ReviewReturnType

        val reviewResult = ReviewReturnType()

        fun reviewDirectTarget() = Unit

        fun reviewValueOnlyTarget() = Unit

        fun reviewNestedOnlyTarget() = Unit

        fun reviewOuter(): ReviewReturnType {
            reviewDirectTarget()
            val callback = ::reviewValueOnlyTarget
            fun nested() {
                reviewNestedOnlyTarget()
            }
            class Local {
                fun invoke() {
                    reviewNestedOnlyTarget()
                }
            }
            return reviewResult
        }
        """.trimIndent(),
    )

    @Test
    fun `callers include invocations but exclude callable references`() = runBlocking {
        assertEquals(
            setOf("reviewOuter"),
            relatedNames("reviewDirectTarget", NativeRelationFamily.CALLERS),
        )
        assertTrue(
            relatedNames("reviewValueOnlyTarget", NativeRelationFamily.CALLERS).isEmpty(),
        )
    }

    @Test
    fun `callees exclude type property callable-reference and nested declaration references`() =
        runBlocking {
            assertEquals(
                setOf("reviewDirectTarget"),
                relatedNames("reviewOuter", NativeRelationFamily.CALLEES),
            )
        }

    private suspend fun relatedNames(
        subjectName: String,
        family: NativeRelationFamily,
    ): Set<String> {
        ensureProjectReady()
        val file = relationFile.get()
        waitUntilIndexesAreReady(project)
        val sourceRoot = readAction {
            Path.of(mainSourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        }
        val workspaceRoot = checkNotNull(sourceRoot.parent)
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(workspaceRoot).refined()
        val lease = CurrentWorkspaceReadLease(root, CurrentWorkspaceEpoch.parse(47L).refined())
        val model = workspaceModel(workspaceRoot, sourceRoot)
        val modelCompilation = model.toWorkspaceSearchScopeModel(root)
        val selector = readAction {
            val subject = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
                .single { it.name == subjectName }
            selector(subject, lease, workspaceRoot)
        }
        val request = NativeRelationRequest(
            selector = selector,
            family = family,
            budget = NativeRelationBudget(
                resources = ResourceBudget(
                    resultLimit = ResultLimit.parse(20).refined(),
                    workUnitLimit = WorkUnitLimit.parse(1_000L).refined(),
                    elapsedTimeLimit = ElapsedTimeLimitMillis.parse(5_000L).refined(),
                ),
                returnedBytes = NativeRelationByteLimit.parse(100_000L).refined(),
            ),
        )
        val result = IntellijNativeRelationAdapter(IntellijKotlinRelationSemantics).read(
            project = project,
            currentLease = lease,
            request = request,
            modelCompilation = modelCompilation,
        )
        check(result is IntellijNativeRelationResult.Read) { "relation read rejected: $result" }
        val batch = when (val outcome = result.outcome) {
            is NativeRelationOutcome.Complete -> outcome.batch
            is NativeRelationOutcome.Qualified -> outcome.batch
        }
        return batch.facts.mapTo(linkedSetOf()) { fact -> fact.related.evidence.name.value }
    }

    private fun selector(
        subject: KtNamedFunction,
        lease: CurrentWorkspaceReadLease,
        workspaceRoot: Path,
    ): ExactDeclarationSelector {
        val path = Path.of(checkNotNull(subject.containingFile.virtualFile).path)
            .toAbsolutePath()
            .normalize()
        val scope = workspaceScope()
        val discoveryRequest = SymbolDiscoveryRequest(
            scope = SymbolSearchScopeRequest(lease, scope),
            kind = SymbolDiscoveryKind.SYMBOL,
            pattern = SymbolDiscoveryPattern.parse(checkNotNull(subject.name)).refined(),
            budget = SymbolDiscoveryBudget(
                resources = ResourceBudget(
                    resultLimit = ResultLimit.parse(1).refined(),
                    workUnitLimit = WorkUnitLimit.parse(10L).refined(),
                    elapsedTimeLimit = ElapsedTimeLimitMillis.parse(1_000L).refined(),
                ),
                returnedBytes = SymbolDiscoveryByteLimit.parse(10_000L).refined(),
            ),
        )
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            kind = SymbolDiscoveryKind.SYMBOL,
            rawName = checkNotNull(subject.name),
            lease = lease,
            nativePath = path,
            virtualFileUrl = checkNotNull(subject.containingFile.virtualFile).url,
            rawOffset = subject.textOffset,
        ).refined()
        val batch = SymbolDiscoveryBatch.create(
            request = discoveryRequest,
            candidates = listOf(candidate),
            encodedBytes = candidate.projectedUtf8Size(),
            examinedWorkUnits = SymbolDiscoveryWorkCount.parse(1L).refined(),
            timings = SymbolDiscoveryTimings(
                nativeQuery = SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                projection = SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
            ),
        ).refined("discovery batch")
        val fileIdentity = SymbolDiscoveryFileIdentity.fromBoundary(
            CanonicalWorkspaceRoot.fromCanonicalPath(workspaceRoot).refined(),
            path,
            checkNotNull(subject.containingFile.virtualFile).url,
        ).refined()
        val evidence = ExactDeclarationEvidence.fromBoundary(
            file = fileIdentity,
            rawStartInclusive = subject.textOffset,
            rawEndExclusive = subject.textRange.endOffset,
            rawName = checkNotNull(subject.name),
            rawQualifiedIdentity = (subject as? PsiQualifiedNamedElement)?.qualifiedName,
            rawRuntimeType = subject.javaClass.name,
        ).refined()
        val selection = SymbolDiscoverySelection.select(batch, 0).refined("selection")
        return ExactDeclarationSelector.issue(
            selection,
            evidence,
        ).refined("selector issue")
    }

    private fun workspaceModel(
        workspaceRoot: Path,
        sourceRoot: Path,
    ) = IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
        listOf(workspaceRoot),
        true,
        emptyList(),
        emptyList(),
        emptyList(),
        listOf(
            IdeaGradleProjectLoadBridge.GradleModuleAssociation(
                mainModuleFixture.get().name,
                workspaceRoot,
                workspaceRoot,
                ":",
                false,
                false,
                listOf(
                    IdeaGradleProjectLoadBridge.GradleSourceSetAssociation(
                        "main",
                        listOf(authoredGradleSourceRoot(sourceRoot)),
                    ),
                ),
            ),
        ),
    )

    private fun workspaceScope() = SymbolSearchScope.Workspace(
        sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
        generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
        libraries = SymbolLibraryPolicy.EXCLUDE,
    )

    private fun <T, E> Refinement<T, E>.refined(context: String = "refinement"): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("$context rejected: $failure")
        }
}

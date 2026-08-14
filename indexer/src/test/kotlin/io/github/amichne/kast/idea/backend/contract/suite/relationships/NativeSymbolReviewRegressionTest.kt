package io.github.amichne.kast.idea

import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.skill.KastNativeReadCompleteness
import io.github.amichne.kast.api.contract.skill.KastNativeReadQualification
import io.github.amichne.kast.idea.backend.workspace.nativeItemAdmission
import io.github.amichne.kast.idea.backend.workspace.nativePublicSymbolReader
import io.github.amichne.kast.server.NativePublicSymbolReadResult
import io.github.amichne.kast.server.NativePublicSymbolReader
import io.github.amichne.kast.server.PublicSymbolReadMatch
import io.github.amichne.kast.server.PublicSymbolReadProjection
import io.github.amichne.kast.server.PublicSymbolReadQuery
import io.github.amichne.kast.symbol.intellij.IntellijDiscoveryItemAdmission
import io.github.amichne.kast.symbol.intellij.IntellijReadNanoClock
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

@TestApplication
internal class NativeSymbolReviewRegressionTest : KastIndexerBackendContractTestFixture() {
    private val collisionA: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "NativeReviewCollisionA.kt",
        """
        package review.a

        class NativeReviewCollision
        """.trimIndent(),
    )
    private val collisionB: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "NativeReviewCollisionB.kt",
        """
        package review.b

        class NativeReviewCollision
        """.trimIndent(),
    )
    private val collisionTarget: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "NativeReviewCollisionTarget.kt",
        """
        package review.target

        class NativeReviewCollision
        """.trimIndent(),
    )
    private val collisionFunction: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "NativeReviewCollisionFunction.kt",
        """
        package review.function

        fun NativeReviewCollision(): String = "function"
        """.trimIndent(),
    )
    private val unsupportedAlias: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "NativeReviewAlias.kt",
        """
        package review.alias

        typealias NativeReviewAlias = String
        """.trimIndent(),
    )
    private val oversizedDefinition: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "NativeReviewOversized.kt",
        buildString {
            appendLine("package review.budget")
            appendLine("/**")
            append("x".repeat(1_060_000))
            appendLine()
            appendLine("*/")
            appendLine("fun nativeReviewOversized(): String = \"large\"")
        },
    )

    @Test
    fun `fully qualified identity is admitted before the native record cap`() = runBlocking {
        val reader = reviewReader()

        val completed = reader.read(
            query(
                pattern = "review.target.NativeReviewCollision",
                maxResults = 2,
            ),
        ) as NativePublicSymbolReadResult.Completed

        assertEquals(KastNativeReadCompleteness.EXACT, completed.evidence.completeness)
        assertEquals(
            listOf("review.target.NativeReviewCollision"),
            completed.definitions.map { it.symbol.fqName },
        )
    }

    @Test
    fun `precise symbol kind is admitted before the native record cap`() = runBlocking {
        val reader = reviewReader()

        val completed = reader.read(
            query(
                pattern = "NativeReviewCollision",
                maxResults = 2,
                kind = SymbolKind.FUNCTION,
            ),
        ) as NativePublicSymbolReadResult.Completed

        assertEquals(KastNativeReadCompleteness.EXACT, completed.evidence.completeness)
        assertEquals(
            listOf("review.function.NativeReviewCollision"),
            completed.definitions.map { it.symbol.fqName },
        )
    }

    @Test
    fun `unsupported declaration kind is rejected before selector issuance`() = runBlocking {
        ensureReviewProjectReady()
        val alias = readAction {
            checkNotNull(PsiTreeUtil.findChildOfType(unsupportedAlias.get(), KtTypeAlias::class.java))
        }

        val admission = readAction {
            query(pattern = "NativeReviewAlias", maxResults = 10)
                .nativeItemAdmission()
                .admit(alias)
        }

        assertEquals(IntellijDiscoveryItemAdmission.UNSUPPORTED, admission)
    }

    @Test
    fun `oversized detached definition is excluded before the returned byte budget`() = runBlocking {
        val reader = reviewReader()

        val completed = reader.read(
            query(
                pattern = "nativeReviewOversized",
                maxResults = 10,
                projection = PublicSymbolReadProjection.DOCUMENTATION,
            ),
        ) as NativePublicSymbolReadResult.Completed

        assertTrue(completed.definitions.isEmpty())
        assertEquals(KastNativeReadCompleteness.QUALIFIED, completed.evidence.completeness)
        assertTrue(
            KastNativeReadQualification.BYTE_LIMIT_REACHED in completed.evidence.qualifications,
        )
        assertTrue(completed.evidence.projectionBytes <= 1_048_576L)
    }

    @Test
    fun `elapsed budget is rechecked after exact semantic resolution`() = runBlocking {
        val clock = ExactResolutionDelayClock()
        val reader = reviewReader(
            limits = ServerLimits(
                maxResults = 500,
                requestTimeoutMillis = 1_000L,
                maxConcurrentRequests = 4,
            ),
            clock = clock,
        )

        val completed = reader.read(
            query(pattern = "greet", maxResults = 10),
        ) as NativePublicSymbolReadResult.Completed

        assertTrue(completed.definitions.isEmpty())
        assertEquals(KastNativeReadCompleteness.QUALIFIED, completed.evidence.completeness)
        assertTrue(
            KastNativeReadQualification.TIME_LIMIT_REACHED in completed.evidence.qualifications,
        )
    }

    private suspend fun reviewReader(
        limits: ServerLimits? = null,
        clock: IntellijReadNanoClock = IntellijReadNanoClock(System::nanoTime),
    ): NativePublicSymbolReader {
        ensureReviewProjectReady()
        val sourceRoot = readAction {
            Path.of(mainSourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
        }
        val workspaceRoot = checkNotNull(sourceRoot.parent)
        val model = IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
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
        return backend(
            workspaceRoot = workspaceRoot,
            limits = limits ?: contractLimits,
            workspaceModelReader = { model },
        ).nativePublicSymbolReader(clock)
    }

    private fun ensureReviewProjectReady() {
        ensureProjectReady()
        collisionA.get()
        collisionB.get()
        collisionTarget.get()
        collisionFunction.get()
        unsupportedAlias.get()
        oversizedDefinition.get()
        waitUntilIndexesAreReady(project)
    }

    private fun query(
        pattern: String,
        maxResults: Int,
        kind: SymbolKind? = null,
        projection: PublicSymbolReadProjection = PublicSymbolReadProjection.BASIC,
    ) = PublicSymbolReadQuery(
        workspaceRoot = NormalizedPath.of(
            checkNotNull(
                Path.of(mainSourceRootFixture.get().virtualFile.path)
                    .toAbsolutePath()
                    .normalize()
                    .parent,
            ),
        ),
        pattern = NonBlankString(pattern),
        maxResults = PositiveInt(maxResults),
        match = PublicSymbolReadMatch.EXACT_NAME,
        projection = projection,
        kind = kind,
    )

    private class ExactResolutionDelayClock : IntellijReadNanoClock {
        private var adapterElapsedObservations = 0

        override fun now(): Long {
            val observesAdapterElapsed = Thread.currentThread().stackTrace.any { frame ->
                frame.className.endsWith("IntellijFastSymbolReadAdapter") &&
                frame.methodName == "elapsedSince"
            }
            if (!observesAdapterElapsed) {
                return 0L
            }
            adapterElapsedObservations += 1
            return if (adapterElapsedObservations >= 4) 1_000_000_000L else 0L
        }
    }
}

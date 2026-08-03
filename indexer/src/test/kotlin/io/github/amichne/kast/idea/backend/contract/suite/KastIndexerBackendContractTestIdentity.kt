package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastIndexerBackend

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSearchQuery
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.CallRelationsResult
import io.github.amichne.kast.api.contract.result.HierarchyRelationsResult
import io.github.amichne.kast.api.contract.result.ImplementationRelationsResult
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.RelationshipSearchCoverage
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import io.github.amichne.kast.api.contract.skill.KastCallersQuery
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.api.contract.skill.KastHierarchyQuery
import io.github.amichne.kast.api.contract.skill.KastImplementationsQuery
import io.github.amichne.kast.api.contract.skill.WrapperCallDirection
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.reference.SymbolReferencePage
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

@TestApplication
internal class KastIndexerBackendContractTestIdentity : KastIndexerBackendContractTestFixture() {
    @Test
    fun `find references preserves every Kotlin convention identity without spelling heuristics`() = runBlocking {
        ensureProjectReady()
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                val root = mainSourceRootFixture.get().virtualFile
                VfsUtil.saveText(
                    root.createChildData(this, "ConventionDeclaration.kt"),
                    """
                    package demo.convention

                    import kotlin.reflect.KProperty

                    class Box(var value: Int) {
                        override fun equals(other: Any?): Boolean = other is Box && value == other.value
                        override fun hashCode(): Int = value
                        operator fun contains(candidate: Int): Boolean = candidate == value
                        operator fun get(index: Int): Int = value + index
                        operator fun set(index: Int, replacement: Int) { value = replacement + index }
                        operator fun component1(): Int = value
                        operator fun invoke(): Int = value
                    }

                    class Delegate {
                        operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = 7
                    }
                    """.trimIndent(),
                )
                VfsUtil.saveText(
                    root.createChildData(this, "ConventionUsage.kt"),
                    """
                    package demo.convention

                    fun useConventions(left: Box, right: Box) {
                        val equal = left == right
                        val unequal = left != right
                        val included = 1 in left
                        val excluded = 2 !in left
                        val indexed = left[0]
                        left[0] = 3
                        val delegated by Delegate()
                        val (component) = left
                        val invoked = left()
                    }
                    """.trimIndent(),
                )
            }
        }
        waitUntilIndexesAreReady(project)
        val declarationFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("ConventionDeclaration.kt"))
        }
        val usageFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("ConventionUsage.kt"))
        }
        val (workspaceRoot, declarationFilePath, declarationOffsets) = readAction {
            Triple(
                commonWorkspaceRoot(declarationFile.virtualFile.path, usageFile.virtualFile.path),
                declarationFile.virtualFile.path,
                listOf("equals", "contains", "get", "set", "getValue", "component1", "invoke")
                    .associateWith { declarationName -> declarationFile.text.indexOf("fun $declarationName") + 4 },
            )
        }
        val backend = backend(workspaceRoot, referenceIndexLookup = ReferenceIndexLookup.Unavailable)

        val expectedUsageByDeclaration = mapOf(
            "equals" to listOf("left == right", "left != right"),
            "contains" to listOf("1 in left", "2 !in left"),
            "get" to listOf("left[0]"),
            "set" to listOf("left[0] = 3"),
            "getValue" to listOf("delegated by Delegate()"),
            "component1" to listOf("val (component) = left"),
            "invoke" to listOf("left()"),
        )
        expectedUsageByDeclaration.forEach { (declarationName, expectedPreviews) ->
            val references = collectAllReferencePages(
                backend = backend,
                position = FilePosition(
                    filePath = declarationFilePath,
                    offset = declarationOffsets.getValue(declarationName),
                ),
            )
            expectedPreviews.forEach { expectedPreview ->
                assertTrue(references.any { reference -> reference.location.preview.contains(expectedPreview) }) {
                    "Expected $declarationName reference at '$expectedPreview', got: $references"
                }
            }
        }
    }

    @Test
    fun `reference budget interruption fails closed without a continuation`() = runBlocking {
        ensureProjectReady()
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                val root = mainSourceRootFixture.get().virtualFile
                VfsUtil.saveText(
                    root.createChildData(this, "MidLeafContinuationDeclaration.kt"),
                    """
                    package demo.midleaf

                    class Invokable {
                        operator fun invoke(): Int = 1
                    }
                    """.trimIndent(),
                )
                VfsUtil.saveText(
                    root.createChildData(this, "MidLeafContinuationUsage.kt"),
                    """
                    package demo.midleaf

                    fun useInvocation(target: Invokable): Int = target()
                    """.trimIndent(),
                )
            }
        }
        waitUntilIndexesAreReady(project)
        val declarationFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("MidLeafContinuationDeclaration.kt"))
        }
        val usageFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("MidLeafContinuationUsage.kt"))
        }
        val testData = readAction {
            MidLeafReferenceTestData(
                workspaceRoot = commonWorkspaceRoot(declarationFile.virtualFile.path, usageFile.virtualFile.path),
                position = FilePosition(
                    declarationFile.virtualFile.path,
                    declarationFile.text.indexOf("invoke"),
                ),
                usageFilePath = usageFile.virtualFile.path,
                usageLeafOffset = usageFile.text.indexOf("target()"),
            )
        }
        val clockNanos = AtomicLong(0L)
        val interrupted = AtomicBoolean(false)
        val processedReferenceIndices = mutableListOf<Int>()
        var referencesInLeaf = 0
        val observer = object : ReferenceTraversalObserver {
            override fun closed() = Unit

            override fun referenceProcessed(
                filePath: String,
                leafOffset: Int,
                referenceIndex: Int,
                referenceCount: Int,
            ) {
                if (
                    filePath == testData.usageFilePath &&
                    leafOffset == testData.usageLeafOffset &&
                    referenceCount > 1
                ) {
                    processedReferenceIndices += referenceIndex
                    referencesInLeaf = referenceCount
                    if (referenceIndex == 0 && interrupted.compareAndSet(false, true)) {
                        clockNanos.set(2_000_000L)
                    }
                }
            }
        }
        val backend = backend(
            workspaceRoot = testData.workspaceRoot,
            limits = contractLimits.copy(
                requestTimeoutMillis = 1L,
                perFileScanBudgetMillis = 30_000L,
            ),
            referenceIndexLookup = ReferenceIndexLookup.Unavailable,
            referenceSearchClock = ReferenceSearchClock(clockNanos::get),
            referenceTraversalObserver = observer,
        )
        val result = backend.findReferences(
            ReferencesQuery(position = testData.position, includeDeclaration = false, maxResults = 50),
        )

        assertTrue(referencesInLeaf > 1, "test usage leaf did not expose multiple Kotlin references")
        assertEquals(listOf(0), processedReferenceIndices)
        assertEquals(null, result.page)
        val evidence = result.evidence as RelationshipResultEvidence.Limited
        assertTrue(RelationshipSearchLimitation.TIMED_OUT in evidence.coverage.limitations)
    }

    @Test
    fun `reference traversal disposes exactly once on exhaustion exception and shutdown`() = runBlocking {
        ensureProjectReady()
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                VfsUtil.saveText(
                    mainSourceRootFixture.get().virtualFile.createChildData(this, "TraversalLifecycleUsage.kt"),
                    """
                    package demo

                    fun traversalLifecycleUses(): List<String> = listOf(greet("one"), greet("two"), greet("three"))
                    """.trimIndent(),
                )
            }
        }
        waitUntilIndexesAreReady(project)
        val usageFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("TraversalLifecycleUsage.kt"))
        }
        val (workspaceRoot, position) = readAction {
            commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path) to
                FilePosition(sampleFile.virtualFile.path, sampleFile.text.indexOf("greet"))
        }

        val exhaustedCloseCount = AtomicInteger()
        val exhaustedBackend = backend(
            workspaceRoot = workspaceRoot,
            referenceTraversalObserver = ReferenceTraversalObserver { exhaustedCloseCount.incrementAndGet() },
        )
        val exhausted = exhaustedBackend.findReferences(
            ReferencesQuery(position = position, includeDeclaration = false, maxResults = 50),
        )
        assertEquals(null, exhausted.page)
        assertEquals(1, exhaustedCloseCount.get())

        val shutdownCloseCount = AtomicInteger()
        val shutdownBackend = backend(
            workspaceRoot = workspaceRoot,
            referenceTraversalObserver = ReferenceTraversalObserver { shutdownCloseCount.incrementAndGet() },
        )
        val retained = shutdownBackend.findReferences(
            ReferencesQuery(position = position, includeDeclaration = false, maxResults = 1),
        )
        assertNotNull(retained.page?.nextPageToken)
        shutdownBackend.close()
        shutdownBackend.close()
        assertEquals(1, shutdownCloseCount.get())

        var failClock = false
        val exceptionCloseCount = AtomicInteger()
        val exceptionBackend = backend(
            workspaceRoot = workspaceRoot,
            referenceSearchClock = ReferenceSearchClock {
                if (failClock) error("clock failure") else System.nanoTime()
            },
            referenceTraversalObserver = ReferenceTraversalObserver { exceptionCloseCount.incrementAndGet() },
        )
        val first = exceptionBackend.findReferences(
            ReferencesQuery(position = position, includeDeclaration = false, maxResults = 1),
        )
        failClock = true
        val failure = runCatching {
            exceptionBackend.findReferences(
                ReferencesQuery(
                    position = position,
                    includeDeclaration = false,
                    maxResults = 1,
                    pageToken = requireNotNull(first.page?.nextPageToken),
                ),
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, exceptionCloseCount.get())
    }

}

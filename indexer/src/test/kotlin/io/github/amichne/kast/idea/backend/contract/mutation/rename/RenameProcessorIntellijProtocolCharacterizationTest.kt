package io.github.amichne.kast.idea.backend.contract.mutation.rename

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.application.readAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiTreeChangeListener
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.disposableFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.idea.waitUntilIndexesAreReady
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val FEATURE_SUGGESTER_LISTENER_CLASS_NAME = "training.featuresSuggester.listeners.PsiActionsListener"

@TestApplication
internal class RenameProcessorIntellijProtocolCharacterizationTest {
    private val projectFixture = projectFixture()
    private val mainSourceRootFixture = projectFixture.moduleFixture("main").sourceRootFixture()
    private val project get() = projectFixture.get()
    private val platformListenerLifetime = disposableFixture()
    private val psiTreeChangeListeners = ExtensionPointName.create<PsiTreeChangeListener>(PsiTreeChangeListener.EP.name)
    private val privateDeclarationFixture = mainSourceRootFixture.psiFileFixture(
        "PrivateRenameTarget.kt",
        """
            package demo.rename.privatecase

            private fun privateTarget(): Int = 1
            fun privateCaller(): Int = privateTarget()

            class SameNameOwner {
                fun privateTarget(): Int = 2
            }
        """.trimIndent(),
    )
    private val publicDeclarationFixture = mainSourceRootFixture.psiFileFixture(
        "PublicRenameTarget.kt",
        """
            package demo.rename.publiccase

            fun publicTarget(value: Int): Int = value + 1
        """.trimIndent(),
    )
    private val publicUsageFixture = mainSourceRootFixture.psiFileFixture(
        "PublicRenameUsage.kt",
        """
            package demo.rename.publiccase

            fun publicCaller(): Int = publicTarget(1)
        """.trimIndent(),
    )
    private val overloadFixture = mainSourceRootFixture.psiFileFixture(
        "PublicRenameOverload.kt",
        """
            package demo.rename.publiccase

            fun publicTarget(value: String): String = value
        """.trimIndent(),
    )
    private val unrelatedFixture = mainSourceRootFixture.psiFileFixture(
        "UnrelatedSameName.kt",
        """
            package demo.rename.unrelated

            fun publicTarget(): String = "unrelated"
        """.trimIndent(),
    )
    private val overrideBaseFixture = mainSourceRootFixture.psiFileFixture(
        "RenameOverrideBase.kt",
        """
            package demo.rename.overridecase

            interface RenameContract {
                fun overrideTarget(): String
            }
        """.trimIndent(),
    )
    private val overrideImplementationFixture = mainSourceRootFixture.psiFileFixture(
        "RenameOverrideImplementation.kt",
        """
            package demo.rename.overridecase

            class RenameImplementation : RenameContract {
                override fun overrideTarget(): String = "implemented"
            }
        """.trimIndent(),
    )
    private val overrideUsageFixture = mainSourceRootFixture.psiFileFixture(
        "RenameOverrideUsage.kt",
        """
            package demo.rename.overridecase

            fun overrideCaller(contract: RenameContract): String = contract.overrideTarget()
        """.trimIndent(),
    )
    private val propertyFixture = mainSourceRootFixture.psiFileFixture(
        "RenameProperty.kt",
        """
            package demo.rename.propertycase

            class PropertyOwner {
                var status: Int = 0
            }
        """.trimIndent(),
    )
    private val propertyUsageFixture = mainSourceRootFixture.psiFileFixture(
        "RenamePropertyUsage.kt",
        """
            package demo.rename.propertycase

            fun update(owner: PropertyOwner): Int {
                owner.status = 2
                return owner.status
            }
        """.trimIndent(),
    )
    private val conflictFixture = mainSourceRootFixture.psiFileFixture(
        "RenameConflict.kt",
        """
            package demo.rename.conflictcase

            fun collisionTarget(): Int = 1
            fun occupiedName(): Int = 2
        """.trimIndent(),
    )

    @Test
    fun privateOrFileLocalTargetIsSupportedWithoutSameNamedRelatedRename() {
        runBlocking {
            val file = ready(privateDeclarationFixture)
            val sameNamedFunctions = namedFunctions(file, "privateTarget")
            val target = sameNamedFunctions.first { function -> function.parent is KtFile }
            val unrelated = sameNamedFunctions.first { function -> function.parent !is KtFile }

            val supported = assertInstanceOf(
                RenameProcessorCharacterizationResult.Supported::class.java,
                RenameProcessorIntellijProtocolCharacterizer(project, ::prepareProjectForRename).characterize(
                    target = target,
                    newName = "renamedPrivateTarget",
                    protectedUnrelatedDeclarations = listOf(unrelated),
                ),
            )

            assertEquals(
                setOf(file.virtualFile.path),
                supported.evidence.affectedFilePaths,
            )
            assertEquals(
                setOf(file.virtualFile.path),
                supported.evidence.preRunReferencePaths,
            )
            assertEquals("privateTarget", supported.evidence.targetNameBefore)
            assertEquals("renamedPrivateTarget", supported.evidence.targetNameAfter)
            assertEquals("privateTarget", readAction { unrelated.name })
            assertSearchPhasesAndDuration(supported.evidence)
        }
    }

    @Test
    fun publicMultiFileTargetPreservesOverloadsAndSameNamedUnrelatedDeclarations() {
        runBlocking {
            val declarationFile = ready(publicDeclarationFixture)
            val usageFile = publicUsageFixture.get() as KtFile
            val overloadFile = overloadFixture.get() as KtFile
            val unrelatedFile = unrelatedFixture.get() as KtFile
            val target = namedFunctions(declarationFile, "publicTarget").single()
            val overload = namedFunctions(overloadFile, "publicTarget").single()
            val unrelated = namedFunctions(unrelatedFile, "publicTarget").single()

            val supported = assertInstanceOf(
                RenameProcessorCharacterizationResult.Supported::class.java,
                RenameProcessorIntellijProtocolCharacterizer(project, ::prepareProjectForRename).characterize(
                    target = target,
                    newName = "renamedPublicTarget",
                    protectedUnrelatedDeclarations = listOf(overload, unrelated),
                ),
            )

            assertEquals(
                setOf(declarationFile.virtualFile.path, usageFile.virtualFile.path),
                supported.evidence.affectedFilePaths,
            )
            assertEquals(setOf(usageFile.virtualFile.path), supported.evidence.preRunReferencePaths)
            assertEquals("publicTarget", readAction { overload.name })
            assertEquals("publicTarget", readAction { unrelated.name })
            assertSearchPhasesAndDuration(supported.evidence)
        }
    }

    @Test
    fun explicitOverrideFamilyStrategyRenamesOnlyDeclaredFamilyAndUsages() {
        runBlocking {
            val baseFile = ready(overrideBaseFixture)
            val implementationFile = overrideImplementationFixture.get() as KtFile
            val usageFile = overrideUsageFixture.get() as KtFile
            val base = namedFunctions(baseFile, "overrideTarget").single()
            val implementation = namedFunctions(implementationFile, "overrideTarget").single()

            val supported = assertInstanceOf(
                RenameProcessorCharacterizationResult.Supported::class.java,
                RenameProcessorIntellijProtocolCharacterizer(project, ::prepareProjectForRename).characterize(
                    target = base,
                    newName = "renamedOverrideTarget",
                    strategy = RenameProcessorStrategy.EXPLICIT_RELATED_ELEMENTS,
                    declaredRelatedRenames = listOf(
                        DeclaredRelatedRename(implementation, "renamedOverrideTarget"),
                    ),
                ),
            )

            assertEquals(RenameProcessorStrategy.EXPLICIT_RELATED_ELEMENTS, supported.evidence.strategy)
            assertEquals(
                setOf(
                    baseFile.virtualFile.path,
                    implementationFile.virtualFile.path,
                    usageFile.virtualFile.path,
                ),
                supported.evidence.affectedFilePaths,
            )
            assertEquals("renamedOverrideTarget", readAction { implementation.name })
            assertSearchPhasesAndDuration(supported.evidence)
        }
    }

    @Test
    fun propertyStrategyRenamesPropertyAccessesAcrossFiles() {
        runBlocking {
            val declarationFile = ready(propertyFixture)
            val usageFile = propertyUsageFixture.get() as KtFile
            val property = readAction {
                PsiTreeUtil.findChildOfType(declarationFile, KtProperty::class.java)!!
            }

            val supported = assertInstanceOf(
                RenameProcessorCharacterizationResult.Supported::class.java,
                RenameProcessorIntellijProtocolCharacterizer(project, ::prepareProjectForRename).characterize(
                    target = property,
                    newName = "renamedStatus",
                ),
            )

            assertEquals(
                setOf(declarationFile.virtualFile.path, usageFile.virtualFile.path),
                supported.evidence.affectedFilePaths,
            )
            assertEquals(setOf(usageFile.virtualFile.path), supported.evidence.preRunReferencePaths)
            assertSearchPhasesAndDuration(supported.evidence)
        }
    }

    @Test
    fun sameScopeNameCollisionIsAClosedConflictWithoutAffectedFiles() {
        runBlocking {
            val file = ready(conflictFixture)
            val target = namedFunctions(file, "collisionTarget").single()

            val unsupported = assertInstanceOf(
                RenameProcessorCharacterizationResult.Unsupported::class.java,
                RenameProcessorIntellijProtocolCharacterizer(project, ::prepareProjectForRename).characterize(
                    target = target,
                    newName = "occupiedName",
                ),
            )

            assertEquals(RenameProcessorProtocolLimitation.CONFLICT, unsupported.limitation)
            assertEquals(emptySet<String>(), unsupported.affectedFilePaths)
            assertEquals("collisionTarget", readAction { target.name })
        }
    }

    @Test
    fun cancellationDuringProcessorSearchIsClosedAndDoesNotWrite() {
        runBlocking {
            val file = ready(privateDeclarationFixture)
            val sourceBefore = readAction { file.text }
            val target = namedFunctions(file, "privateTarget")
                .first { function -> function.parent is KtFile }
            val unsupported = assertInstanceOf(
                RenameProcessorCharacterizationResult.Unsupported::class.java,
                RenameProcessorIntellijProtocolCharacterizer(
                    project = project,
                    beforeProcessorRun = ::prepareProjectForRename,
                    duringRunSearch = { throw ProcessCanceledException() },
                ).characterize(target, "cancelledTarget"),
            )

            assertEquals(RenameProcessorProtocolLimitation.CANCELLED, unsupported.limitation)
            assertTrue(
                RenameProcessorProtocolPhase.DURING_RUN_USAGE_SEARCH in unsupported.phases,
            )
            assertEquals(emptySet<String>(), unsupported.affectedFilePaths)
            assertEquals(sourceBefore, readAction { file.text })
        }
    }

    @Test
    fun processorSilentAbortCannotBecomeSupportedEvidence() {
        runBlocking {
            val file = ready(privateDeclarationFixture)
            val target = namedFunctions(file, "privateTarget")
                .first { function -> function.parent is KtFile }

            val unsupported = assertInstanceOf(
                RenameProcessorCharacterizationResult.Unsupported::class.java,
                RenameProcessorIntellijProtocolCharacterizer(
                    project = project,
                    beforeProcessorRun = ::prepareProjectForRename,
                    processorRunner = {},
                ).characterize(target, "silentlyIgnoredTarget"),
            )

            assertEquals(RenameProcessorProtocolLimitation.SILENT_ABORT, unsupported.limitation)
            assertEquals(emptySet<String>(), unsupported.affectedFilePaths)
            assertEquals(
                setOf(
                    RenameProcessorProtocolPhase.TARGET_SELECTED,
                    RenameProcessorProtocolPhase.PRE_RUN_REFERENCE_SEARCH,
                    RenameProcessorProtocolPhase.PROCESSOR_COMMAND_COMPLETED,
                ),
                unsupported.phases,
            )
            assertEquals("privateTarget", readAction { target.name })
        }
    }

    private suspend fun ready(fileFixture: TestFixture<PsiFile>): KtFile {
        val projectManager = ProjectManagerEx.getInstanceEx()
        check(!projectManager.isProjectOpened(project))
        val file = fileFixture.get() as KtFile
        publicUsageFixture.get()
        overloadFixture.get()
        unrelatedFixture.get()
        overrideImplementationFixture.get()
        overrideUsageFixture.get()
        propertyUsageFixture.get()
        waitUntilIndexesAreReady(project)
        return file.also { check(!projectManager.isProjectOpened(project)) }
    }

    private suspend fun prepareProjectForRename() {
        val projectManager = ProjectManagerEx.getInstanceEx()
        val opened = projectManager.openProjectAsync(
            Path.of(requireNotNull(project.basePath)),
            OpenProjectTask.build().copy(preloadServices = false, isNewProject = true).withProject(project),
        )
        check(opened === project)
        check(project.isInitialized)
        val registered = PsiTreeChangeListener.EP.getExtensions(project)
        val retained = registered.filterNot { it.javaClass.name == FEATURE_SUGGESTER_LISTENER_CLASS_NAME }
        if (retained.size != registered.size) {
            ExtensionTestUtil.maskExtensions(
                pointName = psiTreeChangeListeners,
                newExtensions = retained,
                parentDisposable = platformListenerLifetime.get(),
                areaInstance = project,
            )
        }
        val effective = PsiTreeChangeListener.EP.getExtensions(project)
        assertEquals(retained, effective)
        assertTrue(
            effective.none { it.javaClass.name == FEATURE_SUGGESTER_LISTENER_CLASS_NAME },
            "Feature-suggester listener remained registered at RenameProcessor.run",
        )
    }

    private suspend fun namedFunctions(file: KtFile, name: String): List<KtNamedFunction> = readAction {
        PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
            .filter { function -> function.name == name }
    }

    private fun assertSearchPhasesAndDuration(evidence: RenameProcessorProtocolEvidence) {
        assertEquals(
            setOf(
                RenameProcessorProtocolPhase.TARGET_SELECTED,
                RenameProcessorProtocolPhase.PRE_RUN_REFERENCE_SEARCH,
                RenameProcessorProtocolPhase.DURING_RUN_USAGE_SEARCH,
                RenameProcessorProtocolPhase.PROCESSOR_COMMAND_COMPLETED,
            ),
            evidence.phases,
        )
        assertTrue(evidence.usageCount.value > 0)
        assertTrue(evidence.commandDuration.nanoseconds > 0L)
    }
}

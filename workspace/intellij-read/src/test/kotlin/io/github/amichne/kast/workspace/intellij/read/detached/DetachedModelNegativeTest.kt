package io.github.amichne.kast.workspace.intellij.read

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetachedModelNegativeTest {
    @Test
    fun `closed observation outcomes cannot manufacture a detached model`() {
        assertFailures(
            DetachedModelObservation.Rejected(DetachedModelCaptureFailure.OBSERVATION_FAILED),
            DetachedModelCaptureFailure.OBSERVATION_FAILED,
        )
        assertFailures(
            DetachedModelObservation.Rejected(DetachedModelCaptureFailure.READ_PREEMPTED),
            DetachedModelCaptureFailure.READ_PREEMPTED,
        )
        assertFailures(
            DetachedModelObservation.Rejected(DetachedModelCaptureFailure.WRONG_THREAD),
            DetachedModelCaptureFailure.WRONG_THREAD,
        )
        assertFailures(
            DetachedModelObservation.Rejected(DetachedModelCaptureFailure.PROJECT_NOT_OPEN),
            DetachedModelCaptureFailure.PROJECT_NOT_OPEN,
        )
        assertFailures(
            DetachedModelObservation.Rejected(DetachedModelCaptureFailure.PROJECT_NOT_INITIALIZED),
            DetachedModelCaptureFailure.PROJECT_NOT_INITIALIZED,
        )
    }

    @Test
    fun `project state root and model failures remain finite data`() {
        projectCases().forEach { case ->
            assertFailures(
                DetachedModelObservation.Observed(case.boundary),
                case.failure,
                case.name,
            )
        }
    }

    @Test
    fun `module ownership source root SDK and classpath failures remain finite data`() {
        moduleCases().forEach { case ->
            assertFailures(
                DetachedModelObservation.Observed(
                    detachedModelBoundary(modules = case.modules),
                ),
                case.failure,
                case.name,
            )
        }
    }

    @Test
    fun `oversized observations reject instead of truncating into authority`() {
        assertFailures(
            DetachedModelObservation.Observed(
                detachedModelBoundary(
                    modules = (0..256).map(::detachedModuleBoundary),
                ),
            ),
            DetachedModelCaptureFailure.TOO_MANY_MODULES,
            "module limit",
        )
        assertFailures(
            observationWithModule(
                detachedModuleBoundary(
                    sourceRoots = (0..256).map { index ->
                        detachedSourceRootBoundary("roots/root-$index")
                    },
                ),
            ),
            DetachedModelCaptureFailure.TOO_MANY_SOURCE_ROOTS,
            "source-root limit",
        )
        assertFailures(
            observationWithModule(
                detachedModuleBoundary(
                    classpath = (0..512).map(::detachedClasspathBoundary),
                ),
            ),
            DetachedModelCaptureFailure.TOO_MANY_CLASSPATH_ENTRIES,
            "classpath limit",
        )
    }

    @Test
    fun `duplicate roots and cross module ownership ambiguity reject`() {
        val shared = detachedSourceRootBoundary("shared/src/main/kotlin")
        assertRejected(
            observationWithModule(
                detachedModuleBoundary(sourceRoots = listOf(shared, shared)),
            ),
            "duplicate source-root observation",
        )
        assertRejected(
            DetachedModelObservation.Observed(
                detachedModelBoundary(
                    modules = listOf(
                        detachedModuleBoundary(index = 0, sourceRoots = listOf(shared)),
                        detachedModuleBoundary(index = 1, sourceRoots = listOf(shared)),
                    ),
                ),
            ),
            "one source root with two Gradle owners",
        )
        assertFailures(
            observationWithModule(
                detachedModuleBoundary(
                    sourceRoots = listOf(
                        shared,
                        shared.copy(kind = DetachedSourceRootKind.TEST),
                    ),
                ),
            ),
            DetachedModelCaptureFailure.CONFLICTING_SOURCE_ROOT_KIND,
        )
    }

    @Test
    fun `text bounds and rejection collections fail closed`() {
        assertFailures(
            DetachedModelObservation.Observed(
                detachedModelBoundary(projectRoot = "/" + "r".repeat(4_097)),
            ),
            DetachedModelCaptureFailure.PATH_IDENTITY_TOO_LONG,
        )
        assertFailures(
            observationWithModule(detachedModuleBoundary(name = "m".repeat(513))),
            DetachedModelCaptureFailure.IDENTITY_TOO_LONG,
        )
        assertFailures(
            observationWithModule(
                detachedModuleBoundary(
                    classpath = listOf(
                        DetachedClasspathBoundary("file:///" + "c".repeat(8_193)),
                    ),
                ),
            ),
            DetachedModelCaptureFailure.CLASSPATH_IDENTITY_TOO_LONG,
        )
        val additionalFailures = linkedSetOf(DetachedModelCaptureFailure.READ_PREEMPTED)
        val rejected = DetachedModelCapture.Rejected(
            DetachedModelCaptureFailure.OBSERVATION_FAILED,
            additionalFailures,
        )
        additionalFailures.clear()
        assertEquals(
            setOf(
                DetachedModelCaptureFailure.OBSERVATION_FAILED,
                DetachedModelCaptureFailure.READ_PREEMPTED,
            ),
            rejected.failures,
        )
        @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        val javaFailures = rejected.failures as java.util.Set<DetachedModelCaptureFailure>
        val mutation = runCatching { javaFailures.clear() }
        assertTrue(mutation.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(
            DetachedModelCapture.Rejected::class.java.declaredConstructors.none { constructor ->
                constructor.parameterTypes.contentEquals(arrayOf(java.util.Set::class.java))
            },
            "Rejected must not expose an empty-set construction path",
        )
        assertTrue(
            DetachedIdeWorkspaceModel::class.java.declaredConstructors.any { constructor ->
                constructor.parameterTypes.any { parameter ->
                    parameter.simpleName == "RefinedDetachedModules"
                }
            },
            "model construction must consume the refined module aggregate",
        )
    }

    private fun projectCases(): List<ProjectCase> = listOf(
        ProjectCase(
            "disposed Project",
            DetachedModelCaptureFailure.PROJECT_DISPOSED,
            detachedModelBoundary(disposed = true),
        ),
        ProjectCase(
            "dumb Project",
            DetachedModelCaptureFailure.PROJECT_DUMB,
            detachedModelBoundary(smart = false),
        ),
        ProjectCase(
            "missing root",
            DetachedModelCaptureFailure.ROOT_UNAVAILABLE,
            detachedModelBoundary(projectRoot = null),
        ),
        ProjectCase(
            "relative root",
            DetachedModelCaptureFailure.ROOT_UNAVAILABLE,
            detachedModelBoundary(projectRoot = "workspace/kast"),
        ),
        ProjectCase(
            "non-normalized root",
            DetachedModelCaptureFailure.ROOT_UNAVAILABLE,
            detachedModelBoundary(projectRoot = "/workspace/./kast"),
        ),
        ProjectCase(
            "other root",
            DetachedModelCaptureFailure.ROOT_MISMATCH,
            detachedModelBoundary(projectRoot = "/workspace/other"),
        ),
        ProjectCase(
            "incomplete cached model",
            DetachedModelCaptureFailure.GRADLE_MODEL_INCOMPLETE,
            detachedModelBoundary(gradleModelComplete = false),
        ),
        ProjectCase(
            "missing modules",
            DetachedModelCaptureFailure.NO_MODULES,
            detachedModelBoundary(modules = emptyList()),
        ),
    )

    private fun moduleCases(): List<ModuleCase> {
        val valid = detachedModuleBoundary()
        val classpath = detachedClasspathBoundary(0)
        return listOf(
            ModuleCase(
                "disposed module",
                DetachedModelCaptureFailure.MODULE_DISPOSED,
                listOf(valid.copy(disposed = true)),
            ),
            ModuleCase(
                "blank module name",
                DetachedModelCaptureFailure.INVALID_MODULE_NAME,
                listOf(valid.copy(name = " ")),
            ),
            ModuleCase(
                "duplicate module",
                DetachedModelCaptureFailure.DUPLICATE_MODULE,
                listOf(valid, valid),
            ),
            ModuleCase(
                "module without cached Gradle ownership",
                DetachedModelCaptureFailure.NOT_GRADLE_OWNED,
                listOf(valid.copy(gradleOwned = false)),
            ),
            ModuleCase(
                "relative Gradle build root",
                DetachedModelCaptureFailure.INVALID_GRADLE_BUILD_ROOT,
                listOf(valid.copy(gradleBuildRoot = "workspace/kast")),
            ),
            ModuleCase(
                "outside Gradle build root",
                DetachedModelCaptureFailure.GRADLE_BUILD_ROOT_OUTSIDE_WORKSPACE,
                listOf(valid.copy(gradleBuildRoot = "/workspace/other")),
            ),
            ModuleCase(
                "relative Gradle project root",
                DetachedModelCaptureFailure.INVALID_GRADLE_PROJECT_ROOT,
                listOf(valid.copy(gradleProjectRoot = "workspace/kast/module0")),
            ),
            ModuleCase(
                "outside Gradle project root",
                DetachedModelCaptureFailure.GRADLE_PROJECT_ROOT_OUTSIDE_WORKSPACE,
                listOf(valid.copy(gradleProjectRoot = "/workspace/other/module0")),
            ),
            ModuleCase(
                "invalid Gradle project identity",
                DetachedModelCaptureFailure.INVALID_GRADLE_PROJECT_IDENTITY,
                listOf(valid.copy(gradleProjectIdentity = " module0 ")),
            ),
            ModuleCase(
                "missing source roots",
                DetachedModelCaptureFailure.NO_SOURCE_ROOTS,
                listOf(valid.copy(sourceRoots = emptyList())),
            ),
            ModuleCase(
                "relative source root",
                DetachedModelCaptureFailure.INVALID_SOURCE_ROOT,
                listOf(
                    valid.copy(
                        sourceRoots = listOf(
                            DetachedSourceRootBoundary(
                                "src/main/kotlin",
                                DetachedSourceRootKind.PRODUCTION,
                                DetachedSourceRootProvenance.AUTHORED,
                            ),
                        ),
                    ),
                ),
            ),
            ModuleCase(
                "outside source root",
                DetachedModelCaptureFailure.SOURCE_ROOT_OUTSIDE_WORKSPACE,
                listOf(
                    valid.copy(
                        sourceRoots = listOf(
                            DetachedSourceRootBoundary(
                                "/workspace/other/src",
                                DetachedSourceRootKind.PRODUCTION,
                                DetachedSourceRootProvenance.AUTHORED,
                            ),
                        ),
                    ),
                ),
            ),
            ModuleCase(
                "unknown source-root kind",
                DetachedModelCaptureFailure.INVALID_SOURCE_ROOT_KIND,
                listOf(
                    valid.copy(
                        sourceRoots = listOf(
                            DetachedSourceRootBoundary(
                                "${FIXTURE_ROOT.value}/src",
                                null,
                                DetachedSourceRootProvenance.AUTHORED,
                            ),
                        ),
                    ),
                ),
            ),
            ModuleCase(
                "unknown source-root provenance",
                DetachedModelCaptureFailure.INVALID_SOURCE_ROOT_PROVENANCE,
                listOf(
                    valid.copy(
                        sourceRoots = listOf(
                            DetachedSourceRootBoundary(
                                "${FIXTURE_ROOT.value}/src",
                                DetachedSourceRootKind.PRODUCTION,
                                null,
                            ),
                        ),
                    ),
                ),
            ),
            ModuleCase(
                "missing SDK",
                DetachedModelCaptureFailure.INVALID_SDK_IDENTITY,
                listOf(valid.copy(sdk = null)),
            ),
            ModuleCase(
                "unknown SDK version",
                DetachedModelCaptureFailure.INVALID_SDK_IDENTITY,
                listOf(valid.copy(sdk = detachedSdkBoundary(version = null))),
            ),
            ModuleCase(
                "missing classpath",
                DetachedModelCaptureFailure.NO_CLASSPATH,
                listOf(valid.copy(classpath = emptyList())),
            ),
            ModuleCase(
                "invalid classpath identity",
                DetachedModelCaptureFailure.INVALID_CLASSPATH_IDENTITY,
                listOf(valid.copy(classpath = listOf(classpath.copy(url = " ")))),
            ),
            ModuleCase(
                "duplicate classpath identity",
                DetachedModelCaptureFailure.DUPLICATE_CLASSPATH_IDENTITY,
                listOf(valid.copy(classpath = listOf(classpath, classpath))),
            ),
        )
    }

    private fun observationWithModule(module: DetachedModuleBoundary) =
        DetachedModelObservation.Observed(detachedModelBoundary(modules = listOf(module)))

    private fun assertFailures(
        observation: DetachedModelObservation,
        failure: DetachedModelCaptureFailure,
        message: String = failure.name,
    ) {
        val rejected = assertInstanceOf(
            DetachedModelCapture.Rejected::class.java,
            captureDetachedFixture(observation),
            message,
        )
        assertEquals(setOf(failure), rejected.failures, message)
    }

    private fun assertRejected(
        observation: DetachedModelObservation,
        message: String,
    ) {
        val capture = captureDetachedFixture(observation)
        assertTrue(capture is DetachedModelCapture.Rejected, message)
    }

    private data class ProjectCase(
        val name: String,
        val failure: DetachedModelCaptureFailure,
        val boundary: DetachedModelBoundary,
    )

    private data class ModuleCase(
        val name: String,
        val failure: DetachedModelCaptureFailure,
        val modules: List<DetachedModuleBoundary>,
    )
}

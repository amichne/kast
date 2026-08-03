package io.github.amichne.kast.api.contract.transformation.admission.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RepositoryOperationAdmissionTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `missing required resource bound is rejected before admission`() {
        val input = validInput(
            resourceBounds = validBounds().copy(timeLimitMillis = null),
        )

        val result = RepositoryOperationAdmission.admit(input)

        assertEquals(
            RepositoryOperationAdmission.Result.Rejected(
                RepositoryOperationRejection.ResourceBoundMissing(ResourceBoundKind.TIME),
            ),
            result,
        )
    }

    private fun validInput(
        resourceBounds: RawResourceBoundsInput = validBounds(),
    ): RawRepositoryOperationInput {
        val root = temporaryDirectory.resolve("repository")
        Files.createDirectories(root.resolve(".git"))
        Files.createDirectories(root.resolve("misleading-layout/src"))
        return RawRepositoryOperationInput(
            repository = RawRepositoryInput(
                requestedRoot = root.toString(),
                baseDirectory = temporaryDirectory.toString(),
            ),
            sourceState = RawSourceStateInput(
                revision = "0123456789abcdef0123456789abcdef01234567",
                inputs = emptyList(),
            ),
            buildOwnership = RawBuildOwnershipEvidence.Available(
                compilationUnits = listOf(
                    RawCompilationUnitInput(
                        ownerId = "unit-main",
                        moduleName = "application",
                        sourceSetName = "jvmMain",
                        variantName = "debug",
                        sourceRoots = setOf("misleading-layout/src"),
                        declarations = listOf(
                            RawOwnedDeclarationInput(
                                fullyQualifiedName = "sample.Application",
                                path = "misleading-layout/src/Application.kt",
                            ),
                        ),
                        families = setOf("sample.Application"),
                        compiler = RawCompilerInput(
                            compilerVersion = "2.3.0",
                            languageVersion = "2.3",
                            apiVersion = "2.3",
                            languageSettings = mapOf("progressive" to "true"),
                            resolvedDependencies = setOf("org.example:library:1.0"),
                            compilerPlugins = setOf("org.example:plugin:1.0"),
                        ),
                    ),
                ),
            ),
            scope = listOf(RawScopeSelector.Module("application")),
            resourceBounds = resourceBounds,
        )
    }

    private fun validBounds(): RawResourceBoundsInput = RawResourceBoundsInput(
        timeLimitMillis = 30_000,
        memoryLimitBytes = 512L * 1024L * 1024L,
        traversalDepthLimit = 32,
        pathLimit = 10_000,
        resultLimit = 1_000,
    )
}

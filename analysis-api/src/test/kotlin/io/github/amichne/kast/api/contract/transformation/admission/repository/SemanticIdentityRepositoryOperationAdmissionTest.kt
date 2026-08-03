package io.github.amichne.kast.api.contract.transformation.admission.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class SemanticIdentityRepositoryOperationAdmissionTest : RepositoryOperationAdmissionFixture() {
    @Test
    fun `each semantic compiler input changes repository state identity`() {
        val root = repositoryRoot()
        val unit = validCompilationUnit()
        val compiler = requireNotNull(unit.compiler)
        val baseline = admitted(validInput(root, buildOwnership = available(unit))).repositoryState.identity
        val variants = listOf(
            unit.copy(compiler = compiler.copy(compilerVersion = "2.4.0")),
            unit.copy(compiler = compiler.copy(languageVersion = "2.2")),
            unit.copy(compiler = compiler.copy(apiVersion = "2.2")),
            unit.copy(compiler = compiler.copy(languageSettings = mapOf("progressive" to "false"))),
            unit.copy(
                compiler = compiler.copy(
                    compilerImplementation = requireNotNull(compiler.compilerImplementation).copy(
                        contentSha256 = DIGEST_B,
                    ),
                ),
            ),
            unit.copy(
                compiler = compiler.copy(
                    toolchain = requireNotNull(compiler.toolchain).copy(contentSha256 = DIGEST_B),
                ),
            ),
            unit.copy(
                compiler = compiler.copy(
                    compilerOptions = listOf(compilerOption("-progressive"), compilerOption("-Xcontext-receivers")),
                ),
            ),
            unit.copy(
                compiler = compiler.copy(
                    resolvedDependencies = listOf(
                        resolvedArtifact(
                            component = "org.example:library:1.0",
                            contentSha256 = DIGEST_B,
                        ),
                    ),
                ),
            ),
            unit.copy(
                compiler = compiler.copy(
                    compilerPlugins = listOf(compilerPlugin(artifactSha256 = DIGEST_B)),
                ),
            ),
            unit.copy(
                compiler = compiler.copy(
                    compilerPlugins = listOf(
                        compilerPlugin(options = listOf("enabled=false")),
                    ),
                ),
            ),
            unit.copy(moduleIdentity = ":included:application"),
            unit.copy(sourceSetName = "commonMain"),
            unit.copy(variantName = "release"),
            unit.copy(
                sourceRoots = setOf("alternate/src"),
                generatedSourceRoots = setOf("alternate/src/generated"),
                declarations = listOf(
                    RawOwnedDeclarationInput("sample.Application", "alternate/src/Application.kt"),
                ),
            ),
            unit.copy(
                declarations = listOf(
                    RawOwnedDeclarationInput("sample.Alternate", "misleading-layout/src/Alternate.kt"),
                ),
            ),
            unit.copy(families = setOf("sample.Alternate")),
            unit.copy(generatedSourceRoots = setOf("misleading-layout/src/other-generated")),
        )

        variants.forEach { changedUnit ->
            val changed = admitted(
                validInput(root, buildOwnership = available(changedUnit)),
            ).repositoryState.identity
            assertNotEquals(baseline, changed, "semantic input must change repository state identity")
        }
    }

    @Test
    fun `repository identity is permutation invariant across complete evidence`() {
        val root = repositoryRoot()
        val declarations = listOf(
            RawOwnedDeclarationInput("sample.Duplicate", "misleading-layout/src/A.kt"),
            RawOwnedDeclarationInput("sample.Duplicate", "misleading-layout/src/B.kt"),
        )
        val firstUnit = validCompilationUnit().copy(
            declarations = declarations,
            families = linkedSetOf("sample.Second", "sample.First"),
            compiler = validCompiler().copy(
                languageSettings = linkedMapOf("second" to "2", "first" to "1"),
                resolvedDependencies = listOf(
                    resolvedArtifact("dependency:b", contentSha256 = DIGEST_B),
                    resolvedArtifact("dependency:a", contentSha256 = DIGEST_A),
                ),
                compilerPlugins = listOf(
                    compilerPlugin("plugin:b", artifactSha256 = DIGEST_B),
                    compilerPlugin("plugin:a", artifactSha256 = DIGEST_A),
                ),
            ),
        )
        val secondUnit = validCompilationUnit(
            ownerId = "unit-test",
            sourceSetName = "jvmTest",
            compiler = requireNotNull(firstUnit.compiler),
        ).copy(
            declarations = declarations,
            families = firstUnit.families,
        )
        val firstCompiler = requireNotNull(firstUnit.compiler)
        val firstSourcePath = root.resolve("src/A.kt")
        val secondSourcePath = root.resolve("src/B.kt")
        Files.createDirectories(firstSourcePath.parent)
        Files.writeString(firstSourcePath, "class A")
        Files.writeString(secondSourcePath, "class B")
        val sourceInputs = listOf(
            includedSource("src/B.kt", RawSourceInputKind.UNTRACKED, sha256(secondSourcePath)),
            includedSource("src/A.kt", RawSourceInputKind.UNTRACKED, sha256(firstSourcePath)),
        )
        val forward = admitted(
            validInput(
                root = root,
                sourceState = validSourceState(inputs = sourceInputs, root = root),
                buildOwnership = available(firstUnit, secondUnit),
            ),
        )
        val reverseFirst = firstUnit.copy(
            declarations = declarations.reversed(),
            families = firstUnit.families?.reversed()?.toSet(),
            compiler = firstCompiler.copy(
                languageSettings = firstCompiler.languageSettings?.entries
                    ?.reversed()
                    ?.associate { entry -> entry.key to entry.value },
                resolvedDependencies = firstCompiler.resolvedDependencies,
                compilerPlugins = firstCompiler.compilerPlugins,
            ),
        )
        val reverseSecond = secondUnit.copy(
            declarations = declarations.reversed(),
            families = secondUnit.families?.reversed()?.toSet(),
        )
        val reversed = admitted(
            validInput(
                root = root,
                sourceState = validSourceState(inputs = sourceInputs.reversed(), root = root),
                buildOwnership = available(reverseSecond, reverseFirst),
            ),
        )

        assertEquals(forward.repositoryState.identity, reversed.repositoryState.identity)
    }

    @Test
    fun `repository identity preserves semantic field boundaries`() {
        val root = repositoryRoot()
        val first = validCompilationUnit().copy(
            compiler = validCompiler().copy(languageSettings = mapOf("a=b" to "c")),
        )
        val second = validCompilationUnit().copy(
            compiler = validCompiler().copy(languageSettings = mapOf("a" to "b=c")),
        )

        val firstIdentity = admitted(validInput(root, buildOwnership = available(first))).repositoryState.identity
        val secondIdentity = admitted(validInput(root, buildOwnership = available(second))).repositoryState.identity

        assertNotEquals(firstIdentity, secondIdentity)
    }

    @Test
    fun `resolved dependency classpath order changes semantic configuration`() {
        val root = repositoryRoot()
        val forward = validCompilationUnit().copy(
            compiler = validCompiler().copy(
                resolvedDependencies = listOf(
                    resolvedArtifact("classpath:first", contentSha256 = DIGEST_A),
                    resolvedArtifact("classpath:second", contentSha256 = DIGEST_B),
                ),
            ),
        )
        val reversed = validCompilationUnit().copy(
            compiler = validCompiler().copy(
                resolvedDependencies = listOf(
                    resolvedArtifact("classpath:second", contentSha256 = DIGEST_B),
                    resolvedArtifact("classpath:first", contentSha256 = DIGEST_A),
                ),
            ),
        )

        val forwardIdentity = admitted(validInput(root, buildOwnership = available(forward)))
            .repositoryState
            .identity
        val reversedIdentity = admitted(validInput(root, buildOwnership = available(reversed)))
            .repositoryState
            .identity

        assertNotEquals(forwardIdentity, reversedIdentity)
    }

    @Test
    fun `compiler plugin order changes semantic configuration`() {
        val root = repositoryRoot()
        val forward = validCompilationUnit().copy(
            compiler = validCompiler().copy(
                compilerPlugins = listOf(
                    compilerPlugin("plugin:first", artifactSha256 = DIGEST_A),
                    compilerPlugin("plugin:second", artifactSha256 = DIGEST_B),
                ),
            ),
        )
        val reversed = validCompilationUnit().copy(
            compiler = validCompiler().copy(
                compilerPlugins = listOf(
                    compilerPlugin("plugin:second", artifactSha256 = DIGEST_B),
                    compilerPlugin("plugin:first", artifactSha256 = DIGEST_A),
                ),
            ),
        )

        val forwardIdentity = admitted(validInput(root, buildOwnership = available(forward)))
            .repositoryState
            .identity
        val reversedIdentity = admitted(validInput(root, buildOwnership = available(reversed)))
            .repositoryState
            .identity

        assertNotEquals(forwardIdentity, reversedIdentity)
    }

    @Test
    fun `compiler and plugin option order changes semantic configuration`() {
        val root = repositoryRoot()
        val compilerOptionsForward = validCompilationUnit().copy(
            compiler = validCompiler().copy(
                compilerOptions = listOf(compilerOption("-first"), compilerOption("-second")),
            ),
        )
        val compilerOptionsReversed = validCompilationUnit().copy(
            compiler = validCompiler().copy(
                compilerOptions = listOf(compilerOption("-second"), compilerOption("-first")),
            ),
        )
        val pluginOptionsForward = validCompilationUnit().copy(
            compiler = validCompiler().copy(
                compilerPlugins = listOf(compilerPlugin(options = listOf("first=1", "second=2"))),
            ),
        )
        val pluginOptionsReversed = validCompilationUnit().copy(
            compiler = validCompiler().copy(
                compilerPlugins = listOf(compilerPlugin(options = listOf("second=2", "first=1"))),
            ),
        )

        assertNotEquals(
            admitted(validInput(root, buildOwnership = available(compilerOptionsForward))).repositoryState.identity,
            admitted(validInput(root, buildOwnership = available(compilerOptionsReversed))).repositoryState.identity,
        )
        assertNotEquals(
            admitted(validInput(root, buildOwnership = available(pluginOptionsForward))).repositoryState.identity,
            admitted(validInput(root, buildOwnership = available(pluginOptionsReversed))).repositoryState.identity,
        )
    }
}

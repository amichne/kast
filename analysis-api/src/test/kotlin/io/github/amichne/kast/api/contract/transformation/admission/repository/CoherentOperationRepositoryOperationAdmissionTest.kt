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

class CoherentOperationRepositoryOperationAdmissionTest : RepositoryOperationAdmissionFixture() {
    @Test
    fun `module scope distinguishes build-qualified authority from ambiguous display names`() {
        val first = validCompilationUnit(
            ownerId = "included-one-main",
            moduleIdentity = ":included-one:application",
        )
        val second = validCompilationUnit(
            ownerId = "included-two-main",
            moduleIdentity = ":included-two:application",
        )
        val ambiguous = RawScopeSelector.Module("application")
        assertRejected(
            RepositoryOperationRejection.AmbiguousScope(
                selector = ambiguous,
                candidates = listOf(":included-one:application", ":included-two:application"),
            ),
            validInput(
                buildOwnership = available(first, second),
                scope = listOf(ambiguous),
            ),
        )

        val exact = admitted(
            validInput(
                buildOwnership = available(first, second),
                scope = listOf(RawScopeSelector.Module(":included-one:application")),
            ),
        )
        assertEquals(
            listOf("included-one-main"),
            exact.resolvedScope.compilationUnits.map { unit -> unit.id.value },
        )
    }

    @Test
    fun `one module identity cannot carry conflicting display names`() {
        val first = validCompilationUnit()
        val second = validCompilationUnit(
            ownerId = "unit-test",
            moduleName = "other-application",
            sourceSetName = "jvmTest",
        )

        assertRejected(
            RepositoryOperationRejection.SemanticConfigurationIncomplete(
                SemanticConfigurationField.MODULE_IDENTITY,
                ":application",
            ),
            validInput(
                buildOwnership = available(first, second),
                scope = listOf(RawScopeSelector.Module(":application")),
            ),
        )
    }

    @Test
    fun `module selectors reject collisions between identity and display namespaces`() {
        val identityMatch = validCompilationUnit(
            ownerId = "identity-main",
            moduleIdentity = "application",
            moduleName = "identity-module",
        )
        val displayMatch = validCompilationUnit(
            ownerId = "display-main",
            moduleIdentity = ":display-module",
            moduleName = "application",
        )
        val selector = RawScopeSelector.Module("application")

        assertRejected(
            RepositoryOperationRejection.AmbiguousScope(
                selector = selector,
                candidates = listOf(":display-module", "application"),
            ),
            validInput(
                buildOwnership = available(identityMatch, displayMatch),
                scope = listOf(selector),
            ),
        )
    }

    @Test
    fun `Gradle ownership outranks misleading directory layout`() {
        val operation = admitted(
            validInput(scope = listOf(RawScopeSelector.Declaration("sample.Application"))),
        )

        val owner = operation.resolvedScope.compilationUnits.single()
        assertEquals("application", owner.moduleName.value)
        assertEquals("jvmMain", owner.sourceSetName.value)
        assertEquals("misleading-layout/src/Application.kt", owner.declarations.single().path.value)
    }

    @Test
    fun `incompatible configurations are rejected before analysis`() {
        val first = validCompilationUnit()
        val second = validCompilationUnit(
            ownerId = "unit-test",
            sourceSetName = "jvmTest",
            compiler = validCompiler().copy(compilerVersion = "2.4.0"),
        )

        assertRejected(
            RepositoryOperationRejection.IncompatibleSemanticConfigurations(
                listOf("unit-main", "unit-test"),
            ),
            validInput(
                buildOwnership = available(first, second),
                scope = listOf(RawScopeSelector.Module("application")),
            ),
        )

        val artifactConflict = validCompilationUnit(
            ownerId = "unit-artifact-conflict",
            sourceSetName = "jvmTest",
            compiler = validCompiler().copy(
                resolvedDependencies = listOf(resolvedArtifact(contentSha256 = DIGEST_B)),
            ),
        )
        assertRejected(
            RepositoryOperationRejection.IncompatibleSemanticConfigurations(
                listOf("unit-artifact-conflict", "unit-main"),
            ),
            validInput(
                buildOwnership = available(first, artifactConflict),
                scope = listOf(RawScopeSelector.Module("application")),
            ),
        )

        val pluginConflict = validCompilationUnit(
            ownerId = "unit-plugin-conflict",
            sourceSetName = "jvmTest",
            compiler = validCompiler().copy(
                compilerPlugins = listOf(compilerPlugin(options = listOf("enabled=false"))),
            ),
        )
        assertRejected(
            RepositoryOperationRejection.IncompatibleSemanticConfigurations(
                listOf("unit-main", "unit-plugin-conflict"),
            ),
            validInput(
                buildOwnership = available(first, pluginConflict),
                scope = listOf(RawScopeSelector.Module("application")),
            ),
        )

        val toolchainConflict = validCompilationUnit(
            ownerId = "unit-toolchain-conflict",
            sourceSetName = "jvmTest",
            compiler = validCompiler().let { compiler ->
                compiler.copy(
                    toolchain = requireNotNull(compiler.toolchain).copy(contentSha256 = DIGEST_B),
                )
            },
        )
        assertRejected(
            RepositoryOperationRejection.IncompatibleSemanticConfigurations(
                listOf("unit-main", "unit-toolchain-conflict"),
            ),
            validInput(
                buildOwnership = available(first, toolchainConflict),
                scope = listOf(RawScopeSelector.Module("application")),
            ),
        )

        val dependencyConflict = validCompilationUnit(
            ownerId = "unit-dependency-conflict",
            sourceSetName = "jvmTest",
            compiler = validCompiler().copy(
                resolvedDependencies = listOf(
                    resolvedArtifact("org.example:library:2.0", contentSha256 = DIGEST_B),
                ),
            ),
        )
        assertRejected(
            RepositoryOperationRejection.IncompatibleSemanticConfigurations(
                listOf("unit-dependency-conflict", "unit-main"),
            ),
            validInput(
                buildOwnership = available(first, dependencyConflict),
                scope = listOf(RawScopeSelector.Module("application")),
            ),
        )
    }

    @Test
    fun `coherent multi-unit module scope retains every compilation unit`() {
        val first = validCompilationUnit()
        val second = validCompilationUnit(
            ownerId = "unit-test",
            sourceSetName = "jvmTest",
        )

        val operation = admitted(
            validInput(
                buildOwnership = available(first, second),
                scope = listOf(RawScopeSelector.Module("application")),
            ),
        )

        assertEquals(
            listOf("unit-main", "unit-test"),
            operation.resolvedScope.compilationUnits.map { unit -> unit.id.value },
        )
    }

    @Test
    fun `valid input exposes only trusted exact admission values`() {
        val root = repositoryRoot()
        val operation = admitted(validInput(root))

        assertEquals(root.toRealPath().toString(), operation.repositoryState.canonicalRoot.value)
        assertEquals(git(root, "rev-parse", "HEAD"), operation.repositoryState.sourceState.revision.value)
        assertEquals("application", operation.resolvedScope.compilationUnits.single().moduleName.value)
        assertEquals(
            "2.3.0",
            operation.repositoryState.semanticConfiguration.compilerVersion.value,
        )
        assertEquals(
            DIGEST_A,
            operation.repositoryState.semanticConfiguration.compilerImplementation.contentDigest.value,
        )
        assertEquals("jvm", operation.repositoryState.semanticConfiguration.toolchain.targetPlatform.value)
        assertEquals(30_000, operation.resourceBounds.timeLimitMillis.value)
        assertEquals(512L * 1024L * 1024L, operation.resourceBounds.memoryLimitBytes.value)
        assertEquals(32, operation.resourceBounds.traversalDepthLimit.value)
        assertEquals(10_000, operation.resourceBounds.pathLimit.value)
        assertEquals(1_000, operation.resourceBounds.resultLimit.value)
        assertTrue(operation.repositoryState.identity.value.isNotBlank())
    }

    @Test
    fun `trusted aggregate construction is owned by admission factories`() {
        val trustedAggregates = listOf(
            AdmittedRepositoryOperation::class.java,
            AdmittedRepositoryState::class.java,
            ExactSourceState::class.java,
            ExactSourceInput::class.java,
            ResolvedRepositoryScope::class.java,
            AdmittedCompilationUnit::class.java,
            AdmittedDeclaration::class.java,
            SourceSetRelationship::class.java,
            ResolvedBuildArtifact::class.java,
            CompilerToolchain::class.java,
            CompilerPluginInvocation::class.java,
            CoherentSemanticConfiguration::class.java,
            EstablishedResourceBounds::class.java,
        )

        trustedAggregates.forEach { trustedType ->
            assertTrue(
                trustedType.declaredConstructors
                    .filterNot { constructor -> constructor.isSynthetic }
                    .all { constructor -> Modifier.isPrivate(constructor.modifiers) },
                "${trustedType.simpleName} exposes a constructor that bypasses admission",
            )
        }
    }

    @Test
    fun `trusted evidence collections cannot change after identity creation`() {
        val root = repositoryRoot()
        val compiler = validCompiler().copy(
            languageSettings = linkedMapOf("first" to "1", "second" to "2"),
            compilerOptions = listOf(compilerOption("-first"), compilerOption("-second")),
            resolvedDependencies = listOf(
                resolvedArtifact("dependency:a", contentSha256 = DIGEST_A),
                resolvedArtifact("dependency:b", contentSha256 = DIGEST_B),
            ),
            compilerPlugins = listOf(
                compilerPlugin("plugin:a", artifactSha256 = DIGEST_A),
                compilerPlugin("plugin:b", artifactSha256 = DIGEST_B),
            ),
        )
        val declarations = listOf(
            RawOwnedDeclarationInput("sample.First", "misleading-layout/src/First.kt"),
            RawOwnedDeclarationInput("sample.Second", "misleading-layout/src/Second.kt"),
        )
        val first = validCompilationUnit(compiler = compiler).copy(
            declarations = declarations,
            families = linkedSetOf("sample.First", "sample.Second"),
        )
        val second = validCompilationUnit(
            ownerId = "unit-test",
            sourceSetName = "jvmTest",
            compiler = compiler,
        ).copy(
            declarations = declarations,
            families = first.families,
        )
        val firstSourcePath = root.resolve("src/First.kt")
        val secondSourcePath = root.resolve("src/Second.kt")
        Files.createDirectories(firstSourcePath.parent)
        Files.writeString(firstSourcePath, "class First")
        Files.writeString(secondSourcePath, "class Second")
        val operation = admitted(
            validInput(
                root = root,
                sourceState = validSourceState(
                    inputs = listOf(
                        includedSource(
                            "src/First.kt",
                            RawSourceInputKind.UNTRACKED,
                            sha256(firstSourcePath),
                        ),
                        includedSource(
                            "src/Second.kt",
                            RawSourceInputKind.UNTRACKED,
                            sha256(secondSourcePath),
                        ),
                    ),
                    root = root,
                ),
                buildOwnership = available(first, second),
            ),
        )
        val unit = operation.resolvedScope.compilationUnits.first()
        val configuration = unit.semanticConfiguration
        val mutationAttempts = listOf<() -> Unit>(
            { (operation.repositoryState.compilationUnits as MutableList).clear() },
            { (operation.repositoryState.sourceState.inputs as MutableList).clear() },
            { (operation.resolvedScope.compilationUnits as MutableList).clear() },
            { (unit.sourceRoots as MutableList).clear() },
            { (unit.declarations as MutableList).clear() },
            { (unit.families as MutableSet).clear() },
            { (unit.generatedSourceRoots as MutableList).clear() },
            { (unit.sourceSetRelationships as MutableSet).clear() },
            { (configuration.languageSettings as MutableMap).clear() },
            { (configuration.compilerOptions as MutableList).clear() },
            { (configuration.resolvedDependencies as MutableList).clear() },
            { (configuration.compilerPlugins as MutableList).clear() },
            { (configuration.compilerPlugins.first().classpath as MutableList).clear() },
            { (configuration.compilerPlugins.first().options as MutableList).clear() },
        )

        mutationAttempts.forEach { mutate ->
            assertThrows(UnsupportedOperationException::class.java, mutate)
        }
    }

    @Test
    fun `typed rejection evidence cannot be changed by its caller`() {
        val candidateInput = mutableListOf("unit-main", "unit-other")
        val unitInput = mutableListOf("unit-main", "unit-test")
        val ambiguous = RepositoryOperationRejection.AmbiguousScope(
            selector = RawScopeSelector.Declaration("sample.Application"),
            candidates = candidateInput,
        )
        val incompatible = RepositoryOperationRejection.IncompatibleSemanticConfigurations(unitInput)

        candidateInput.clear()
        unitInput.clear()

        assertEquals(listOf("unit-main", "unit-other"), ambiguous.candidates)
        assertEquals(listOf("unit-main", "unit-test"), incompatible.compilationUnits)
        assertThrows(UnsupportedOperationException::class.java) {
            (ambiguous.candidates as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (incompatible.compilationUnits as MutableList).clear()
        }
    }
}

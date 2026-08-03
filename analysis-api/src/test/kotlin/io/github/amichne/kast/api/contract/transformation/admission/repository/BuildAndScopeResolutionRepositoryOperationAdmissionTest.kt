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

class BuildAndScopeResolutionRepositoryOperationAdmissionTest : RepositoryOperationAdmissionFixture() {
    @Test
    fun `incomplete build and compiler evidence is rejected`() {
        val unit = validCompilationUnit()
        val compiler = requireNotNull(unit.compiler)
        assertRejected(
            RepositoryOperationRejection.BuildOwnershipEvidenceUnavailable,
            validInput(buildOwnership = RawBuildOwnershipEvidence.Unavailable),
        )
        listOf<List<RawCompilationUnitInput>?>(null, emptyList()).forEach { units ->
            assertRejected(
                RepositoryOperationRejection.SemanticConfigurationIncomplete(
                    SemanticConfigurationField.COMPILATION_UNITS,
                    null,
                ),
                validInput(buildOwnership = RawBuildOwnershipEvidence.Available(units)),
            )
        }
        val cases = listOf(
            Triple(SemanticConfigurationField.OWNER_ID, null, unit.copy(ownerId = null)),
            Triple(
                SemanticConfigurationField.MODULE_IDENTITY,
                "unit-main",
                unit.copy(moduleIdentity = null),
            ),
            Triple(SemanticConfigurationField.MODULE, "unit-main", unit.copy(moduleName = null)),
            Triple(SemanticConfigurationField.SOURCE_SET, "unit-main", unit.copy(sourceSetName = null)),
            Triple(SemanticConfigurationField.VARIANT, "unit-main", unit.copy(variantName = null)),
            Triple(SemanticConfigurationField.SOURCE_ROOTS, "unit-main", unit.copy(sourceRoots = null)),
            Triple(SemanticConfigurationField.DECLARATIONS, "unit-main", unit.copy(declarations = null)),
            Triple(SemanticConfigurationField.FAMILIES, "unit-main", unit.copy(families = null)),
            Triple(
                SemanticConfigurationField.GENERATED_SOURCE_ROOTS,
                "unit-main",
                unit.copy(generatedSourceRoots = null),
            ),
            Triple(
                SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS,
                "unit-main",
                unit.copy(sourceSetRelationships = null),
            ),
            Triple(SemanticConfigurationField.COMPILER, "unit-main", unit.copy(compiler = null)),
            Triple(
                SemanticConfigurationField.COMPILER_VERSION,
                "unit-main",
                unit.copy(compiler = compiler.copy(compilerVersion = null)),
            ),
            Triple(
                SemanticConfigurationField.LANGUAGE_VERSION,
                "unit-main",
                unit.copy(compiler = compiler.copy(languageVersion = null)),
            ),
            Triple(
                SemanticConfigurationField.API_VERSION,
                "unit-main",
                unit.copy(compiler = compiler.copy(apiVersion = null)),
            ),
            Triple(
                SemanticConfigurationField.LANGUAGE_SETTINGS,
                "unit-main",
                unit.copy(compiler = compiler.copy(languageSettings = null)),
            ),
            Triple(
                SemanticConfigurationField.COMPILER_IMPLEMENTATION,
                "unit-main",
                unit.copy(compiler = compiler.copy(compilerImplementation = null)),
            ),
            Triple(
                SemanticConfigurationField.TOOLCHAIN,
                "unit-main",
                unit.copy(compiler = compiler.copy(toolchain = null)),
            ),
            Triple(
                SemanticConfigurationField.COMPILER_OPTIONS,
                "unit-main",
                unit.copy(compiler = compiler.copy(compilerOptions = null)),
            ),
            Triple(
                SemanticConfigurationField.DEPENDENCIES,
                "unit-main",
                unit.copy(compiler = compiler.copy(resolvedDependencies = null)),
            ),
            Triple(
                SemanticConfigurationField.COMPILER_PLUGINS,
                "unit-main",
                unit.copy(compiler = compiler.copy(compilerPlugins = null)),
            ),
            Triple(
                SemanticConfigurationField.COMPILER_IMPLEMENTATION,
                "unit-main",
                unit.copy(
                    compiler = compiler.copy(
                        compilerImplementation = requireNotNull(compiler.compilerImplementation).copy(
                            contentSha256 = null,
                        ),
                    ),
                ),
            ),
            Triple(
                SemanticConfigurationField.TOOLCHAIN,
                "unit-main",
                unit.copy(
                    compiler = compiler.copy(
                        toolchain = requireNotNull(compiler.toolchain).copy(contentSha256 = null),
                    ),
                ),
            ),
            Triple(
                SemanticConfigurationField.DEPENDENCIES,
                "unit-main",
                unit.copy(
                    compiler = compiler.copy(
                        resolvedDependencies = listOf(resolvedArtifact(contentSha256 = null)),
                    ),
                ),
            ),
            Triple(
                SemanticConfigurationField.COMPILER_PLUGINS,
                "unit-main",
                unit.copy(
                    compiler = compiler.copy(
                        compilerPlugins = listOf(compilerPlugin().copy(options = null)),
                    ),
                ),
            ),
        )
        cases.forEach { (field, owner, incompleteUnit) ->
            assertRejected(
                RepositoryOperationRejection.SemanticConfigurationIncomplete(field, owner),
                validInput(buildOwnership = available(incompleteUnit)),
            )
        }
    }

    @Test
    fun `source set resolution relationships are exact and build qualified`() {
        val root = repositoryRoot()
        val main = validCompilationUnit()
        val common = validCompilationUnit(
            ownerId = "unit-common",
            sourceSetName = "commonMain",
        )
        val dependsOn = main.copy(
            sourceSetRelationships = setOf(
                sourceSetRelationship(SourceSetRelationshipKind.DEPENDS_ON, "unit-common"),
            ),
        )
        val friend = main.copy(
            sourceSetRelationships = setOf(
                sourceSetRelationship(SourceSetRelationshipKind.FRIEND, "unit-common"),
            ),
        )
        val scope = listOf(RawScopeSelector.SourceSet(":application", "jvmMain"))
        val baselineIdentity = admitted(
            validInput(root, buildOwnership = available(main, common), scope = scope),
        ).repositoryState.identity
        val dependsOnIdentity = admitted(
            validInput(root, buildOwnership = available(dependsOn, common), scope = scope),
        ).repositoryState.identity
        val friendIdentity = admitted(
            validInput(root, buildOwnership = available(friend, common), scope = scope),
        ).repositoryState.identity

        assertNotEquals(baselineIdentity, dependsOnIdentity)
        assertNotEquals(dependsOnIdentity, friendIdentity)
        assertRejected(
            RepositoryOperationRejection.SemanticConfigurationIncomplete(
                SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS,
                "unit-main",
            ),
            validInput(
                root,
                buildOwnership = available(
                    main.copy(
                        sourceSetRelationships = setOf(
                            sourceSetRelationship(SourceSetRelationshipKind.DEPENDS_ON, "unit-missing"),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `scope includes the compatible transitive source set relationship closure`() {
        val main = validCompilationUnit(
            sourceSetRelationships = setOf(
                sourceSetRelationship(SourceSetRelationshipKind.DEPENDS_ON, "unit-common"),
            ),
        )
        val common = validCompilationUnit(
            ownerId = "unit-common",
            sourceSetName = "commonMain",
            sourceSetRelationships = setOf(
                sourceSetRelationship(SourceSetRelationshipKind.FRIEND, "unit-support"),
            ),
        )
        val support = validCompilationUnit(
            ownerId = "unit-support",
            sourceSetName = "supportMain",
        )

        val operation = admitted(
            validInput(
                buildOwnership = available(main, common, support),
                scope = listOf(RawScopeSelector.SourceSet(":application", "jvmMain")),
            ),
        )

        assertEquals(
            listOf("unit-common", "unit-main", "unit-support"),
            operation.resolvedScope.compilationUnits.map { unit -> unit.id.value },
        )
    }

    @Test
    fun `source set relationship cycles and incompatible closure are rejected`() {
        val main = validCompilationUnit(
            sourceSetRelationships = setOf(
                sourceSetRelationship(SourceSetRelationshipKind.DEPENDS_ON, "unit-common"),
            ),
        )
        val cyclicCommon = validCompilationUnit(
            ownerId = "unit-common",
            sourceSetName = "commonMain",
            sourceSetRelationships = setOf(
                sourceSetRelationship(SourceSetRelationshipKind.DEPENDS_ON, "unit-main"),
            ),
        )
        assertRejected(
            RepositoryOperationRejection.SemanticConfigurationIncomplete(
                SemanticConfigurationField.SOURCE_SET_RELATIONSHIPS,
                "unit-main",
            ),
            validInput(buildOwnership = available(main, cyclicCommon)),
        )

        val incompatibleCommon = validCompilationUnit(
            ownerId = "unit-common",
            sourceSetName = "commonMain",
            compiler = validCompiler().copy(compilerVersion = "2.4.0"),
        )
        assertRejected(
            RepositoryOperationRejection.IncompatibleSemanticConfigurations(
                listOf("unit-common", "unit-main"),
            ),
            validInput(
                buildOwnership = available(main, incompatibleCommon),
                scope = listOf(RawScopeSelector.SourceSet(":application", "jvmMain")),
            ),
        )
    }

    @Test
    fun `all scope selector kinds resolve mechanically or reject unknown input`() {
        val validSelectors = listOf(
            RawScopeSelector.Module("application"),
            RawScopeSelector.SourceSet("application", "jvmMain"),
            RawScopeSelector.Declaration("sample.Application"),
            RawScopeSelector.Family("sample.Application"),
        )
        validSelectors.forEach { selector ->
            val operation = admitted(validInput(scope = listOf(selector)))
            assertEquals(listOf("unit-main"), operation.resolvedScope.compilationUnits.map { it.id.value })
        }
        val unknownSelectors = listOf(
            RawScopeSelector.Module("missing"),
            RawScopeSelector.SourceSet("application", "missing"),
            RawScopeSelector.Declaration("missing"),
            RawScopeSelector.Family("missing"),
        )
        unknownSelectors.forEach { selector ->
            assertRejected(
                RepositoryOperationRejection.UnknownScope(selector),
                validInput(scope = listOf(selector)),
            )
        }
        assertRejected(
            RepositoryOperationRejection.ScopeResolvesToNothing,
            validInput(scope = emptyList()),
        )
    }

    @Test
    fun `singular scope selectors reject ambiguous build ownership`() {
        val first = validCompilationUnit()
        val second = validCompilationUnit(
            ownerId = "unit-other",
        )
        val selectors = listOf(
            RawScopeSelector.SourceSet("application", "jvmMain"),
            RawScopeSelector.Declaration("sample.Application"),
            RawScopeSelector.Family("sample.Application"),
        )
        selectors.forEach { selector ->
            val candidates = if (selector is RawScopeSelector.Declaration) {
                listOf(
                    "unit-main:misleading-layout/src/Application.kt",
                    "unit-other:misleading-layout/src/Application.kt",
                )
            } else {
                listOf("unit-main", "unit-other")
            }
            assertRejected(
                RepositoryOperationRejection.AmbiguousScope(
                    selector = selector,
                    candidates = candidates,
                ),
                validInput(
                    buildOwnership = available(first, second),
                    scope = listOf(selector),
                ),
            )
        }
    }

    @Test
    fun `a declaration selector rejects duplicate occurrences inside one compilation unit`() {
        val selector = RawScopeSelector.Declaration("sample.Duplicate")
        val unit = validCompilationUnit().copy(
            declarations = listOf(
                RawOwnedDeclarationInput(
                    "sample.Duplicate",
                    "misleading-layout/src/FirstDuplicate.kt",
                ),
                RawOwnedDeclarationInput(
                    "sample.Duplicate",
                    "misleading-layout/src/SecondDuplicate.kt",
                ),
            ),
        )

        assertRejected(
            RepositoryOperationRejection.AmbiguousScope(
                selector = selector,
                candidates = listOf(
                    "unit-main:misleading-layout/src/FirstDuplicate.kt",
                    "unit-main:misleading-layout/src/SecondDuplicate.kt",
                ),
            ),
            validInput(
                buildOwnership = available(unit),
                scope = listOf(selector),
            ),
        )
    }
}

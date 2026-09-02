package support.architecture.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import support.architecture.AcceptedArchitectureVerification
import support.architecture.ArchitectureObservationFailure
import support.architecture.ArchitectureObservationParser
import support.architecture.ArchitectureObservationValidation
import support.architecture.ArchitecturePolicyValidation
import support.architecture.ArchitectureVerificationAdmission
import support.architecture.KastArchitecturePolicy
import support.architecture.ModuleId
import support.architecture.ModuleLifecycle
import support.architecture.ModulePolicy
import support.architecture.ModulePolicyValidation
import support.architecture.ModulePolicyValidator
import support.architecture.ModuleRole
import support.architecture.ModuleRoleConventionObservation
import support.architecture.ModuleRoleConventionRequirement
import support.architecture.ObservedProjectGraph
import support.architecture.ProjectDependencyObservation
import support.architecture.ValidatedArchitecturePolicy
import support.architecture.ForbiddenEffect
import support.architecture.JvmClassName

class ModuleKnowledgeProjectionTest {
    @Test
    fun `projection binds validated architecture to scoped agent guides deterministically`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val verification = acceptedVerification(
            architecture,
            listOf(":symbol:intellij -> :symbol:contract"),
        )
        val input = RawModuleKnowledgeInput(
            productVersion = "1.2.3",
            sourceRevision = "a".repeat(40),
            architectureVerification = verification,
            agentGuides = listOf(
                RawAgentGuide("AGENTS.md", "# Root\n"),
                RawAgentGuide("symbol/AGENTS.md", "# Symbol\n"),
                RawAgentGuide("symbol/intellij/AGENTS.md", "# IntelliJ symbol\n"),
                RawAgentGuide(
                    "symbol/intellij/src/main/AGENTS.md",
                    "# IntelliJ symbol production\n",
                ),
            ),
        )

        val first = assertInstanceOf<ModuleKnowledgeProjectionResult.Complete>(
            ModuleKnowledgeProjection.render(input),
        ).encoded
        val second = assertInstanceOf<ModuleKnowledgeProjectionResult.Complete>(
            ModuleKnowledgeProjection.render(input),
        ).encoded
        val document = moduleKnowledgeJson.decodeFromString(
            ModuleKnowledgeDocument.serializer(),
            first,
        )

        assertEquals(first, second)
        assertTrue(first.endsWith("\n"))
        assertEquals(1, document.schemaVersion)
        assertEquals("1.2.3", document.productVersion)
        assertEquals("a".repeat(40), document.sourceRevision)
        assertEquals("ACCEPTED", document.architectureVerification.status)
        assertEquals(2, document.architecturePolicy.schemaVersion)
        assertEquals(41, document.architecturePolicy.modules.size)
        assertEquals(
            listOf(
                ObservedProjectDependencyDocument(
                    consumerProjectPath = ":symbol:intellij",
                    dependencyProjectPath = ":symbol:contract",
                ),
            ),
            document.observedProjectDependencies,
        )
        assertEquals(emptyList<ObservedProjectDependencyDocument>(), document.observedExportedProjectDependencies)
        assertEquals(4, document.agentGuides.size)
        assertTrue(document.agentGuides.all { it.sha256.matches(SHA256_IDENTITY) })

        val binding = document.moduleGuideBindings.single {
            it.projectPath == ":symbol:intellij"
        }
        assertEquals("symbol/intellij", binding.moduleDirectory)
        assertEquals(
            listOf("AGENTS.md", "symbol/AGENTS.md", "symbol/intellij/AGENTS.md"),
            binding.governingAgentGuidePaths,
        )
        assertEquals(
            listOf("symbol/intellij/src/main/AGENTS.md"),
            binding.descendantAgentGuidePaths,
        )
    }

    @Test
    fun `projection rejects missing root guide as finite data`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val verification = acceptedVerification(architecture)
        val result = assertInstanceOf<ModuleKnowledgeProjectionResult.Rejected>(
            ModuleKnowledgeProjection.render(
                RawModuleKnowledgeInput(
                    productVersion = "1.2.3",
                    sourceRevision = "b".repeat(40),
                    architectureVerification = verification,
                    agentGuides = listOf(RawAgentGuide("symbol/AGENTS.md", "# Symbol\n")),
                ),
            ),
        )

        assertTrue(ModuleKnowledgeFailure.MissingRootAgentGuide in result.failures)
    }

    @Test
    fun `accepted architecture evidence snapshots mutable graph collections`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val sourceModules = architecture.modules.toMutableMap()
        val sourceModuleOrder = architecture.moduleOrder.toMutableList()
        val ownedArchitecture = ValidatedArchitecturePolicy(sourceModules, sourceModuleOrder)
        val admitted = validGraph(
            ownedArchitecture,
            listOf(":symbol:intellij -> :symbol:contract"),
        )
        val modules = admitted.modules.toMutableSet()
        val dependencies = admitted.projectDependencies.toMutableSet()
        val exportedDependencies = admitted.exportedProjectDependencies.toMutableSet()
        val conventions = admitted.moduleRoleConventions.conventions.toMutableMap()
        val mutableGraph = ObservedProjectGraph(
            modules,
            dependencies,
            exportedDependencies,
            ModuleRoleConventionObservation.Collected(conventions),
        )
        val verification = assertInstanceOf<ArchitectureVerificationAdmission.Accepted>(
            AcceptedArchitectureVerification.establish(
                ownedArchitecture,
                mutableGraph,
                emptySet(),
            ),
        ).evidence
        val input = RawModuleKnowledgeInput(
            productVersion = "1.2.3",
            sourceRevision = "e".repeat(40),
            architectureVerification = verification,
            agentGuides = listOf(RawAgentGuide("AGENTS.md", "# Root\n")),
        )
        val before = assertInstanceOf<ModuleKnowledgeProjectionResult.Complete>(
            ModuleKnowledgeProjection.render(input),
        ).encoded

        sourceModules.clear()
        sourceModuleOrder.reverse()
        modules.clear()
        dependencies += ProjectDependencyObservation(ModuleId.KERNEL, ModuleId.CLI)
        exportedDependencies += ProjectDependencyObservation(
            ModuleId.SYMBOL_INTELLIJ,
            ModuleId.SYMBOL_CONTRACT,
        )
        conventions.clear()
        assertThrows<UnsupportedOperationException> {
            (ownedArchitecture.modules as MutableMap).clear()
        }
        assertThrows<UnsupportedOperationException> {
            (ownedArchitecture.moduleOrder as MutableList).reverse()
        }
        assertThrows<UnsupportedOperationException> {
            (
                ownedArchitecture.modules.getValue(ModuleId.SYMBOL_INTELLIJ)
                    .allowedProjectDependencies as MutableSet
            ).clear()
        }

        val after = assertInstanceOf<ModuleKnowledgeProjectionResult.Complete>(
            ModuleKnowledgeProjection.render(input),
        ).encoded
        assertEquals(before, after)
        val document = moduleKnowledgeJson.decodeFromString(
            ModuleKnowledgeDocument.serializer(),
            after,
        )
        assertEquals(
            listOf(
                ObservedProjectDependencyDocument(
                    consumerProjectPath = ":symbol:intellij",
                    dependencyProjectPath = ":symbol:contract",
                ),
            ),
            document.observedProjectDependencies,
        )
        assertEquals(emptyList<ObservedProjectDependencyDocument>(), document.observedExportedProjectDependencies)
    }

    @Test
    fun `validated module policy snapshots raw collection ownership`() {
        val dependencies = mutableSetOf<ModuleId>()
        val effects = mutableSetOf<ForbiddenEffect>()
        val scoped = mutableMapOf<ForbiddenEffect, Set<JvmClassName>>()
        val raw = ModulePolicy(
            id = ModuleId.KERNEL,
            lifecycle = ModuleLifecycle.ACTIVE,
            role = ModuleRole.KERNEL,
            allowedProjectDependencies = dependencies,
            allowedEffects = effects,
            allowedScopedEffectCallers = scoped,
        )
        val validated = assertInstanceOf<ModulePolicyValidation.Valid>(
            ModulePolicyValidator.validate(raw, mapOf(ModuleId.KERNEL to raw)),
        ).module

        dependencies += ModuleId.CLI
        effects += ForbiddenEffect.NETWORK_ACCESS
        scoped[ForbiddenEffect.FILESYSTEM_WRITE] = setOf(JvmClassName("attacker/Writer"))

        assertEquals(emptySet<ModuleId>(), validated.allowedProjectDependencies)
        assertEquals(emptySet<ForbiddenEffect>(), validated.allowedEffects)
        assertEquals(emptyMap<ForbiddenEffect, Set<JvmClassName>>(), validated.allowedScopedEffectCallers)
        assertThrows<UnsupportedOperationException> {
            (validated.allowedEffects as MutableSet).add(ForbiddenEffect.NETWORK_ACCESS)
        }
        listOf(
            validated.boundary.allowedDependencyRoles,
            validated.boundary.allowedDependencyCosts,
            validated.boundary.allowedExportedDependencyRoles,
            validated.boundary.allowedEffects,
            validated.boundary.allowedScopedEffects,
        ).forEach { boundaryValues ->
            assertThrows<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (boundaryValues as MutableSet<Any?>).clear()
            }
        }
    }

    @Test
    fun `unapproved typed graph cannot establish architecture verification evidence`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val graph = validGraph(
            architecture,
            listOf(":kernel -> :cli"),
        )
        val result = assertInstanceOf<ArchitectureVerificationAdmission.Rejected>(
            AcceptedArchitectureVerification.establish(architecture, graph, emptySet()),
        )

        assertTrue(result.violations.isNotEmpty())
    }

    @Test
    fun `canonical observation parser rejects malformed unknown and unobserved exports`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val result = assertInstanceOf<ArchitectureObservationValidation.Invalid>(
            ArchitectureObservationParser.parse(
                architecture,
                activeProjectPaths(architecture),
                listOf("malformed", ":kernel -> :unknown"),
                listOf(":symbol:intellij -> :symbol:contract"),
                roleConventions(architecture),
            ),
        )

        assertTrue(
            result.failures.any { it is ArchitectureObservationFailure.MalformedProjectDependency },
        )
        assertTrue(
            result.failures.any { it is ArchitectureObservationFailure.UnknownProjectPath },
        )
        assertTrue(
            result.failures.any {
                it is ArchitectureObservationFailure.ExportedProjectDependencyNotObserved
            },
        )
    }

    private fun acceptedVerification(
        architecture: ValidatedArchitecturePolicy,
        dependencies: List<String> = emptyList(),
    ): AcceptedArchitectureVerification {
        val graph = validGraph(architecture, dependencies)
        return assertInstanceOf<ArchitectureVerificationAdmission.Accepted>(
            AcceptedArchitectureVerification.establish(architecture, graph, emptySet()),
        ).evidence
    }

    private fun validGraph(
        architecture: ValidatedArchitecturePolicy,
        dependencies: List<String>,
    ) = assertInstanceOf<ArchitectureObservationValidation.Valid>(
        ArchitectureObservationParser.parse(
            architecture,
            activeProjectPaths(architecture),
            dependencies,
            emptyList(),
            roleConventions(architecture),
        ),
    ).graph

    private fun activeProjectPaths(architecture: ValidatedArchitecturePolicy): List<String> =
        architecture.modules.values
            .filter { module -> module.lifecycle == ModuleLifecycle.ACTIVE }
            .map { module -> module.id.projectPath }

    private fun roleConventions(architecture: ValidatedArchitecturePolicy): List<String> =
        architecture.modules.values
            .filter { module -> module.lifecycle == ModuleLifecycle.ACTIVE }
            .mapNotNull { module ->
                when (val requirement = module.conventionRequirement) {
                    ModuleRoleConventionRequirement.UnmarkedLegacy -> null
                    is ModuleRoleConventionRequirement.Required ->
                        module.id.projectPath + ArchitectureObservationParser.ROLE_SEPARATOR +
                            requirement.convention.pluginId
                }
            }

    private companion object {
        val SHA256_IDENTITY = Regex("sha256:[0-9a-f]{64}")
    }
}

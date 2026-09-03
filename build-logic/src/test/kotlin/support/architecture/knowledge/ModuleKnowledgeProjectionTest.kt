package support.architecture.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import support.architecture.ArchitecturePolicyValidation
import support.architecture.KastArchitecturePolicy

class ModuleKnowledgeProjectionTest {
    @Test
    fun `projection binds validated architecture to scoped agent guides deterministically`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val input = RawModuleKnowledgeInput(
            productVersion = "1.2.3",
            sourceRevision = "a".repeat(40),
            architectureVerificationReport = ACCEPTED_REPORT,
            observedProjectDependencies = listOf(
                ":symbol:intellij -> :symbol:contract",
            ),
            observedExportedProjectDependencies = emptyList(),
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
            ModuleKnowledgeProjection.render(architecture, input),
        ).encoded
        val second = assertInstanceOf<ModuleKnowledgeProjectionResult.Complete>(
            ModuleKnowledgeProjection.render(architecture, input),
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
        val result = assertInstanceOf<ModuleKnowledgeProjectionResult.Rejected>(
            ModuleKnowledgeProjection.render(
                architecture,
                RawModuleKnowledgeInput(
                    productVersion = "1.2.3",
                    sourceRevision = "b".repeat(40),
                    architectureVerificationReport = ACCEPTED_REPORT,
                    observedProjectDependencies = emptyList(),
                    observedExportedProjectDependencies = emptyList(),
                    agentGuides = listOf(RawAgentGuide("symbol/AGENTS.md", "# Symbol\n")),
                ),
            ),
        )

        assertTrue(ModuleKnowledgeFailure.MissingRootAgentGuide in result.failures)
    }

    @Test
    fun `projection rejects unaccepted architecture report as finite data`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val result = assertInstanceOf<ModuleKnowledgeProjectionResult.Rejected>(
            ModuleKnowledgeProjection.render(
                architecture,
                RawModuleKnowledgeInput(
                    productVersion = "1.2.3",
                    sourceRevision = "c".repeat(40),
                    architectureVerificationReport =
                        """{"schemaVersion":1,"status":"REJECTED","findings":[]}
                        """.trimIndent().encodeToByteArray(),
                    observedProjectDependencies = emptyList(),
                    observedExportedProjectDependencies = emptyList(),
                    agentGuides = listOf(RawAgentGuide("AGENTS.md", "# Root\n")),
                ),
            ),
        )

        assertTrue(
            result.failures.any { it is ModuleKnowledgeFailure.ArchitectureNotAccepted },
        )
    }

    @Test
    fun `projection rejects an unapproved observed dependency as finite data`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val result = assertInstanceOf<ModuleKnowledgeProjectionResult.Rejected>(
            ModuleKnowledgeProjection.render(
                architecture,
                RawModuleKnowledgeInput(
                    productVersion = "1.2.3",
                    sourceRevision = "d".repeat(40),
                    architectureVerificationReport = ACCEPTED_REPORT,
                    observedProjectDependencies = listOf(
                        ":kernel -> :cli",
                        "malformed",
                        ":kernel -> :unknown",
                    ),
                    observedExportedProjectDependencies = emptyList(),
                    agentGuides = listOf(RawAgentGuide("AGENTS.md", "# Root\n")),
                ),
            ),
        )

        assertTrue(
            result.failures.any { it is ModuleKnowledgeFailure.UnapprovedObservedProjectDependency },
        )
        assertTrue(
            result.failures.any { it is ModuleKnowledgeFailure.MalformedObservedProjectDependency },
        )
        assertTrue(
            result.failures.any {
                it is ModuleKnowledgeFailure.UnknownObservedProjectDependencyEndpoint
            },
        )
    }

    private companion object {
        val ACCEPTED_REPORT: ByteArray =
            """{"schemaVersion":1,"status":"ACCEPTED","findings":[]}
            """.trimIndent().encodeToByteArray()
        val SHA256_IDENTITY = Regex("sha256:[0-9a-f]{64}")
    }
}

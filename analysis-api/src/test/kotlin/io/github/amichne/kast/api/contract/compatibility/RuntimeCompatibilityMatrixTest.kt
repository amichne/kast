package io.github.amichne.kast.api.contract.compatibility

import io.github.amichne.kast.api.contract.MutationCapability
import io.github.amichne.kast.api.contract.ReadCapability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

class RuntimeCompatibilityMatrixTest {
    @Test
    fun `same-release facts are compatible only when their exact row is present`() {
        val matrix = RuntimeCompatibilityMatrix(setOf(supportedPair()))

        assertTrue(matrix.assess(facts()) is RuntimeCompatibilityOutcome.Compatible)

        val absentAdjacent = facts(cliVersion = CliImplementationVersion("0.12.9"))
        assertEquals(
            RuntimeCompatibilityOutcome.UpdateRequired(
                RuntimeCompatibilityUpdateRequirement.UnsupportedReleasePair(
                    pluginVersion = CURRENT_PLUGIN,
                    cliVersion = CliImplementationVersion("0.12.9"),
                    pluginRevision = CURRENT_RELEASE,
                    cliRevision = CURRENT_RELEASE,
                ),
            ),
            matrix.assess(absentAdjacent),
        )
    }

    @Test
    fun `an explicitly listed adjacent-release row is compatible`() {
        val adjacentCli = CliImplementationVersion("0.12.9")
        val adjacentCliRevision = ReleaseRevision("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        val matrix = RuntimeCompatibilityMatrix(
            setOf(
                supportedPair(),
                supportedPair(
                    cliVersion = adjacentCli,
                    cliRevision = adjacentCliRevision,
                ),
            ),
        )

        assertTrue(
            matrix.assess(
                facts(
                    cliVersion = adjacentCli,
                    cliRevision = adjacentCliRevision,
                ),
            ) is
                RuntimeCompatibilityOutcome.Compatible,
        )
    }

    @Test
    fun `unsupported protocol and metadata revisions fail closed with typed updates`() {
        val matrix = RuntimeCompatibilityMatrix(setOf(supportedPair()))

        assertEquals(
            RuntimeCompatibilityOutcome.UpdateRequired(
                RuntimeCompatibilityUpdateRequirement.UnsupportedProtocolRevision(
                    actual = ProtocolRevision(2),
                    supported = setOf(CURRENT_PROTOCOL),
                ),
            ),
            matrix.assess(facts(protocolRevision = ProtocolRevision(2))),
        )
        assertEquals(
            RuntimeCompatibilityOutcome.UpdateRequired(
                RuntimeCompatibilityUpdateRequirement.UnsupportedWorkspaceMetadataRevision(
                    actual = WorkspaceMetadataRevision(4),
                    supported = setOf(CURRENT_METADATA),
                ),
            ),
            matrix.assess(facts(workspaceMetadataRevision = WorkspaceMetadataRevision(4))),
        )
    }

    @Test
    fun `missing optional capability disables only its operation`() {
        val matrix = RuntimeCompatibilityMatrix(setOf(supportedPair()))
        val withoutOptionalRename = facts(mutationCapabilities = emptySet())

        assertEquals(
            RuntimeCompatibilityOutcome.MissingCapability(RENAME),
            matrix.assess(withoutOptionalRename, operationCapability = RENAME),
        )
        assertTrue(
            matrix.assess(withoutOptionalRename, operationCapability = DIAGNOSTICS) is
                RuntimeCompatibilityOutcome.Compatible,
        )
    }

    @Test
    fun `missing matrix-required capability requests an update`() {
        val matrix = RuntimeCompatibilityMatrix(setOf(supportedPair()))

        assertEquals(
            RuntimeCompatibilityOutcome.UpdateRequired(
                RuntimeCompatibilityUpdateRequirement.MissingRequiredCapability(DIAGNOSTICS),
            ),
            matrix.assess(facts(readCapabilities = emptySet())),
        )
    }

    @Test
    fun `same-version artifacts from different release revisions fail closed`() {
        val pluginRevision = ReleaseRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val cliRevision = ReleaseRevision("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        val matrix = RuntimeCompatibilityMatrix(
            setOf(
                supportedPair(
                    pluginRevision = pluginRevision,
                    cliRevision = pluginRevision,
                ),
            ),
        )

        assertEquals(
            RuntimeCompatibilityOutcome.UpdateRequired(
                RuntimeCompatibilityUpdateRequirement.MismatchedReleaseRevision(
                    pluginRevision = pluginRevision,
                    cliRevision = cliRevision,
                ),
            ),
            matrix.assess(
                facts(
                    pluginRevision = pluginRevision,
                    cliRevision = cliRevision,
                ),
            ),
        )
    }

    @Test
    fun `typed update requirements reject empty supported evidence`() {
        assertThrows<IllegalArgumentException> {
            RuntimeCompatibilityUpdateRequirement.UnsupportedProtocolRevision(
                actual = ProtocolRevision(2),
                supported = emptySet(),
            )
        }
        assertThrows<IllegalArgumentException> {
            RuntimeCompatibilityUpdateRequirement.UnsupportedWorkspaceMetadataRevision(
                actual = WorkspaceMetadataRevision(3),
                supported = emptySet(),
            )
        }
        assertThrows<IllegalArgumentException> {
            RuntimeCompatibilityUpdateRequirement.UnsupportedRuntimeIdentity(
                actual = CURRENT_RUNTIME,
                supported = emptySet(),
            )
        }
    }

    @Test
    fun `typed update requirements reject an actual value listed as supported`() {
        assertThrows<IllegalArgumentException> {
            RuntimeCompatibilityUpdateRequirement.UnsupportedProtocolRevision(
                actual = CURRENT_PROTOCOL,
                supported = setOf(CURRENT_PROTOCOL),
            )
        }
        assertThrows<IllegalArgumentException> {
            RuntimeCompatibilityUpdateRequirement.UnsupportedWorkspaceMetadataRevision(
                actual = CURRENT_METADATA,
                supported = setOf(CURRENT_METADATA),
            )
        }
        assertThrows<IllegalArgumentException> {
            RuntimeCompatibilityUpdateRequirement.UnsupportedRuntimeIdentity(
                actual = CURRENT_RUNTIME,
                supported = setOf(CURRENT_RUNTIME),
            )
        }
    }

    private fun supportedPair(
        cliVersion: CliImplementationVersion = CURRENT_CLI,
        pluginRevision: ReleaseRevision = CURRENT_RELEASE,
        cliRevision: ReleaseRevision = CURRENT_RELEASE,
    ): SupportedRuntimeCompatibilityPair = SupportedRuntimeCompatibilityPair(
        facts = facts(
            cliVersion = cliVersion,
            pluginRevision = pluginRevision,
            cliRevision = cliRevision,
        ),
        requiredCapabilities = setOf(DIAGNOSTICS),
    )

    private fun facts(
        pluginVersion: PluginImplementationVersion = CURRENT_PLUGIN,
        cliVersion: CliImplementationVersion = CURRENT_CLI,
        pluginRevision: ReleaseRevision = CURRENT_RELEASE,
        cliRevision: ReleaseRevision = CURRENT_RELEASE,
        protocolRevision: ProtocolRevision = CURRENT_PROTOCOL,
        workspaceMetadataRevision: WorkspaceMetadataRevision = CURRENT_METADATA,
        readCapabilities: Set<ReadCapability> = setOf(ReadCapability.DIAGNOSTICS),
        mutationCapabilities: Set<MutationCapability> = setOf(MutationCapability.RENAME),
        runtimeIdentity: RuntimeIdentity = CURRENT_RUNTIME,
    ): RuntimeCompatibilityFacts = RuntimeCompatibilityFacts(
        pluginVersion = pluginVersion,
        cliVersion = cliVersion,
        pluginRevision = pluginRevision,
        cliRevision = cliRevision,
        protocolRevision = protocolRevision,
        workspaceMetadataRevision = workspaceMetadataRevision,
        readCapabilities = readCapabilities,
        mutationCapabilities = mutationCapabilities,
        runtimeIdentity = runtimeIdentity,
    )

    private companion object {
        val CURRENT_PLUGIN = PluginImplementationVersion("0.13.0")
        val CURRENT_CLI = CliImplementationVersion("0.13.0")
        val CURRENT_RELEASE = ReleaseRevision("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val CURRENT_PROTOCOL = ProtocolRevision(1)
        val CURRENT_METADATA = WorkspaceMetadataRevision(3)
        val CURRENT_RUNTIME = RuntimeIdentity(
            implementationVersion = RuntimeImplementationVersion("0.13.0"),
            backendKind = RuntimeBackendKind.IDEA,
        )
        val DIAGNOSTICS = RuntimeCapability.Read(ReadCapability.DIAGNOSTICS)
        val RENAME = RuntimeCapability.Mutation(MutationCapability.RENAME)
    }
}

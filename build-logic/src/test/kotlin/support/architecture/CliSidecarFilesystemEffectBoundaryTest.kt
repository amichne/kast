package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class CliSidecarFilesystemEffectBoundaryTest {
    private val expectedCallers = setOf(
        JvmClassName("io/github/amichne/kast/cli/ApfsCoWIndexSeedCloner"),
        JvmClassName("io/github/amichne/kast/cli/FilesystemRootSidecarCacheLifecycle"),
        JvmClassName("io/github/amichne/kast/cli/FilesystemSidecarCachePreparer"),
        JvmClassName("io/github/amichne/kast/cli/IndexSeedFilesystemService"),
        JvmClassName("io/github/amichne/kast/cli/IndexSeedFilesystemServiceKt"),
        JvmClassName("io/github/amichne/kast/cli/InstalledSidecarRuntimeDemandKt"),
        JvmClassName("io/github/amichne/kast/cli/SidecarCacheIdentityFile"),
        JvmClassName("io/github/amichne/kast/cli/SidecarCacheStateFile"),
        JvmClassName("io/github/amichne/kast/cli/bootstrap/SidecarBootstrapAttemptLock"),
        JvmClassName("io/github/amichne/kast/cli/broker/MacOsPersistentBrokerServiceHost"),
        JvmClassName(
            "io/github/amichne/kast/cli/broker/InstalledBrokerServerConfiguration\$Companion",
        ),
        JvmClassName("io/github/amichne/kast/cli/broker/InstalledBrokerServerKt"),
        JvmClassName("io/github/amichne/kast/cli/broker/OwnedBrokerServiceReadiness"),
        JvmClassName(
            "io/github/amichne/kast/cli/broker/protocol/codex/CodexProtocolQualifier\$retireTemporaryTree\$1\$1",
        ),
        JvmClassName("io/github/amichne/kast/cli/broker/protocol/FileThreadCatalogStore"),
        JvmClassName("io/github/amichne/kast/cli/broker/runtime/OwnedUnixSocket"),
        JvmClassName("io/github/amichne/kast/cli/broker/runtime/UnixSocketPathOwnership"),
    )

    @Test
    fun `CLI filesystem authority is finite and exact-callsite scoped`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val cli = architecture.modules.getValue(ModuleId.CLI)

        assertEquals(
            expectedCallers,
            cli.allowedScopedEffectCallers.getValue(ForbiddenEffect.FILESYSTEM_WRITE),
        )
        assertTrue(ForbiddenEffect.FILESYSTEM_WRITE !in cli.allowedEffects)

        val admitted = filesystemWrite(expectedCallers.first())
        assertInstanceOf<ArchitectureAdmission.Accepted>(
            ArchitectureAdmission.evaluate(architecture, observation(architecture, admitted)),
        )

        val foreign = filesystemWrite(
            JvmClassName("io/github/amichne/kast/cli/UnrelatedCliCommand"),
        )
        val rejected = assertInstanceOf<ArchitectureAdmission.Rejected>(
            ArchitectureAdmission.evaluate(architecture, observation(architecture, foreign)),
        )
        assertTrue(ArchitectureViolation.ForbiddenEffectUse(foreign) in rejected.violations)
    }

    private fun observation(
        architecture: ValidatedArchitecturePolicy,
        effect: EffectObservation,
    ) = ObservedArchitecture(
        modules = architecture.modules.values
            .filter { it.lifecycle == ModuleLifecycle.ACTIVE }
            .mapTo(linkedSetOf(), ValidatedModulePolicy::id),
        projectDependencies = emptySet(),
        effects = setOf(effect),
    )

    private fun filesystemWrite(owner: JvmClassName) = EffectObservation(
        module = ModuleId.CLI,
        effect = ForbiddenEffect.FILESYSTEM_WRITE,
        caller = JvmMember(
            owner,
            JvmMemberName("effect"),
            JvmDescriptor("()V"),
        ),
        target = JvmMember.of(
            "java/nio/file/Files",
            "createDirectory",
            "(Ljava/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/file/Path;",
        ),
    )
}

package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class TopologySourceRootVfsSynchronizationAuthorityBoundaryTest {
    @Test
    fun `exact topology source root synchronizer refines generic recursive refresh authority`() {
        assertEquals(
            setOf(ForbiddenEffect.TOPOLOGY_SOURCE_ROOT_VFS_SYNCHRONIZATION),
            refreshEffects(TOPOLOGY_SYNCHRONIZER),
        )
        assertEquals(
            setOf(ForbiddenEffect.RECURSIVE_VFS_REFRESH),
            refreshEffects(
                JvmMember.of(
                    "io/github/amichne/kast/topology/intellij/UnscopedRefresh",
                    "synchronize",
                    TOPOLOGY_SYNCHRONIZER.descriptor.value,
                ),
            ),
        )
    }

    @Test
    fun `topology IntelliJ is the sole source root synchronization owner`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture

        assertEquals(
            setOf(ModuleId.TOPOLOGY_INTELLIJ),
            architecture.modules.values
                .filter { ForbiddenEffect.TOPOLOGY_SOURCE_ROOT_VFS_SYNCHRONIZATION in it.allowedEffects }
                .mapTo(linkedSetOf(), ValidatedModulePolicy::id),
        )

        val injected = KastArchitecturePolicy.definition().copy(
            modules = KastArchitecturePolicy.definition().modules.map { module ->
                if (module.id == ModuleId.DIAGNOSTIC_INTELLIJ) {
                    module.copy(
                        allowedEffects = module.allowedEffects +
                            ForbiddenEffect.TOPOLOGY_SOURCE_ROOT_VFS_SYNCHRONIZATION,
                    )
                } else {
                    module
                }
            },
        )
        val invalid = assertInstanceOf<ArchitecturePolicyValidation.Invalid>(
            ArchitecturePolicyValidator.validate(injected),
        )
        assertTrue(invalid.failures.any { failure ->
            failure is ArchitecturePolicyFailure.InvalidExclusiveEffectOwners &&
                failure.effect == ForbiddenEffect.TOPOLOGY_SOURCE_ROOT_VFS_SYNCHRONIZATION
        })
    }

    private fun refreshEffects(caller: JvmMember): Set<ForbiddenEffect> =
        EffectRules.classify(ModuleRole.INTELLIJ_READ_ADAPTER, caller, VFS_REFRESH_TARGET)
            .filterTo(linkedSetOf()) { effect ->
                effect == ForbiddenEffect.RECURSIVE_VFS_REFRESH ||
                    effect == ForbiddenEffect.TOPOLOGY_SOURCE_ROOT_VFS_SYNCHRONIZATION
            }

    private companion object {
        val TOPOLOGY_SYNCHRONIZER = JvmMember.of(
            "io/github/amichne/kast/topology/intellij/InstalledTopologySourceRootVfsSynchronizer",
            "synchronize",
            "(Lio/github/amichne/kast/workspace/contract/PublishedWorkspace;Ljava/util/List;)" +
                "Lio/github/amichne/kast/topology/intellij/TopologySourceRootVfsSynchronization;",
        )
        val VFS_REFRESH_TARGET = JvmMember.of(
            "com/intellij/openapi/vfs/VfsUtil",
            "markDirtyAndRefresh",
            "(ZZZ[Ljava/io/File;)V",
        )
    }
}

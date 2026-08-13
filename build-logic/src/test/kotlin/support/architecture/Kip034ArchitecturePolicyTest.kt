package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class Kip034ArchitecturePolicyTest {
    @Test
    fun `KIP 034 activates exact apply owners and indexer composition edges`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            ArchitecturePolicyValidator.validate(
                KastArchitecturePolicy.definition().copy(legacyAllowances = emptyList()),
            ),
        ).architecture
        val applyOwners = setOf(
            ModuleId.CHANGE_APPLY_SPI,
            ModuleId.CHANGE_APPLY_SERVICE,
            ModuleId.CHANGE_APPLY_INTELLIJ,
        )
        val directConsumerOwners = applyOwners + setOf(
            ModuleId.CHANGE_RECOVERY_CONTRACT,
            ModuleId.CHANGE_RECOVERY_SPI,
            ModuleId.CHANGE_RECOVERY_FILESYSTEM,
            ModuleId.CHANGE_RECOVERY_SERVICE,
        )

        assertEquals(
            setOf(ModuleLifecycle.ACTIVE),
            applyOwners.mapTo(mutableSetOf()) { architecture.modules.getValue(it).lifecycle },
        )
        assertEquals(
            setOf(ModuleId.CHANGE_CONTRACT, ModuleId.CHANGE_RECOVERY_CONTRACT),
            architecture.modules.getValue(ModuleId.CHANGE_JOURNAL_CONTRACT)
                .allowedProjectDependencies,
        )
        assertEquals(
            setOf(
                ModuleId.CHANGE_CONTRACT,
                ModuleId.CHANGE_JOURNAL_CONTRACT,
                ModuleId.CHANGE_RECOVERY_CONTRACT,
            ),
            architecture.modules.getValue(ModuleId.CHANGE_APPLY_SPI).allowedProjectDependencies,
        )
        assertEquals(
            setOf(
                ModuleId.CHANGE_APPLY_SPI,
                ModuleId.CHANGE_CONTRACT,
                ModuleId.CHANGE_JOURNAL_CONTRACT,
                ModuleId.CHANGE_RECOVERY_CONTRACT,
            ),
            architecture.modules.getValue(ModuleId.CHANGE_APPLY_SERVICE).allowedProjectDependencies,
        )
        assertEquals(
            setOf(ModuleId.CHANGE_APPLY_SPI, ModuleId.CHANGE_CONTRACT),
            architecture.modules.getValue(ModuleId.CHANGE_APPLY_INTELLIJ).allowedProjectDependencies,
        )
        assertEquals(
            directConsumerOwners,
            architecture.modules.getValue(ModuleId.INDEXER).allowedProjectDependencies
                .intersect(directConsumerOwners),
        )
        assertEquals(
            setOf(ForbiddenEffect.INTELLIJ_WRITE),
            architecture.modules.getValue(ModuleId.CHANGE_APPLY_INTELLIJ).allowedEffects,
        )
        assertEquals(
            emptySet<ForbiddenEffect>(),
            architecture.modules.getValue(ModuleId.CHANGE_APPLY_SERVICE).allowedEffects,
        )
        assertEquals(
            emptySet<ForbiddenEffect>(),
            architecture.modules.getValue(ModuleId.CHANGE_APPLY_SPI).allowedEffects,
        )
    }
}

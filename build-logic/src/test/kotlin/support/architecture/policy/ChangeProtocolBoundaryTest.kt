package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class ChangeProtocolBoundaryTest {
    @Test
    fun `change protocol cannot acquire startup platform or persistence effects`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(KastArchitecturePolicy.validate()).architecture
        val protocol = architecture.modules.getValue(ModuleId.CHANGE_PROTOCOL)
        assertEquals(ModuleRole.SERVICE, protocol.role)
        assertTrue(protocol.allowedEffects.isEmpty())
        assertTrue(protocol.allowedScopedEffectCallers.isEmpty())
        val closure = linkedSetOf<ModuleId>()
        fun visit(id: ModuleId) {
            if (closure.add(id)) architecture.modules.getValue(id).allowedProjectDependencies.forEach(::visit)
        }
        visit(ModuleId.CHANGE_PROTOCOL)
        assertEquals(emptySet<ModuleId>(), closure.intersect(setOf(
            ModuleId.RUNTIME_COMPOSITION, ModuleId.INDEXER, ModuleId.WORKSPACE_SERVICE,
            ModuleId.WORKSPACE_INTELLIJ, ModuleId.WORKSPACE_INTELLIJ_READ,
            ModuleId.CHANGE_INTELLIJ, ModuleId.EVIDENCE_SQLITE, ModuleId.DISTRIBUTION_MANAGED,
            ModuleId.APP_SERVER, ModuleId.CLI,
        )))
    }
}

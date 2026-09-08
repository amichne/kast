package support.architecture

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class AppServerBoundaryTest {
    @Test fun `persistent transport is owned by app server with a one way CLI dependency`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(KastArchitecturePolicy.validate()).architecture
        val cli = architecture.modules.getValue(ModuleId.CLI)
        val server = architecture.modules.getValue(ModuleId.APP_SERVER)
        assertTrue(ModuleId.APP_SERVER in cli.allowedProjectDependencies)
        assertFalse(ModuleId.CLI in server.allowedProjectDependencies)
        assertFalse(ForbiddenEffect.FILESYSTEM_WRITE in server.allowedEffects)
        val serverCallers = server.allowedScopedEffectCallers.getValue(ForbiddenEffect.FILESYSTEM_WRITE)
        assertTrue(serverCallers.isNotEmpty())
        assertTrue(serverCallers.none { it in cli.allowedScopedEffectCallers.getValue(ForbiddenEffect.FILESYSTEM_WRITE) })
        assertTrue(ModuleId.APP_SERVER !in architecture.modules.getValue(ModuleId.RUNTIME_COMPOSITION).allowedProjectDependencies)
    }
}

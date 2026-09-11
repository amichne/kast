package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class LiveReadModuleBoundaryTest {
    @Test
    fun `only named semantic adapters may consume the shared IDE read authority`() {
        val modules = KastArchitecturePolicy.definition().modules.associateBy(ModulePolicy::id)
        for (id in setOf(ModuleId.SYMBOL_INTELLIJ, ModuleId.SOURCE_INTELLIJ)) {
            val reader = modules.getValue(id)
            assertTrue(ModuleId.WORKSPACE_INTELLIJ_READ in reader.allowedProjectDependencies)
            assertInstanceOf<ModulePolicyValidation.Valid>(ModulePolicyValidator.validate(reader, modules))

            val unnamedReader = reader.copy(id = ModuleId.QUERY_SERVICE)
            val rejected = assertInstanceOf<ModulePolicyValidation.Invalid>(
                ModulePolicyValidator.validate(unnamedReader, modules),
            )
            assertTrue(ArchitecturePolicyFailure.ForbiddenModuleRoleDependency(
                ModuleId.QUERY_SERVICE, ModuleId.WORKSPACE_INTELLIJ_READ, ModuleRole.IDE_READ_ONLY,
            ) in rejected.failures)
        }
    }

    @Test
    fun `query protocol stays pure while both runtime owners explicitly construct it`() {
        val architecture = canonical()
        val protocol = architecture.modules.getValue(ModuleId.QUERY_PROTOCOL)
        assertEquals(ModuleRole.SERVICE, protocol.role)
        assertEquals(ModuleCost.HOST_NEUTRAL, protocol.cost)
        assertEquals(setOf(
            ModuleId.KERNEL, ModuleId.PROTOCOL_CONTRACT, ModuleId.QUERY_CONTRACT,
            ModuleId.WORKSPACE_CONTRACT, ModuleId.SYMBOL_CONTRACT, ModuleId.SOURCE_CONTRACT,
            ModuleId.RELATION_CONTRACT, ModuleId.DIAGNOSTIC_CONTRACT, ModuleId.TRAVERSAL_CONTRACT,
        ), protocol.allowedProjectDependencies)
        assertTrue(protocol.allowedEffects.isEmpty())
        assertTrue(protocol.allowedScopedEffectCallers.isEmpty())
        for (owner in setOf(ModuleId.RUNTIME_COMPOSITION, ModuleId.RUNTIME_HOSTED)) {
            assertTrue(ModuleId.QUERY_PROTOCOL in architecture.modules.getValue(owner).allowedProjectDependencies)
        }

        val modules = KastArchitecturePolicy.definition().modules.associateBy(ModulePolicy::id)
        val effectful = modules.getValue(ModuleId.QUERY_PROTOCOL).copy(
            allowedProjectDependencies = protocol.allowedProjectDependencies + ModuleId.SYMBOL_INTELLIJ,
            allowedEffects = setOf(ForbiddenEffect.INTELLIJ_PLATFORM),
        )
        val rejected = assertInstanceOf<ModulePolicyValidation.Invalid>(
            ModulePolicyValidator.validate(effectful, modules),
        )
        assertTrue(ArchitecturePolicyFailure.ForbiddenModuleRoleDependency(
            ModuleId.QUERY_PROTOCOL, ModuleId.SYMBOL_INTELLIJ, ModuleRole.INTELLIJ_READ_ADAPTER,
        ) in rejected.failures)
        assertTrue(ArchitecturePolicyFailure.ForbiddenModuleRoleEffect(
            ModuleId.QUERY_PROTOCOL, ForbiddenEffect.INTELLIJ_PLATFORM,
        ) in rejected.failures)
    }

    @Test
    fun `hosted composition includes explicit changes but excludes isolated runtime owners`() {
        val architecture = canonical()
        val closure = linkedSetOf<ModuleId>()
        fun visit(id: ModuleId) {
            if (closure.add(id)) architecture.modules.getValue(id).allowedProjectDependencies.forEach(::visit)
        }
        visit(ModuleId.RUNTIME_HOSTED)

        assertTrue(setOf(
            ModuleId.QUERY_PROTOCOL, ModuleId.QUERY_SERVICE,
            ModuleId.SYMBOL_INTELLIJ, ModuleId.SOURCE_INTELLIJ, ModuleId.RELATION_INTELLIJ,
            ModuleId.DIAGNOSTIC_INTELLIJ, ModuleId.WORKSPACE_INTELLIJ_READ,
            ModuleId.CHANGE_INTELLIJ, ModuleId.CHANGE_PLAN, ModuleId.CHANGE_APPLY,
            ModuleId.CHANGE_VERIFY, ModuleId.CHANGE_RECOVERY, ModuleId.EVIDENCE_SQLITE,
        ).all(closure::contains))
        assertEquals(emptySet<ModuleId>(), closure.intersect(setOf(
            ModuleId.RUNTIME_COMPOSITION, ModuleId.INDEXER, ModuleId.RUNTIME_TELEMETRY,
            ModuleId.WORKSPACE_INTELLIJ, ModuleId.WORKSPACE_SERVICE,
            ModuleId.DISTRIBUTION_MANAGED, ModuleId.TOPOLOGY_BUILD, ModuleId.TOPOLOGY_INTELLIJ,
            ModuleId.TOPOLOGY_SERVICE,
        )))
    }

    @Test
    fun `shared semantic read adapters cannot acquire writers or durable mutation stores`() {
        val architecture = canonical()
        val closure = linkedSetOf<ModuleId>()
        fun visit(id: ModuleId) {
            if (closure.add(id)) architecture.modules.getValue(id).allowedProjectDependencies.forEach(::visit)
        }
        setOf(ModuleId.QUERY_PROTOCOL, ModuleId.QUERY_SERVICE, ModuleId.SYMBOL_INTELLIJ,
            ModuleId.SOURCE_INTELLIJ, ModuleId.RELATION_INTELLIJ, ModuleId.DIAGNOSTIC_INTELLIJ,
            ModuleId.WORKSPACE_INTELLIJ_READ).forEach(::visit)
        assertEquals(emptySet<ModuleId>(), closure.intersect(setOf(
            ModuleId.RUNTIME_HOSTED, ModuleId.RUNTIME_COMPOSITION, ModuleId.INDEXER,
            ModuleId.CHANGE_INTELLIJ, ModuleId.CHANGE_PLAN, ModuleId.CHANGE_APPLY,
            ModuleId.CHANGE_VERIFY, ModuleId.CHANGE_RECOVERY, ModuleId.EVIDENCE_SQLITE,
            ModuleId.WORKSPACE_INTELLIJ, ModuleId.WORKSPACE_SERVICE,
        )))
    }

    private fun canonical(): ValidatedArchitecturePolicy =
        KastArchitecturePolicy.validate().let { result ->
            assertInstanceOf<ArchitecturePolicyValidation.Valid>(result, result.toString()).architecture
        }
}

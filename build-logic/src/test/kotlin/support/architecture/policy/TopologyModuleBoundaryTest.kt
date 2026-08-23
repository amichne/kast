package support.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class TopologyModuleBoundaryTest {
    @Test
    fun `foreign topology build or publication authority is rejected`() {
        val definition = KastArchitecturePolicy.definition()
        val injected = definition.copy(
            modules = definition.modules.map { module ->
                if (module.id == ModuleId.TRAVERSAL_SERVICE) {
                    module.copy(
                        allowedEffects = module.allowedEffects +
                            ForbiddenEffect.TOPOLOGY_BUILD_AUTHORITY +
                            ForbiddenEffect.TOPOLOGY_PUBLICATION,
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
                failure.effect == ForbiddenEffect.TOPOLOGY_BUILD_AUTHORITY
        })
        assertTrue(invalid.failures.any { failure ->
            failure is ArchitecturePolicyFailure.InvalidExclusiveEffectOwners &&
                failure.effect == ForbiddenEffect.TOPOLOGY_PUBLICATION
        })
    }
}

package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class Kvp024EndpointAuthorityTest {
    @Test
    fun `only IDE plugin selects the two endpoint effects`() {
        val policies = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture.modules

        assertEquals(
            setOf(
                ForbiddenEffect.INTELLIJ_PLATFORM,
                ForbiddenEffect.UDS_BIND,
                ForbiddenEffect.ENDPOINT_DESCRIPTOR_WRITE,
            ),
            policies.getValue(ModuleId.IDE_PLUGIN).allowedEffects,
        )
        assertEquals(
            setOf(ForbiddenEffect.INTELLIJ_PLATFORM),
            policies.getValue(ModuleId.RUNTIME_IDE_READ).allowedEffects,
        )
        assertEquals(
            setOf(ForbiddenEffect.INTELLIJ_PLATFORM),
            policies.getValue(ModuleId.WORKSPACE_INTELLIJ_READ).allowedEffects,
        )
    }

    @Test
    fun `endpoint package receives named effects instead of generic write authority`() {
        val caller = JvmMember.of(
            "io/github/amichne/kast/ide/endpoint/NioIdeEndpointPublicationBoundary",
            "publish",
            "()V",
        )

        assertEquals(
            setOf(ForbiddenEffect.ENDPOINT_DESCRIPTOR_WRITE),
            EffectRules.classify(
                ModuleRole.IDE_READ_ONLY,
                caller,
                JvmMember.of("java/nio/file/Files", "move", "()V"),
            ),
        )
        assertEquals(
            setOf(ForbiddenEffect.UDS_BIND),
            EffectRules.classify(
                ModuleRole.IDE_READ_ONLY,
                caller,
                JvmMember.of("java/nio/channels/ServerSocketChannel", "bind", "()V"),
            ),
        )
    }

    @Test
    fun `filesystem writes outside endpoint package remain generic`() {
        assertEquals(
            setOf(ForbiddenEffect.FILESYSTEM_WRITE),
            EffectRules.classify(
                ModuleRole.IDE_READ_ONLY,
                JvmMember.of("io/github/amichne/kast/ide/OtherOwner", "write", "()V"),
                JvmMember.of("java/nio/file/Files", "writeString", "()V"),
            ),
        )
    }
}

package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path

class IdeReadFirewallTest {
    @Test
    fun `workspace split IDE read graph is physically narrow`() {
        val proof = completeProof(canonical())

        assertEquals(IdeReadFirewall.moduleIds, proof.modules.mapTo(mutableSetOf()) { it.id })
        assertEquals(IdeReadFirewallStage.WORKSPACE_SPLIT, proof.stage)
        assertEquals(
            setOf(ModuleId.IDE_PLUGIN, ModuleId.WORKSPACE_INTELLIJ_READ),
            proof.modules.filter { it.lifecycle == ModuleLifecycle.ACTIVE }.mapTo(mutableSetOf()) {
                it.id
            },
        )
        assertTrue(proof.modules.all { it.role == ModuleRole.IDE_READ_ONLY })
        assertTrue(proof.modules.all {
            it.allowedEffects == setOf(ForbiddenEffect.INTELLIJ_PLATFORM)
        })
        assertEquals(
            setOf(ModuleId.PROTOCOL_CONTRACT, ModuleId.WORKSPACE_CONTRACT),
            proof.modules.single { it.id == ModuleId.WORKSPACE_INTELLIJ_READ }
                .allowedProjectDependencies,
        )
        assertEquals(
            setOf(
                ModuleId.PROTOCOL_CONTRACT,
                ModuleId.RUNTIME_IDE_READ,
                ModuleId.WORKSPACE_INTELLIJ_READ,
            ),
            proof.modules.single { it.id == ModuleId.IDE_PLUGIN }.allowedProjectDependencies,
        )
    }

    @Test
    fun `compiled forbidden references derive every finite firewall effect`(@TempDir temporary: Path) {
        val policy = canonical()
        val module = policy.modules.getValue(ModuleId.IDE_PLUGIN)
        val fixture = forbiddenReferenceFixture(temporary)

        val scanned = assertInstanceOf<BytecodeScanOutcome.Scanned>(
            JvmEffectScanner.scan(module, listOf(fixture)),
        )

        IdeReadForbiddenAuthority.entries.forEach { authority ->
            assertTrue(
                scanned.effects().any { it.effect == authority.requiredEffect },
                authority.name,
            )
        }
    }

    @Test
    fun `firewall rejects an allowed project open effect`() {
        val policy = canonical()
        val original = policy.modules.getValue(ModuleId.IDE_PLUGIN)
        val leaked = ValidatedModulePolicy(
            ModulePolicy(
                original.id,
                original.lifecycle,
                original.role,
                original.allowedProjectDependencies,
                original.allowedEffects + ForbiddenEffect.PROJECT_OPEN,
            ),
            original.boundary,
        )
        val changed = ValidatedArchitecturePolicy(
            policy.modules + (leaked.id to leaked),
            policy.moduleOrder,
        )

        val rejected = assertInstanceOf<IdeReadFirewallResult.Rejected>(
            IdeReadFirewall.derive(changed),
        )

        assertTrue(
            IdeReadFirewallFailure.AllowedEffectsMismatch(
                ModuleId.IDE_PLUGIN,
                setOf(ForbiddenEffect.INTELLIJ_PLATFORM, ForbiddenEffect.PROJECT_OPEN),
            ) in rejected.failures,
        )
        assertTrue(
            IdeReadFirewallFailure.AuthorityAllowed(
                ModuleId.IDE_PLUGIN,
                IdeReadForbiddenAuthority.PROJECT_OPEN,
                ForbiddenEffect.PROJECT_OPEN,
            ) in rejected.failures,
        )
    }

    @Test
    fun `firewall rejects a lifecycle stage that skips the workspace split`() {
        val policy = canonical()
        val runtime = policy.modules.getValue(ModuleId.RUNTIME_IDE_READ)
        val activatedRuntime = ValidatedModulePolicy(
            ModulePolicy(
                runtime.id,
                ModuleLifecycle.ACTIVE,
                runtime.role,
                runtime.allowedProjectDependencies,
                runtime.allowedEffects,
            ),
            runtime.boundary,
        )
        val workspace = policy.modules.getValue(ModuleId.WORKSPACE_INTELLIJ_READ)
        val skippedWorkspace = ValidatedModulePolicy(
            ModulePolicy(
                workspace.id,
                ModuleLifecycle.PLANNED,
                workspace.role,
                workspace.allowedProjectDependencies,
                workspace.allowedEffects,
            ),
            workspace.boundary,
        )
        val changed = ValidatedArchitecturePolicy(
            policy.modules +
                (activatedRuntime.id to activatedRuntime) +
                (skippedWorkspace.id to skippedWorkspace),
            policy.moduleOrder,
        )

        val rejected = assertInstanceOf<IdeReadFirewallResult.Rejected>(
            IdeReadFirewall.derive(changed),
        )

        assertTrue(
            IdeReadFirewallFailure.LifecycleProgressionMismatch(
                setOf(ModuleId.IDE_PLUGIN, ModuleId.RUNTIME_IDE_READ),
            ) in rejected.failures,
        )
    }

    @Test
    fun `closed report codec preserves the complete firewall proof`() {
        val encoded = encodeIdeReadFirewallReport(completeProof(canonical()))

        val decoded = assertInstanceOf<IdeReadFirewallReportResult.Complete>(
            decodeIdeReadFirewallReport(encoded),
        )

        assertEquals(3, decoded.proof.modules.size)
        assertEquals(9, decoded.proof.forbiddenAuthorities.size)
        assertTrue("\"schemaVersion\": 2" in encoded)
        assertTrue("\"stage\": \"WORKSPACE_SPLIT\"" in encoded)
    }

    @Test
    fun `closed report codec rejects task tampering and unknown fields`() {
        val encoded = encodeIdeReadFirewallReport(completeProof(canonical()))

        assertEquals(
            IdeReadFirewallReportResult.Rejected(
                IdeReadFirewallReportFailure.TASK_ID_MISMATCH,
            ),
            decodeIdeReadFirewallReport(encoded.replace("KVP-009", "KVP-010")),
        )
        assertEquals(
            IdeReadFirewallReportResult.Rejected(
                IdeReadFirewallReportFailure.MALFORMED_DOCUMENT,
            ),
            decodeIdeReadFirewallReport(encoded.replaceFirst("{", "{\"unknown\":true,")),
        )
    }

    private fun completeProof(policy: ValidatedArchitecturePolicy) =
        assertInstanceOf<IdeReadFirewallResult.Complete>(IdeReadFirewall.derive(policy)).proof

    private fun canonical(): ValidatedArchitecturePolicy =
        assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture

    private fun forbiddenReferenceFixture(temporary: Path): Path {
        val bytecode = ClassWriter(0).apply {
            visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                "io/github/amichne/kast/ide/HostedReadFixture",
                null,
                "java/lang/Object",
                null,
            )
            IdeReadForbiddenAuthority.entries.forEachIndexed { index, authority ->
                visitMethod(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                    "authority$index",
                    "()V",
                    null,
                    null,
                ).apply {
                    visitCode()
                    visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        authority.target.owner.internalName,
                        authority.target.name.value,
                        authority.target.descriptor.value,
                        false,
                    )
                    visitInsn(Opcodes.RETURN)
                    visitMaxs(0, 0)
                    visitEnd()
                }
            }
            visitEnd()
        }.toByteArray()
        return temporary.resolve("HostedReadFixture.class").also { Files.write(it, bytecode) }
    }
}

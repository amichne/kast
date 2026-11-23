package support.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class InstallationParentDerivationBoundaryTest {
    private val architecture =
        assertInstanceOf<ArchitecturePolicyValidation.Valid>(KastArchitecturePolicy.validate()).architecture

    @Test
    fun `compiled parent chains are observed once per chain and split chains are not`(@TempDir temporary: Path) {
        val scanned = assertInstanceOf<BytecodeScanOutcome.Scanned>(
            JvmEffectScanner.scan(
                architecture.modules.getValue(ModuleId.APP_SERVER),
                listOf(copyClassFile(ParentChainFixture::class.java, temporary)),
            ),
        )

        val chains = scanned.effects()
            .filter { it.effect == ForbiddenEffect.INSTALLATION_PARENT_DERIVATION }
            .map { it.caller.name.value }
            .sorted()

        // `split`, `single`, and `sibling` are outside the rule's observable shape by construction.
        assertEquals(listOf("asserted", "chained", "deep", "physical", "safe"), chains)
    }

    @Test
    fun `a parent chain outside the layout is rejected`() {
        val foreign = chain(ModuleId.APP_SERVER, "io/github/amichne/kast/appserver/NewInstallationReader")
        val rejected = assertInstanceOf<ArchitectureAdmission.Rejected>(evaluate(foreign))
        assertTrue(ArchitectureViolation.ForbiddenEffectUse(foreign) in rejected.violations)

        val sibling = chain(ModuleId.DISTRIBUTION_CONTRACT, "io/github/amichne/kast/distribution/contract/Other")
        assertInstanceOf<ArchitectureAdmission.Rejected>(evaluate(sibling))

        val cli = chain(ModuleId.CLI, "io/github/amichne/kast/cli/KastServiceMainKt")
        assertInstanceOf<ArchitectureAdmission.Rejected>(evaluate(cli))
    }

    @Test
    fun `the layout's own compiled parent chain is admitted`(@TempDir temporary: Path) {
        val owner = "io/github/amichne/kast/distribution/contract/InstallationLayout\$Companion"
        val scanned = assertInstanceOf<BytecodeScanOutcome.Scanned>(
            JvmEffectScanner.scan(
                architecture.modules.getValue(ModuleId.DISTRIBUTION_CONTRACT),
                listOf(parentChainClassFile(temporary, owner)),
            ),
        )
        val chains = scanned.effects().filter { it.effect == ForbiddenEffect.INSTALLATION_PARENT_DERIVATION }

        assertEquals(1, chains.size)
        assertEquals(JvmClassName(owner), chains.single().caller.owner)
        assertInstanceOf<ArchitectureAdmission.Accepted>(evaluate(*chains.toTypedArray()))
    }

    @Test
    fun `no module may chain parents without a named owner`() {
        assertTrue(
            architecture.modules.values.none { ForbiddenEffect.INSTALLATION_PARENT_DERIVATION in it.allowedEffects },
        )
        val owners = architecture.modules.values
            .filter { ForbiddenEffect.INSTALLATION_PARENT_DERIVATION in it.allowedScopedEffectCallers }
            .associate { it.id to it.allowedScopedEffectCallers.getValue(ForbiddenEffect.INSTALLATION_PARENT_DERIVATION) }

        // Only the layout's owners may climb an installation root; the rest climb stores, bundles, or `/tmp` roots.
        assertEquals(
            mapOf(
                ModuleId.DISTRIBUTION_CONTRACT to InstallationParentDerivationOwners.layout,
                ModuleId.DISTRIBUTION_CLI to InstallationParentDerivationOwners.containerStore,
                ModuleId.DISTRIBUTION_MANAGED to InstallationParentDerivationOwners.endpointAlias,
                ModuleId.APP_SERVER to InstallationParentDerivationOwners.appServer,
                ModuleId.RUNTIME_HOSTED to InstallationParentDerivationOwners.hostedEndpoint,
            ),
            owners,
        )
    }

    private fun evaluate(vararg effects: EffectObservation): ArchitectureAdmission =
        ArchitectureAdmission.evaluate(
            architecture,
            ObservedArchitecture(
                modules = architecture.modules.values
                    .filter { it.lifecycle == ModuleLifecycle.ACTIVE }
                    .mapTo(linkedSetOf(), ValidatedModulePolicy::id),
                projectDependencies = emptySet(),
                effects = effects.toSet(),
            ),
        )

    private fun chain(module: ModuleId, owner: String) = EffectObservation(
        module = module,
        effect = ForbiddenEffect.INSTALLATION_PARENT_DERIVATION,
        caller = JvmMember.of(owner, "read", "(Ljava/nio/file/Path;)Ljava/nio/file/Path;"),
        target = JvmMember.of("java/nio/file/Path", "getParent", "()Ljava/nio/file/Path;"),
    )

    private fun copyClassFile(type: Class<*>, temporary: Path): Path {
        val resource = "/${type.name.replace('.', '/')}.class"
        val target = temporary.resolve("${type.simpleName}.class")
        type.getResourceAsStream(resource).use { input ->
            requireNotNull(input) { "missing fixture bytecode: $resource" }
            Files.copy(input, target)
        }
        return target
    }

    private fun parentChainClassFile(temporary: Path, owner: String): Path {
        val bytecode = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
            visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "ofKastExecutable",
                "(Ljava/nio/file/Path;)Ljava/nio/file/Path;",
                null,
                null,
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                repeat(2) {
                    visitMethodInsn(
                        Opcodes.INVOKEINTERFACE,
                        "java/nio/file/Path",
                        "getParent",
                        "()Ljava/nio/file/Path;",
                        true,
                    )
                }
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
        return temporary.resolve("InstallationLayout\$Companion.class").also { Files.write(it, bytecode) }
    }

    @Suppress("unused")
    private class ParentChainFixture {
        fun chained(kast: Path): Path = kast.parent.parent

        fun safe(payload: Path): Path? = payload.parent?.parent

        fun physical(kast: Path): Path = kast.toRealPath().parent.parent

        fun deep(executable: Path): Path = executable.parent.parent.parent.parent

        fun asserted(kast: Path): Path {
            val root: Path = kast.parent.parent
            return root
        }

        fun split(kast: Path): Path {
            val bin = kast.parent
            return bin.parent
        }

        fun single(kast: Path): Path = kast.parent

        fun sibling(kast: Path): Path = kast.parent.resolve("lib").parent
    }
}

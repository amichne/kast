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

class InstallationPathDerivationBoundaryTest {
    private val architecture =
        assertInstanceOf<ArchitecturePolicyValidation.Valid>(KastArchitecturePolicy.validate()).architecture

    @Test
    fun `compiled installation path literals are derivations wherever they reach a path call`(
        @TempDir temporary: Path,
    ) {
        val scanned = assertInstanceOf<BytecodeScanOutcome.Scanned>(
            JvmEffectScanner.scan(
                architecture.modules.getValue(ModuleId.APP_SERVER),
                listOf(copyClassFile(InstallationPathFixture::class.java, temporary)),
            ),
        )

        val derivations = scanned.effects()
            .filter { it.effect == ForbiddenEffect.INSTALLATION_PATH_DERIVATION }
            .mapTo(sortedSetOf()) { it.caller.name.value }

        assertEquals(
            sortedSetOf(
                "configuration",
                "lifecycleLock",
                "lifetimeLock",
                "management",
                "manifest",
                "readiness",
                "workspaceStore",
            ),
            derivations,
        )
    }

    @Test
    fun `a new derivation outside the layout is rejected`() {
        val foreign = derivation(ModuleId.APP_SERVER, "io/github/amichne/kast/appserver/NewInstallationReader")
        val rejected = assertInstanceOf<ArchitectureAdmission.Rejected>(evaluate(foreign))
        assertTrue(ArchitectureViolation.ForbiddenEffectUse(foreign) in rejected.violations)

        val sibling = derivation(ModuleId.DISTRIBUTION_CONTRACT, "io/github/amichne/kast/distribution/contract/Other")
        assertInstanceOf<ArchitectureAdmission.Rejected>(evaluate(sibling))

        val unlistedCli = derivation(ModuleId.DISTRIBUTION_CLI, "io/github/amichne/kast/distribution/cli/NewReaderKt")
        assertInstanceOf<ArchitectureAdmission.Rejected>(evaluate(unlistedCli))
    }

    @Test
    fun `the layout's own compiled derivations are admitted`(@TempDir temporary: Path) {
        val layout = "io/github/amichne/kast/distribution/contract/InstallationLayout"
        val scanned = assertInstanceOf<BytecodeScanOutcome.Scanned>(
            JvmEffectScanner.scan(
                architecture.modules.getValue(ModuleId.DISTRIBUTION_CONTRACT),
                listOf(layoutClassFile(temporary, layout)),
            ),
        )
        val derivations = scanned.effects().filter { it.effect == ForbiddenEffect.INSTALLATION_PATH_DERIVATION }

        assertEquals(1, derivations.size)
        assertEquals(JvmClassName(layout), derivations.single().caller.owner)
        assertInstanceOf<ArchitectureAdmission.Accepted>(evaluate(*derivations.toTypedArray()))
    }

    @Test
    fun `only the layout and container profiles may derive these shapes`() {
        val owners = architecture.modules.values
            .filter { ForbiddenEffect.INSTALLATION_PATH_DERIVATION in it.allowedScopedEffectCallers }
            .associate { it.id to it.allowedScopedEffectCallers.getValue(ForbiddenEffect.INSTALLATION_PATH_DERIVATION) }

        assertEquals(
            mapOf(
                ModuleId.DISTRIBUTION_CONTRACT to InstallationPathDerivationOwners.layout,
                ModuleId.DISTRIBUTION_CLI to InstallationPathDerivationOwners.containerProfile,
            ),
            owners,
        )
        assertTrue(
            architecture.modules.values.none { ForbiddenEffect.INSTALLATION_PATH_DERIVATION in it.allowedEffects },
        )
    }

    @Test
    fun `only installation-shaped literals are classified`() {
        listOf(
            "config/environment",
            "state",
            "state/broker/",
            "share/kast/libexec/kast-management",
            "installation",
            "recovery/replacement",
            "installation.json",
            "management.json",
            "workspaces.json",
        ).forEach { assertTrue(InstallationPathDerivationRule.installationRelative(it), it) }
        listOf("shared/gradle", "bin", "lib/kast-linux-support", ".local/share/kast", "service.log", "")
            .forEach { assertTrue(!InstallationPathDerivationRule.installationRelative(it), it) }
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

    private fun derivation(module: ModuleId, owner: String) = EffectObservation(
        module = module,
        effect = ForbiddenEffect.INSTALLATION_PATH_DERIVATION,
        caller = JvmMember.of(owner, "read", "(Ljava/nio/file/Path;)Ljava/nio/file/Path;"),
        target = JvmMember.of("java/nio/file/Path", "resolve", "(Ljava/lang/String;)Ljava/nio/file/Path;"),
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

    private fun layoutClassFile(temporary: Path, owner: String): Path {
        val bytecode = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
            visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "configuration",
                "(Ljava/nio/file/Path;)Ljava/nio/file/Path;",
                null,
                null,
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitLdcInsn("config")
                visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    "java/nio/file/Path",
                    "resolve",
                    "(Ljava/lang/String;)Ljava/nio/file/Path;",
                    true,
                )
                visitInsn(Opcodes.ARETURN)
                visitMaxs(2, 1)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
        return temporary.resolve("InstallationLayout.class").also { Files.write(it, bytecode) }
    }

    @Suppress("unused")
    private class InstallationPathFixture {
        fun configuration(root: Path): Path = root.resolve("config/environment")

        fun readiness(root: Path, profile: String): Path = root.resolve("state/broker/$profile/service-readiness.json")

        fun management(executable: Path): Boolean = executable.endsWith(Path.of("share/kast/libexec/kast-management"))

        fun manifest(root: Path): Path = root.resolve("installation.json")

        fun lifecycleLock(share: Path): Path = share.resolve("kast.lock")

        fun workspaceStore(management: Path): Path = management.resolveSibling("kast-workspaces")

        fun lifetimeLock(store: Path): Path = store.resolveSibling(".kast-workspaces.lifetime.lock")

        fun unrelated(root: Path, java: Path): List<Path> =
            listOf(root.resolve("shared/gradle"), java.resolve("bin"), root.resolve(".local/share/kast"))

        fun parameterized(root: Path, relative: String): Path = root.resolve(relative)
    }
}

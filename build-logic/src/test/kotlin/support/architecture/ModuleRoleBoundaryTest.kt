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

class ModuleRoleBoundaryTest {
    @Test
    fun `canonical non-legacy roles carry convention and cost proof`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val expectedPlugins = mapOf(
            ModuleRole.KERNEL to "kast.role.kernel",
            ModuleRole.CONTRACT to "kast.role.contract",
            ModuleRole.SPI to "kast.role.spi",
            ModuleRole.SERVICE to "kast.role.service",
            ModuleRole.IDE_READ_ONLY to "kast.role.ide-read-only",
            ModuleRole.IDE_HOST to "kast.role.ide-host",
            ModuleRole.INTELLIJ_READ_ADAPTER to "kast.role.intellij-read",
            ModuleRole.INTELLIJ_WRITE_ADAPTER to "kast.role.intellij-write",
            ModuleRole.FILESYSTEM_WRITE_ADAPTER to "kast.role.filesystem-write",
            ModuleRole.SQLITE_ADAPTER to "kast.role.sqlite",
            ModuleRole.WORKSPACE_ADAPTER to "kast.role.workspace",
            ModuleRole.TRANSPORT to "kast.role.transport",
            ModuleRole.COMPOSITION to "kast.role.composition",
            ModuleRole.CLI to "kast.role.cli",
            ModuleRole.INDEXER_HOST to "kast.role.indexer-host",
        )

        assertEquals(
            expectedPlugins,
            ModuleRoleConvention.entries.associate { it.role to it.pluginId },
        )
        val readModule = architecture.modules.getValue(ModuleId.SYMBOL_INTELLIJ)
        assertEquals(ModuleCost.BOUNDED_READ, readModule.cost)
        assertEquals(
            ModuleRoleConventionRequirement.Required(ModuleRoleConvention.INTELLIJ_READ),
            readModule.conventionRequirement,
        )
    }

    @Test
    fun `read role rejects implementation role cost and effect even when raw policy allows them`() {
        val dependency = ModuleId.CHANGE_INTELLIJ
        val definition = KastArchitecturePolicy.definition().copy(
            modules = KastArchitecturePolicy.definition().modules.map { module ->
                if (module.id == ModuleId.SYMBOL_INTELLIJ) {
                    module.copy(
                        allowedProjectDependencies = module.allowedProjectDependencies + dependency,
                        allowedEffects = module.allowedEffects + ForbiddenEffect.INTELLIJ_WRITE,
                    )
                } else {
                    module
                }
            },
        )

        val invalid = assertInstanceOf<ArchitecturePolicyValidation.Invalid>(
            ArchitecturePolicyValidator.validate(definition),
        )

        assertTrue(
            ArchitecturePolicyFailure.ForbiddenModuleRoleDependency(
                ModuleId.SYMBOL_INTELLIJ,
                dependency,
                ModuleRole.INTELLIJ_WRITE_ADAPTER,
            ) in invalid.failures,
        )
        assertTrue(
            ArchitecturePolicyFailure.ForbiddenModuleCostDependency(
                ModuleId.SYMBOL_INTELLIJ,
                dependency,
                ModuleCost.PHYSICAL_EFFECT,
            ) in invalid.failures,
        )
        assertTrue(
            ArchitecturePolicyFailure.ForbiddenModuleRoleEffect(
                ModuleId.SYMBOL_INTELLIJ,
                ForbiddenEffect.INTELLIJ_WRITE,
            ) in invalid.failures,
        )
    }

    @Test
    fun `exported implementation and missing or mismatched role conventions fail admission`() {
        val architecture = canonical()
        val dependency = ProjectDependencyObservation(ModuleId.SYMBOL_INTELLIJ, ModuleId.SYMBOL_CONTRACT)
        val modules = architecture.modules.keys
        val matchingRoles = mapOf(
            ModuleId.SYMBOL_CONTRACT to ModuleRoleConvention.CONTRACT,
            ModuleId.SYMBOL_INTELLIJ to ModuleRoleConvention.INTELLIJ_READ,
        )

        val exported = assertInstanceOf<ArchitectureAdmission.Rejected>(
            ArchitectureAdmission.evaluate(
                architecture,
                ObservedArchitecture(
                    modules = modules,
                    projectDependencies = setOf(dependency),
                    effects = emptySet(),
                    exportedProjectDependencies = setOf(dependency),
                    moduleRoleConventions =
                        ModuleRoleConventionObservation.Collected(matchingRoles),
                ),
            ),
        )
        assertTrue(
            ArchitectureViolation.ForbiddenExportedProjectDependency(dependency) in exported.violations,
        )

        val missing = assertInstanceOf<ArchitectureAdmission.Rejected>(
            ArchitectureAdmission.evaluate(
                architecture,
                ObservedArchitecture(
                    modules = modules,
                    projectDependencies = emptySet(),
                    effects = emptySet(),
                    moduleRoleConventions =
                        ModuleRoleConventionObservation.Collected(emptyMap()),
                ),
            ),
        )
        assertTrue(
            ArchitectureViolation.MissingModuleRoleConvention(
                ModuleId.SYMBOL_INTELLIJ,
                ModuleRoleConvention.INTELLIJ_READ,
            ) in missing.violations,
        )

        val mismatched = assertInstanceOf<ArchitectureAdmission.Rejected>(
            ArchitectureAdmission.evaluate(
                architecture,
                ObservedArchitecture(
                    modules = modules,
                    projectDependencies = emptySet(),
                    effects = emptySet(),
                    moduleRoleConventions = ModuleRoleConventionObservation.Collected(
                        matchingRoles +
                        (ModuleId.SYMBOL_INTELLIJ to ModuleRoleConvention.INTELLIJ_WRITE),
                    ),
                ),
            ),
        )
        assertTrue(
            ArchitectureViolation.MismatchedModuleRoleConvention(
                ModuleId.SYMBOL_INTELLIJ,
                ModuleRoleConvention.INTELLIJ_READ,
                ModuleRoleConvention.INTELLIJ_WRITE,
            ) in mismatched.violations,
        )
    }

    @Test
    fun `transport exports inward contracts but rejects outward implementations`() {
        val architecture = canonical()
        val inward = ProjectDependencyObservation(
            ModuleId.RUNTIME_SERVER,
            ModuleId.PROTOCOL_WIRE,
        )
        val outward = ProjectDependencyObservation(
            ModuleId.RUNTIME_SERVER,
            ModuleId.SYMBOL_INTELLIJ,
        )
        val rejected = assertInstanceOf<ArchitectureAdmission.Rejected>(
            ArchitectureAdmission.evaluate(
                architecture,
                ObservedArchitecture(
                    modules = architecture.modules.values
                        .filter { it.lifecycle == ModuleLifecycle.ACTIVE }
                        .mapTo(mutableSetOf(), ValidatedModulePolicy::id),
                    projectDependencies = emptySet(),
                    effects = emptySet(),
                    exportedProjectDependencies = setOf(inward, outward),
                ),
            ),
        )

        assertTrue(
            ArchitectureViolation.ForbiddenExportedProjectDependency(inward) !in
                rejected.violations,
        )
        assertTrue(
            ArchitectureViolation.ForbiddenExportedProjectDependency(outward) in
                rejected.violations,
        )
    }

    @Test
    fun `Gradle role and exported edge observations parse into typed evidence`() {
        val architecture = canonical()
        val dependency = ProjectDependencyObservation(ModuleId.SYMBOL_INTELLIJ, ModuleId.SYMBOL_CONTRACT)

        val parsed = assertInstanceOf<ArchitectureObservationValidation.Valid>(
            ArchitectureObservationParser.parse(
                architecture,
                rawProjectPaths = listOf(ModuleId.SYMBOL_INTELLIJ.projectPath),
                rawProjectDependencies = listOf(
                    ":symbol:intellij" + ArchitectureObservationParser.EDGE_SEPARATOR + ":symbol:contract",
                ),
                rawExportedProjectDependencies = listOf(
                    ":symbol:intellij" + ArchitectureObservationParser.EDGE_SEPARATOR + ":symbol:contract",
                ),
                rawModuleRoleConventions = listOf(
                    ":symbol:intellij" + ArchitectureObservationParser.ROLE_SEPARATOR + "kast.role.intellij-read",
                ),
            ),
        ).graph

        assertEquals(setOf(dependency), parsed.exportedProjectDependencies)
        assertEquals(
            ModuleRoleConventionObservation.Collected(
                mapOf(ModuleId.SYMBOL_INTELLIJ to ModuleRoleConvention.INTELLIJ_READ),
            ),
            parsed.moduleRoleConventions,
        )

        val unknown = assertInstanceOf<ArchitectureObservationValidation.Invalid>(
            ArchitectureObservationParser.parse(
                architecture,
                rawProjectPaths = emptyList(),
                rawProjectDependencies = emptyList(),
                rawModuleRoleConventions = listOf(
                    ":symbol:intellij" + ArchitectureObservationParser.ROLE_SEPARATOR + "kast.role.unknown",
                ),
            ),
        )
        assertTrue(
            ArchitectureObservationFailure.UnknownModuleRoleConvention("kast.role.unknown") in
                unknown.failures,
        )
    }

    @Test
    fun `bytecode scanner classifies every read-forbidden authority`(@TempDir temporary: Path) {
        val classFile = forbiddenAuthorityFixture(temporary)

        val scanned = assertInstanceOf<BytecodeScanOutcome.Scanned>(
            JvmEffectScanner.scan(canonical().modules.getValue(ModuleId.SYMBOL_INTELLIJ), listOf(classFile)),
        )

        assertEquals(
            setOf(
                ForbiddenEffect.INTELLIJ_PLATFORM,
                ForbiddenEffect.INTELLIJ_WRITE,
                ForbiddenEffect.FILESYSTEM_WRITE,
                ForbiddenEffect.SOURCE_FILESYSTEM_WRITE,
                ForbiddenEffect.JDBC,
                ForbiddenEffect.GRADLE_PLATFORM,
                ForbiddenEffect.GRADLE_IMPORT,
                ForbiddenEffect.RECURSIVE_VFS_REFRESH,
                ForbiddenEffect.WORKSPACE_TRANSITION,
                ForbiddenEffect.GRAPH_BUILD,
                ForbiddenEffect.PROCESS_CONTROL,
                ForbiddenEffect.ANALYSIS_BACKEND,
            ),
            scanned.effects().mapTo(mutableSetOf(), EffectObservation::effect),
        )
    }

    private fun canonical(): ValidatedArchitecturePolicy {
        val definition = KastArchitecturePolicy.definition()
        return assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            ArchitecturePolicyValidator.validate(definition),
        ).architecture
    }

    private fun forbiddenAuthorityFixture(temporary: Path): Path {
        val targets = listOf(
            JvmMember.of(
                "com/intellij/openapi/command/WriteCommandAction",
                "runWriteCommandAction",
                "()V",
            ),
            JvmMember.of("java/nio/file/Files", "deleteIfExists", "()V"),
            JvmMember.of("java/sql/Connection", "prepareStatement", "()V"),
            JvmMember.of(
                "com/intellij/openapi/externalSystem/util/ExternalSystemUtil",
                "refreshProject",
                "()V",
            ),
            JvmMember.of(
                "com/intellij/openapi/vfs/VfsUtil",
                "markDirtyAndRefresh",
                "()V",
            ),
            JvmMember.of(
                "io/github/amichne/kast/workspace/spi/WorkspaceTransitionPort",
                "request",
                "()V",
            ),
            JvmMember.of("org/gradle/tooling/ProjectConnection", "model", "()V"),
            JvmMember.of("java/lang/ProcessBuilder", "start", "()V"),
            JvmMember.of(
                "io/github/amichne/kast/api/contract/AnalysisBackend",
                "health",
                "()V",
            ),
        )
        val bytecode = ClassWriter(0).apply {
            visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                "io/github/amichne/kast/api/io/LocalDiskFileOperations",
                null,
                "java/lang/Object",
                null,
            )
            targets.forEachIndexed { index, target ->
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
                        target.owner.internalName,
                        target.name.value,
                        target.descriptor.value,
                        false,
                    )
                    visitInsn(Opcodes.RETURN)
                    visitMaxs(0, 0)
                    visitEnd()
                }
            }
            visitEnd()
        }.toByteArray()
        return temporary.resolve("ForbiddenAuthorities.class").also { Files.write(it, bytecode) }
    }

}

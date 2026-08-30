package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ProjectFileIndexAuthorityBoundaryTest {
    @Test
    fun `ProjectFileIndex authority is detected and confined to its workspace adapter`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val diagnosticEffects = scanProjectFileIndexReference(
            architecture.modules.getValue(ModuleId.DIAGNOSTIC_INTELLIJ),
        )

        assertEquals(
            setOf(
                ForbiddenEffect.INTELLIJ_PLATFORM,
                ForbiddenEffect.PROJECT_FILE_INDEX_AUTHORITY,
            ),
            diagnosticEffects.mapTo(linkedSetOf(), EffectObservation::effect),
        )
        val authorityEffect = ForbiddenEffect.PROJECT_FILE_INDEX_AUTHORITY
        val foreignAuthorityObservation = diagnosticEffects.first {
            it.effect == authorityEffect
        }
        val activeModules = architecture.modules.values
            .filter { it.lifecycle == ModuleLifecycle.ACTIVE }
            .mapTo(linkedSetOf(), ValidatedModulePolicy::id)

        val foreignAdmission = assertInstanceOf<ArchitectureAdmission.Rejected>(
            ArchitectureAdmission.evaluate(
                architecture,
                ObservedArchitecture(
                    modules = activeModules,
                    projectDependencies = emptySet(),
                    effects = diagnosticEffects,
                ),
            ),
        )
        assertTrue(
            ArchitectureViolation.ForbiddenEffectUse(foreignAuthorityObservation) in
                foreignAdmission.violations,
        )

        val ownerEffects = scanProjectFileIndexReference(
            architecture.modules.getValue(ModuleId.WORKSPACE_INTELLIJ_READ),
        )
        assertInstanceOf<ArchitectureAdmission.Accepted>(
            ArchitectureAdmission.evaluate(
                architecture,
                ObservedArchitecture(
                    modules = activeModules,
                    projectDependencies = emptySet(),
                    effects = ownerEffects,
                ),
            ),
        )
        assertEquals(
            setOf(ModuleId.WORKSPACE_INTELLIJ_READ),
            architecture.modules.values
                .filter { authorityEffect in it.allowedEffects }
                .mapTo(linkedSetOf(), ValidatedModulePolicy::id),
        )
    }

    private fun scanProjectFileIndexReference(
        module: ValidatedModulePolicy,
    ): Set<EffectObservation> = assertInstanceOf<BytecodeScanOutcome.Scanned>(
        JvmEffectScanner.scanBytes(
            module,
            listOf(
                HostedReadClassBytes.capture(
                    "fixture/ProjectFileIndexCaller.class",
                    projectFileIndexCallerBytes(),
                ),
            ),
        ),
    ).effects()

    private fun projectFileIndexCallerBytes(): ByteArray = ClassWriter(0).apply {
        visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC,
            "fixture/ProjectFileIndexCaller",
            null,
            "java/lang/Object",
            null,
        )
        visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "getInstance",
            "(Lcom/intellij/openapi/project/Project;)Lcom/intellij/openapi/roots/ProjectFileIndex;",
            null,
            null,
        ).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "com/intellij/openapi/roots/ProjectFileIndex",
                "getInstance",
                "(Lcom/intellij/openapi/project/Project;)" +
                    "Lcom/intellij/openapi/roots/ProjectFileIndex;",
                false,
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()
}

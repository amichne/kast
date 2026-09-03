package support.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ProjectReadEpochAuthorityBoundaryTest {
    @Test
    fun `project read epoch authority is detected and confined to the endpoint host`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(
            KastArchitecturePolicy.validate(),
        ).architecture
        val authorityEffect = ForbiddenEffect.PROJECT_READ_EPOCH_AUTHORITY
        val foreignEffects = scanProjectReadEpochAdmission(
            architecture.modules.getValue(ModuleId.DIAGNOSTIC_INTELLIJ),
        )

        assertEquals(
            setOf(
                ForbiddenEffect.INTELLIJ_PLATFORM,
                authorityEffect,
            ),
            foreignEffects.mapTo(linkedSetOf(), EffectObservation::effect),
        )
        val foreignAuthorityObservation = foreignEffects.single { it.effect == authorityEffect }
        val activeModules = architecture.modules.values
            .filter { it.lifecycle == ModuleLifecycle.ACTIVE }
            .mapTo(linkedSetOf(), ValidatedModulePolicy::id)

        val foreignAdmission = assertInstanceOf<ArchitectureAdmission.Rejected>(
            ArchitectureAdmission.evaluate(
                architecture,
                ObservedArchitecture(
                    modules = activeModules,
                    projectDependencies = emptySet(),
                    effects = foreignEffects,
                ),
            ),
        )
        assertTrue(
            ArchitectureViolation.ForbiddenEffectUse(foreignAuthorityObservation) in
                foreignAdmission.violations,
        )

        val ownerEffects = scanProjectReadEpochAdmission(
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

    private fun scanProjectReadEpochAdmission(
        module: ValidatedModulePolicy,
    ): Set<EffectObservation> = assertInstanceOf<BytecodeScanOutcome.Scanned>(
        JvmEffectScanner.scanBytes(
            module,
            listOf(
                HostedReadClassBytes.capture(
                    "fixture/ProjectReadEpochAuthorityCaller.class",
                    projectReadEpochAuthorityCallerBytes(),
                ),
            ),
        ),
    ).effects()

    private fun projectReadEpochAuthorityCallerBytes(): ByteArray = ClassWriter(0).apply {
        visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC,
            "fixture/ProjectReadEpochAuthorityCaller",
            null,
            "java/lang/Object",
            null,
        )
        visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "admit",
            ADMISSION_CALLER_DESCRIPTOR,
            null,
            null,
        ).apply {
            visitCode()
            repeat(5) { index -> visitVarInsn(Opcodes.ALOAD, index) }
            visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PROJECT_ADMISSION_SESSION_OWNER,
                PROJECT_ADMISSION_JVM_NAME,
                ADMISSION_TARGET_DESCRIPTOR,
                false,
            )
            visitInsn(Opcodes.ARETURN)
            visitMaxs(5, 5)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private companion object {
        const val PROJECT_ADMISSION_SESSION_OWNER =
            "io/github/amichne/kast/workspace/intellij/read/AdmittedIdeProjectSession"
        const val PROJECT_ADMISSION_JVM_NAME = "admit-iuezf4c"
        const val PROJECT_DESCRIPTOR = "Lcom/intellij/openapi/project/Project;"
        const val ROOT_DESCRIPTOR = "Ljava/lang/String;"
        const val CANDIDATE_DESCRIPTOR =
            "Lio/github/amichne/kast/protocol/contract/IdeHostCompatibilityCandidate;"
        const val POLICY_DESCRIPTOR =
            "Lio/github/amichne/kast/protocol/contract/IdeHostCompatibilityPolicy;"
        const val ADMISSION_DESCRIPTOR =
            "Lio/github/amichne/kast/workspace/intellij/read/ExistingProjectAdmission;"
        const val ADMISSION_TARGET_DESCRIPTOR =
            "($PROJECT_DESCRIPTOR$ROOT_DESCRIPTOR$CANDIDATE_DESCRIPTOR$POLICY_DESCRIPTOR)" +
                ADMISSION_DESCRIPTOR
        const val ADMISSION_CALLER_DESCRIPTOR =
            "(L$PROJECT_ADMISSION_SESSION_OWNER;" +
                "$PROJECT_DESCRIPTOR$ROOT_DESCRIPTOR$CANDIDATE_DESCRIPTOR$POLICY_DESCRIPTOR)" +
                ADMISSION_DESCRIPTOR
    }
}

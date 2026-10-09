package support.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class ObservedRelationSearchBoundaryTest {
    @Test
    fun `compiled native searches must belong to the accounted relation boundary`() {
        val architecture = assertInstanceOf<ArchitecturePolicyValidation.Valid>(KastArchitecturePolicy.validate()).architecture
        val boundary = "io/github/amichne/kast/relation/intellij/ObservedRelationSearchKt"
        for (target in listOf("ReferencesSearch", "DefinitionsScopedSearch")) {
            assertInstanceOf<ArchitectureAdmission.Accepted>(admit(architecture, ModuleId.RELATION_INTELLIJ, boundary, target))
            assertInstanceOf<ArchitectureAdmission.Rejected>(admit(architecture, ModuleId.RELATION_INTELLIJ,
                "io/github/amichne/kast/relation/intellij/Bypass", target))
            assertInstanceOf<ArchitectureAdmission.Rejected>(admit(architecture, ModuleId.SYMBOL_INTELLIJ, boundary, target))
        }
    }

    private fun admit(architecture: ValidatedArchitecturePolicy, module: ModuleId, caller: String, target: String): ArchitectureAdmission {
        val bytecode = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, caller, null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "effect", "()V", null, null).apply {
                visitCode()
                visitMethodInsn(Opcodes.INVOKESTATIC, "com/intellij/psi/search/searches/$target", "search", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
        val scanned = assertInstanceOf<BytecodeScanOutcome.Scanned>(JvmEffectScanner.scanBytes(
            architecture.modules.getValue(module), listOf(HostedReadClassBytes.capture("Boundary.class", bytecode))))
        assertTrue(scanned.effects().any { it.effect == ForbiddenEffect.NATIVE_RELATION_SEARCH })
        return ArchitectureAdmission.evaluate(architecture, ObservedArchitecture(
            modules = architecture.modules.values.filter { it.lifecycle == ModuleLifecycle.ACTIVE }.mapTo(linkedSetOf(), ValidatedModulePolicy::id),
            projectDependencies = emptySet(), effects = scanned.effects()))
    }
}

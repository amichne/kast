package support.architecture

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

sealed interface BytecodeScanFailure {
    data class UnreadableClass(val path: Path) : BytecodeScanFailure

    data class MalformedClass(val path: Path) : BytecodeScanFailure
}

sealed interface BytecodeScanOutcome {
    data class Scanned(val effects: Set<EffectObservation>) : BytecodeScanOutcome

    data class Failed(val failures: List<BytecodeScanFailure>) : BytecodeScanOutcome
}

object JvmEffectScanner {
    /**
     * Proof transition: `(ValidatedModulePolicy, class-file paths) -> BytecodeScanOutcome.Scanned`.
     *
     * Establishes an exact set of JVM references governed by the validated module's role effect
     * profile. [BytecodeScanOutcome.Failed] is the closed expected failure for unreadable or
     * malformed bytecode. Raw paths and ASM values are extracted only inside this scanner.
     */
    fun scan(
        module: ValidatedModulePolicy,
        classFiles: Iterable<Path>,
    ): BytecodeScanOutcome {
        val effects = linkedSetOf<EffectObservation>()
        val failures = mutableListOf<BytecodeScanFailure>()
        classFiles.sortedBy(Path::toString).forEach { classFile ->
            val bytes = try {
                Files.readAllBytes(classFile)
            } catch (_: IOException) {
                failures += BytecodeScanFailure.UnreadableClass(classFile)
                return@forEach
            }
            try {
                ClassReader(bytes).accept(EffectClassVisitor(module, effects), ClassReader.SKIP_FRAMES)
            } catch (_: IllegalArgumentException) {
                failures += BytecodeScanFailure.MalformedClass(classFile)
            }
        }
        return if (failures.isEmpty()) BytecodeScanOutcome.Scanned(effects) else BytecodeScanOutcome.Failed(failures)
    }
}

private class EffectClassVisitor(
    private val module: ValidatedModulePolicy,
    private val effects: MutableSet<EffectObservation>,
) : ClassVisitor(Opcodes.ASM9) {
    private lateinit var classInternalName: String

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        classInternalName = name
        val caller = JvmMember(JvmClassName(classInternalName), JvmMemberName("<class>"), JvmDescriptor(""))
        superName?.let { record(caller, typeMember(it)) }
        interfaces.orEmpty().forEach { record(caller, typeMember(it)) }
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?,
    ): FieldVisitor? {
        val caller = JvmMember(JvmClassName(classInternalName), JvmMemberName(name), JvmDescriptor(descriptor))
        recordDescriptorTypes(caller, descriptor)
        recordConstant(caller, value)
        return null
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        val caller = JvmMember(JvmClassName(classInternalName), JvmMemberName(name), JvmDescriptor(descriptor))
        recordDescriptorTypes(caller, descriptor)
        exceptions.orEmpty().forEach { record(caller, typeMember(it)) }
        return object : MethodVisitor(Opcodes.ASM9) {
            override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
                isInterface: Boolean,
            ) = record(caller, JvmMember.of(owner, name, descriptor))

            override fun visitFieldInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
            ) =
                record(caller, JvmMember.of(owner, name, descriptor))

            override fun visitTypeInsn(
                opcode: Int,
                type: String,
            ) = record(caller, typeMember(type))

            override fun visitLdcInsn(value: Any?) = recordConstant(caller, value)

            override fun visitInvokeDynamicInsn(
                name: String,
                descriptor: String,
                bootstrapMethodHandle: Handle,
                vararg bootstrapMethodArguments: Any,
            ) {
                recordHandle(caller, bootstrapMethodHandle)
                bootstrapMethodArguments.forEach { recordConstant(caller, it) }
                recordDescriptorTypes(caller, descriptor)
            }
        }
    }

    private fun recordConstant(
        caller: JvmMember,
        value: Any?,
    ) {
        when (value) {
            is Type -> value.referencedInternalNames().forEach { record(caller, typeMember(it)) }
            is Handle -> recordHandle(caller, value)
            is ConstantDynamic -> {
                recordHandle(caller, value.bootstrapMethod)
                repeat(value.bootstrapMethodArgumentCount) { index ->
                    recordConstant(caller, value.getBootstrapMethodArgument(index))
                }
            }
        }
    }

    private fun recordHandle(
        caller: JvmMember,
        handle: Handle,
    ) {
        record(caller, JvmMember.of(handle.owner, handle.name, handle.desc))
    }

    private fun recordDescriptorTypes(
        caller: JvmMember,
        descriptor: String,
    ) {
        runCatching { Type.getType(descriptor) }
            .getOrNull()
            ?.referencedInternalNames()
            ?.forEach { record(caller, typeMember(it)) }
    }

    private fun record(
        caller: JvmMember,
        target: JvmMember,
    ) {
        EffectRules.classify(module.role, caller, target).forEach { effect ->
            effects += EffectObservation(module.id, effect, caller, target)
        }
    }

    private fun typeMember(internalName: String): JvmMember =
        JvmMember.of(internalName, "<type>", "")
}

private object EffectRules {
    private val filesystemMutators = setOf(
        "copy",
        "createDirectories",
        "createDirectory",
        "createFile",
        "delete",
        "deleteIfExists",
        "move",
        "newBufferedWriter",
        "newOutputStream",
        "write",
        "writeString",
    )
    private val psiMutators = setOf("add", "addAfter", "addBefore", "delete", "deleteChildRange", "replace")

    fun classify(
        moduleRole: ModuleRole,
        caller: JvmMember,
        target: JvmMember,
    ): Set<ForbiddenEffect> = buildSet {
        val owner = target.owner.internalName
        val name = target.name.value
        if (
            owner == "com/intellij/openapi/command/WriteCommandAction" ||
            (owner.startsWith("com/intellij/openapi/application/") && name.contains("writeAction", true)) ||
            (owner.startsWith("com/intellij/psi/") && name in psiMutators)
        ) {
            add(ForbiddenEffect.INTELLIJ_WRITE)
        }
        if (
            (owner == "java/nio/file/Files" && name in filesystemMutators) ||
            (owner.startsWith("kotlin/io/path/") && filesystemMutators.any(name::startsWith))
        ) {
            add(ForbiddenEffect.FILESYSTEM_WRITE)
            if (caller.isSourceMutationSurface()) add(ForbiddenEffect.SOURCE_FILESYSTEM_WRITE)
        }
        if (owner.startsWith("java/sql/") || owner.startsWith("org/sqlite/")) {
            add(ForbiddenEffect.JDBC)
        }
        if (isGradleImportAuthority(owner, name)) {
            add(ForbiddenEffect.GRADLE_IMPORT)
        }
        if (moduleRole != ModuleRole.LEGACY_HOST && isGraphBuildAuthority(owner, name)) {
            add(ForbiddenEffect.GRAPH_BUILD)
        }
        if (moduleRole != ModuleRole.LEGACY_HOST && isProcessControlAuthority(owner, name)) {
            add(ForbiddenEffect.PROCESS_CONTROL)
        }
        if (
            owner == "io/github/amichne/kast/api/contract/AnalysisBackend" ||
            owner == "io/github/amichne/kast/api/contract/CloseableAnalysisBackend"
        ) {
            add(ForbiddenEffect.ANALYSIS_BACKEND)
        }
    }

    private fun JvmMember.isSourceMutationSurface(): Boolean = owner.internalName.let { callerOwner ->
        callerOwner == "io/github/amichne/kast/api/io/LocalDiskFileOperations"
    }

    private fun isGradleImportAuthority(
        owner: String,
        name: String,
    ): Boolean =
        (owner == "com/intellij/openapi/externalSystem/util/ExternalSystemUtil" &&
         name in setOf("linkExternalProject", "refreshProject")) ||
        owner.startsWith("com/intellij/openapi/externalSystem/importing/") ||
        owner.startsWith("org/jetbrains/plugins/gradle/service/project/open/") ||
        (owner == "org/jetbrains/plugins/gradle/settings/GradleProjectSettings" &&
         (name == "<init>" || name.startsWith("set"))) ||
        (owner in setOf(
            "org/jetbrains/plugins/gradle/settings/GradleSettings",
            "org/jetbrains/plugins/gradle/settings/GradleSystemSettings",
        ) && name.startsWith("set"))

    private fun isGraphBuildAuthority(
        owner: String,
        name: String,
    ): Boolean =
        (owner == "org/gradle/tooling/ProjectConnection" &&
         name in setOf("action", "model", "newBuild")) ||
        owner in setOf(
            "org/gradle/tooling/BuildActionExecuter",
            "org/gradle/tooling/BuildLauncher",
            "org/gradle/tooling/ModelBuilder",
        )

    private fun isProcessControlAuthority(
        owner: String,
        name: String,
    ): Boolean =
        owner == "java/lang/ProcessBuilder" ||
        (owner == "java/lang/Runtime" && name == "exec") ||
        (owner == "java/lang/Process" && name in setOf("destroy", "destroyForcibly")) ||
        (owner == "java/lang/ProcessHandle" && name in setOf("destroy", "destroyForcibly")) ||
        (owner == "com/intellij/execution/process/ProcessHandler" &&
         name in setOf("destroyProcess", "detachProcess", "killProcess"))
}

private fun Type.referencedInternalNames(): Set<String> = when (sort) {
    Type.OBJECT -> setOf(internalName)
    Type.ARRAY -> elementType.referencedInternalNames()
    Type.METHOD -> (argumentTypes.asList() + returnType).flatMapTo(linkedSetOf(), Type::referencedInternalNames)
    else -> emptySet()
}

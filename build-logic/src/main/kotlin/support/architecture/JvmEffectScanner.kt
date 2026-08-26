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
import java.nio.file.InvalidPathException
import java.nio.file.Files
import java.nio.file.Path

sealed interface BytecodeScanFailure {
    data class UnreadableClass(val path: Path) : BytecodeScanFailure

    data class InvalidClassIdentity(val relativeName: String) : BytecodeScanFailure

    data class MalformedClass(val path: Path) : BytecodeScanFailure
}

internal class HostedReadClassBytes private constructor(
    val relativeName: String,
    bytes: ByteArray,
) {
    private val snapshot: ByteArray = bytes.copyOf()

    internal fun <T> useBytes(block: (ByteArray) -> T): T = block(snapshot.copyOf())

    internal companion object {
        /**
         * Proof transition: `(String, ByteArray) -> HostedReadClassBytes`.
         *
         * Captures an owner-confined immutable byte snapshot. The caller's mutable array is never
         * retained, and byte extraction returns only a copy at the scanner or digest boundary.
         */
        fun capture(relativeName: String, bytes: ByteArray): HostedReadClassBytes =
            HostedReadClassBytes(relativeName, bytes)
    }
}

sealed interface BytecodeScanOutcome {
    sealed interface Scanned : BytecodeScanOutcome {
        fun effects(): Set<EffectObservation>
    }

    sealed interface Failed : BytecodeScanOutcome {
        fun failures(): List<BytecodeScanFailure>
    }
}

private class ScannedBytecodeOutcome(effects: Set<EffectObservation>) : BytecodeScanOutcome.Scanned {
    private val snapshot = effects.toSet()

    override fun effects(): Set<EffectObservation> = snapshot.toMutableSet()
}

private class FailedBytecodeScanOutcome(failures: List<BytecodeScanFailure>) :
    BytecodeScanOutcome.Failed {
    private val snapshot = failures.toList()

    override fun failures(): List<BytecodeScanFailure> = snapshot.toMutableList()
}

object JvmEffectScanner {
    /**
     * Proof transition: `(ValidatedModulePolicy, Iterable<Path>) -> BytecodeScanOutcome`.
     *
     * Establishes an exact set of JVM references governed by the validated module's role effect
     * profile. [BytecodeScanOutcome.Failed] is the closed expected failure for unreadable or
     * malformed bytecode. Raw paths and ASM values are extracted only inside this scanner.
     */
    fun scan(
        module: ValidatedModulePolicy,
        classFiles: Iterable<Path>,
    ): BytecodeScanOutcome {
        val failures = mutableListOf<BytecodeScanFailure>()
        val classes = classFiles.sortedBy(Path::toString).mapNotNull { classFile ->
            try {
                HostedReadClassBytes.capture(classFile.toString(), Files.readAllBytes(classFile))
            } catch (_: IOException) {
                failures += BytecodeScanFailure.UnreadableClass(classFile)
                null
            } catch (_: SecurityException) {
                failures += BytecodeScanFailure.UnreadableClass(classFile)
                null
            }
        }
        if (failures.isNotEmpty()) return FailedBytecodeScanOutcome(failures)
        return scanBytes(module, classes)
    }

    /**
     * Proof transition: `(ValidatedModulePolicy, Iterable<HostedReadClassBytes>) ->
     * BytecodeScanOutcome`.
     *
     * Establishes the finite JVM effects of the exact admitted bytes supplied by the caller, so
     * inventory hashing and scanning can share one immutable observation. Malformed bytes remain
     * closed [BytecodeScanFailure.MalformedClass] data. Raw ASM extraction is confined here.
     */
    internal fun scanBytes(
        module: ValidatedModulePolicy,
        classes: Iterable<HostedReadClassBytes>,
    ): BytecodeScanOutcome {
        val effects = linkedSetOf<EffectObservation>()
        val failures = mutableListOf<BytecodeScanFailure>()
        classes.sortedBy(HostedReadClassBytes::relativeName).forEach { artifact ->
            val identity = try {
                Path.of(artifact.relativeName)
            } catch (_: InvalidPathException) {
                failures += BytecodeScanFailure.InvalidClassIdentity(artifact.relativeName)
                return@forEach
            }
            try {
                artifact.useBytes { bytes ->
                    ClassReader(bytes).accept(
                        EffectClassVisitor(module, effects),
                        ClassReader.SKIP_FRAMES,
                    )
                }
            } catch (_: IllegalArgumentException) {
                failures += BytecodeScanFailure.MalformedClass(identity)
            } catch (_: IndexOutOfBoundsException) {
                failures += BytecodeScanFailure.MalformedClass(identity)
            }
        }
        return if (failures.isEmpty()) {
            ScannedBytecodeOutcome(effects)
        } else {
            FailedBytecodeScanOutcome(failures)
        }
    }
}

internal object JvmEffectClassifier {
    /**
     * Proof transition: `(ValidatedModulePolicy, JvmMember, JvmMember) -> Set<ForbiddenEffect>`.
     * Establishes the complete finite effect classification for one compiled JVM reference. The
     * mapping is total and has no expected failure; raw ASM values remain outside this boundary.
     */
    fun classify(
        module: ValidatedModulePolicy,
        caller: JvmMember,
        target: JvmMember,
    ): Set<ForbiddenEffect> = EffectRules.classify(module.role, caller, target)
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
        if (
            access and Opcodes.ACC_INTERFACE == 0 &&
            interfaces.orEmpty().any { it in TOPOLOGY_PUBLISHER_INTERFACES }
        ) {
            effects += EffectObservation(
                module.id,
                ForbiddenEffect.TOPOLOGY_PUBLICATION,
                caller,
                typeMember(interfaces.orEmpty().first { it in TOPOLOGY_PUBLISHER_INTERFACES }),
            )
        }
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
        Type.getType(descriptor).referencedInternalNames()
            .forEach { record(caller, typeMember(it)) }
    }

    private fun record(
        caller: JvmMember,
        target: JvmMember,
    ) {
        JvmEffectClassifier.classify(module, caller, target).forEach { effect ->
            effects += EffectObservation(module.id, effect, caller, target)
        }
    }

    private fun typeMember(internalName: String): JvmMember =
        JvmMember.of(internalName, "<type>", "")
}

private val TOPOLOGY_PUBLISHER_INTERFACES = setOf(
    "io/github/amichne/kast/topology/contract/TopologySnapshotPublisher",
    "io/github/amichne/kast/topology/contract/TopologySnapshotStore",
)

private fun Type.referencedInternalNames(): Set<String> = when (sort) {
    Type.OBJECT -> setOf(internalName)
    Type.ARRAY -> elementType.referencedInternalNames()
    Type.METHOD -> (argumentTypes.asList() + returnType).flatMapTo(linkedSetOf(), Type::referencedInternalNames)
    else -> emptySet()
}

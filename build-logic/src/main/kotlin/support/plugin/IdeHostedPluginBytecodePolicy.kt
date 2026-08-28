package support.plugin

import java.util.Collections
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

@JvmInline
internal value class AdmittedClassDefinition private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: ASM definition owner `String -> AdmittedClassDefinition`.
         *
         * Establishes exclusion of platform, bootstrap, mutation, topology, JDBC, and runtime-
         * acquisition definitions. Expected rejection is [IdePluginLayoutFailure]. Raw owner text
         * may be extracted only while refining it into the exact JAR-entry owner.
         */
        fun admit(owner: String): ClassDefinitionAdmission = when {
            owner.startsWith("com/intellij/") -> rejectedDefinition(
                IdePluginLayoutFailure.INTELLIJ_PLATFORM_CLASS,
            )
            owner.startsWith("org/jetbrains/kotlin/") -> rejectedDefinition(
                IdePluginLayoutFailure.KOTLIN_PLATFORM_CLASS,
            )
            owner.startsWith("org/gradle/") -> rejectedDefinition(
                IdePluginLayoutFailure.GRADLE_PLATFORM_CLASS,
            )
            owner.startsWith("sun/") || owner.startsWith("jdk/") ||
                owner.startsWith("com/sun/") -> rejectedDefinition(
                    IdePluginLayoutFailure.JBR_CLASS,
                )
            owner.startsWith("io/github/amichne/kast/indexer/") -> rejectedDefinition(
                IdePluginLayoutFailure.BOOTSTRAP_CLASS,
            )
            owner.startsWith("io/github/amichne/kast/change/") -> rejectedDefinition(
                IdePluginLayoutFailure.MUTATION_CLASS,
            )
            owner.startsWith("io/github/amichne/kast/topology/") -> rejectedDefinition(
                IdePluginLayoutFailure.TOPOLOGY_CLASS,
            )
            owner.startsWith("org/sqlite/") ||
                owner.startsWith("io/github/amichne/kast/evidence/sqlite/") -> rejectedDefinition(
                    IdePluginLayoutFailure.JDBC_CLASS,
                )
            owner.startsWith("io/github/amichne/kast/distribution/") -> rejectedDefinition(
                IdePluginLayoutFailure.RUNTIME_ACQUISITION_CLASS,
            )
            else -> ClassDefinitionAdmission.Complete(AdmittedClassDefinition(owner))
        }
    }
}

internal sealed interface ClassDefinitionAdmission {
    data class Complete(val definition: AdmittedClassDefinition) : ClassDefinitionAdmission
    data class Rejected(val failure: IdePluginLayoutFailure) : ClassDefinitionAdmission
}

internal enum class NestedJarEntryKind { CLASS_FILE, RESOURCE }

@JvmInline
internal value class NestedJarEntryIdentity private constructor(val value: String) :
    Comparable<NestedJarEntryIdentity> {
    companion object {
        /**
         * Proof transition: raw nested entry name `String -> NestedJarEntryIdentity`.
         *
         * Establishes a non-empty relative identity without traversal. Expected rejection is
         * `INVALID_ARCHIVE_ENTRY`; raw extraction is permitted only at the nested class boundary.
         */
        fun admit(raw: String): NestedJarEntryIdentityResult = if (
            raw.isNotEmpty() && !raw.startsWith('/') && ".." !in raw
        ) {
            NestedJarEntryIdentityResult.Complete(NestedJarEntryIdentity(raw))
        } else {
            NestedJarEntryIdentityResult.Rejected
        }
    }

    override fun compareTo(other: NestedJarEntryIdentity): Int = value.compareTo(other.value)
}

internal sealed interface NestedJarEntryIdentityResult {
    data class Complete(val identity: NestedJarEntryIdentity) : NestedJarEntryIdentityResult
    data object Rejected : NestedJarEntryIdentityResult
}

internal class AdmittedNestedJarEntry private constructor(
    val identity: NestedJarEntryIdentity,
    val kind: NestedJarEntryKind
) : Comparable<AdmittedNestedJarEntry> {
    companion object {
        /**
         * Proof transition: nested JAR entry name `String -> AdmittedNestedJarEntry`.
         *
         * Establishes typed class/resource state and exclusion of forbidden native or runtime
         * resources. Expected rejection is `FORBIDDEN_NATIVE_OR_RUNTIME_RESOURCE`.
         */
        fun admit(name: String): NestedJarEntryAdmission {
            val identity = when (val admitted = NestedJarEntryIdentity.admit(name)) {
                is NestedJarEntryIdentityResult.Complete -> admitted.identity
                NestedJarEntryIdentityResult.Rejected -> return NestedJarEntryAdmission.Rejected(
                    IdePluginLayoutFailure.INVALID_ARCHIVE_ENTRY,
                )
            }
            if (name.startsWith("jbr/") || name.startsWith("idea-home/") ||
                name.startsWith("bin/") || name.endsWith(".dylib") ||
                name.endsWith(".jnilib") || name.endsWith(".so")
            ) {
                return NestedJarEntryAdmission.Rejected(
                    IdePluginLayoutFailure.FORBIDDEN_NATIVE_OR_RUNTIME_RESOURCE,
                )
            }
            return NestedJarEntryAdmission.Complete(
                AdmittedNestedJarEntry(
                    identity,
                    if (name.endsWith(".class")) {
                        NestedJarEntryKind.CLASS_FILE
                    } else {
                        NestedJarEntryKind.RESOURCE
                    },
                ),
            )
        }
    }

    override fun compareTo(other: AdmittedNestedJarEntry): Int = identity.compareTo(other.identity)
    override fun equals(other: Any?): Boolean =
        other is AdmittedNestedJarEntry && identity == other.identity
    override fun hashCode(): Int = identity.hashCode()
}

internal sealed interface NestedJarEntryAdmission {
    data class Complete(val entry: AdmittedNestedJarEntry) : NestedJarEntryAdmission
    data class Rejected(val failure: IdePluginLayoutFailure) : NestedJarEntryAdmission
}

@JvmInline
internal value class AdmittedBytecodeReference private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: ASM reference `String -> AdmittedBytecodeReference`.
         *
         * Establishes exclusion of mutation, topology, JDBC, and runtime-acquisition references.
         * Expected rejection is the finite [IdePluginLayoutFailure]. Raw reference text remains at
         * the private ASM visitor boundary.
         */
        fun admit(reference: String): BytecodeReferenceAdmission = when {
            "io/github/amichne/kast/change/" in reference -> rejectedReference(
                IdePluginLayoutFailure.MUTATION_CLASS,
            )
            "io/github/amichne/kast/topology/" in reference -> rejectedReference(
                IdePluginLayoutFailure.TOPOLOGY_CLASS,
            )
            "org/sqlite/" in reference || "java/sql/" in reference ||
                "javax/sql/" in reference ||
                "io/github/amichne/kast/evidence/sqlite/" in reference -> rejectedReference(
                    IdePluginLayoutFailure.JDBC_CLASS,
                )
            "io/github/amichne/kast/distribution/" in reference -> rejectedReference(
                IdePluginLayoutFailure.RUNTIME_ACQUISITION_CLASS,
            )
            else -> BytecodeReferenceAdmission.Complete(AdmittedBytecodeReference(reference))
        }
    }
}

internal sealed interface BytecodeReferenceAdmission {
    data class Complete(val reference: AdmittedBytecodeReference) : BytecodeReferenceAdmission
    data class Rejected(val failure: IdePluginLayoutFailure) : BytecodeReferenceAdmission
}

internal class VerifiedBytecodeReferences private constructor(
    references: List<AdmittedBytecodeReference>,
) {
    private val evidence = Collections.unmodifiableList(references.toList())
    val count: Int get() = evidence.size

    companion object {
        /**
         * Proof transition: parsed class `ClassReader -> VerifiedBytecodeReferences`.
         *
         * Performs the complete ASM visit before constructing immutable reference evidence.
         * Expected rejection is the finite [IdePluginLayoutFailure].
         */
        fun inspect(reader: ClassReader): BytecodePolicyResult {
            val visitor = ForbiddenReferenceVisitor()
            reader.accept(visitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            return when (val state = visitor.finishEvidence()) {
                is ReferenceScanProgress.Collecting -> BytecodePolicyResult.Complete(
                    VerifiedBytecodeReferences(state.references),
                )
                is ReferenceScanProgress.Rejected -> BytecodePolicyResult.Rejected(state.failure)
            }
        }
    }
}

internal sealed interface BytecodePolicyResult {
    data class Complete(val references: VerifiedBytecodeReferences) : BytecodePolicyResult
    data class Rejected(val failure: IdePluginLayoutFailure) : BytecodePolicyResult
}

private sealed interface ReferenceScanProgress {
    data class Collecting(val references: MutableList<AdmittedBytecodeReference>) :
        ReferenceScanProgress

    data class Rejected(val failure: IdePluginLayoutFailure) : ReferenceScanProgress
}

private class ForbiddenReferenceVisitor : ClassVisitor(Opcodes.ASM9) {
    private var progress: ReferenceScanProgress = ReferenceScanProgress.Collecting(mutableListOf())

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        if (superName != null) observeReference(superName)
        interfaces.orEmpty().forEach(::observeReference)
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?,
    ): FieldVisitor? {
        observeReference(descriptor)
        return null
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        observeReference(descriptor)
        exceptions.orEmpty().forEach(::observeReference)
        return object : MethodVisitor(Opcodes.ASM9) {
            override fun visitTypeInsn(opcode: Int, type: String) = observeReference(type)

            override fun visitFieldInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
            ) {
                observeReference(owner)
                observeReference(descriptor)
            }

            override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
                isInterface: Boolean,
            ) {
                observeMethod(owner, name)
                observeReference(descriptor)
            }

            override fun visitMultiANewArrayInsn(descriptor: String, dimensions: Int) =
                observeReference(descriptor)
        }
    }

    fun finishEvidence(): ReferenceScanProgress = progress

    private fun observeMethod(owner: String, name: String) {
        if (owner == "java/lang/ProcessBuilder" || owner == "java/lang/Process" ||
            owner == "java/lang/Runtime" && name == "exec"
        ) {
            reject(IdePluginLayoutFailure.PROCESS_LAUNCH_REFERENCE)
        } else {
            observeReference(owner)
        }
    }

    private fun observeReference(raw: String) {
        when (val admitted = AdmittedBytecodeReference.admit(raw)) {
            is BytecodeReferenceAdmission.Complete -> when (val state = progress) {
                is ReferenceScanProgress.Collecting -> state.references += admitted.reference
                is ReferenceScanProgress.Rejected -> Unit
            }
            is BytecodeReferenceAdmission.Rejected -> reject(admitted.failure)
        }
    }

    private fun reject(failure: IdePluginLayoutFailure) {
        if (progress is ReferenceScanProgress.Collecting) {
            progress = ReferenceScanProgress.Rejected(failure)
        }
    }
}

private fun rejectedDefinition(failure: IdePluginLayoutFailure) =
    ClassDefinitionAdmission.Rejected(failure)

private fun rejectedReference(failure: IdePluginLayoutFailure) =
    BytecodeReferenceAdmission.Rejected(failure)

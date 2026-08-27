package support.architecture

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

enum class DefaultRuntimeFallbackAuthority(
    internal val forbiddenOwners: Set<String> = emptySet(),
) {
    MANAGED_SEMANTIC_RUNTIME_PROVIDER(
        setOf("io/github/amichne/kast/distribution/managed/ManagedSemanticRuntimeProvider"),
    ),
    EXACT_ROOT_PROCESS_RUNTIME_DEMANDER(
        setOf("io/github/amichne/kast/cli/ExactRootProcessRuntimeDemander"),
    ),
    INDEXER_EXECUTABLE(setOf("io/github/amichne/kast/cli/IndexerExecutable")),
    RUNTIME_STORE(setOf("io/github/amichne/kast/distribution/managed/RuntimeStore")),
    PROCESS_OR_LAUNCHCTL_FALLBACK(setOf("java/lang/ProcessBuilder")),
}

class RuntimeClassBytes private constructor(
    val relativeName: String,
    bytes: ByteArray,
) {
    private val snapshot = bytes.copyOf()

    internal fun <Value> useBytes(block: (ByteArray) -> Value): Value = block(snapshot.copyOf())

    companion object {
        /**
         * Proof transition: `String + ByteArray -> RuntimeClassBytes`.
         *
         * Captures one immutable class-byte observation with its relative identity. Raw mutable
         * bytes may be extracted only by [NoDefaultRuntimeFallbackInspection].
         */
        fun capture(relativeName: String, bytes: ByteArray): RuntimeClassBytes =
            RuntimeClassBytes(relativeName, bytes)
    }
}

sealed interface NoDefaultRuntimeFallbackFailure {
    data object InstalledCompositionMissing : NoDefaultRuntimeFallbackFailure
    data class DuplicateClass(val owner: String) : NoDefaultRuntimeFallbackFailure
    data class MalformedClass(val relativeName: String) : NoDefaultRuntimeFallbackFailure
    data class ForbiddenReachable(
        val authority: DefaultRuntimeFallbackAuthority,
        val owner: String,
    ) : NoDefaultRuntimeFallbackFailure
}

data class NoDefaultRuntimeFallbackProof(
    val entrypoint: String,
    val reachableClasses: List<String>,
    val verifiedAuthorities: List<DefaultRuntimeFallbackAuthority>,
)

sealed interface NoDefaultRuntimeFallbackVerification {
    data class Complete(val proof: NoDefaultRuntimeFallbackProof) :
        NoDefaultRuntimeFallbackVerification

    data class Rejected(
        val first: NoDefaultRuntimeFallbackFailure,
        val additional: List<NoDefaultRuntimeFallbackFailure>,
    ) : NoDefaultRuntimeFallbackVerification
}

object NoDefaultRuntimeFallbackInspection {
    private const val ENTRYPOINT =
        "io/github/amichne/kast/cli/InstalledKastCliComposition"

    /**
     * Proof transition: `List<RuntimeClassBytes> -> NoDefaultRuntimeFallbackVerification`.
     *
     * Establishes the transitive compiled-class closure of the installed composition and proves
     * that none of the five KVP-027 fallback authorities is reachable. Missing, duplicate,
     * malformed, and forbidden observations remain closed [NoDefaultRuntimeFallbackFailure].
     * Raw ASM values exist only inside this bytecode boundary.
     */
    fun inspect(classes: List<RuntimeClassBytes>): NoDefaultRuntimeFallbackVerification {
        val observations = linkedMapOf<String, RuntimeClassObservation>()
        val failures = mutableListOf<NoDefaultRuntimeFallbackFailure>()
        classes.sortedBy(RuntimeClassBytes::relativeName).forEach { artifact ->
            val observation = try {
                artifact.useBytes(::observe)
            } catch (_: IllegalArgumentException) {
                failures += NoDefaultRuntimeFallbackFailure.MalformedClass(artifact.relativeName)
                return@forEach
            } catch (_: IndexOutOfBoundsException) {
                failures += NoDefaultRuntimeFallbackFailure.MalformedClass(artifact.relativeName)
                return@forEach
            }
            if (observations.putIfAbsent(observation.owner, observation) != null) {
                failures += NoDefaultRuntimeFallbackFailure.DuplicateClass(observation.owner)
            }
        }
        if (ENTRYPOINT !in observations) {
            failures += NoDefaultRuntimeFallbackFailure.InstalledCompositionMissing
        }
        if (failures.isNotEmpty()) return failures.rejected()

        val reachable = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        pending += ENTRYPOINT
        while (pending.isNotEmpty()) {
            val owner = pending.removeFirst()
            if (!reachable.add(owner)) continue
            observations[owner]?.references.orEmpty().sorted().forEach { reference ->
                if (reference in observations && reference !in reachable) pending += reference
            }
        }
        val reachableObservations = reachable.mapNotNull(observations::get)
        DefaultRuntimeFallbackAuthority.entries.forEach { authority ->
            val owner = reachableObservations.asSequence()
                .flatMap { it.references.asSequence() }
                .firstOrNull(authority.forbiddenOwners::contains)
            if (owner != null) {
                failures += NoDefaultRuntimeFallbackFailure.ForbiddenReachable(authority, owner)
            }
            if (
                authority == DefaultRuntimeFallbackAuthority.PROCESS_OR_LAUNCHCTL_FALLBACK &&
                reachableObservations.any(RuntimeClassObservation::hasLaunchctlLiteral)
            ) {
                failures += NoDefaultRuntimeFallbackFailure.ForbiddenReachable(
                    authority,
                    "launchctl",
                )
            }
        }
        return if (failures.isEmpty()) {
            NoDefaultRuntimeFallbackVerification.Complete(
                NoDefaultRuntimeFallbackProof(
                    ENTRYPOINT,
                    reachable.sorted(),
                    DefaultRuntimeFallbackAuthority.entries,
                ),
            )
        } else {
            failures.rejected()
        }
    }

    private fun observe(bytes: ByteArray): RuntimeClassObservation {
        val references = linkedSetOf<String>()
        var owner = ""
        var launchctl = false
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    owner = name
                    superName?.let(references::add)
                    interfaces.orEmpty().forEach(references::add)
                }

                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    descriptorOwners(descriptor).forEach(references::add)
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    descriptorOwners(descriptor).forEach(references::add)
                    exceptions.orEmpty().forEach(references::add)
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            references += owner
                            descriptorOwners(descriptor).forEach(references::add)
                        }

                        override fun visitFieldInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                        ) {
                            references += owner
                            descriptorOwners(descriptor).forEach(references::add)
                        }

                        override fun visitTypeInsn(opcode: Int, type: String) {
                            references += type
                        }

                        override fun visitLdcInsn(value: Any?) {
                            if (value is Type) value.internalName?.let(references::add)
                            if (value is String && "launchctl" in value) launchctl = true
                        }
                    }
                }
            },
            ClassReader.SKIP_FRAMES,
        )
        return RuntimeClassObservation(owner, references, launchctl)
    }
}

private data class RuntimeClassObservation(
    val owner: String,
    val references: Set<String>,
    val hasLaunchctlLiteral: Boolean,
)

private fun descriptorOwners(descriptor: String): List<String> {
    val type = Type.getType(descriptor)
    return when (type.sort) {
        Type.METHOD -> (type.argumentTypes.toList() + type.returnType).flatMap(::typeOwners)
        else -> typeOwners(type)
    }
}

private fun typeOwners(type: Type): List<String> = when (type.sort) {
    Type.ARRAY -> typeOwners(type.elementType)
    Type.OBJECT -> listOf(type.internalName)
    else -> emptyList()
}

private fun List<NoDefaultRuntimeFallbackFailure>.rejected() =
    NoDefaultRuntimeFallbackVerification.Rejected(first(), drop(1))

package support.pr633

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor

@JvmInline
internal value class JvmInternalName private constructor(val value: String) {
    companion object {
        /**
         * Boundary transition: ASM classfile internal name `String -> JvmInternalName`.
         *
         * ASM has already parsed the owner, descriptor, or signature grammar. Raw extraction is
         * permitted only while visiting that classfile evidence; policy strings use a separate
         * canonical prefix refinement.
         */
        fun fromClassfile(value: String): JvmInternalName = JvmInternalName(value)
    }
}

internal data class BytecodeReference(
    val origin: JvmInternalName,
    val location: String,
    val target: JvmInternalName,
) {
    fun display(): String = "${origin.value}#$location -> ${target.value}"
}

private data class ScannedBytecodeClass(
    val name: JvmInternalName,
    val references: Set<BytecodeReference>,
)

internal sealed interface BytecodeAuthorityViolation {
    data class DuplicateClass(val className: JvmInternalName) : BytecodeAuthorityViolation
    data class MissingCaller(
        val prefix: BytecodeAuthorityPolicy.JvmInternalNamePrefix,
    ) : BytecodeAuthorityViolation
    data class ForbiddenReference(
        val entryPoint: JvmInternalName,
        internal val firstReference: BytecodeReference,
        internal val additionalReferences: List<BytecodeReference>,
    ) : BytecodeAuthorityViolation {
        internal val path: List<BytecodeReference> = listOf(firstReference) + additionalReferences
    }

    fun display(): String = when (this) {
        is DuplicateClass -> "duplicate scanned class: ${className.value}"
        is MissingCaller -> "configured caller matched no scanned class: ${prefix.value}"
        is ForbiddenReference -> path.joinToString(" => ", prefix = "entry ${entryPoint.value}: ") {
            it.display()
        }
    }
}

internal sealed interface BytecodeAuthorityVerification {
    data object Accepted : BytecodeAuthorityVerification
    data class Rejected(
        val firstViolation: BytecodeAuthorityViolation,
        val additionalViolations: List<BytecodeAuthorityViolation>,
    ) : BytecodeAuthorityVerification {
        val violations: List<BytecodeAuthorityViolation> = listOf(firstViolation) + additionalViolations
    }
}

/**
 * Proof transition: `List<ByteArray> -> BytecodeAuthorityVerification` under a trusted
 * [BytecodeAuthorityPolicy].
 *
 * [BytecodeAuthorityVerification.Accepted] proves that every configured caller exists and no
 * path through the scanned class graph reaches a forbidden owner. Rejection is the closed
 * [BytecodeAuthorityViolation] set. Raw class bytes are read only by the Gradle task boundary.
 */
internal fun verifyBytecodeAuthority(
    policy: BytecodeAuthorityPolicy,
    classBytes: List<ByteArray>,
): BytecodeAuthorityVerification {
    val scanned = classBytes.map(::scanBytecodeClass)
    val duplicates = scanned.groupBy(ScannedBytecodeClass::name)
        .filterValues { it.size > 1 }
        .keys
        .map(BytecodeAuthorityViolation::DuplicateClass)
    val classes = scanned.associateBy(ScannedBytecodeClass::name)
    val missingCallers = policy.callers
        .filter { prefix -> classes.keys.none { it.value.startsWith(prefix.value) } }
        .map(BytecodeAuthorityViolation::MissingCaller)
    val entryPoints = classes.keys.filter { name ->
        policy.callers.any { name.value.startsWith(it.value) }
    }
    val forbiddenReferences = entryPoints.flatMap { entryPoint ->
        forbiddenReferencesFrom(entryPoint, classes, policy.forbiddenOwners)
    }
    val violations = duplicates + missingCallers + forbiddenReferences
    return if (violations.isEmpty()) {
        BytecodeAuthorityVerification.Accepted
    } else {
        val distinct = violations.distinct()
        BytecodeAuthorityVerification.Rejected(distinct.first(), distinct.drop(1))
    }
}

private data class ReachableBytecodeClass(
    val name: JvmInternalName,
    val path: List<BytecodeReference>,
)

private fun forbiddenReferencesFrom(
    entryPoint: JvmInternalName,
    classes: Map<JvmInternalName, ScannedBytecodeClass>,
    forbiddenPrefixes: Set<BytecodeAuthorityPolicy.JvmInternalNamePrefix>,
): List<BytecodeAuthorityViolation.ForbiddenReference> {
    val queue = ArrayDeque<ReachableBytecodeClass>()
    val visited = mutableSetOf<JvmInternalName>()
    val violations = mutableListOf<BytecodeAuthorityViolation.ForbiddenReference>()
    queue += ReachableBytecodeClass(entryPoint, emptyList())
    while (queue.isNotEmpty()) {
        val reachable = queue.removeFirst()
        if (!visited.add(reachable.name)) continue
        classes.getValue(reachable.name).references.sortedBy(BytecodeReference::display).forEach { reference ->
            val path = reachable.path + reference
            if (forbiddenPrefixes.any { reference.target.value.startsWith(it.value) }) {
                violations += BytecodeAuthorityViolation.ForbiddenReference(
                    entryPoint,
                    path.first(),
                    path.drop(1),
                )
            } else if (reference.target in classes && reference.target !in visited) {
                queue += ReachableBytecodeClass(reference.target, path)
            }
        }
    }
    return violations
}

/**
 * Proof transition: `ByteArray -> ScannedBytecodeClass`.
 *
 * ASM parsing establishes one exact JVM internal class name and its complete reference edge set
 * for the supported classfile structures. Malformed classfiles are exceptional build corruption;
 * raw bytes are supplied only by the Gradle filesystem boundary.
 */
private fun scanBytecodeClass(bytes: ByteArray): ScannedBytecodeClass {
    val reader = ClassReader(bytes)
    val collector = BytecodeReferenceCollector(JvmInternalName.fromClassfile(reader.className))
    reader.accept(collector, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    return ScannedBytecodeClass(JvmInternalName.fromClassfile(reader.className), collector.references)
}

private class BytecodeReferenceCollector(
    private val origin: JvmInternalName,
) : ClassVisitor(Opcodes.ASM9) {
    val references = linkedSetOf<BytecodeReference>()

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?,
    ) {
        superName?.let { recordClassfileName("class superclass", it) }
        interfaces.orEmpty().forEach { recordClassfileName("class interface", it) }
        signature?.let { recordSignature("class signature", JvmSignature.Declaration(it)) }
    }

    override fun visitRecordComponent(
        name: String,
        descriptor: String,
        signature: String?,
    ): RecordComponentVisitor {
        recordDescriptor("record component $name", descriptor)
        signature?.let { recordSignature("record component $name signature", JvmSignature.Type(it)) }
        return object : RecordComponentVisitor(Opcodes.ASM9) {}
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?,
    ): FieldVisitor {
        recordDescriptor("field $name", descriptor)
        signature?.let { recordSignature("field $name signature", JvmSignature.Type(it)) }
        recordConstant("field $name constant", value)
        return object : FieldVisitor(Opcodes.ASM9) {}
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        recordDescriptor("method $name descriptor", descriptor)
        signature?.let { recordSignature("method $name signature", JvmSignature.Declaration(it)) }
        exceptions.orEmpty().forEach { recordClassfileName("method $name exception", it) }
        return methodVisitor(name)
    }

    private fun methodVisitor(method: String): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
        override fun visitTypeInsn(opcode: Int, type: String) = recordType(method, type)

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
            recordClassfileName(method, owner)
            recordDescriptor("$method field $name descriptor", descriptor)
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            recordClassfileName(method, owner)
            recordDescriptor("$method invocation $name descriptor", descriptor)
        }

        override fun visitLdcInsn(value: Any?) = recordConstant("$method constant", value)

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any,
        ) {
            recordDescriptor("$method invokedynamic descriptor", descriptor)
            recordConstant("$method invokedynamic bootstrap", bootstrapMethodHandle)
            bootstrapMethodArguments.forEach { recordConstant("$method invokedynamic argument", it) }
        }

        override fun visitMultiANewArrayInsn(descriptor: String, dimensions: Int) =
            recordDescriptor("$method array descriptor", descriptor)

        override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
            type?.let { recordClassfileName("$method catch", it) }
        }
    }

    private fun recordType(location: String, type: String) {
        if (type.startsWith("[")) {
            recordDescriptor(location, type)
        } else {
            recordClassfileName(location, type)
        }
    }

    private fun recordConstant(location: String, value: Any?) {
        when (value) {
            is Type -> recordDescriptor(location, value.descriptor)
            is Handle -> {
                recordClassfileName(location, value.owner)
                recordDescriptor("$location handle descriptor", value.desc)
            }
            is ConstantDynamic -> {
                recordDescriptor("$location dynamic descriptor", value.descriptor)
                recordConstant("$location dynamic bootstrap", value.bootstrapMethod)
                repeat(value.bootstrapMethodArgumentCount) { index ->
                    recordConstant("$location dynamic argument", value.getBootstrapMethodArgument(index))
                }
            }
        }
    }

    private fun recordDescriptor(location: String, descriptor: String) {
        Type.getType(descriptor).referencedInternalNames().forEach { record(location, it) }
    }

    private fun recordSignature(location: String, signature: JvmSignature) {
        signature.referencedInternalNames().forEach { record(location, it) }
    }

    private fun recordClassfileName(location: String, target: String) {
        if (target.isNotBlank()) record(location, JvmInternalName.fromClassfile(target))
    }

    private fun record(location: String, target: JvmInternalName) {
        if (target != origin) {
            references += BytecodeReference(origin, location, target)
        }
    }
}

private sealed interface JvmSignature {
    val value: String

    data class Type(override val value: String) : JvmSignature
    data class Declaration(override val value: String) : JvmSignature
}

private sealed interface SignatureOwnerState {
    data object OutsideClass : SignatureOwnerState
    data class VisitingClass(val internalName: JvmInternalName) : SignatureOwnerState
}

/**
 * Proof transition: `JvmSignature -> Set<JvmInternalName>`.
 *
 * Establishes every referenced owner while preserving whether ASM must parse a type or declaration
 * signature. Nested generic arguments receive independent owner state, so a following inner class
 * remains attached to its lexical outer owner. Malformed signatures are exceptional classfile
 * corruption; raw signature text is wrapped at the ASM visitor boundary.
 */
private fun JvmSignature.referencedInternalNames(): Set<JvmInternalName> {
    val names = linkedSetOf<JvmInternalName>()
    fun visitor(): SignatureVisitor = object : SignatureVisitor(Opcodes.ASM9) {
        private var owner: SignatureOwnerState = SignatureOwnerState.OutsideClass

        override fun visitClassType(name: String) {
            val className = JvmInternalName.fromClassfile(name)
            owner = SignatureOwnerState.VisitingClass(className)
            names += className
        }

        override fun visitInnerClassType(name: String) {
            val nestedOwner = when (val current = owner) {
                SignatureOwnerState.OutsideClass ->
                    error("inner class signature has no outer owner")
                is SignatureOwnerState.VisitingClass ->
                    SignatureOwnerState.VisitingClass(
                        JvmInternalName.fromClassfile("${current.internalName.value}\$$name"),
                    )
            }
            owner = nestedOwner
            names += nestedOwner.internalName
        }

        override fun visitTypeArgument(wildcard: Char): SignatureVisitor = visitor()
    }
    when (this) {
        is JvmSignature.Type -> SignatureReader(value).acceptType(visitor())
        is JvmSignature.Declaration -> SignatureReader(value).accept(visitor())
    }
    return names
}

private fun Type.referencedInternalNames(): List<JvmInternalName> = when (sort) {
    Type.OBJECT -> listOf(JvmInternalName.fromClassfile(internalName))
    Type.ARRAY -> elementType.referencedInternalNames()
    Type.METHOD -> argumentTypes.flatMap(Type::referencedInternalNames) + returnType.referencedInternalNames()
    else -> emptyList()
}
